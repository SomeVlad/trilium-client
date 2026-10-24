package ru.vlad.triliumnotes.data

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class AttachmentUploadTest {
    private val server = MockWebServer()
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var api: TriliumApi
    private lateinit var repository: NotesRepository
    private val catalog = mutableListOf<JSONObject>()
    private var createCount = 0
    private var putCount = 0
    private var failPut = false
    private var loseCreateResponse = false
    private var returnUploadedVersion = true
    private var uploaded = byteArrayOf()

    @Before fun start() {
        directory = Files.createTempDirectory("attachment-upload-test").toFile()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = directory
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (path == "/etapi/notes/noteAAAA/content") return MockResponse().setBody("<p>Original</p>")
                if (path == "/etapi/notes/noteAAAA/attachments") return MockResponse().setBody(JSONArray(catalog).toString())
                if (request.method == "POST" && path == "/etapi/attachments") {
                    createCount++
                    val attachment = JSONObject(request.body.readUtf8()).put("attachmentId", "attachAAAA")
                    catalog += attachment
                    if (loseCreateResponse) {
                        loseCreateResponse = false
                        return MockResponse().setResponseCode(503)
                    }
                    return MockResponse().setResponseCode(201).setBody(attachment.toString())
                }
                if (request.method == "PUT") {
                    putCount++
                    if (failPut) return MockResponse().setResponseCode(503)
                    assertEquals("application/octet-stream", request.getHeader("Content-Type"))
                    uploaded = request.body.readByteArray()
                    if (returnUploadedVersion) catalog.single().put("blobId", "confirmedBlob")
                    return MockResponse().setResponseCode(204)
                }
                if (request.method == "PATCH") {
                    catalog.single().put("title", JSONObject(request.body.readUtf8()).getString("title"))
                    return MockResponse().setBody(catalog.single().toString())
                }
                return MockResponse().setBody(JSONObject().put("noteId", "noteAAAA").put("title", "Note")
                    .put("type", "text").put("mime", "text/html").put("isProtected", false)
                    .put("blobId", "v1").put("parentNoteIds", JSONArray().put("root"))
                    .put("childBranchIds", JSONArray()).toString())
            }
        }
        server.start()
        api = TriliumApi(server.url("/").toString(), "synthetic-token")
        repository = NotesRepository(context, api)
        runBlocking { repository.load("noteAAAA") }
    }

    @After fun stop() { api.close(); server.shutdown(); directory.deleteRecursively() }

    private fun queue(append: suspend (PendingAttachmentUpload, AttachmentMeta) -> Unit = { _, _ -> }) =
        AttachmentUploadQueue(context, api, repository, appendReference = append)

    private suspend fun stage(queue: AttachmentUploadQueue, bytes: ByteArray = byteArrayOf(0, 1, 13, 10, -1)): PendingAttachmentUpload {
        val source = File(directory, "phone.pdf").apply { writeBytes(bytes) }
        val upload = queue.stage(Uri.fromFile(source), "noteAAAA", false)
        source.delete()
        return upload
    }

    @Test fun failedBinaryUploadSurvivesRestartAndNeverAddsAnEmptyAttachmentLink() = runBlocking {
        var appended = 0
        val first = queue { _, _ -> appended++ }
        val upload = stage(first)
        failPut = true
        first.syncPending()
        assertEquals(0, appended)
        assertEquals(1, first.state.value.pendingCount)
        assertNotNull(first.state.value.error)
        assertTrue(File(directory, "attachment-uploads/${upload.uploadId}/content").isFile)
        failPut = false
        val restarted = queue { _, _ -> appended++ }
        assertEquals(1, restarted.state.value.pendingCount)
        restarted.syncPending()
        assertEquals(1, appended)
        assertEquals(1, createCount)
        assertArrayEquals(byteArrayOf(0, 1, 13, 10, -1), uploaded)
        assertEquals(0, restarted.state.value.pendingCount)
        assertEquals("phone.pdf", catalog.single().getString("title"))
        assertArrayEquals(uploaded, NoteResources(context, api).load("api/attachments/noteAAAA/attachAAAA/phone.pdf")!!.bytes)
        assertArrayEquals(uploaded, NoteResources(context, api).loadLinkedAttachment("api/attachments/noteAAAA/attachAAAA/phone.pdf").file.readBytes())
        assertEquals("confirmedBlob", File(directory, "note-resources/attachment_attachAAAA.version").readText())
    }

    @Test fun lostCreateResponseIsRecoveredFromUniqueMarkerWithoutDuplicateAttachment() = runBlocking {
        val first = queue()
        stage(first)
        loseCreateResponse = true
        first.syncPending()
        assertEquals(1, first.state.value.pendingCount)
        assertEquals(0, putCount)
        val restarted = queue()
        restarted.syncPending()
        assertEquals(1, createCount)
        assertEquals(1, putCount)
        assertEquals(0, restarted.state.value.pendingCount)
    }

    @Test fun confirmedPutIsNotRepeatedWhenDurableReferenceCallbackFails() = runBlocking {
        val first = queue { _, _ -> throw IOException("Synthetic note save failure") }
        stage(first)
        first.syncPending()
        assertEquals(1, first.state.value.pendingCount)
        assertEquals(1, putCount)
        var appended = false
        val restarted = queue { upload, _ -> assertTrue(upload.contentUploaded); appended = true }
        restarted.syncPending()
        assertTrue(appended)
        assertEquals(1, putCount)
        assertEquals(1, createCount)
        assertEquals(0, restarted.state.value.pendingCount)
    }

    @Test fun missingCacheVersionKeepsConfirmedUploadAndOriginalBytesUntilCachingCanFinish() = runBlocking {
        var appended = 0
        val first = queue { _, _ -> appended++ }
        val upload = stage(first)
        returnUploadedVersion = false
        first.syncPending()
        assertEquals(0, appended)
        assertEquals(1, first.state.value.pendingCount)
        assertTrue(File(directory, "attachment-uploads/${upload.uploadId}/content").isFile)
        catalog.single().put("blobId", "confirmedBlob")
        val restarted = queue { _, _ -> appended++ }
        restarted.syncPending()
        assertEquals(1, appended)
        assertEquals(1, putCount)
        assertEquals(0, restarted.state.value.pendingCount)
        assertTrue(File(directory, "note-resources/attachment_attachAAAA.data").isFile)
    }

    @Test fun missingPickerSourceCannotEnterTheDurableQueue() = runBlocking {
        val queue = queue()
        try {
            queue.stage(Uri.fromFile(File(directory, "missing.pdf")), "noteAAAA", false)
            fail("Missing source must fail")
        } catch (_: IOException) { }
        assertEquals(0, queue.state.value.pendingCount)
        assertFalse(File(directory, "attachment-uploads").listFiles().orEmpty().any { it.isDirectory })
    }

    @Test fun activeEditorDeferralIsPendingWithoutAnUploadError() = runBlocking {
        val deferred = queue { _, _ -> throw AttachmentInsertionDeferred() }
        stage(deferred)
        deferred.syncPending()
        assertEquals(1, deferred.state.value.pendingCount)
        assertNull(deferred.state.value.error)
        assertEquals(1, putCount)
        val resumed = queue { upload, attachment -> repository.appendAttachmentReference(upload, attachment) }
        resumed.syncPending()
        assertEquals(0, resumed.state.value.pendingCount)
        assertEquals(1, putCount)
        assertTrue(repository.cachedNote("noteAAAA")!!.html.contains("attachAAAA"))
    }

    @Test fun atomicAppendPreservesCurrentDraftAndIsIdempotentAcrossRestart() = runBlocking {
        val upload = PendingAttachmentUpload("id", "noteAAAA", "phone.pdf", "application/pdf", false,
            "attachAAAA", contentUploaded = true)
        val attachment = AttachmentMeta("attachAAAA", "noteAAAA", "file", upload.mime, upload.title, 0)
        repository.saveDraft("noteAAAA", "Locally renamed", "<p>Latest unsynced typing</p>")
        val first = repository.appendAttachmentReference(upload, attachment)
        assertEquals("Locally renamed", first.note.title)
        assertTrue(first.html.startsWith("<p>Latest unsynced typing</p>"))
        val restarted = NotesRepository(context, api)
        val second = restarted.appendAttachmentReference(upload, attachment)
        assertEquals(first.html, second.html)
        assertEquals(first.note.title, second.note.title)
        assertTrue(second.pending)
    }

    @Test fun deletedOwnerRetainsLocalBytesWithoutRetryingUpload() = runBlocking {
        val queue = queue()
        val upload = stage(queue)
        repository.delete("noteAAAA")
        queue.syncPending()
        assertEquals(0, queue.state.value.pendingCount)
        assertEquals(0, createCount)
        assertTrue(File(directory, "attachment-uploads-retained/${upload.uploadId}/content").isFile)
    }

    @Test fun stagingSupportsFilesAboveTheInlinePreviewLimit() = runBlocking {
        val source = File(directory, "large.pdf")
        val chunks = MAX_INLINE_RESOURCE_BYTES / 8192 + 128
        source.outputStream().use { output ->
            val chunk = ByteArray(8192) { 42 }
            repeat(chunks) { output.write(chunk) }
        }
        val queue = queue()
        val upload = queue.stage(Uri.fromFile(source), "noteAAAA", false)
        val copy = File(directory, "attachment-uploads/${upload.uploadId}/content")
        assertEquals(chunks * 8192L, copy.length())
        source.delete()
        assertEquals(chunks * 8192L, copy.length())
        assertEquals(1, AttachmentUploadQueue(context, api, repository) { _, _ -> }.state.value.pendingCount)
    }

    @Test fun referencesEscapeUntrustedFilenamesForBothEditors() {
        val upload = PendingAttachmentUpload("id", "noteAAAA", "x\"](<script>) &.pdf", "application/pdf", false)
        val meta = AttachmentMeta("attachAAAA", "noteAAAA", "file", upload.mime, upload.title, 0)
        val html = attachmentReference(upload, meta, false)
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&amp;"))
        assertTrue(html.contains("%3Cscript%3E"))
        val markdown = attachmentReference(upload, meta, true)
        assertTrue(markdown.contains("\\]"))
        assertTrue(markdown.contains("(<api/attachments/noteAAAA/attachAAAA/"))
        assertEquals("hidden.pdf", uploadTitle("../hidden.pdf\n"))
    }
}
