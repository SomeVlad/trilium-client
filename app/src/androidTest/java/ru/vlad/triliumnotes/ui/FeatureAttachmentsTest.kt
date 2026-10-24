package ru.vlad.triliumnotes.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import ru.vlad.triliumnotes.data.AttachmentUploadQueue
import ru.vlad.triliumnotes.data.NoteResources
import ru.vlad.triliumnotes.data.NotesRepository
import ru.vlad.triliumnotes.data.TriliumApi
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real picker contract/content URI and private outbox; every HTTP response is synthetic. */
class FeatureAttachmentsTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun filePickerResultIsDurableBeforeNetworkAndResumesIntoAnOfflineReadableLink() = exercise(false)
    @Test fun imagePickerResultCreatesAnInlineImageWithoutDownloadingTheUploadedBytes() = exercise(true)

    private fun exercise(image: Boolean) {
        val directory = File(app.filesDir, "accounts/feature-attachments-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = directory
        }
        val expected = byteArrayOf(0, 1, 13, 10, -1)
        val source = File(directory, if (image) "phone.png" else "phone.pdf").apply { writeBytes(expected) }
        val sourceUri = FileProvider.getUriForFile(app, "${app.packageName}.files", source)
        val accepted = AtomicBoolean(false)
        val attachment = AtomicReference<JSONObject?>(null)
        var creates = 0
        var binaryDownloads = 0
        var received: ByteArray? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            var status = 200
            val body = when {
                request.method == "GET" && path == "/etapi/notes/noteAAAA/content" -> "<p>Synthetic note</p>"
                request.method == "GET" && path == "/etapi/notes/noteAAAA/attachments" ->
                    JSONArray().apply { attachment.get()?.let { put(it) } }.toString()
                request.method == "POST" && path == "/etapi/attachments" -> {
                    creates++
                    status = 201
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    JSONObject(buffer.readUtf8()).put("attachmentId", "attachAAAA").also { attachment.set(it) }.toString()
                }
                request.method == "PUT" && path == "/etapi/attachments/attachAAAA/content" -> {
                    status = if (accepted.get()) 204 else 503
                    if (accepted.get()) {
                        val buffer = Buffer()
                        request.body!!.writeTo(buffer)
                        received = buffer.readByteArray()
                        attachment.get()!!.put("blobId", "confirmedBlob")
                    }
                    ""
                }
                request.method == "PATCH" && path == "/etapi/attachments/attachAAAA" -> {
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    attachment.get()!!.put("title", JSONObject(buffer.readUtf8()).getString("title")).toString()
                }
                request.method == "GET" && path == "/etapi/attachments/attachAAAA/content" -> {
                    binaryDownloads++
                    throw java.io.IOException("Uploaded bytes must already be cached")
                }
                else -> JSONObject().put("noteId", "noteAAAA").put("title", "Synthetic note")
                    .put("type", "text").put("mime", "text/html").put("blobId", "noteBlob")
                    .put("parentNoteIds", JSONArray().put("root")).put("childBranchIds", JSONArray()).toString()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic")
                .header("Content-Type", "application/json").body(body.toResponseBody()).build()
        }.build()
        val api = TriliumApi("https://attachment-fixture.invalid", "synthetic-test-token", client)
        try {
            val repository = NotesRepository(context, api)
            runBlocking { repository.load("noteAAAA") }
            val resources = NoteResources(context, api)
            val queue = AttachmentUploadQueue(context, api, repository, resources) { upload, meta ->
                repository.appendAttachmentReference(upload, meta)
            }
            val staged = AtomicBoolean(false)
            val failure = AtomicReference<Throwable?>(null)
            var starts = 0
            var requestedMime: String? = null
            val registry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                    options: ActivityOptionsCompat?) {
                    assertTrue(contract is ActivityResultContracts.OpenDocument)
                    requestedMime = (input as Array<*>).single() as String
                    dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(sourceUri))
                }
            }
            val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
            compose.setContent {
                val scope = rememberCoroutineScope()
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    NotesTheme {
                        AttachmentPicker(enabled = true, onPickStarted = { starts++ }, onPicked = { uri: Uri, inline: Boolean ->
                            assertEquals(image, inline)
                            scope.launch {
                                try { queue.stage(uri, "noteAAAA", inline); staged.set(true) }
                                catch (error: Throwable) { failure.set(error) }
                            }
                        })
                    }
                }
            }
            compose.onNodeWithTag("attach").performClick()
            compose.onNodeWithText(AppLanguage.get(app, if (image) R.string.action_attach_image else R.string.action_attach_file)).performClick()
            compose.waitUntil(10_000) { staged.get() || failure.get() != null }
            failure.get()?.let { throw AssertionError("Picker content URI could not be staged", it) }
            assertEquals(1, starts)
            assertEquals(if (image) "image/*" else "*/*", requestedMime)
            assertTrue(source.delete())
            runBlocking {
                assertTrue(queue.hasPending("noteAAAA"))
                assertFalse(queue.hasPending("otherAAAA"))
                queue.syncPending()
                assertEquals(1, queue.state.value.pendingCount)
                assertFalse(repository.cachedNote("noteAAAA")!!.html.contains("attachAAAA"))
                accepted.set(true)
                val restarted = AttachmentUploadQueue(context, api, repository, resources) { upload, meta ->
                    repository.appendAttachmentReference(upload, meta)
                }
                restarted.syncPending()
                assertFalse(restarted.hasPending("noteAAAA"))
                assertEquals(0, restarted.state.value.pendingCount)
                val body = repository.cachedNote("noteAAAA")!!.html
                assertTrue(body.contains(if (image) "<img " else "<a "))
                val path = "api/attachments/noteAAAA/attachAAAA/${source.name}"
                assertTrue(body.contains(path))
                assertArrayEquals(expected, resources.load(path)!!.bytes)
                assertArrayEquals(expected, resources.loadLinkedAttachment(path).file.readBytes())
                assertEquals(1, creates)
                assertEquals(0, binaryDownloads)
                assertArrayEquals(expected, received)
            }
        } finally {
            api.close()
            directory.deleteRecursively()
        }
    }
}
