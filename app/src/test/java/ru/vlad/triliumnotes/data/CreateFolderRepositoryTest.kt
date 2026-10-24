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
class CreateFolderRepositoryTest {
    private val server = MockWebServer()
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var api: TriliumApi
    private val remote = linkedMapOf<String, JSONObject>()
    private val createdTypes = mutableListOf<String>()
    private var rejectCreates = false

    @Before fun start() {
        directory = Files.createTempDirectory("create-folder-test").toFile()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = directory
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/etapi/create-note") {
                    if (rejectCreates) return MockResponse().setResponseCode(503)
                    val note = JSONObject(request.body.readUtf8())
                    createdTypes += note.getString("type")
                    remote[note.getString("noteId")] = note
                    return MockResponse().setResponseCode(201).setBody(JSONObject().put("note", meta(note)).toString())
                }
                val id = request.path.orEmpty().removePrefix("/etapi/notes/").substringBefore('/')
                val note = remote[id] ?: return MockResponse().setResponseCode(404)
                return MockResponse().setBody(if (request.path.orEmpty().endsWith("/content"))
                    note.getString("content") else meta(note).toString())
            }
        }
        server.start()
        api = TriliumApi(server.url("/").toString(), "test-token")
    }

    @After fun stop() { server.shutdown(); directory.deleteRecursively() }

    @Test fun emptyFolderAndChildAreDurableOfflineAndSyncInParentOrder() = runBlocking {
        val repository = NotesRepository(context, api)
        val folder = repository.createFolder("root", "  Проекты  ")
        assertEquals("book", folder.note.type)
        assertEquals("Проекты", folder.note.title)
        assertEquals(emptyList<TreeItem>(), repository.cachedChildren(folder.note.id))
        assertEquals(0, server.requestCount)

        val restored = NotesRepository(context, api)
        assertEquals("book", restored.cachedChildren("root")!!.single().note.type)
        assertEquals(emptyList<TreeItem>(), restored.cachedChildren(folder.note.id))
        val child = restored.create(folder.note.id, "План", "<p>Текст</p>")
        assertEquals(child.note.id, restored.cachedChildren(folder.note.id)!!.single().note.id)
        restored.syncPending()
        assertEquals(2, createdTypes.size)
        assertEquals(listOf("book", "text"), createdTypes)
        assertEquals(0, restored.syncState.value.pendingCount)
        assertEquals(folder.note.id, remote.getValue(child.note.id).getString("parentNoteId"))

        val afterSync = NotesRepository(context, api)
        assertEquals("book", afterSync.cachedChildren("root")!!.single().note.type)
        assertEquals(child.note.id, afterSync.cachedChildren(folder.note.id)!!.single().note.id)
        assertFalse(afterSync.cachedNote(folder.note.id)!!.pending)
    }

    @Test fun failedUploadKeepsFolderAndRetryCreatesItOnce() = runBlocking {
        val repository = NotesRepository(context, api)
        val folder = repository.createFolder("root", "Офлайн")
        rejectCreates = true
        assertEquals(1, repository.syncPending().pendingCount)
        assertNotNull(repository.syncState.value.error)
        assertEquals("book", repository.cachedChildren("root")!!.single().note.type)

        rejectCreates = false
        val restored = NotesRepository(context, api)
        assertEquals(0, restored.syncPending().pendingCount)
        assertEquals(0, restored.syncPending().pendingCount)
        assertEquals(listOf(folder.note.id), remote.keys.toList())
        assertEquals(listOf("book"), createdTypes)
    }

    @Test fun blankFolderTitleDoesNotWriteToDiskOrServer() = runBlocking {
        val repository = NotesRepository(context, api)
        try {
            repository.createFolder("root", "   ")
            fail("A folder requires a visible title")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, repository.syncState.value.pendingCount)
        assertEquals(0, server.requestCount)
        assertFalse(File(directory, "trilium-cache.json").exists())
    }

    @Test fun localWriteFailureLeavesExistingCacheUntouchedAndDoesNotQueueFolder() = runBlocking {
        val cache = File(directory, "trilium-cache.json")
        cache.writeText("unreadable existing cache")
        val repository = NotesRepository(context, api)
        try {
            repository.createFolder("root", "Папка")
            fail("Corrupt cache must not be overwritten")
        } catch (_: java.io.IOException) { }
        assertEquals("unreadable existing cache", cache.readText())
        assertEquals(0, repository.syncState.value.pendingCount)
        assertEquals(0, server.requestCount)
    }

    private fun meta(note: JSONObject) = JSONObject().put("noteId", note.getString("noteId"))
        .put("title", note.getString("title")).put("type", note.getString("type"))
        .put("mime", note.getString("mime"))
        .put("parentNoteIds", JSONArray().put(note.getString("parentNoteId")))
        .put("childBranchIds", JSONArray())
}
