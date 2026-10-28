package ru.vlad.triliumnotes.data

import android.content.ContextWrapper
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
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class AttachmentManagerTest {
    private val server = MockWebServer()
    private lateinit var directory: File
    private lateinit var context: ContextWrapper
    private lateinit var api: TriliumApi
    private lateinit var repository: NotesRepository
    private var failSync = false
    private var deleted = false
    private var failMetadata = false
    private var failCatalog = false
    private var patchCount = 0
    private var requestCheck: (() -> Unit)? = null
    private var deleteCount = 0
    private var title = "photo.png"
    private var content = "<p>Before<img src='api/attachments/ownerAAA/attachAA/photo.png'>After</p>"

    private fun note() = JSONObject().put("noteId", "ownerAAA").put("title", "Note").put("type", "text")
        .put("mime", "text/html").put("isProtected", false).put("blobId", "blobAAAA")
        .put("childBranchIds", JSONArray()).put("parentNoteIds", JSONArray(listOf("root")))
    private fun attachment() = JSONObject().put("attachmentId", "attachAA").put("ownerId", "ownerAAA")
        .put("title", title).put("role", "image").put("mime", "image/png").put("position", 0)

    @Before fun setup() {
        directory = Files.createTempDirectory("attachment-manager-test").toFile()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = directory }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestCheck?.invoke()
                val path = request.requestUrl!!.encodedPath
                if (path == "/etapi/notes/ownerAAA") return if (failSync) MockResponse().setResponseCode(503)
                    else MockResponse().setBody(note().toString())
                if (path == "/etapi/notes/ownerAAA/content") {
                    if (request.method == "PUT") { content = request.body.readUtf8(); return MockResponse().setResponseCode(204) }
                    return MockResponse().setBody(content)
                }
                if (path == "/etapi/notes/ownerAAA/attachments") {
                    if (failCatalog) return MockResponse().setResponseCode(503)
                    return MockResponse().setBody((if (deleted) JSONArray() else JSONArray().put(attachment())).toString())
                }
                if (path == "/etapi/attachments/attachAA") {
                    if (request.method == "DELETE") {
                        assertFalse("Reference edit must reach server before binary deletion", content.contains("attachAA"))
                        deleted = true; deleteCount++; return MockResponse().setResponseCode(204)
                    }
                    if (request.method == "PATCH") {
                        patchCount++
                        if (failMetadata) return MockResponse().setResponseCode(503)
                        title = JSONObject(request.body.readUtf8()).getString("title")
                        return MockResponse().setBody(attachment().toString())
                    }
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
        api = TriliumApi(server.url("/").toString(), "synthetic-token")
        repository = NotesRepository(context, api)
        runBlocking { repository.load("ownerAAA") }
    }
    @After fun cleanup() { api.close(); server.shutdown(); directory.deleteRecursively() }

    @Test fun deleteWaitsForDurableReferenceSyncAcrossRestart() = runBlocking {
        val manager = AttachmentManager(context, api, repository)
        assertEquals(1, manager.list("ownerAAA").size)
        manager.delete("ownerAAA", "attachAA")
        failSync = true
        assertEquals(1, manager.syncPending())
        assertEquals(0, deleteCount)
        assertTrue(content.contains("attachAA"))
        assertFalse(repository.cachedNote("ownerAAA")!!.html.contains("attachAA"))
        val reopenedRepository = NotesRepository(context, api)
        val reopened = AttachmentManager(context, api, reopenedRepository)
        assertTrue(reopened.list("ownerAAA").isEmpty())
        failSync = false
        assertEquals(0, reopened.syncPending())
        assertEquals(1, deleteCount)
        assertFalse(content.contains("attachAA"))
    }

    @Test fun renameKeepsAttachmentIdAndBinaryReference() = runBlocking {
        val manager = AttachmentManager(context, api, repository)
        manager.list("ownerAAA")
        manager.rename("ownerAAA", "attachAA", "New photo.png")
        assertEquals(0, manager.syncPending())
        assertEquals("New photo.png", title)
        assertEquals(0, deleteCount)
        assertTrue(content.contains("api/attachments/ownerAAA/attachAA/photo.png"))
        assertTrue(content.contains("alt=\"New photo.png\""))
    }

    @Test fun conflictedReferenceEditNeverDeletesRemoteAttachment() = runBlocking {
        val manager = AttachmentManager(context, api, repository)
        manager.list("ownerAAA")
        manager.delete("ownerAAA", "attachAA")
        content += "<p>Other device edit</p>"
        assertEquals(1, manager.syncPending())
        assertTrue(repository.cachedNote("ownerAAA")!!.conflict)
        assertEquals(0, deleteCount)
        val restarted = AttachmentManager(context, api, NotesRepository(context, api))
        assertEquals(1, restarted.state.value.pendingCount)
        assertEquals(1, restarted.syncPending())
        assertEquals(0, deleteCount)
    }

    @Test fun failedRenameMetadataSurvivesRestartAndCompletesOnlyOnce() = runBlocking {
        val manager = AttachmentManager(context, api, repository)
        manager.list("ownerAAA")
        manager.rename("ownerAAA", "attachAA", "Renamed.png")
        failMetadata = true
        assertEquals(1, manager.syncPending())
        assertNotNull(manager.state.value.error)
        assertFalse(manager.state.value.isSyncing)
        assertEquals("photo.png", title)
        val restarted = AttachmentManager(context, api, NotesRepository(context, api))
        assertEquals(1, restarted.state.value.pendingCount)
        failMetadata = false
        assertEquals(0, restarted.syncPending())
        assertEquals("Renamed.png", title)
        assertEquals(2, patchCount)
        restarted.syncPending()
        assertEquals(2, patchCount)
        assertEquals(0, restarted.state.value.pendingCount)
        assertEquals(0, deleteCount)
    }


    @Test fun nativeTrashPausesMetadataQueueAndRestoreResumesIt() = runBlocking {
        val manager = AttachmentManager(context, api, repository)
        manager.list("ownerAAA")
        manager.delete("ownerAAA", "attachAA")
        repository.trash("ownerAAA")
        assertEquals(0, manager.refreshPendingCount())
        assertEquals(0, manager.syncPending())
        assertEquals(0, deleteCount)
        val restarted = AttachmentManager(context, api, repository)
        assertEquals(0, restarted.state.value.pendingCount)
        repository.restore("ownerAAA")
        assertEquals(1, restarted.refreshPendingCount())
        assertEquals(0, restarted.syncPending())
        assertEquals(1, deleteCount)
    }

    @Test fun editorGateDefersReplayAndGuardsEveryNetworkAndMetadataStepOnRetry() = runBlocking {
        val manager = AttachmentManager(context, api, repository)
        manager.list("ownerAAA")
        manager.rename("ownerAAA", "attachAA", "Guarded.png")
        val requestsBefore = server.requestCount
        manager.syncGate = { throw AttachmentInsertionDeferred() }
        assertEquals(1, manager.syncPending())
        assertNull(manager.state.value.error)
        assertEquals(requestsBefore, server.requestCount)
        val inGate = java.util.concurrent.atomic.AtomicBoolean(false)
        requestCheck = { assertTrue("Every replay request must remain inside the editor gate", inGate.get()) }
        manager.syncGate = { block ->
            inGate.set(true)
            try { block() } finally { inGate.set(false) }
        }
        assertEquals(0, manager.syncPending())
        assertEquals("Guarded.png", title)
        assertFalse(inGate.get())
    }

    @Test fun damagedMetadataQueueNeverPreventsReadingNotesOrOverwritesRecoveryEvidence() = runBlocking {
        val damaged = File(directory, "attachment-manager.json").apply { writeText("{damaged pending queue") }
        val manager = AttachmentManager(context, api, repository)
        assertNotNull(manager.state.value.error)
        assertNotNull(repository.cachedNote("ownerAAA"))
        assertEquals(1, manager.list("ownerAAA").size)
        assertEquals("{damaged pending queue", damaged.readText())
        try {
            manager.rename("ownerAAA", "attachAA", "Must not overwrite.png")
            fail("A damaged outbox cannot accept writes")
        } catch (_: java.io.IOException) { }
        assertEquals("{damaged pending queue", damaged.readText())
        assertNotNull(repository.cachedNote("ownerAAA"))
    }

    @Test fun catalogIsRememberedForCopiesBeforeAndAfterMetadataChanges() = runBlocking {
        val manager = AttachmentManager(context, api, repository)
        manager.list("ownerAAA")
        fun cachedAttachment() = JSONObject(File(directory, "trilium-cache.json").readText())
            .getJSONObject("attachmentCatalog").getJSONArray("ownerAAA").getJSONObject(0)
        assertEquals("attachAA", cachedAttachment().getString("attachmentId"))
        manager.rename("ownerAAA", "attachAA", "Copy keeps this name.png")
        manager.syncPending()
        assertEquals("Copy keeps this name.png", cachedAttachment().getString("title"))
    }


    @Test fun unsupportedOwnerReferenceKeepsBinaryUntilReferenceIsExplicitlyRemoved() = runBlocking {
        repository.saveDraft("ownerAAA", "Note", "<video src='api/attachments/ownerAAA/attachAA/movie.mp4'></video>")
        val manager = AttachmentManager(context, api, repository)
        manager.list("ownerAAA")
        manager.delete("ownerAAA", "attachAA")
        assertEquals(1, manager.syncPending())
        assertEquals(0, deleteCount)
        assertNotNull(manager.state.value.error)
        repository.saveDraft("ownerAAA", "Note", "<p>Video removed</p>")
        assertEquals(0, manager.syncPending())
        assertEquals(1, deleteCount)
    }


    @Test fun firstManagerOpenOfflineUsesSyncedRepositoryAttachmentCatalog() = runBlocking {
        repository.rememberAttachments("ownerAAA", listOf(AttachmentMeta("attachAA", "ownerAAA", "image", "image/png", "Synced photo.png", 0)))
        assertFalse(File(directory, "attachment-manager.json").exists())
        // Reopen the repository to prove this is durable sync metadata, not manager-local state.
        val reopenedRepository = NotesRepository(context, api)
        failCatalog = true
        val manager = AttachmentManager(context, api, reopenedRepository)
        val items = manager.list("ownerAAA")
        assertEquals(listOf("attachAA"), items.map { it.id })
        assertEquals("Synced photo.png", items.single().title)
        manager.rename("ownerAAA", "attachAA", "Offline rename.png")
        assertEquals(1, manager.state.value.pendingCount)
        assertEquals("Offline rename.png", manager.list("ownerAAA").single().title)
        assertEquals(0, patchCount)
        assertEquals(0, deleteCount)
    }

    @Test fun emptySyncedAttachmentCatalogIsAvailableOnFirstOfflineManagerOpen() = runBlocking {
        repository.rememberAttachments("ownerAAA", emptyList())
        failCatalog = true
        val manager = AttachmentManager(context, api, NotesRepository(context, api))
        assertTrue(manager.list("ownerAAA").isEmpty())
    }

}
