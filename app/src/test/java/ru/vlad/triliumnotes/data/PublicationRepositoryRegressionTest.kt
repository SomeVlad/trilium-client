package ru.vlad.triliumnotes.data

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
class PublicationRepositoryRegressionTest {
    private val server = MockWebServer()
    private lateinit var directory: File
    private lateinit var context: ContextWrapper
    @Before fun start() {
        server.start()
        directory = Files.createTempDirectory("publication-repository").toFile()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = directory
        }
    }
    @After fun stop() { server.shutdown(); directory.deleteRecursively() }
    private fun repository() = NotesRepository(context, TriliumApi(server.url("/").toString(), "synthetic-token"))
    private fun meta(id: String, title: String = "Original", parent: String = "oldParent") =
        JSONObject().put("noteId", id).put("title", title).put("type", "text").put("mime", "text/html")
            .put("blobId", "unchanged").put("utcDateModified", "2026-01-01")
            .put("childBranchIds", org.json.JSONArray()).put("parentNoteIds", org.json.JSONArray(listOf(parent)))
    private fun json(body: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())

    @Test fun missingRemoteMetadataBecomesRecoverableConflict() = missingRemote(contentMissing = false)
    @Test fun missingRemoteContentBecomesRecoverableConflict() = missingRemote(contentMissing = true)

    @Test fun offlineChildOfServerDeletedParentCanBeRecoveredAtRoot() = runBlocking {
        val first = repository()
        val child = first.create("goneParent", "Offline child", "<p>Child draft</p>")
        server.enqueue(MockResponse().setResponseCode(404)) // Its new ID is not on the server.
        server.enqueue(MockResponse().setResponseCode(404)) // create-note cannot find the parent.
        val result = first.syncPending()
        assertEquals(setOf(child.note.id), result.conflicts)
        assertNull(result.error)
        val reopened = repository()
        assertTrue(requireNotNull(reopened.cachedNote(child.note.id)).remoteMissing)
        val copy = reopened.saveConflictAsCopy(child.note.id)
        assertEquals(listOf("root"), copy.note.parentNoteIds)
        assertEquals(child.html, copy.html)
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(json(JSONObject().put("note", meta(copy.note.id, copy.note.title, "root"))))
        assertEquals(0, reopened.syncPending().pendingCount)
    }

    private fun missingRemote(contentMissing: Boolean) = runBlocking {
        val first = repository()
        server.enqueue(json(meta("noteAAAA")))
        server.enqueue(MockResponse().setBody("<p>Original</p>"))
        first.load("noteAAAA")
        first.saveDraft("noteAAAA", "My draft", "<p>Must survive remote deletion</p>")
        if (contentMissing) server.enqueue(json(meta("noteAAAA")))
        server.enqueue(MockResponse().setResponseCode(404))
        val result = first.syncPending()
        assertEquals(setOf("noteAAAA"), result.conflicts)
        assertNull("A resolved missing-note conflict must not keep the retry loop alive", result.error)
        val before = server.requestCount
        first.syncPending()
        assertEquals("Conflicts wait for user recovery, not repeated HTTP requests", before, server.requestCount)

        val reopened = repository()
        val draft = requireNotNull(reopened.cachedNote("noteAAAA"))
        assertTrue(draft.remoteMissing)
        assertEquals("My draft", draft.note.title)
        assertEquals("<p>Must survive remote deletion</p>", draft.html)
        val copy = reopened.saveConflictAsCopy("noteAAAA")
        assertEquals(listOf("root"), copy.note.parentNoteIds)
        assertEquals(draft.html, copy.html)
        assertNull("The deleted server note must not survive as a phantom local search hit", reopened.cachedNote("noteAAAA"))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(json(JSONObject().put("note", meta(copy.note.id, copy.note.title, "root"))))
        assertEquals(0, reopened.syncPending().pendingCount)
        repeat(before + 1) { server.takeRequest() }
        val create = server.takeRequest()
        assertEquals("/etapi/create-note", create.path)
        val body = JSONObject(create.body.readUtf8())
        assertEquals("root", body.getString("parentNoteId"))
        assertEquals(draft.html, body.getString("content"))
    }

    @Test fun unchangedTreeRefreshDoesNotRewriteWholeCache() = runBlocking {
        val repository = repository()
        server.enqueue(json(meta("root", "Root", "")))
        repository.listChildrenResult("root")
        val cache = File(directory, "trilium-cache.json")
        val bytes = cache.readBytes()
        val sentinel = 1_700_000_000_000L
        assertTrue(cache.setLastModified(sentinel))
        repeat(3) {
            server.enqueue(json(meta("root", "Root", "")))
            repository.listChildrenResult("root")
        }
        assertEquals(4, server.requestCount)
        assertEquals("Identical metadata must not fsync the complete store", sentinel, cache.lastModified())
        assertArrayEquals(bytes, cache.readBytes())
    }
}
