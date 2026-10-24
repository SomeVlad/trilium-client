package ru.vlad.triliumnotes.data

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class DeleteNotesRepositoryTest {
    private val server = MockWebServer()
    private val fake = DeleteServer()
    private lateinit var context: Context
    private lateinit var temp: File

    @Before fun start() {
        temp = Files.createTempDirectory("trilium-delete-test").toFile()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = temp
        }
        server.dispatcher = fake
        server.start()
    }

    @After fun stop() {
        fake.release.countDown()
        server.shutdown()
        temp.deleteRecursively()
    }

    private fun api(offline: Boolean = false) = TriliumApi(
        if (offline) "http://127.0.0.1:1" else server.url("/").toString(), "synthetic-token",
        OkHttpClient.Builder().retryOnConnectionFailure(false)
            .connectTimeout(200, TimeUnit.MILLISECONDS).readTimeout(3, TimeUnit.SECONDS).build())

    @Test fun offlineDeletionReplacesEditAndSurvivesRestartWithoutResurrection() = runBlocking {
        val first = NotesRepository(context, api())
        first.listChildren("root")
        first.load("folderAA")
        first.load("otherAAA")
        first.saveDraft("folderAA", "Edited", "local edit")
        first.delete("folderAA")
        assertEquals(1, first.syncState.value.pendingCount)
        assertNull(first.cachedNote("folderAA"))
        assertEquals("body otherAAA", first.cachedNote("otherAAA")?.html)
        assertTrue(first.cachedChildren("root").orEmpty().none { it.note.id == "folderAA" })
        val offline = NotesRepository(context, api(offline = true))
        assertTrue(offline.isDeleted("folderAA"))
        assertEquals(1, offline.syncPending().pendingCount)
        assertTrue(offline.search("folder").isEmpty())
        val online = NotesRepository(context, api())
        assertEquals(0, online.syncPending().pendingCount)
        assertEquals(listOf("folderAA"), fake.deletes)
        assertEquals(0, fake.updates)

        // Simulate a proxy or an earlier in-flight request returning pre-delete metadata.
        fake.stale = true
        val restarted = NotesRepository(context, api())
        assertTrue(restarted.listChildren("root").none { it.note.id == "folderAA" })
        assertTrue(restarted.search("folder").none { it.id == "folderAA" })
        assertNull(restarted.prefetch(fake.meta("folderAA")))
        try { restarted.load("folderAA"); fail("Deleted note must not load") }
        catch (error: ApiException) { assertEquals(404, error.status) }
        assertNull(restarted.cachedNote("folderAA"))
        assertEquals("body otherAAA", restarted.cachedNote("otherAAA")?.html)
        assertFalse(File(temp, "trilium-cache.json").readText().contains("local edit"))
    }

    @Test fun deletionCascadesOnlyToOrphansAndCancelsTheirOutbox() = runBlocking {
        val repository = NotesRepository(context, api())
        repository.listChildren("root")
        repository.listChildren("folderAA")
        repository.load("orphanAA")
        repository.load("weakAAAA")
        repository.load("sharedAA")
        repository.load("leafAAAA")
        repository.saveDraft("orphanAA", "Unsynced", "orphan edit")
        repository.saveDraft("sharedAA", "Shared edit", "shared edit")
        val local = repository.create("folderAA", "Local child", "not uploaded")
        repository.delete("folderAA")
        assertTrue(repository.isDeleted("orphanAA"))
        assertTrue(repository.isDeleted("weakAAAA"))
        assertTrue(repository.isDeleted(local.note.id))
        assertFalse(repository.isDeleted("sharedAA"))
        assertFalse(repository.isDeleted("leafAAAA"))
        assertEquals("shared edit", repository.cachedNote("sharedAA")?.html)
        assertEquals(2, repository.syncState.value.pendingCount)
        assertEquals(0, repository.syncPending().pendingCount)
        assertEquals(listOf("folderAA"), fake.deletes)
        assertEquals(0, fake.creates)
        assertEquals("shared edit", fake.bodies["sharedAA"])
        val reopened = NotesRepository(context, api(offline = true))
        assertNull(reopened.cachedNote("orphanAA"))
        assertNotNull(reopened.cachedNote("sharedAA"))
        assertNotNull(reopened.cachedNote("leafAAAA"))
        assertTrue(reopened.cachedChildren("root").orEmpty().any { it.note.id == "sharedAA" })
        try { reopened.create("folderAA", "Late", "body"); fail("Deleted parent") }
        catch (_: IllegalArgumentException) { }
        try { reopened.saveDraft("orphanAA", "Late", "body"); fail("Deleted note") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun remotelyClonedDescendantRecoversAfterDeleteWithoutLosingOfflineDraft() = runBlocking {
        val repository = NotesRepository(context, api())
        repository.listChildren("root")
        repository.listChildren("folderAA")
        repository.load("orphanAA")
        repository.saveDraft("orphanAA", "Local orphan", "offline orphan draft")
        // Another client adds a placement after this device cached only folderAA.
        fake.parents["orphanAA"] = listOf("folderAA", "root")
        repository.delete("folderAA")
        assertTrue(repository.isDeleted("orphanAA"))
        assertNull(repository.cachedNote("orphanAA"))
        assertEquals(1, repository.syncState.value.pendingCount)

        val restarted = NotesRepository(context, api())
        assertTrue(restarted.isDeleted("orphanAA"))
        assertEquals(0, restarted.syncPending().pendingCount)
        assertEquals("body orphanAA", fake.bodies["orphanAA"])
        assertEquals(listOf("root"), fake.parents["orphanAA"])
        fake.stale = true
        assertTrue(restarted.search("orphan").none { it.id == "orphanAA" })
        assertTrue(restarted.isDeleted("orphanAA"))
        assertTrue(restarted.isDeleted("folderAA"))

        fake.stale = false
        assertTrue(restarted.listChildren("root").any { it.note.id == "orphanAA" })
        assertFalse(restarted.isDeleted("orphanAA"))
        assertEquals("offline orphan draft", restarted.cachedNote("orphanAA")?.html)
        assertEquals(1, restarted.syncState.value.pendingCount)
        // Force a real edit conflict to check that its recovered anchor supports a copy.
        fake.bodies["orphanAA"] = "remote preserved body"
        assertTrue("orphanAA" in restarted.syncPending().conflicts)
        val copy = restarted.saveConflictAsCopy("orphanAA")
        assertEquals(listOf("root"), copy.note.parentNoteIds)
        assertEquals("offline orphan draft", copy.html)
        assertEquals(0, restarted.syncPending().pendingCount)
        assertEquals("remote preserved body", fake.bodies["orphanAA"])
        assertEquals("offline orphan draft", fake.bodies[copy.note.id])
        assertTrue(restarted.isDeleted("folderAA"))
    }

    @Test fun sharedConflictCopyUsesSurvivingParentAfterFolderDeletion() = runBlocking {
        val repository = NotesRepository(context, api())
        repository.listChildren("root")
        repository.listChildren("folderAA")
        repository.load("sharedAA")
        repository.saveDraft("sharedAA", "Local shared", "local shared body")
        fake.bodies["sharedAA"] = "remote shared body"
        assertTrue("sharedAA" in repository.syncPending().conflicts)
        repository.delete("folderAA")
        val restarted = NotesRepository(context, api())
        val copy = restarted.saveConflictAsCopy("sharedAA")
        assertEquals(listOf("root"), copy.note.parentNoteIds)
        assertEquals("local shared body", copy.html)
        assertEquals(0, restarted.syncPending().pendingCount)
        assertEquals(listOf("root"), fake.parents[copy.note.id])
        assertEquals("remote shared body", fake.bodies["sharedAA"])
        assertEquals("local shared body", fake.bodies[copy.note.id])
    }

    @Test fun neverSentCreateIsCancelledWithoutNetworkButAttemptedCreateIsDeleted() = runBlocking {
        var repository = NotesRepository(context, api())
        val unsent = repository.create("root", "", "")
        repository.saveDraft(unsent.note.id, "Typed title", "Typed body")
        // Editing and a process restart must not turn an unsent create into an attempted POST.
        repository = NotesRepository(context, api())
        repository.delete(unsent.note.id)
        assertEquals(0, NotesRepository(context, api()).syncPending().pendingCount)
        assertEquals(0, fake.creates)
        assertTrue(fake.deletes.isEmpty())

        fake.dropCreate = true
        val attempted = repository.create("root", "Attempted", "body")
        assertEquals(1, repository.syncPending().pendingCount)
        assertEquals(1, fake.creates)
        repository.delete(attempted.note.id)
        assertEquals(0, NotesRepository(context, api()).syncPending().pendingCount)
        assertEquals(listOf(attempted.note.id), fake.deletes)
        assertEquals(1, fake.creates)
        assertFalse(attempted.note.id in fake.parents)
    }

    @Test fun lostDeleteResponseRetriesAs404AfterRestart() = runBlocking {
        val repository = NotesRepository(context, api())
        repository.load("folderAA")
        repository.delete("folderAA")
        fake.dropDelete = true
        assertEquals(1, repository.syncPending().pendingCount)
        assertFalse("folderAA" in fake.parents)
        val reopened = NotesRepository(context, api())
        assertEquals(0, reopened.syncPending().pendingCount)
        assertEquals(listOf("folderAA", "folderAA"), fake.deletes)
        assertTrue(reopened.isDeleted("folderAA"))
        assertNull(reopened.cachedNote("folderAA"))
    }

    @Test fun deleteDuringCreateLookupPreventsPostAndLateCacheCommit() = runBlocking {
        val repository = NotesRepository(context, api())
        val local = repository.create("root", "Local", "body")
        fake.blockPath = "/etapi/notes/${local.note.id}"
        val syncing = async(Dispatchers.Default) { repository.syncPending() }
        assertTrue(fake.entered.await(2, TimeUnit.SECONDS))
        try { repository.delete(local.note.id) } finally { fake.release.countDown() }
        assertEquals(0, syncing.await().pendingCount)
        assertEquals(0, fake.creates)
        assertNull(repository.cachedNote(local.note.id))
        assertTrue(NotesRepository(context, api()).isDeleted(local.note.id))
    }

    @Test fun deleteDuringPutKeepsDeleteIntentAndSkipsLateUpdateCommit() = runBlocking {
        val repository = NotesRepository(context, api())
        repository.load("otherAAA")
        repository.saveDraft("otherAAA", "Edited", "new body")
        fake.blockPath = "/etapi/notes/otherAAA/content"
        fake.blockMethod = "PUT"
        val syncing = async(Dispatchers.Default) { repository.syncPending() }
        assertTrue(fake.entered.await(2, TimeUnit.SECONDS))
        try { repository.delete("otherAAA") } finally { fake.release.countDown() }
        assertEquals(1, syncing.await().pendingCount)
        assertNull(repository.cachedNote("otherAAA"))
        assertEquals(0, NotesRepository(context, api()).syncPending().pendingCount)
        assertEquals(listOf("otherAAA"), fake.deletes)
        assertFalse("otherAAA" in fake.parents)
    }

    @Test fun failedDeleteKeepsDurableIntentAndReportsDeletionError() = runBlocking {
        val repository = NotesRepository(context, api())
        repository.load("otherAAA")
        repository.delete("otherAAA")
        fake.deleteFailure = 403
        val failed = repository.syncPending()
        assertEquals(1, failed.pendingCount)
        assertTrue(failed.error.orEmpty().contains("Не удалось удалить заметку"))
        assertTrue("otherAAA" in fake.parents)
        val reopened = NotesRepository(context, api())
        assertTrue(reopened.isDeleted("otherAAA"))
        fake.deleteFailure = null
        assertEquals(0, reopened.syncPending().pendingCount)
    }

    @Test fun rootAndHiddenSystemNotesCannotBeDeleted() = runBlocking {
        val repository = NotesRepository(context, api())
        repository.load("root")
        try { repository.delete("root"); fail("Root deletion must be rejected") }
        catch (_: IllegalArgumentException) { }
        assertFalse(fake.meta("root").canDelete)
        assertFalse(fake.meta("_hidden").canDelete)
        assertTrue(fake.meta("folderAA").copy(isProtected = true).canDelete)
        assertTrue(fake.deletes.isEmpty())
    }

    private class DeleteServer : Dispatcher() {
        val parents = linkedMapOf(
            "root" to emptyList(), "folderAA" to listOf("root"),
            "orphanAA" to listOf("folderAA"), "weakAAAA" to listOf("folderAA", "_share", "_lbBookmarks"), "sharedAA" to listOf("folderAA", "root"),
            "leafAAAA" to listOf("sharedAA"), "otherAAA" to listOf("root"))
        private val original = parents.toMap()
        val bodies = parents.keys.associateWithTo(mutableMapOf()) { "body $it" }
        val titles = parents.keys.associateWithTo(mutableMapOf()) { it }
        val deletes = mutableListOf<String>()
        var creates = 0
        var updates = 0
        @Volatile var stale = false
        @Volatile var dropCreate = false
        @Volatile var dropDelete = false
        @Volatile var deleteFailure: Int? = null
        @Volatile var blockPath: String? = null
        @Volatile var blockMethod = "GET"
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun meta(id: String): NoteMeta = parseNote(json(id, original))
        private fun json(id: String, graph: Map<String, List<String>> = parents): JSONObject = JSONObject()
            .put("noteId", id).put("title", titles[id] ?: id).put("type", "text")
            .put("mime", "text/html").put("isProtected", false)
            .put("blobId", "blobAAAA").put("utcDateModified", "2026-10-24")
            .put("parentNoteIds", JSONArray(graph[id].orEmpty()))
            .put("childBranchIds", JSONArray(graph.filter { id in it.value }.keys.map { "${id}_$it" }))

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl!!.encodedPath
            val method = request.method
            if (path == blockPath && method == blockMethod) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            val graph = if (stale) original else parents
            if (path == "/etapi/notes" && method == "GET")
                return response(200, JSONObject().put("results", JSONArray(graph.keys.filter { it != "root" }.map { json(it, graph) })))
            if (path.startsWith("/etapi/branches/")) {
                val branch = path.substringAfterLast('/')
                val parts = branch.split('_')
                return response(200, JSONObject().put("branchId", branch).put("parentNoteId", parts[0])
                    .put("noteId", parts[1]).put("prefix", "").put("notePosition", 10))
            }
            if (path == "/etapi/create-note" && method == "POST") {
                val body = JSONObject(request.body.readUtf8())
                val id = body.getString("noteId")
                creates++
                parents[id] = listOf(body.getString("parentNoteId"))
                bodies[id] = body.getString("content")
                titles[id] = body.getString("title")
                if (dropCreate) { dropCreate = false; return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
                return response(201, JSONObject().put("note", json(id)))
            }
            val id = path.removePrefix("/etapi/notes/").substringBefore('/')
            if (method == "DELETE") {
                deletes += id
                deleteFailure?.let { return response(it) }
                if (id !in parents) return response(404)
                parents.remove(id)
                var changed: Boolean
                do {
                    val orphans = parents.filter { it.key != "root" && it.value.isNotEmpty() && it.value.all { parent -> parent !in parents } }.keys
                    changed = orphans.isNotEmpty()
                    orphans.toList().forEach { parents.remove(it) }
                } while (changed)
                parents.replaceAll { _, values -> values.filter { it in parents } }
                if (dropDelete) { dropDelete = false; return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
                return response(204)
            }
            if (id !in graph) return response(404)
            if (method == "GET") return response(200, if (path.endsWith("/content")) bodies[id] ?: "body $id" else json(id, graph))
            if (method == "PUT") { updates++; bodies[id] = request.body.readUtf8(); return response(204) }
            if (method == "PATCH") { updates++; titles[id] = JSONObject(request.body.readUtf8()).getString("title"); return response(200, json(id)) }
            return response(405)
        }

        private fun response(status: Int, body: Any? = null) = MockResponse().setResponseCode(status)
            .apply { if (body != null) setBody(body.toString()) }
    }
}
