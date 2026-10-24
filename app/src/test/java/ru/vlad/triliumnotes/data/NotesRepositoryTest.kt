package ru.vlad.triliumnotes.data

import android.content.Context
import android.content.ContextWrapper
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class NotesRepositoryTest {
    private val server = MockWebServer()
    private lateinit var temp: File
    private lateinit var context: Context
    private val fake = FakeTrilium()

    @Before fun setUp() {
        temp = Files.createTempDirectory("trilium-repo-test").toFile()
        val files = File(temp, "files").apply { mkdirs() }
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = files
        }
        server.dispatcher = fake
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
        temp.deleteRecursively()
    }

    private fun api() = TriliumApi(server.url("/").toString().trimEnd('/'), "test-token",
        OkHttpClient.Builder().retryOnConnectionFailure(false)
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS).build())

    @Test fun newNoteCanHaveAnEmptyLocalTitleButCreationSendsANonemptyFallback() = runBlocking {
        val repository = NotesRepository(context, api())
        val draft = repository.create("root", "", "<p></p>")
        assertEquals("", draft.note.title)
        assertTrue(draft.pending)
        val reopened = NotesRepository(context, api())
        assertEquals("", reopened.cachedNote(draft.note.id)?.note?.title)

        assertEquals(0, reopened.syncPending().pendingCount)

        assertEquals(1, fake.createRequests)
        assertEquals("Без названия", fake.created.getValue(draft.note.id).title)
        assertEquals("Без названия", reopened.cachedNote(draft.note.id)?.note?.title)
    }

    @Test fun draftSurvivesRestartAndOfflineThenUploads() = runBlocking {
        val first = NotesRepository(context, api())
        first.load("noteAAAA")
        first.saveDraft("noteAAAA", "", "<p>offline draft</p>")

        val offlineApi = TriliumApi("http://127.0.0.1:1", "test-token",
            OkHttpClient.Builder().connectTimeout(200, TimeUnit.MILLISECONDS).build())
        val reopened = NotesRepository(context, offlineApi)
        assertEquals("<p>offline draft</p>", reopened.cachedNote("noteAAAA")?.html)
        assertTrue(reopened.cachedNote("noteAAAA")?.pending == true)
        assertEquals(1, reopened.syncPending().pendingCount)
        assertEquals("<p>original</p>", fake.html)

        val reconnected = NotesRepository(context, api())
        assertEquals(0, reconnected.syncPending().pendingCount)
        assertEquals("<p>offline draft</p>", fake.html)
        assertEquals("Без названия", fake.title)
        assertFalse(reconnected.cachedNote("noteAAAA")!!.pending)
    }

    @Test fun conflictKeepsDraftAndCopyPreservesBothVersions() = runBlocking {
        val repository = NotesRepository(context, api())
        repository.load("noteAAAA")
        repository.saveDraft("noteAAAA", "Local", "<p>local</p>")
        fake.title = "Remote"
        fake.html = "<p>remote</p>"

        val conflicted = repository.syncPending()
        assertTrue("noteAAAA" in conflicted.conflicts)
        assertEquals("<p>local</p>", repository.cachedNote("noteAAAA")?.html)
        assertEquals("<p>remote</p>", fake.html)

        val copy = repository.saveConflictAsCopy("noteAAAA")
        assertEquals("<p>remote</p>", repository.cachedNote("noteAAAA")?.html)
        assertEquals("<p>local</p>", copy.html)
        assertEquals(0, repository.syncPending().pendingCount)
        assertEquals("<p>remote</p>", fake.html)
        assertEquals("<p>local</p>", fake.created.getValue(copy.note.id).html)
    }

    @Test fun lostCreateResponseDoesNotDuplicateNote() = runBlocking {
        fake.dropFirstCreateResponse = true
        val repository = NotesRepository(context, api())
        val staged = repository.create("root", "New", "<p>new</p>")
        assertEquals(1, repository.syncPending().pendingCount)
        assertEquals(1, fake.createRequests)
        assertTrue(staged.note.id in fake.created)
        assertFalse(fake.createIncludedBranchId)

        repository.saveDraft(staged.note.id, "Newer", "<p>newer</p>")

        val reopened = NotesRepository(context, api())
        assertEquals(1, reopened.syncPending().pendingCount)
        assertEquals(0, reopened.syncPending().pendingCount)
        assertEquals(1, fake.createRequests)
        assertEquals("<p>newer</p>", fake.htmlOf(staged.note.id))
    }

    @Test fun corruptCacheIsReportedAndNeverOverwritten() = runBlocking {
        val cache = File(context.filesDir, "trilium-cache.json")
        cache.writeText("{invalid-cache")
        val repository = NotesRepository(context, api())
        assertNotNull(repository.syncState.value.error)
        try {
            repository.create("root", "New", "<p>local</p>")
            throw AssertionError("Expected local write to be blocked")
        } catch (_: IOException) {
            assertEquals("{invalid-cache", cache.readText())
        }
    }

    @Test fun offlineCreatedNoteRemainsInTreeAfterSyncAndRestart() = runBlocking {
        val offlineApi = TriliumApi("http://127.0.0.1:1", "test-token",
            OkHttpClient.Builder().connectTimeout(200, TimeUnit.MILLISECONDS).build())
        val offline = NotesRepository(context, offlineApi)
        val staged = offline.create("root", "On phone", "<p>draft</p>")
        assertEquals(staged.note.id, offline.cachedChildren("root")?.single()?.note?.id)

        val reopened = NotesRepository(context, offlineApi)
        assertEquals(staged.note.id, reopened.listChildren("root").single().note.id)
        assertEquals(1, reopened.syncPending().pendingCount)

        val online = NotesRepository(context, api())
        assertEquals(0, online.syncPending().pendingCount)
        assertEquals(staged.note.id, online.cachedChildren("root")?.single()?.note?.id)
        val restarted = NotesRepository(context, offlineApi)
        assertEquals(staged.note.id, restarted.cachedChildren("root")?.single()?.note?.id)
        assertEquals("<p>draft</p>", restarted.cachedNote(staged.note.id)?.html)
    }

    @Test fun treeAndSearchRefreshCannotChangeCachedEditBaseline() = runBlocking {
        val repository = NotesRepository(context, api())
        repository.load("noteAAAA")
        fake.title = "Remote title"
        assertEquals("Remote title", repository.listChildren("root").single().note.title)
        assertEquals("Remote title", repository.search("term").single().title)
        assertEquals("Original", repository.cachedNote("noteAAAA")?.note?.title)

        repository.saveDraft("noteAAAA", "Original", "<p>local body</p>")
        assertTrue("noteAAAA" in repository.syncPending().conflicts)
        assertEquals("Remote title", fake.title)
        assertEquals("<p>original</p>", fake.html)
    }

    @Test fun markdownCodeBodySurvivesOfflineDraftAndSyncWithoutHtmlConversion() = runBlocking {
        fake.type = "code"
        fake.mime = "text/x-markdown"
        fake.html = "# Heading\n\n- [ ] A task\n"
        val repository = NotesRepository(context, api())
        assertEquals(fake.html, repository.load("noteAAAA").html)
        repository.saveDraft("noteAAAA", "Markdown", "# Edited\n\n```kotlin\nval n = 1\n```\n")
        val reopened = NotesRepository(context, api())
        assertTrue(reopened.cachedNote("noteAAAA")!!.pending)
        assertEquals(0, reopened.syncPending().pendingCount)
        assertEquals("# Edited\n\n```kotlin\nval n = 1\n```\n", fake.html)
    }

    @Test fun failingChildKeepsPreviouslyVisibleCachedBranchAndReportsPartialFailure() = runBlocking {
        val repository = NotesRepository(context, api())
        assertEquals(1, repository.listChildren("root").size)
        fake.noteUnavailable = true
        val partial = repository.listChildrenResult("root")
        assertEquals(listOf("branchAAAA"), partial.failedBranchIds)
        assertEquals("noteAAAA", partial.items.single().note.id)
        assertEquals("root", partial.parent.id)
    }

    @Test fun markdownConflictCopyPreservesTypeAndMime() = runBlocking {
        fake.type = "code"
        fake.mime = "text/x-markdown"
        val repository = NotesRepository(context, api())
        repository.load("noteAAAA")
        repository.saveDraft("noteAAAA", "Local", "# Local\n")
        fake.html = "# Remote\n"
        repository.syncPending()
        val copy = repository.saveConflictAsCopy("noteAAAA")
        assertEquals("code", copy.note.type)
        assertEquals("text/x-markdown", copy.note.mime)
        assertEquals(0, repository.syncPending().pendingCount)
        val created = fake.created.getValue(copy.note.id)
        assertEquals("code", created.type)
        assertEquals("text/x-markdown", created.mime)
        assertEquals("# Local\n", created.html)
    }

    @Test fun nonEditableStructuredBodyIsLoadedWithoutEnablingDestructiveEditing() = runBlocking {
        fake.type = "relationMap"
        fake.mime = "application/json"
        fake.html = "{\"notes\":[{\"noteId\":\"noteBBBB\"}],\"relations\":[]}"
        val repository = NotesRepository(context, api())
        val loaded = repository.load("noteAAAA")
        assertEquals(fake.html, loaded.html)
        assertFalse(loaded.note.isEditable)
        try {
            repository.saveDraft("noteAAAA", "Unsafe", "<p>replacement</p>")
            throw AssertionError("Structured notes must not be replaced by HTML drafts")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, repository.syncState.value.pendingCount)
    }

    @Test fun onlineSearchIncludesUnsyncedDraftsAndOfflineSearchReportsCache() = runBlocking {
        val repository = NotesRepository(context, api())
        val draft = repository.create("root", "Unique local title", "<p>local body</p>")
        val online = repository.searchResult("Unique local")
        assertFalse(online.fromCache)
        assertTrue(online.notes.any { it.id == draft.note.id })
        val offline = NotesRepository(context, TriliumApi("http://127.0.0.1:1", "test-token"))
            .searchResult("Unique local")
        assertTrue(offline.fromCache)
        assertEquals(draft.note.id, offline.notes.single().id)
    }

    private data class Created(val parentId: String, val title: String, val html: String,
        val type: String = "text", val mime: String = "text/html")

    private class FakeTrilium : Dispatcher() {
        @Volatile var title = "Original"
        @Volatile var type = "text"
        @Volatile var mime = "text/html"
        @Volatile var noteUnavailable = false
        @Volatile var html = "<p>original</p>"
        @Volatile var dropFirstCreateResponse = false
        @Volatile var createRequests = 0
        @Volatile var createIncludedBranchId = false
        val created = mutableMapOf<String, Created>()

        fun htmlOf(id: String): String = created.getValue(id).html

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl?.encodedPath.orEmpty()
            val method = request.method
            if (method == "GET" && path == "/etapi/notes/root") {
                return response(200, meta("root", "Root", "root")
                    .put("childBranchIds", JSONArray().put("branchAAAA")))
            }
            if (method == "GET" && path == "/etapi/branches/branchAAAA") {
                return response(200, JSONObject().put("branchId", "branchAAAA")
                    .put("noteId", "noteAAAA").put("parentNoteId", "root")
                    .put("prefix", JSONObject.NULL).put("notePosition", 10)
                    .put("isExpanded", false))
            }
            if (method == "GET" && path == "/etapi/notes") {
                return response(200, JSONObject().put("results", JSONArray().put(meta("noteAAAA", title, "root"))))
            }
            if (path == "/etapi/create-note" && method == "POST") {
                val body = JSONObject(request.body.readUtf8())
                val id = body.getString("noteId")
                createRequests++
                createIncludedBranchId = body.has("branchId")
                if (id in created) return response(409)
                val note = Created(body.getString("parentNoteId"), body.getString("title"),
                    body.getString("content"), body.optString("type", "text"), body.optString("mime", "text/html"))
                created[id] = note
                if (dropFirstCreateResponse) {
                    dropFirstCreateResponse = false
                    return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                }
                return response(201, JSONObject().put("note", meta(id, note.title, note.parentId)
                    .put("type", note.type).put("mime", note.mime)))
            }
            val id = path.removePrefix("/etapi/notes/").substringBefore('/')
            if (id == "noteAAAA" && noteUnavailable) return response(403)
            val found = id == "noteAAAA" || id in created
            if (!found) return response(404)
            if (method == "GET" && path.endsWith("/content"))
                return response(200, if (id == "noteAAAA") html else created.getValue(id).html)
            if (method == "GET") {
                val note = created[id]
                return response(200, meta(id, note?.title ?: title, note?.parentId ?: "root")
                    .put("type", if (id == "noteAAAA") type else note?.type ?: "text")
                    .put("mime", if (id == "noteAAAA") mime else note?.mime ?: "text/html"))
            }
            if (id == "noteAAAA" && method == "PUT" && path.endsWith("/content")) {
                html = request.body.readUtf8()
                return response(204)
            }
            if (id in created && method == "PUT" && path.endsWith("/content")) {
                val old = created.getValue(id)
                created[id] = old.copy(html = request.body.readUtf8())
                return response(204)
            }
            if (id == "noteAAAA" && method == "PATCH") {
                title = JSONObject(request.body.readUtf8()).getString("title")
                return response(200, meta(id, title, "root"))
            }
            if (id in created && method == "PATCH") {
                val old = created.getValue(id)
                val changed = JSONObject(request.body.readUtf8()).getString("title")
                created[id] = old.copy(title = changed)
                return response(200, meta(id, changed, old.parentId))
            }
            return response(405)
        }

        private fun meta(id: String, title: String, parent: String) = JSONObject()
            .put("noteId", id).put("title", title).put("type", "text")
            .put("mime", "text/html").put("isProtected", false)
            .put("blobId", "blob1234").put("utcDateModified", "2026-09-18 10:00:00.000Z")
            .put("parentNoteIds", JSONArray().put(parent)).put("childBranchIds", JSONArray())

        private fun response(code: Int, body: Any? = null): MockResponse = MockResponse()
            .setResponseCode(code)
            .apply { if (body != null) setBody(body.toString()) }
    }
}
