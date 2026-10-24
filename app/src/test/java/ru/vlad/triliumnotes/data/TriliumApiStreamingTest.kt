package ru.vlad.triliumnotes.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Generated streams keep the test itself from buffering the large fixture. */
internal class GeneratedBinaryBody(
    private val size: Long,
    private val declared: Boolean = true,
    private val failAfter: Long = Long.MAX_VALUE,
    private val onRead: (() -> Unit)? = null,
) : ResponseBody() {
    var emitted = 0L
    var largestRead = 0L
    private val stream = object : Source {
        override fun timeout() = Timeout.NONE
        override fun close() = Unit
        override fun read(sink: Buffer, byteCount: Long): Long {
            onRead?.invoke()
            if (emitted >= failAfter) throw IOException("Interrupted synthetic download")
            if (emitted == size) return -1
            largestRead = maxOf(largestRead, byteCount)
            val count = minOf(8192L, byteCount, size - emitted).toInt()
            sink.write(ByteArray(count) { 42 })
            emitted += count
            return count.toLong()
        }
    }.buffer()
    override fun contentType() = "video/mp4".toMediaType()
    override fun contentLength() = if (declared) size else -1L
    override fun source(): BufferedSource = stream
}

internal fun binaryApi(body: ResponseBody, status: Int = 200): TriliumApi {
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(status).message("Synthetic").header("Content-Type", "video/mp4").body(body).build()
    }.build()
    return TriliumApi("https://notes.example", "private-test-token", client)
}

@RunWith(RobolectricTestRunner::class)
class TriliumApiStreamingTest {
    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("binary-stream-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun declaredAndChunkedLargeDownloadsStreamToFile() = withDirectory { directory ->
        for (declared in listOf(true, false)) {
            val size = 100L * 1024 * 1024
            val body = GeneratedBinaryBody(size, declared)
            val destination = File(directory, "large-$declared.mp4")
            val result = runBlocking { binaryApi(body).downloadBinary("noteAAAA", destination) }
            assertEquals(size, result.file.length())
            assertEquals("video/mp4", result.mimeType)
            assertTrue("Read requests must remain bounded", body.largestRead <= 65_536)
            destination.inputStream().use { assertEquals(42, it.read()) }
        }
    }

    @Test fun interruptedStreamRestoresPreviousFile() = withDirectory { directory ->
        val destination = File(directory, "old.mp4").apply { writeText("previous-file") }
        val body = GeneratedBinaryBody(1024 * 1024, failAfter = 200_000)
        try {
            runBlocking { binaryApi(body).downloadAttachment("attachAAAA", destination) }
            fail("Interrupted transfer must fail")
        } catch (_: IOException) { }
        assertEquals("previous-file", destination.readText())
        assertFalse(File(destination.path + ".new").exists())
    }

    @Test fun endedSessionRestoresPreviousFile() = withDirectory { directory ->
        val destination = File(directory, "old.mp4").apply { writeText("previous-file") }
        lateinit var api: TriliumApi
        lateinit var body: GeneratedBinaryBody
        body = GeneratedBinaryBody(1024 * 1024, onRead = { if (body.emitted > 200_000) api.close() })
        api = binaryApi(body)
        try {
            runBlocking { api.downloadBinary("noteAAAA", destination) }
            fail("Ended session must cancel the transfer")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals("previous-file", destination.readText())
    }

    @Test fun compatibilityByteArrayApiRejectsDeclaredAndChunkedOversize() {
        for (declared in listOf(true, false)) {
            val body = GeneratedBinaryBody(MAX_INLINE_RESOURCE_BYTES.toLong() + 100_000, declared)
            try {
                runBlocking { binaryApi(body).getBinary("noteAAAA") }
                fail("In-memory binary API must reject oversized data")
            } catch (_: IOException) { }
            assertTrue(body.emitted <= MAX_INLINE_RESOURCE_BYTES + 16_384L)
        }
    }

    @Test fun textualApiAlsoRejectsDeclaredAndChunkedOversize() {
        for (declared in listOf(true, false)) {
            val body = GeneratedBinaryBody(MAX_INLINE_RESOURCE_BYTES.toLong() + 100_000, declared)
            try {
                runBlocking { binaryApi(body).getContent("noteAAAA") }
                fail("Text API must reject oversized data before decoding it as a String")
            } catch (_: IOException) { }
            assertTrue(body.emitted <= MAX_INLINE_RESOURCE_BYTES + 16_384L)
        }
    }
}
