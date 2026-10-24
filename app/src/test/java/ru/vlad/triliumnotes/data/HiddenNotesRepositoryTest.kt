package ru.vlad.triliumnotes.data

import android.content.Context
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
class HiddenNotesRepositoryTest {
    private val server = MockWebServer()
    private lateinit var temp: File
    private lateinit var context: Context
    private var offlineSearch = false
    private var failedHiddenBranch = false
    private var hiddenMetadataRequests = 0
    private var createRequests = 0
    private val metadata = linkedMapOf<String, JSONObject>()

    private fun meta(id: String, parents: List<String> = listOf("root"), title: String = "query $id") = JSONObject()
        .put("noteId", id).put("title", title).put("type", "text").put("mime", "text/html")
        .put("isProtected", false).put("blobId", "blobAAAA").put("utcDateModified", "2026-10-23")
        .put("parentNoteIds", JSONArray(parents)).put("childBranchIds", JSONArray())
    private fun item(id: String, branch: String = "branch$id") = JSONObject()
        .put("branchId", branch).put("note", metadata.getValue(id)).put("prefix", "")
        .put("position", 1).put("isExpanded", false)

    @Before fun start() {
        temp = Files.createTempDirectory("hidden-notes-test").toFile()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = temp
        }
        metadata["root"] = meta("root", emptyList(), "Root")
        metadata["_hidden"] = meta("_hidden")
        metadata["hiddenParent"] = meta("hiddenParent", listOf("_hidden"))
        metadata["deepHidden"] = meta("deepHidden", listOf("hiddenParent"))
        metadata["visibleUser"] = meta("visibleUser", title = "Hidden Notes")
        metadata["visibleClone"] = meta("visibleClone", listOf("hiddenParent", "root"))
        metadata["unknownUser"] = meta("unknownUser", listOf("notCachedParent"))
        metadata["legacyLeaf"] = meta("legacyLeaf", emptyList())
        metadata["movedNote"] = meta("movedNote")
        metadata["root"]!!.put("childBranchIds", JSONArray(listOf("branch_hidden", "branchUser", "branchMoved")))
        val notes = JSONObject()
        metadata.forEach { (id, value) -> notes.put(id, JSONObject().put("meta", value).put("html", if (id == "root") "<p>Root</p>" else "<p>query cached</p>")) }
        val children = JSONObject().put("root", JSONArray().put(item("_hidden", "branch_hidden"))
            .put(item("visibleUser", "branchUser")).put(item("movedNote", "branchMoved"))
            .put(item("deepHidden", "branchDeep")))
            .put("_hidden", JSONArray().put(item("hiddenParent")).put(item("legacyLeaf")))
        File(temp, "trilium-cache.json").writeText(JSONObject().put("notes", notes).put("children", children)
            .put("pending", JSONObject()).put("counter", 0).toString())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (path == "/etapi/notes" && request.method == "GET") {
                    if (offlineSearch) return MockResponse().setResponseCode(503)
                    return ok(JSONObject().put("results", JSONArray(metadata.filterKeys { it != "root" }.values.toList())))
                }
                if (path == "/etapi/create-note") {
                    createRequests++
                    val body = JSONObject(request.body.readUtf8())
                    val id = body.getString("noteId")
                    val created = meta(id, listOf(body.getString("parentNoteId")), body.getString("title"))
                    metadata[id] = created
                    return ok(JSONObject().put("note", created)).setResponseCode(201)
                }
                if (path.startsWith("/etapi/branches/")) {
                    val branch = path.substringAfterLast('/')
                    val id = when (branch) { "branch_hidden" -> "_hidden"; "branchUser" -> "visibleUser"; "branchMoved" -> "movedNote"; else -> return MockResponse().setResponseCode(404) }
                    if (failedHiddenBranch && branch == "branchMoved") return MockResponse().setResponseCode(503)
                    return ok(JSONObject().put("branchId", branch).put("noteId", id).put("parentNoteId", "root")
                        .put("notePosition", if (branch == "branchUser") 1 else 2))
                }
                val id = path.removePrefix("/etapi/notes/").substringBefore('/')
                if (id == "_hidden") hiddenMetadataRequests++
                val found = metadata[id] ?: return MockResponse().setResponseCode(404)
                return if (path.endsWith("/content")) MockResponse().setBody("<p>query cached</p>") else ok(found)
            }
        }
        server.start()
    }

    @After fun stop() { server.shutdown(); temp.deleteRecursively() }
    private fun ok(value: JSONObject) = MockResponse().setBody(value.toString())
    private fun repository() = NotesRepository(context, TriliumApi(server.url("/").toString(), "test-token"))

    @Test fun treeSearchAndLegacyOfflineCacheHideSystemNotesWithoutDroppingUserClones() = runBlocking {
        val repository = repository()
        assertEquals(listOf("visibleUser", "movedNote"), repository.cachedChildren("root")!!.map { it.note.id })
        assertTrue(repository.cachedChildren("_hidden")!!.isEmpty())
        assertNull(repository.cachedNote("deepHidden"))
        assertNull(repository.cachedMeta("legacyLeaf"))
        val visible = setOf("visibleUser", "visibleClone", "unknownUser", "movedNote")
        assertEquals(visible, repository.searchResult("query").notes.map { it.id }.toSet())
        val listed = repository.listChildrenResult("root")
        assertTrue(listed.failedBranchIds.isEmpty())
        assertEquals(listOf("visibleUser", "movedNote"), listed.items.map { it.note.id })
        assertEquals(0, hiddenMetadataRequests)
        offlineSearch = true
        val restarted = repository()
        assertEquals(visible, restarted.searchResult("query").notes.map { it.id }.toSet())
        assertTrue(restarted.searchResult("query").fromCache)
        assertNotNull(restarted.cachedNote("visibleUser"))
        assertEquals("Hidden Notes", restarted.cachedMeta("visibleUser")!!.title)
    }

    @Test fun refreshedParentageCannotLeakCachedBodiesThroughLoadFallbackOrFailedBranches() = runBlocking {
        val repository = repository()
        metadata["movedNote"] = meta("movedNote", listOf("_hidden"), "Changed remote title")
        try { repository.load("movedNote"); fail("Hidden load must be denied") }
        catch (error: HiddenNoteException) { assertEquals("Системные заметки скрыты", loadFailureMessage(error)) }
        assertNull(repository.cachedNote("movedNote"))
        failedHiddenBranch = true
        val listed = repository.listChildrenResult("root")
        assertEquals(listOf("visibleUser"), listed.items.map { it.note.id })
        assertTrue("Intentionally hidden retained branch is not a partial loading error", listed.failedBranchIds.isEmpty())
        val disk = JSONObject(File(temp, "trilium-cache.json").readText()).getJSONObject("notes").getJSONObject("movedNote")
        assertEquals("query movedNote", disk.getJSONObject("meta").getString("title"))
        assertEquals("<p>query cached</p>", disk.getString("html"))
    }

    @Test fun refreshedCachedFolderIsHiddenWithoutReplacingItsEditBaseline() = runBlocking {
        val repository = repository()
        metadata["visibleUser"] = meta("visibleUser", listOf("_hidden"), "Changed remote title")
        try { repository.listChildrenResult("visibleUser"); fail("Hidden folder must not be presented") }
        catch (_: HiddenNoteException) { }
        assertNull(repository.cachedMeta("visibleUser"))
        assertNull(repository.cachedNote("visibleUser"))
        val disk = JSONObject(File(temp, "trilium-cache.json").readText()).getJSONObject("notes").getJSONObject("visibleUser")
        assertEquals("Hidden Notes", disk.getJSONObject("meta").getString("title"))
        assertEquals("<p>query cached</p>", disk.getString("html"))
    }

    @Test fun hidingPendingNotesDoesNotDeleteTheirOutboxOrPreventSynchronization() = runBlocking {
        val repository = repository()
        val created = repository.create("_userHidden", "query draft", "<p>query draft</p>")
        assertEquals(1, repository.syncState.value.pendingCount)
        assertFalse(repository.searchResult("query").notes.any { it.id == created.note.id })
        assertNull(repository.cachedNote(created.note.id))
        assertEquals(0, repository.syncPending().pendingCount)
        assertEquals(1, createRequests)
        assertNull(repository.cachedNote(created.note.id))
        assertTrue(JSONObject(File(temp, "trilium-cache.json").readText()).getJSONObject("notes").has(created.note.id))
    }
}
