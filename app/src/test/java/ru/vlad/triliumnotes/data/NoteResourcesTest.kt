package ru.vlad.triliumnotes.data

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class NoteResourcesTest {
    private val server = MockWebServer()
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var api: TriliumApi

    @Before fun start() {
        server.start()
        directory = Files.createTempDirectory("note-resource-test").toFile()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = directory
        }
        api = TriliumApi(server.url("/").toString(), "private-test-token")
    }

    @After fun stop() { server.shutdown(); directory.deleteRecursively() }

    @Test fun actualOwnerAttachmentImageRouteResolvesAttachmentId() {
        assertEquals(ResourceTarget("attachAAAA", true),
            resolveResourceTarget("api/attachments/ownerAAAA/attachAAAA/picture%20one.png", "https://notes.example"))
        assertEquals(ResourceTarget("noteAAAA", false),
            resolveResourceTarget("/api/images/noteAAAA/picture.png", "https://notes.example"))
    }

    @Test fun standardTriliumImageAndDownloadRoutesUseAttachmentIdBeforeAction() {
        listOf("image", "download", "content").forEach { action ->
            assertEquals(ResourceTarget("attachAAAA", true),
                resolveResourceTarget("api/attachments/attachAAAA/$action/picture.png", "https://notes.example"))
        }
    }

    @Test fun foreignOriginsTraversalAndCredentialsCannotResolvePrivateRequests() {
        listOf("https://evil.example/api/images/noteAAAA/a.png", "//evil.example/api/images/noteAAAA/a.png",
            "https://user:password@notes.example/api/images/noteAAAA/a.png",
            "api/attachments/ownerAAAA/attachAAAA/%2e%2e/a.png", "api/images/../../../etc/passwd")
            .forEach { assertNull(it, resolveResourceTarget(it, "https://notes.example")) }
    }

    @Test fun attachmentImageUsesEtapiAndPersistentOfflineCache() {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png-bytes"))
        val first = NoteResources(context, api, server.url("/").toString())
            .load("api/attachments/ownerAAAA/attachAAAA/picture.png")!!
        assertEquals("png-bytes", first.bytes.toString(Charsets.UTF_8))
        assertEquals("/etapi/attachments/attachAAAA/content", server.takeRequest().path)
        assertTrue(File(directory, "note-resources/attachment_attachAAAA.data")
            .setLastModified(System.currentTimeMillis() - 3_600_000))
        var offlineAttempts = 0
        val disconnected = OkHttpClient.Builder().addInterceptor {
            offlineAttempts++
            throw java.io.IOException("Offline transport")
        }.build()
        val offlineApi = TriliumApi("http://127.0.0.1:1", "private-test-token", disconnected)
        val cached = NoteResources(context, offlineApi, "https://notes.example")
            .load("api/attachments/ownerAAAA/attachAAAA/picture.png")!!
        assertArrayEquals(first.bytes, cached.bytes)
        assertEquals("image/png", cached.mimeType)
        assertEquals("Cached viewer image must not wait for any HTTP request", 0, offlineAttempts)
    }

    @Test fun standardImageRouteDownloadsCorrectAttachmentAndReturnsItsBytes() {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody("jpeg-image-bytes"))
        val resource = NoteResources(context, api, server.url("/").toString())
            .load("api/attachments/attachAAAA/image/name%20one.jpg")!!
        assertEquals("/etapi/attachments/attachAAAA/content", server.takeRequest().path)
        assertEquals("jpeg-image-bytes", resource.bytes.toString(Charsets.UTF_8))
        assertEquals("image/jpeg", resource.mimeType)
    }

    @Test fun failedVersionRefreshKeepsOldVersionAndRetriesNextTime() = runBlocking {
        val resources = NoteResources(context, api, server.url("/").toString())
        val note = NoteMeta("noteAAAA", "File", "file", "application/pdf", false,
            "blobAAAA", null, emptyList())
        server.enqueue(MockResponse().setHeader("Content-Type", "application/pdf").setBody("old-pdf"))
        resources.loadNote(note)
        val version = File(directory, "note-resources/note_noteAAAA.version")
        val data = File(directory, "note-resources/note_noteAAAA.data")
        val originalTimestamp = data.lastModified()
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals("old-pdf", resources.loadNote(note.copy(blobId = "blobBBBB")).file.readText())
        assertEquals("blobAAAA", version.readText())
        assertEquals(originalTimestamp, data.lastModified())
        server.enqueue(MockResponse().setHeader("Content-Type", "application/pdf").setBody("new-pdf"))
        assertEquals("new-pdf", resources.loadNote(note.copy(blobId = "blobBBBB")).file.readText())
        assertEquals("blobBBBB", version.readText())
        assertEquals(3, server.requestCount)
    }

    @Test fun externalImagesUseAnonymousClientAndRejectNonImages() {
        var calls = 0
        val anonymous = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
            calls++
            val mime = if (request.url.encodedPath.endsWith(".png")) "image/png" else "text/html"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", mime).body("anonymous-body".toResponseBody(mime.toMediaType())).build()
        }.build()
        val resources = NoteResources(context, api, "https://notes.example", anonymous)
        assertEquals("image/png", resources.load("https://images.example/picture.png")?.mimeType)
        assertNull(resources.load("https://images.example/page.html"))
        assertNull(resources.load("http://images.example/picture.png"))
        assertEquals(2, calls)
        assertEquals(0, server.requestCount)
    }

    @Test fun validatedAttachmentVersionSkipsAgedInlineBytesAndChangedBlobDownloadsAgain() = runBlocking {
        val resources = NoteResources(context, api, server.url("/").toString())
        val owner = NoteMeta("ownerAAAA", "Owner", "text", "text/html", false, "ownerBlob", "v1", emptyList())
        val attachment = AttachmentMeta("attachAAAA", owner.id, "image", "image/png", "Image", 0, "attachmentBlob1")
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("old-image"))
        resources.prefetchAttachment(attachment, owner)
        val data = File(directory, "note-resources/attachment_attachAAAA.data")
        assertTrue(data.setLastModified(System.currentTimeMillis() - 3_600_000))
        resources.prefetchAttachment(attachment, owner)
        resources.prefetchInline("<img src=\"api/attachments/attachAAAA/image/image.png\">", setOf(attachment.id))
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("new-image"))
        resources.prefetchAttachment(attachment.copy(blobId = "attachmentBlob2"), owner)
        assertEquals(2, server.requestCount)
        assertEquals("new-image", data.readText())
    }

    @Test fun markdownDiscoversDirectReferenceAndRawHtmlImagesButIgnoresCodeFences() = runBlocking {
        val resources = NoteResources(context, api, server.url("/").toString())
        repeat(3) { server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("image-$it")) }
        val markdown = """
            ![direct](api/attachments/attachAAAA/image/a.png)
            ![reference][picture]

            [picture]: api/attachments/attachBBBB/image/b.png

            <img src="api/attachments/attachCCCC/image/c.png">

            ```html
            <img src="api/attachments/attachDDDD/image/d.png">
            ![fenced](api/attachments/attachEEEE/image/e.png)
            ```
        """.trimIndent()
        assertEquals(3, resources.prefetchInline(markdown, markdown = true))
        val paths = (0 until 3).map { server.takeRequest().path }.toSet()
        assertEquals(setOf("/etapi/attachments/attachAAAA/content", "/etapi/attachments/attachBBBB/content",
            "/etapi/attachments/attachCCCC/content"), paths)
        assertEquals(3, server.requestCount)
    }
}
