package ru.vlad.triliumnotes.diagrams

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.vlad.triliumnotes.data.NoteMeta

@RunWith(RobolectricTestRunner::class)
class DiagramPackagesTest {
    private lateinit var directory: File
    private val server = MockWebServer()
    private val files = linkedMapOf("excalidraw.js" to "renderer".toByteArray(), "fonts/Test/font.woff2" to "font".toByteArray())

    @Before fun start() { directory = Files.createTempDirectory("diagram-packages").toFile(); server.start() }
    @After fun stop() { server.shutdown(); directory.deleteRecursively() }

    private fun zip(entries: Map<String, ByteArray> = files): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (path, content) -> zip.putNextEntry(ZipEntry(path)); zip.write(content); zip.closeEntry() }
        }
    }.toByteArray()

    private fun spec(bytes: ByteArray = zip(), entries: Map<String, ByteArray> = files) = DiagramPackageSpec(
        server.url("/canvas.zip").toString(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        bytes.size.toLong(), entries.values.sumOf { it.size.toLong() }, entries.keys,
    )

    private fun manager(spec: DiagramPackageSpec = spec()) = DiagramPackages(directory, mapOf("canvas" to spec))
    private fun response(bytes: ByteArray = zip()) = MockResponse().setBody(Buffer().write(bytes))
    private suspend fun failure(block: suspend () -> Unit) {
        try { block(); fail("Expected IOException") } catch (_: IOException) { }
    }

    @Test fun missingThenInstalledPackageSurvivesRestartWithoutNetworkOrCredentials() = runBlocking {
        val packages = manager()
        assertFalse(packages.isReady("canvas"))
        assertEquals(0, packages.changes.value)
        assertNull(packages.openAsset("diagrams/vendor/excalidraw.js"))
        server.enqueue(response())
        packages.ensure("canvas")
        assertTrue(packages.isReady("canvas"))
        assertEquals(1, packages.changes.value)
        packages.ensure("canvas")
        assertEquals(1, packages.changes.value)
        assertEquals("font", packages.openAsset("diagrams/vendor/fonts/Test/font.woff2")!!.bufferedReader().use { it.readText() })
        assertNull(packages.openAsset("diagrams/vendor/../../canvas.zip"))
        assertNull(packages.openAsset("diagrams/index.html"))
        assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        val restarted = manager()
        restarted.ensure("canvas")
        assertTrue(restarted.isReady("canvas"))
        assertEquals(1, server.requestCount)
    }

    @Test fun concurrentViewerAndOfflinePreparationDownloadOnePackage() = runBlocking {
        server.enqueue(response().setBodyDelay(150, TimeUnit.MILLISECONDS))
        val packages = manager()
        coroutineScope { (1..12).map { async { packages.ensure("canvas") } }.awaitAll() }
        assertTrue(packages.isReady("canvas"))
        assertEquals(1, server.requestCount)
        assertEquals(1, packages.changes.value)
    }

    @Test fun cancelledDownloadDoesNotPoisonConcurrentRetry() = runBlocking {
        val packages = manager()
        server.enqueue(response().setBodyDelay(200, TimeUnit.MILLISECONDS))
        server.enqueue(response())
        val first = async(Dispatchers.IO) { packages.ensure("canvas") }
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val retry = async(Dispatchers.IO) { packages.ensure("canvas") }
        withTimeout(5_000) {
            first.cancelAndJoin()
            retry.await()
        }
        assertTrue(packages.isReady("canvas"))
        assertEquals(2, server.requestCount)
        assertTrue(directory.listFiles()!!.none { it.name.startsWith('.') })
    }

    @Test fun httpFailureAndInterruptedBodyCanRetryWithoutPublishingPartialFiles() = runBlocking {
        val packages = manager()
        server.enqueue(MockResponse().setResponseCode(503))
        failure { packages.ensure("canvas") }
        assertFalse(packages.isReady("canvas"))
        assertEquals(0, packages.changes.value)
        assertTrue(directory.listFiles()!!.isEmpty())
        server.enqueue(response().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        failure { packages.ensure("canvas") }
        assertFalse(packages.isReady("canvas"))
        assertTrue(directory.listFiles()!!.isEmpty())
        File(directory, ".canvas-abandoned").apply { mkdir(); resolve("part").writeText("partial") }
        server.enqueue(response())
        packages.ensure("canvas")
        assertTrue(packages.isReady("canvas"))
        assertEquals(1, packages.changes.value)
        assertFalse(File(directory, ".canvas-abandoned").exists())
    }

    @Test fun wrongChecksumCannotPublishAndValidRetryRepairsIt() = runBlocking {
        val packages = manager()
        val corrupted = zip().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        server.enqueue(response(corrupted))
        failure { packages.ensure("canvas") }
        assertFalse(packages.isReady("canvas"))
        server.enqueue(response())
        packages.ensure("canvas")
        assertTrue(packages.isReady("canvas"))
    }

    @Test fun verifiedButMalformedZipAndUnsafeOrExtraEntriesAreRejected() = runBlocking {
        val malformed = "not a zip".toByteArray()
        val attacks = listOf(
            malformed,
            zip(files + ("../outside.js" to "escape".toByteArray())),
            zip(files + ("/absolute.js" to "escape".toByteArray())),
            zip(files + ("fonts\\escape.js" to "escape".toByteArray())),
            zip(files + ("extra.js" to "extra".toByteArray())),
        )
        for (bytes in attacks) {
            val packages = manager(spec(bytes))
            server.enqueue(response(bytes))
            failure { packages.ensure("canvas") }
            assertFalse(packages.isReady("canvas"))
            assertTrue(directory.listFiles()!!.isEmpty())
        }
        assertFalse(File(directory.parentFile, "outside.js").exists())
    }

    @Test fun zipExpansionAndDownloadAreBoundedByPinnedManifest() = runBlocking {
        val expanded = zip(files.mapValues { (_, bytes) -> bytes + ByteArray(1000) })
        val packages = manager(spec(expanded))
        server.enqueue(response(expanded))
        failure { packages.ensure("canvas") }
        assertFalse(packages.isReady("canvas"))
        val bytes = zip()
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(bytes + ByteArray(100)), 64))
        failure { manager().ensure("canvas") }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun missingOrTruncatedCompletedFileTriggersRepair() = runBlocking {
        val packages = manager()
        server.enqueue(response())
        packages.ensure("canvas")
        val completed = directory.listFiles()!!.single()
        File(completed, "excalidraw.js").writeText("x")
        assertFalse(packages.isReady("canvas"))
        server.enqueue(response())
        packages.ensure("canvas")
        File(completed, "fonts/Test/font.woff2").delete()
        assertFalse(packages.isReady("canvas"))
        server.enqueue(response())
        packages.ensure("canvas")
        assertTrue(packages.isReady("canvas"))
        assertEquals(3, server.requestCount)
    }

    @Test fun successfulUpgradeRemovesOnlyOldVersionsOfItsOwnKindAndFailedUpgradeRetainsThem() = runBlocking {
        val old = manager()
        server.enqueue(response())
        old.ensure("canvas")
        val oldDirectory = directory.listFiles()!!.single()
        val otherKind = File(directory, "mermaid-${"a".repeat(64)}").apply { mkdir(); resolve("mermaid.js").writeText("retained") }
        val temporary = File(directory, "canvas-pending").apply { mkdir(); resolve("part").writeText("retained") }
        val unrelated = File(directory, "canvas-manual").apply { mkdir() }
        val updatedFiles = files + ("excalidraw.js" to "updated renderer".toByteArray())
        val updatedBytes = zip(updatedFiles)
        val updated = manager(spec(updatedBytes, updatedFiles))

        server.enqueue(MockResponse().setResponseCode(503))
        failure { updated.ensure("canvas") }
        assertTrue(old.isReady("canvas"))
        assertTrue(oldDirectory.isDirectory)
        assertTrue(otherKind.isDirectory)
        assertTrue(temporary.isDirectory)

        server.enqueue(response(updatedBytes))
        updated.ensure("canvas")
        assertTrue(updated.isReady("canvas"))
        assertFalse(oldDirectory.exists())
        assertFalse(old.isReady("canvas"))
        assertEquals("retained", otherKind.resolve("mermaid.js").readText())
        assertEquals("retained", temporary.resolve("part").readText())
        assertTrue(unrelated.isDirectory)
    }

    @Test fun mermaidAndCanvasRemainIndependentAndProtectedOrHiddenNotesNeedNoPackage() = runBlocking {
        val mermaidFiles = mapOf("mermaid.js" to "mermaid".toByteArray())
        val mermaidBytes = zip(mermaidFiles)
        val packages = DiagramPackages(directory, mapOf("canvas" to spec(), "mermaid" to spec(mermaidBytes, mermaidFiles)))
        fun note(id: String, type: String, protected: Boolean = false) = NoteMeta(id, id, type, "text/plain", protected, null, null, emptyList())
        assertEquals(setOf("mermaid"), packages.needsFor(listOf(note("visible", "mermaid"), note("secret", "canvas", true), note("_hidden", "canvas"))))
        server.enqueue(response(mermaidBytes))
        packages.ensure("mermaid")
        assertFalse(packages.isReady("canvas"))
        packages.ensure("mermaid")
        assertEquals(1, server.requestCount)
    }
}
