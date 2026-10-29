package ru.vlad.triliumnotes

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModelStore
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import ru.vlad.triliumnotes.capture.CaptureInbox
import ru.vlad.triliumnotes.capture.PendingCapture
import ru.vlad.triliumnotes.data.LibrarySection
import ru.vlad.triliumnotes.data.NoteSort
import ru.vlad.triliumnotes.data.NotesRepository
import ru.vlad.triliumnotes.data.NotesServices
import ru.vlad.triliumnotes.data.TriliumApi
import ru.vlad.triliumnotes.ui.LibraryAction
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Public ViewModel operations, backed by real durable repositories and a synthetic ETAPI server. */
@RunWith(RobolectricTestRunner::class)
class NotesViewModelFeaturesTest {
    private val server = MockWebServer()
    private val remote = FeatureServer()
    private val models = ViewModelStore()
    private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val singleton = NotesServices::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previousServices: Any? = null
    private val createdServices = mutableListOf<NotesServices>()
    private lateinit var directory: File
    private lateinit var serviceContext: Context
    private lateinit var captureContext: Context
    private lateinit var sharedFile: File
    private lateinit var services: NotesServices
    private lateinit var model: NotesViewModel
    private val providerOpens = AtomicInteger()
    private val sharedUri = Uri.parse("content://synthetic.vm.capture.fixture/files/1")

    @Before fun start() {
        val application = RuntimeEnvironment.getApplication()
        previousServices = singleton.get(null)
        directory = Files.createTempDirectory("notes-vm-features").toFile()
        captureContext = filesContext("device")
        sharedFile = File(directory, "source.bin").apply { writeBytes(SHARED_BYTES) }
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("shared.bin")) }
            override fun getType(uri: Uri) = "application/octet-stream"
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                providerOpens.incrementAndGet()
                return ParcelFileDescriptor.open(sharedFile, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(application, android.content.pm.ProviderInfo().apply {
            authority = sharedUri.authority; exported = true; grantUriPermissions = true
        })
        ShadowContentResolver.registerProviderInternal(sharedUri.authority, provider)
        server.dispatcher = remote
        server.start()
        WorkManagerTestInitHelper.initializeTestWorkManager(application, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build())
        installModel()
        runBlocking {
            for (id in listOf("root", FOLDER, ALPHA, BETA, MARKDOWN)) {
                services.repository.listChildren(id)
                val document = services.repository.load(id)
                val catalog = services.api.getAttachments(id)
                services.repository.rememberAttachments(id, catalog)
                catalog.forEach { services.resources.prefetchAttachment(it, document.note) }
            }
        }
    }

    @After fun stop() {
        remote.releaseOfflineResponse.countDown()
        tasks.cancel()
        models.clear()
        createdServices.forEach { it.api.close() }
        shadowOf(Looper.getMainLooper()).idle()
        singleton.set(null, previousServices)
        server.shutdown()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test fun captureCrashReplayKeepsBoundAccountParentTitleAndAppliesTextAndFileExactlyOnce() {
        val inbox = CaptureInbox(captureContext)
        val capture = receive(inbox, "VM unique capture", withFile = true)
        val target = "sharedAA"
        val originalTitle = "Chosen title before process death"
        val originalAccount = model.accountId
        inbox.bindTarget(capture.id, originalAccount, target, FOLDER, originalTitle)
        val uploadId = UUID.nameUUIDFromBytes((capture.id + ":0").toByteArray()).toString()
        remote.online = false
        // Simulate process death after durable delivery, before the inbox checkpoints were written.
        runBlocking {
            services.repository.createWithId(FOLDER, originalTitle, "<p></p>", target, "branchAA")
            services.repository.appendCapturedText(target, capture.id, "<p>VM unique capture</p>")
            services.uploads.stageFile(inbox.attachmentFile(capture.id, "0"), target,
                "shared.bin", "application/octet-stream", false, uploadId)
        }
        assertTrue(sharedFile.delete())
        installModel(beforeStart = { remote.holdNextOfflineResponse("/etapi/notes/$target") })
        val restoredInbox = CaptureInbox(captureContext)
        val checkpoint = restoredInbox.entries.value.single()
        assertFalse(checkpoint.textApplied)
        assertTrue(checkpoint.appliedAttachmentIds.isEmpty())

        model.saveCapture(restoredInbox, checkpoint, "A later accidental title", "root", null)

        await { !model.state.value.operationBusy && model.state.value.document?.note?.id == target }
        assertTrue(restoredInbox.entries.value.isEmpty())
        val local = services.repository.cachedNote(target)!!
        assertEquals(originalTitle, local.note.title)
        assertEquals(listOf(FOLDER), local.note.parentNoteIds)
        assertEquals(1, occurrences(local.html, "VM unique capture"))
        assertEquals(originalAccount, model.accountId)
        assertEquals(1, services.uploads.state.value.pendingCount)
        assertArrayEquals(SHARED_BYTES, File(serviceContext.filesDir, "attachment-uploads/$uploadId/content").readBytes())
        assertEquals(1, providerOpens.get())
        assertNull(restoredInbox.receive(textShare("VM unique capture"), capture.id))

        // Reconnect while the old cycle still owns an in-flight response from the offline period.
        // That stale 503 is returned after reconnect: the queued sync must run another complete cycle.
        await { remote.offlineResponseEntered.count == 0L && model.syncState.value.isSyncing }
        remote.requests.clear()
        remote.online = true
        model.syncNow()
        remote.releaseOfflineResponse.countDown()
        await { model.syncState.value.pendingCount == 0 && model.uploadState.value.pendingCount == 0 }
        assertEquals(1, remote.noteCreates.get())
        assertEquals(1, remote.attachmentCreates.get())
        val uploaded = remote.attachments.values.single { it.owner == target }
        assertArrayEquals(SHARED_BYTES, uploaded.bytes)
        assertEquals(1, occurrences(remote.notes.getValue(target).body, "VM unique capture"))
        assertEquals(1, occurrences(remote.notes.getValue(target).body, uploaded.id))
        assertEquals(originalTitle, remote.notes.getValue(target).title)
        assertEquals(FOLDER, remote.notes.getValue(target).parent)
    }

    @Test fun captureBoundToAnotherAccountCannotBeDeliveredOrReboundByViewModel() {
        val inbox = CaptureInbox(captureContext)
        val capture = receive(inbox, "Account scoped capture")
        val originalAccount = model.accountId
        inbox.bindTarget(capture.id, originalAccount, "boundAAA", FOLDER, "Original title")
        val checkpoint = CaptureInbox(captureContext).entries.value.single()
        remote.online = false
        installModel("account-b", server.url("/another-account/").toString())
        assertNotEquals(originalAccount, model.accountId)

        model.saveCapture(inbox, checkpoint, "Wrong account title", "root", null)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(model.state.value.operationBusy)
        assertNull(model.state.value.document)
        assertNull(services.repository.cachedNote("boundAAA"))
        assertEquals(0, model.syncState.value.pendingCount)
        val retained = CaptureInbox(captureContext).entries.value.single()
        assertEquals(originalAccount, retained.accountId)
        assertEquals("boundAAA", retained.targetNoteId)
        assertEquals(FOLDER, retained.targetParentId)
        assertEquals("Original title", retained.targetTitle)
        assertEquals(0, remote.noteCreates.get())
    }

    @Test fun captureIntoExistingMarkdownKeepsPlainTextAndExistingTitle() {
        val inbox = CaptureInbox(captureContext)
        val plain = "<literal> & **plain markdown**\nnext line"
        val capture = receive(inbox, plain)
        remote.online = false

        model.saveCapture(inbox, capture, "Ignored append title", null, MARKDOWN)

        await { !model.state.value.operationBusy && model.state.value.document?.note?.id == MARKDOWN }
        val saved = services.repository.cachedNote(MARKDOWN)!!
        assertEquals("Markdown title", saved.note.title)
        assertEquals("text/x-markdown", saved.note.mime)
        assertEquals("Existing *markdown*\n\n$plain\n", saved.html)
        assertFalse(saved.html.contains("&lt;literal&gt;"))
        assertTrue(CaptureInbox(captureContext).entries.value.isEmpty())
        assertEquals(saved.html, reopenedRepository().cachedNote(MARKDOWN)?.html)
    }

    @Test fun quickCreateReplayAcrossRecreationPreservesTypedDraftAndCreatesOneRemoteNote() {
        remote.online = false
        val handled = AtomicInteger()
        val request = "stable-widget-request-001"
        model.quickCreate(request) { handled.incrementAndGet() }
        await { handled.get() == 1 && !model.state.value.operationBusy }
        val id = model.state.value.document!!.note.id
        model.editTitle(id, "Typed widget title")
        model.editHtml(id, "<p>Typed widget body</p>")
        await { !model.state.value.localWritePending }
        installModel()

        model.quickCreate(request) { handled.incrementAndGet() }

        await { handled.get() == 2 && !model.state.value.operationBusy }
        assertEquals(id, model.state.value.document?.note?.id)
        assertEquals("Typed widget title", model.state.value.document?.note?.title)
        assertEquals("<p>Typed widget body</p>", model.state.value.document?.html)
        assertEquals(1, services.repository.cachedVisibleNotes().count { it.id == id })
        await { !model.syncState.value.isSyncing }
        remote.online = true
        model.syncNow()
        await { model.syncState.value.pendingCount == 0 }
        model.quickCreate(request) { handled.incrementAndGet() }
        await { handled.get() == 3 && !model.state.value.operationBusy }
        assertEquals(1, remote.noteCreates.get())
        assertEquals("Typed widget title", remote.notes.getValue(id).title)
        assertEquals("<p>Typed widget body</p>", remote.notes.getValue(id).body)
    }

    @Test fun bulkTrashAndRestorePreserveQueuedTitleAndBodyAcrossRecreation() {
        val alpha = services.repository.cachedNote(ALPHA)!!
        val beta = services.repository.cachedMeta(BETA)!!
        model.open(alpha.note)
        remote.online = false
        model.editTitle(ALPHA, "Unsent title survives trash")
        model.editHtml(ALPHA, "<p>Unsent body survives trash &amp; restore</p>")
        await { !model.state.value.localWritePending }

        model.trashMany(listOf(alpha.note, beta, alpha.note))

        await { !model.state.value.operationBusy && services.repository.isDeleted(ALPHA) && services.repository.isDeleted(BETA) }
        assertNull(model.state.value.document)
        assertTrue(model.state.value.items.none { it.note.id in setOf(ALPHA, BETA) })
        assertEquals(2, services.repository.cachedTrash().size)
        assertTrue(services.repository.isRecoverablyDeleted(ALPHA))
        installModel()
        model.restoreFromTrash(ALPHA)
        await { !model.state.value.operationBusy }
        await { !model.syncState.value.isSyncing }
        remote.online = true
        model.syncNow()

        await { !services.repository.isDeleted(ALPHA) && model.syncState.value.pendingCount == 0 }
        val restored = services.repository.cachedNote(ALPHA)!!
        assertEquals("Unsent title survives trash", restored.note.title)
        assertEquals("<p>Unsent body survives trash &amp; restore</p>", restored.html)
        assertEquals(restored.html, remote.notes.getValue(ALPHA).body)
        assertEquals(restored.note.title, remote.notes.getValue(ALPHA).title)
        assertTrue(services.repository.isDeleted(BETA))
        assertEquals(restored.html, reopenedRepository().cachedNote(ALPHA)?.html)
        assertEquals(0, remote.noteCreates.get())
    }

    @Test fun favoritesRecentAndFolderSortSurviveRecreationAndStayAccountScoped() {
        model.toggleFavorite(BETA)
        model.toggleFavorite(ALPHA)
        model.selectSort(NoteSort.TITLE)
        model.open(services.repository.cachedMeta(ALPHA)!!)
        await { model.libraryPreferences.value.favorites == setOf(ALPHA, BETA) &&
            model.libraryPreferences.value.recent.firstOrNull() == ALPHA &&
            model.libraryPreferences.value.sorts["root"] == NoteSort.TITLE }
        installModel()

        model.selectSection(LibrarySection.FAVORITES)
        assertEquals(listOf(ALPHA, BETA), model.collectionNotes().map { it.id })
        model.selectSection(LibrarySection.RECENT)
        assertEquals(listOf(ALPHA), model.collectionNotes().map { it.id })
        assertEquals(NoteSort.TITLE, model.libraryPreferences.value.sorts["root"])
        model.toggleFavorite(ALPHA)
        await { model.libraryPreferences.value.favorites == setOf(BETA) }
        installModel("account-b", server.url("/another-account/").toString())
        assertTrue(model.libraryPreferences.value.favorites.isEmpty())
        assertTrue(model.libraryPreferences.value.recent.isEmpty())
        assertTrue(model.libraryPreferences.value.sorts.isEmpty())
        model.selectSection(LibrarySection.FAVORITES)
        assertTrue(model.collectionNotes().isEmpty())
    }

    @Test fun pinIsLocalSurvivesRecreationAndDoesNotChangeFavoritesOrCreateFolderRows() {
        // Compare the same durable-cache lifecycle on both sides of the pin operation.
        // The fixture initially contains live API metadata, before its first cache round trip.
        installModel()
        val folderJob = NotesViewModel::class.java.getDeclaredField("folderJob").apply { isAccessible = true }
        await { (folderJob.get(model) as? kotlinx.coroutines.Job)?.isActive != true }
        val originalRows = model.state.value.items
        val cached = File(serviceContext.filesDir, "trilium-cache.json").readBytes()
        val mutations = remote.requests.filterNot { it.startsWith("GET ") }
        model.toggleFavorite(ALPHA)
        model.togglePin(BETA)
        await { model.libraryPreferences.value.favorites == setOf(ALPHA) && model.isPinned(BETA) }
        installModel()
        await { (folderJob.get(model) as? kotlinx.coroutines.Job)?.isActive != true }

        assertTrue(model.isPinned(BETA))
        assertFalse(model.isPinned(ALPHA))
        assertEquals(originalRows, model.state.value.items)
        model.selectSection(LibrarySection.FAVORITES)
        assertEquals(listOf(ALPHA), model.collectionNotes().map { it.id })
        model.toggleFavorite(BETA)
        await { model.libraryPreferences.value.favorites == setOf(ALPHA, BETA) }
        model.togglePin(BETA)
        await { !model.isPinned(BETA) }
        assertEquals(setOf(ALPHA, BETA), model.libraryPreferences.value.favorites)
        assertEquals(0, model.syncState.value.pendingCount)
        assertEquals(mutations, remote.requests.filterNot { it.startsWith("GET ") })
        assertArrayEquals(cached, File(serviceContext.filesDir, "trilium-cache.json").readBytes())

        model.togglePin(BETA)
        await { model.isPinned(BETA) }
        installModel("account-b", server.url("/another-account/").toString())
        assertTrue(model.libraryPreferences.value.pinned.isEmpty())
        installModel()
        assertEquals(setOf(BETA), model.libraryPreferences.value.pinned)
    }

    @Test fun attachmentMetadataGateDefersReplayWhileEditingThenRefreshesCurrentDocument() {
        model.open(services.repository.cachedMeta(ALPHA)!!)
        model.setEditing(ALPHA, true)
        val before = model.state.value.document!!
        val epoch = model.state.value.editorEpoch
        runBlocking {
            services.attachments.list(ALPHA)
            services.attachments.rename(ALPHA, ATTACHMENT, "renamed file.txt")
        }
        val originalGate = services.attachments.syncGate!!
        val replayCallbacks = AtomicInteger()
        services.attachments.syncGate = { operation -> originalGate { replayCallbacks.incrementAndGet(); operation() } }

        val deferred = tasks.async { services.attachments.syncPending() }
        await { deferred.isCompleted }
        assertEquals(1, runBlocking { deferred.await() })
        assertEquals(0, replayCallbacks.get())
        assertEquals(0, remote.attachmentPatches.get())
        assertEquals(before, model.state.value.document)
        assertFalse(model.state.value.operationBusy)

        model.setEditing(ALPHA, false)

        await { services.attachments.state.value.pendingCount == 0 && remote.attachmentPatches.get() == 1 }
        await { model.state.value.document?.html?.contains(">renamed file.txt</a>") == true && !model.state.value.operationBusy }
        assertEquals(1, replayCallbacks.get())
        assertTrue(model.state.value.editorEpoch > epoch)
        assertEquals(1, occurrences(model.state.value.document!!.html, ATTACHMENT))
        assertEquals("renamed file.txt", remote.attachments.getValue(ATTACHMENT).title)
        assertArrayEquals(ORIGINAL_ATTACHMENT_BYTES, remote.attachments.getValue(ATTACHMENT).bytes)
        services.attachments.syncGate = originalGate
    }

    @Test fun bulkCopyPreflightRejectsUnsupportedSecondSelectionBeforeCreatingFirstCopy() {
        remote.notes.getValue(BETA).isProtected = true
        runBlocking { services.repository.load(BETA) }
        val beforeIds = services.repository.cachedVisibleNotes().map { it.id }.toSet()
        val beforeCache = File(serviceContext.filesDir, "trilium-cache.json").readBytes()
        runBlocking { services.repository.validateCopySubtree(ALPHA, FOLDER) }
        assertArrayEquals(beforeCache, File(serviceContext.filesDir, "trilium-cache.json").readBytes())
        assertFalse(File(serviceContext.filesDir, "tree-copies").exists())

        model.organize(LibraryAction.COPY, listOf(services.repository.cachedMeta(ALPHA)!!,
            services.repository.cachedMeta(BETA)!!), FOLDER, "root")

        await { !model.state.value.operationBusy && model.panelError.value != null }
        assertEquals(beforeIds, services.repository.cachedVisibleNotes().map { it.id }.toSet())
        assertEquals(0, model.syncState.value.pendingCount)
        assertEquals(0, remote.noteCreates.get())
        assertTrue(File(serviceContext.filesDir, "tree-copies").listFiles().isNullOrEmpty())
    }

    private fun installModel(account: String = "account-a", url: String = server.url("/").toString(), beforeStart: (() -> Unit)? = null) {
        models.clear()
        if (::services.isInitialized) services.api.close()
        shadowOf(Looper.getMainLooper()).idle()
        serviceContext = filesContext(account)
        services = NotesServices(serviceContext, api(url, if (beforeStart == null) 2 else 15), url).also(createdServices::add)
        singleton.set(null, services)
        beforeStart?.invoke()
        model = NotesViewModel(RuntimeEnvironment.getApplication(), services)
        models.put("features", model)
        await { !model.state.value.loading }
    }
    private fun filesContext(name: String): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = File(directory, name).apply { mkdirs() }
        override fun getApplicationContext(): Context = this
    }
    private fun api(url: String = server.url("/").toString(), readTimeoutSeconds: Long = 2) = TriliumApi(url, "synthetic-vm-feature-token",
        OkHttpClient.Builder().retryOnConnectionFailure(false).connectTimeout(300, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build())
    private fun reopenedRepository() = NotesRepository(serviceContext, api())
    private fun receive(inbox: CaptureInbox, text: String, withFile: Boolean = false): PendingCapture {
        val intent = textShare(text)
        if (withFile) intent.setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, sharedUri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return inbox.receive(intent, UUID.randomUUID().toString())!!
    }
    private fun textShare(text: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    private fun occurrences(text: String, value: String) = Regex(Regex.escape(value)).findAll(text).count()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        val checkpoints = File(serviceContext.filesDir, "attachment-uploads").listFiles().orEmpty().mapNotNull { folder ->
            runCatching { File(folder, "entry.json").takeIf { it.isFile }?.readText() }.getOrNull()
        }
        assertTrue("ViewModel did not reach expected state: ${model.state.value}; sync=${model.syncState.value}; " +
            "upload=${model.uploadState.value}; panel=${model.panelError.value}; uploadCheckpoints=$checkpoints; " +
            "createdNotes=${remote.noteCreates.get()}; createdAttachments=${remote.attachmentCreates.get()}; " +
            "http=${remote.requests.toList().takeLast(30)}", condition())
    }

    private class FeatureServer : Dispatcher() {
        data class Note(@Volatile var title: String, val type: String = "text", val mime: String = "text/html",
            @Volatile var body: String = "", val parent: String? = "root", @Volatile var isProtected: Boolean = false,
            @Volatile var position: Int = 10)
        data class Attachment(val id: String, val owner: String, @Volatile var title: String,
            val mime: String, val role: String, val position: Int = 0, @Volatile var bytes: ByteArray = byteArrayOf())
        @Volatile var online = true
        val requests = ConcurrentLinkedQueue<String>()
        val offlineResponseEntered = CountDownLatch(1)
        val releaseOfflineResponse = CountDownLatch(1)
        @Volatile private var heldOfflinePath: String? = null
        fun holdNextOfflineResponse(path: String) { heldOfflinePath = path }
        val notes = ConcurrentHashMap<String, Note>().apply {
            put("root", Note("Root", "book", parent = null))
            put(FOLDER, Note("Folder", "book", position = 30))
            put(ALPHA, Note("Alpha", body = "<p><a href=\"api/attachments/$ALPHA/$ATTACHMENT/old.txt\">old.txt</a></p>", position = 10))
            put(BETA, Note("Beta", body = "<p>Beta body</p>", position = 20))
            put(MARKDOWN, Note("Markdown title", "code", "text/x-markdown", "Existing *markdown*", position = 40))
        }
        val attachments = ConcurrentHashMap<String, Attachment>().apply {
            put(ATTACHMENT, Attachment(ATTACHMENT, ALPHA, "old.txt", "text/plain", "file", bytes = ORIGINAL_ATTACHMENT_BYTES))
        }
        private val deleted = ConcurrentHashMap.newKeySet<String>()
        val noteCreates = AtomicInteger()
        val attachmentCreates = AtomicInteger()
        val attachmentPatches = AtomicInteger()
        private fun json(value: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())
        private fun meta(id: String): JSONObject {
            val note = notes.getValue(id)
            val children = notes.entries.filter { it.value.parent == id && it.key !in deleted }.sortedBy { it.value.position }
            return JSONObject().put("noteId", id).put("title", note.title).put("type", note.type).put("mime", note.mime)
                .put("isProtected", note.isProtected).put("blobId", "body-${note.body.hashCode()}")
                .put("utcDateModified", "2026-10-28T00:00:00Z")
                .put("parentNoteIds", JSONArray(listOfNotNull(note.parent)))
                .put("childBranchIds", JSONArray(children.map { "${id}_${it.key}" })).put("attributes", JSONArray())
        }
        private fun attachmentMeta(item: Attachment) = JSONObject().put("attachmentId", item.id).put("ownerId", item.owner)
            .put("title", item.title).put("mime", item.mime).put("role", item.role).put("position", item.position)
            .put("blobId", "attachment-${item.id}-${item.bytes.size}")
        @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
            requests += "${request.method} ${request.requestUrl!!.encodedPath} online=$online"
            if (!online) {
                if (request.requestUrl!!.encodedPath == heldOfflinePath) {
                    heldOfflinePath = null
                    offlineResponseEntered.countDown()
                    releaseOfflineResponse.await(10, TimeUnit.SECONDS)
                }
                // Preserve the response selected before reconnect, even if the test changed online meanwhile.
                return MockResponse().setResponseCode(503)
            }
            val all = request.requestUrl!!.pathSegments
            val path = all.drop(all.indexOf("etapi") + 1)
            val method = request.method
            if (path == listOf("create-note") && method == "POST") {
                val data = JSONObject(request.body.readUtf8())
                val id = data.getString("noteId")
                val parent = data.getString("parentNoteId")
                if (notes.containsKey(id)) return MockResponse().setResponseCode(409)
                if (!notes.containsKey(parent) || parent in deleted) return MockResponse().setResponseCode(404)
                notes[id] = Note(data.getString("title"), data.getString("type"), data.getString("mime"),
                    data.optString("content"), parent, position = 100 + noteCreates.incrementAndGet())
                return json(JSONObject().put("note", meta(id))).setResponseCode(201)
            }
            if (path == listOf("notes", "history")) return json(JSONArray(deleted.map { id ->
                JSONObject().put("noteId", id).put("current_title", notes.getValue(id).title)
                    .put("current_isDeleted", 1).put("canBeUndeleted", true)
            }))
            if (path.firstOrNull() == "notes" && path.size >= 2) {
                val id = path[1]
                val note = notes[id] ?: return MockResponse().setResponseCode(404)
                if (path.last() == "undelete" && method == "POST") {
                    deleted.remove(id)
                    return json(JSONObject().put("success", true))
                }
                if (path.size == 2 && method == "DELETE") { deleted += id; return MockResponse().setResponseCode(204) }
                if (id in deleted) return MockResponse().setResponseCode(404)
                if (path.size == 2) {
                    if (method == "PATCH") note.title = JSONObject(request.body.readUtf8()).getString("title")
                    return json(meta(id))
                }
                if (path.last() == "content") {
                    if (method == "PUT") { note.body = request.body.readUtf8(); return MockResponse().setResponseCode(204) }
                    return MockResponse().setHeader("Content-Type", note.mime).setBody(note.body)
                }
                if (path.last() == "attachments") return json(JSONArray(attachments.values.filter { it.owner == id }.map(::attachmentMeta)))
            }
            if (path.firstOrNull() == "branches" && path.size == 2) {
                val id = notes.keys.firstOrNull { note -> "${notes[note]?.parent}_$note" == path[1] && note !in deleted }
                    ?: return MockResponse().setResponseCode(404)
                return json(JSONObject().put("branchId", path[1]).put("noteId", id).put("parentNoteId", notes[id]!!.parent)
                    .put("prefix", "").put("notePosition", notes[id]!!.position).put("isExpanded", false))
            }
            if (path.firstOrNull() == "attachments") {
                if (path.size == 1 && method == "POST") {
                    val data = JSONObject(request.body.readUtf8())
                    if (data.keys().asSequence().any { it !in setOf("ownerId", "role", "mime", "title", "position", "content") })
                        return MockResponse().setResponseCode(400)
                    val id = "newAtt${attachmentCreates.incrementAndGet()}"
                    val attachment = Attachment(id, data.getString("ownerId"), data.getString("title"),
                        data.getString("mime"), data.getString("role"), data.optInt("position"))
                    attachments[id] = attachment
                    return json(attachmentMeta(attachment)).setResponseCode(201)
                }
                val attachment = attachments[path[1]] ?: return MockResponse().setResponseCode(404)
                if (path.last() == "content") {
                    if (method == "PUT") { attachment.bytes = request.body.readByteArray(); return MockResponse().setResponseCode(204) }
                    return MockResponse().setHeader("Content-Type", attachment.mime).setBody(okio.Buffer().write(attachment.bytes))
                }
                if (method == "PATCH") {
                    attachment.title = JSONObject(request.body.readUtf8()).getString("title")
                    attachmentPatches.incrementAndGet()
                }
                if (method == "DELETE") { attachments.remove(attachment.id); return MockResponse().setResponseCode(204) }
                return json(attachmentMeta(attachment))
            }
            if (path.firstOrNull() == "refresh-note-ordering") return MockResponse().setResponseCode(204)
            return MockResponse().setResponseCode(404)
        }
    }

    private companion object {
        const val FOLDER = "folderAA"
        const val ALPHA = "alphaAAA"
        const val BETA = "betaAAAA"
        const val MARKDOWN = "markdown"
        const val ATTACHMENT = "attachAA"
        val SHARED_BYTES = byteArrayOf(0, 1, 10, 13, -1)
        val ORIGINAL_ATTACHMENT_BYTES = "original attachment bytes".toByteArray()
    }
}
