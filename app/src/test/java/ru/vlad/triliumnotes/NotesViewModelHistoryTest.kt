package ru.vlad.triliumnotes

import android.content.ContextWrapper
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import ru.vlad.triliumnotes.data.NotesRepository
import ru.vlad.triliumnotes.data.NotesServices
import ru.vlad.triliumnotes.data.RevisionDocument
import ru.vlad.triliumnotes.data.TriliumApi
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class NotesViewModelHistoryTest {
    private val server = MockWebServer()
    private val models = ViewModelStore()
    private val singleton = NotesServices::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previousServices: Any? = null
    private lateinit var directory: File
    private lateinit var services: NotesServices
    private lateinit var model: NotesViewModel
    private val events = ConcurrentLinkedQueue<Event>()
    private val snapshots = ConcurrentLinkedQueue<Snapshot>()
    @Volatile private var remoteTitle = "Current server title"
    @Volatile private var remoteHtml = "<p>Current server content</p>"
    @Volatile private var rejectRevision = false
    @Volatile private var rejectContentWrites = false
    @Volatile private var changeRemoteAfterSnapshot = false
    @Volatile private var revisionEntered: CountDownLatch? = null
    @Volatile private var releaseRevision: CountDownLatch? = null

    private data class Event(val method: String, val path: String, val body: String)
    private data class Snapshot(val title: String, val html: String, val description: String)

    @Before fun start() {
        val application = RuntimeEnvironment.getApplication()
        directory = Files.createTempDirectory("notes-history-test").toFile()
        val context = object : ContextWrapper(application) { override fun getFilesDir(): File = directory }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = request.body.readUtf8()
                events += Event(request.method.orEmpty(), path, body)
                return when (path) {
                    "/etapi/notes/root" -> json(JSONObject().put("noteId", "root").put("title", "Root")
                        .put("type", "book").put("mime", "text/html").put("childBranchIds", JSONArray()).toString())
                    "/etapi/notes/$NOTE" -> {
                        if (request.method == "PATCH") remoteTitle = JSONObject(body).getString("title")
                        json(noteMeta().toString())
                    }
                    "/etapi/notes/$NOTE/content" -> {
                        if (request.method == "PUT") {
                            if (rejectContentWrites) return json("""{"code":"TEST_UNAVAILABLE"}""").setResponseCode(503)
                            remoteHtml = body
                            return MockResponse().setResponseCode(204)
                        }
                        MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(remoteHtml)
                    }
                    "/etapi/notes/$NOTE/revisions" -> json(JSONArray().put(revisionMeta()).toString())
                    "/etapi/revisions/$REVISION" -> json(revisionMeta().toString())
                    "/etapi/revisions/$REVISION/content" -> MockResponse()
                        .setHeader("Content-Type", "text/html; charset=utf-8").setBody(HISTORICAL_HTML)
                    "/etapi/notes/$NOTE/revision" -> {
                        revisionEntered?.countDown()
                        releaseRevision?.await(5, TimeUnit.SECONDS)
                        if (rejectRevision) return json("""{"code":"TEST_REVISION_FAILED"}""").setResponseCode(503)
                        snapshots += Snapshot(remoteTitle, remoteHtml, JSONObject(body).optString("description"))
                        if (changeRemoteAfterSnapshot) {
                            remoteTitle = "Another client after snapshot"
                            remoteHtml = "<p>Another client after snapshot</p>"
                        }
                        MockResponse().setResponseCode(204)
                    }
                    "/etapi/notes/$NOTE/attachments", "/etapi/notes/root/attachments" -> json("[]")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val url = server.url("/").toString()
        services = NotesServices(context, TriliumApi(url, "test-token"), url)
        previousServices = singleton.get(null)
        singleton.set(null, services)
        WorkManagerTestInitHelper.initializeTestWorkManager(application, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build())
        model = NotesViewModel(application)
        models.put("notes", model)
        await { !model.state.value.loading }
        val document = runBlocking { services.repository.load(NOTE) }
        model.open(document.note)
        assertEquals(NOTE, model.state.value.document?.note?.id)
    }

    @After fun stop() {
        releaseRevision?.countDown()
        models.clear()
        shadowOf(Looper.getMainLooper()).idle()
        singleton.set(null, previousServices)
        server.shutdown()
        directory.deleteRecursively()
    }

    @Test fun unsentExactTitleAndBodyReachServerAndProtectiveSnapshotBeforeRestoration() {
        val preview = loadPreview()
        val title = "Несинхронизированный заголовок & «цитаты»"
        val html = "<p class='keep'>A&nbsp;B &amp; C<br></p>\n<!-- exact current source -->"
        events.clear()
        model.editTitle(NOTE, title)
        model.editHtml(NOTE, html)
        assertTrue(model.state.value.localWritePending)

        // Start immediately, while the conflated local write channel still owns the draft.
        model.restoreRevision(preview)

        await { model.historyState.value == null && !model.state.value.operationBusy }
        assertEquals(HISTORICAL_TITLE, remoteTitle)
        assertEquals(HISTORICAL_HTML, remoteHtml)
        val snapshot = snapshots.single()
        assertEquals(title, snapshot.title)
        assertEquals(html, snapshot.html)
        assertTrue(snapshot.description.isNotBlank())
        assertEquals(HISTORICAL_TITLE, model.state.value.document?.note?.title)
        assertEquals(HISTORICAL_HTML, model.state.value.document?.html)
        assertFalse(model.state.value.document!!.pending)
        assertEquals(0, model.syncState.value.pendingCount)
        val mutations = events.filter { it.method in setOf("PUT", "PATCH", "POST") }
        assertEquals(listOf("PUT", "PATCH", "POST", "PUT", "PATCH"), mutations.map { it.method })
        assertEquals(html, mutations[0].body)
        assertEquals(title, JSONObject(mutations[1].body).getString("title"))
        assertEquals("/etapi/notes/$NOTE/revision", mutations[2].path)
        assertEquals(HISTORICAL_HTML, mutations[3].body)
        assertEquals(HISTORICAL_TITLE, JSONObject(mutations[4].body).getString("title"))
        val reopened = reopenedRepository().cachedNote(NOTE)!!
        assertEquals(HISTORICAL_HTML, reopened.html)
        assertFalse(reopened.pending)
    }

    @Test fun failedProtectiveSnapshotPreservesCurrentDocumentAndNeverQueuesHistoricalText() {
        val preview = loadPreview()
        val title = "Current unsent title"
        val html = "<p data-preserve='yes'>Current unsent body &amp; exact source</p>"
        model.editTitle(NOTE, title)
        model.editHtml(NOTE, html)
        await { !model.state.value.localWritePending }
        rejectRevision = true

        model.restoreRevision(preview)

        awaitHistoryFailure()
        assertEquals(title, model.state.value.document?.note?.title)
        assertEquals(html, model.state.value.document?.html)
        assertEquals(title, remoteTitle)
        assertEquals(html, remoteHtml)
        assertEquals(html, reopenedRepository().cachedNote(NOTE)?.html)
        assertTrue(snapshots.isEmpty())
        assertFalse(events.any { it.method == "PUT" && it.body == HISTORICAL_HTML })
        assertFalse(events.any { it.method == "PATCH" && JSONObject(it.body).optString("title") == HISTORICAL_TITLE })
    }

    @Test fun conflictingRemoteChangeBlocksSnapshotAndKeepsBothCurrentVersions() {
        val preview = loadPreview()
        val localHtml = "<p>Local edit must survive</p>"
        model.editTitle(NOTE, "Local title")
        model.editHtml(NOTE, localHtml)
        await { !model.state.value.localWritePending }
        remoteTitle = "Another client title"
        remoteHtml = "<p>Another client body</p>"
        events.clear()

        model.restoreRevision(preview)

        awaitHistoryFailure()
        assertEquals("Local title", model.state.value.document?.note?.title)
        assertEquals(localHtml, model.state.value.document?.html)
        assertEquals("Another client title", remoteTitle)
        assertEquals("<p>Another client body</p>", remoteHtml)
        assertTrue(NOTE in model.syncState.value.conflicts)
        assertTrue(services.repository.cachedNote(NOTE)?.conflict == true)
        assertEquals(localHtml, reopenedRepository().cachedNote(NOTE)?.html)
        assertTrue(snapshots.isEmpty())
        assertFalse(events.any { it.method in setOf("PUT", "PATCH", "POST") })
    }

    @Test fun failedLocalWriteBlocksRestorationAndKeepsTheUnsavedBuffer() {
        val preview = loadPreview()
        val durable = services.repository.cachedNote(NOTE)!!
        withBlockedCacheDirectory {
            model.editTitle(NOTE, "Title still only in memory")
            model.editHtml(NOTE, "<p>Body still only in memory &amp; must survive</p>")
            await { model.state.value.localSaveFailed }
            events.clear()

            model.restoreRevision(preview)

            awaitHistoryFailure()
            assertEquals("Title still only in memory", model.state.value.document?.note?.title)
            assertEquals("<p>Body still only in memory &amp; must survive</p>", model.state.value.document?.html)
            assertTrue(model.state.value.localWritePending)
            assertTrue(model.state.value.localSaveFailed)
            assertEquals(durable, services.repository.cachedNote(NOTE))
            assertTrue(snapshots.isEmpty())
            assertTrue(events.isEmpty())
        }
    }

    @Test fun currentDraftWhichCannotSyncBlocksRestorationBeforeProtectiveSnapshot() {
        val preview = loadPreview()
        val html = "<p>Current draft awaiting a working connection</p>"
        model.editHtml(NOTE, html)
        await { !model.state.value.localWritePending }
        rejectContentWrites = true

        model.restoreRevision(preview)

        awaitHistoryFailure()
        assertEquals(html, model.state.value.document?.html)
        assertEquals(html, reopenedRepository().cachedNote(NOTE)?.html)
        assertTrue(services.repository.cachedNote(NOTE)!!.pending)
        assertEquals("<p>Current server content</p>", remoteHtml)
        assertTrue(snapshots.isEmpty())
        assertFalse(events.any { it.method == "POST" })
        assertFalse(events.any { it.method == "PUT" && it.body == HISTORICAL_HTML })
    }

    @Test fun newEditsCannotRaceTheSnapshotAndOverwriteARestoration() {
        val preview = loadPreview()
        revisionEntered = CountDownLatch(1)
        releaseRevision = CountDownLatch(1)
        model.restoreRevision(preview)
        await { revisionEntered!!.count == 0L }
        assertTrue(model.state.value.operationBusy)
        val before = model.state.value.document

        model.editTitle(NOTE, "Delayed editor title")
        model.editHtml(NOTE, "<p>Delayed editor callback</p>")

        assertEquals(before, model.state.value.document)
        releaseRevision!!.countDown()
        await { model.historyState.value == null && !model.state.value.operationBusy }
        assertEquals(HISTORICAL_HTML, model.state.value.document?.html)
        assertEquals(HISTORICAL_HTML, remoteHtml)
        assertFalse(model.state.value.localWritePending)
    }

    @Test fun concurrentRemoteEditAfterSnapshotKeepsHistoricalDraftAndRemoteVersionAsConflict() {
        val preview = loadPreview()
        changeRemoteAfterSnapshot = true
        events.clear()

        model.restoreRevision(preview)

        await { !model.state.value.operationBusy && NOTE in model.syncState.value.conflicts }
        assertEquals("Current server title", snapshots.single().title)
        assertEquals("<p>Current server content</p>", snapshots.single().html)
        assertEquals("Another client after snapshot", remoteTitle)
        assertEquals("<p>Another client after snapshot</p>", remoteHtml)
        assertEquals(HISTORICAL_HTML, model.state.value.document?.html)
        assertTrue(model.state.value.document?.conflict == true)
        assertTrue(model.state.value.document?.pending == true)
        val persisted = reopenedRepository().cachedNote(NOTE)!!
        assertEquals(HISTORICAL_HTML, persisted.html)
        assertTrue(persisted.conflict)
        assertFalse(events.any { it.method in setOf("PUT", "PATCH") })
    }

    @Test fun pendingAttachmentIsKeptAndBlocksRestorationInsteadOfEnteringTheHistoricalBody() {
        val preview = loadPreview()
        val folder = File(directory, "attachment-uploads/12345678-1234-4234-8234-123456789abc").apply { mkdirs() }
        File(folder, "entry.json").writeText(JSONObject().put("uploadId", folder.name).put("noteId", NOTE)
            .put("title", "unsent-image.png").put("mime", "image/png").put("inlineImage", true).toString())
        val bytes = byteArrayOf(1, 2, 3, 4)
        File(folder, "content").writeBytes(bytes)
        runBlocking { assertTrue(services.uploads.hasPending(NOTE)) }
        events.clear()

        model.restoreRevision(preview)

        awaitHistoryFailure()
        assertTrue(File(folder, "entry.json").isFile)
        assertTrue(File(folder, "content").readBytes().contentEquals(bytes))
        assertEquals("<p>Current server content</p>", model.state.value.document?.html)
        assertTrue(snapshots.isEmpty())
        assertTrue(events.isEmpty())
    }

    private fun loadPreview(): RevisionDocument {
        model.openHistory(NOTE)
        await { model.historyState.value?.loading == false }
        val revision = model.historyState.value!!.revisions.single()
        model.selectRevision(revision)
        await { model.historyState.value?.preview != null }
        return model.historyState.value!!.preview!!
    }

    private fun awaitHistoryFailure() {
        await { !model.state.value.operationBusy && model.historyState.value?.working == false &&
            model.historyState.value?.error != null }
        assertNotNull(model.historyState.value?.preview)
    }

    private fun reopenedRepository(): NotesRepository = NotesRepository(
        object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir(): File = directory },
        services.api)

    private fun noteMeta(): JSONObject = JSONObject().put("noteId", NOTE).put("title", remoteTitle)
        .put("type", "text").put("mime", "text/html").put("isProtected", false)
        .put("blobId", "blobCurrent").put("parentNoteIds", JSONArray().put("root"))
        .put("childBranchIds", JSONArray())

    private fun revisionMeta(): JSONObject = JSONObject().put("revisionId", REVISION).put("noteId", NOTE)
        .put("title", HISTORICAL_TITLE).put("type", "text").put("mime", "text/html")
        .put("isProtected", false).put("utcDateCreated", "2026-10-20 08:00:00.000Z")

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun withBlockedCacheDirectory(block: () -> Unit) {
        val backup = File(directory.parentFile, "${directory.name}-saved")
        assertFalse(backup.exists())
        assertTrue(directory.renameTo(backup))
        try {
            directory.writeText("synthetic write blocker")
            block()
        } finally {
            directory.delete()
            if (!backup.renameTo(directory)) {
                backup.deleteRecursively()
                fail("Could not restore the synthetic cache directory")
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("ViewModel did not reach the expected history state", condition())
    }

    private companion object {
        const val NOTE = "noteAAAA"
        const val REVISION = "revisionAAAA"
        const val HISTORICAL_TITLE = "Historical title"
        const val HISTORICAL_HTML = "<p class='past'>Historical&nbsp;source &amp; markup<br></p>\n<!-- retained revision -->"
    }
}
