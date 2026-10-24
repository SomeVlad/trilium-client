package ru.vlad.triliumnotes

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.vlad.triliumnotes.data.NotesServices
import ru.vlad.triliumnotes.data.NotesSyncScheduler
import ru.vlad.triliumnotes.data.OfflineState
import ru.vlad.triliumnotes.data.TriliumApi
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.SocketException
import java.security.KeyStore
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/** Exercises real Activity lifecycle and WorkManager, with no access to the configured server. */
class ResumeSyncLifecycleTest {
    @Test fun completedCopySurvivesRepeatedActualActivityStopsAndStarts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        // The worker must be able to run; an offline emulator would hide the old regression.
        assertTrue("Run this controlled-loopback test with emulator Internet enabled", app.hasValidatedInternet())
        val manager = WorkManager.getInstance(app)
        manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        val singleton = NotesServices::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = singleton.get(null)
        val directory = File(app.cacheDir, "lifecycle-synthetic-${System.nanoTime()}").apply { mkdirs() }
        val filesContext = object : ContextWrapper(app) {
            override fun getFilesDir(): File = directory
        }
        val observations = Collections.synchronizedList(mutableListOf<OfflineState>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            LoopbackFixture().use { server ->
                val services = NotesServices(filesContext,
                    TriliumApi(server.url, "synthetic-test-token", server.client), server.url)
                singleton.set(null, services)
                // Surface fixture transport/TLS failures before the crawler intentionally summarizes them.
                runBlocking { services.api.getNote("root") }
                val completed = runBlocking { services.offline.refreshAll() }
                assertTrue("Synthetic baseline failed: $completed; requests=${server.requests}", completed.complete)
                assertEquals(2, completed.completed)
                val crawlRequests = server.crawlRequests()
                assertTrue("Baseline must include a real complete crawl", crawlRequests >= 2)
                scope.launch { services.offline.state.collect { observations += it } }

                ActivityScenario.launch(MainActivity::class.java).use { activity ->
                    instrumentation.waitForIdleSync()
                    repeat(3) {
                        activity.moveToState(Lifecycle.State.CREATED)
                        activity.moveToState(Lifecycle.State.RESUMED)
                        instrumentation.waitForIdleSync()
                        // Activity may refresh root/child metadata. A full crawl always reads attachments.
                        Thread.sleep(750)
                        assertEquals("Resume restarted the full crawl", crawlRequests, server.crawlRequests())
                        assertEquals("Resume reset offline progress", completed, services.offline.state.value)
                        val fullJobs = manager.getWorkInfosForUniqueWork(NotesSyncScheduler.ONE_TIME_WORK_NAME)
                            .get(10, TimeUnit.SECONDS)
                        assertTrue("Resume queued an unnecessary full crawl", fullJobs.none { !it.state.isFinished })
                    }
                }
                assertFalse("Offline progress briefly restarted", observations.any { it.isRunning })
                assertTrue("Expected actual foreground folder reads", server.requests.any { it == "/etapi/branches/qa_branch" })
            }
        } finally {
            scope.cancel()
            manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
            singleton.set(null, previous)
            directory.deleteRecursively()
        }
    }

    /** A TLS loopback fixture keeps the application's production cleartext policy intact. */
    private class LoopbackFixture : Closeable {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        private val tls: SSLContext
        val client: OkHttpClient
        private val socket: SSLServerSocket
        private val worker: Thread
        @Volatile private var closed = false
        val url: String get() = "https://127.0.0.1:${socket.localPort}"

        init {
            // Public, synthetic test key only. It grants access to no real service.
            val password = "qa-loopback".toCharArray()
            val store = KeyStore.getInstance("PKCS12")
            InstrumentationRegistry.getInstrumentation().context.assets.open("lifecycle-loopback.p12").use {
                store.load(it, password)
            }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
            client = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory,
                trust.trustManagers.filterIsInstance<X509TrustManager>().single()).build()
            socket = tls.serverSocketFactory.createServerSocket(0, 16, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            worker = thread(name = "synthetic-lifecycle-loopback", isDaemon = true) {
                while (!closed) {
                    try {
                        socket.accept().use { connection ->
                            connection.soTimeout = 5_000
                            val reader = connection.getInputStream().bufferedReader()
                            val request = reader.readLine() ?: return@use
                            while (!reader.readLine().isNullOrEmpty()) { /* GET headers */ }
                            val path = request.split(' ').getOrNull(1).orEmpty()
                            requests += path
                            val body = when (path) {
                                "/etapi/notes/root" -> """{"noteId":"root","title":"QA library","type":"text","mime":"text/html","blobId":"root-v1","childBranchIds":["qa_branch"]}"""
                                "/etapi/branches/qa_branch" -> """{"branchId":"qa_branch","noteId":"qa_child","parentNoteId":"root","prefix":"","isExpanded":false,"notePosition":0}"""
                                "/etapi/notes/qa_child" -> """{"noteId":"qa_child","title":"Synthetic note","type":"text","mime":"text/html","blobId":"child-v1","childBranchIds":[],"parentNoteIds":["root"]}"""
                                "/etapi/notes/root/content", "/etapi/notes/qa_child/content" -> "<p>Synthetic body</p>"
                                "/etapi/notes/root/attachments", "/etapi/notes/qa_child/attachments" -> "[]"
                                else -> null
                            }
                            val bytes = (body ?: "{}").toByteArray(Charsets.UTF_8)
                            val status = if (body != null) "200 OK" else "404 Not Found"
                            connection.getOutputStream().apply {
                                write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes)
                                flush()
                            }
                        }
                    } catch (error: SocketException) {
                        if (!closed) throw error
                    }
                }
            }
        }

        fun crawlRequests(): Int = synchronized(requests) { requests.count { it.endsWith("/attachments") } }
        override fun close() { closed = true; socket.close(); worker.join(5_000) }
    }
}
