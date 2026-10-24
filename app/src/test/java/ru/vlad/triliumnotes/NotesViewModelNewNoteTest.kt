package ru.vlad.triliumnotes

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import ru.vlad.triliumnotes.data.NotesServices
import ru.vlad.triliumnotes.data.TriliumApi
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class NotesViewModelNewNoteTest {
    private val server = MockWebServer()
    private val models = ViewModelStore()
    private val singleton = NotesServices::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val remote = ConcurrentHashMap<String, JSONObject>()
    private var previousServices: Any? = null
    private lateinit var directory: File
    private lateinit var model: NotesViewModel

    @Before fun start() {
        val application = RuntimeEnvironment.getApplication()
        directory = Files.createTempDirectory("notes-new-note-test").toFile()
        val context = object : ContextWrapper(application) { override fun getFilesDir(): File = directory }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/etapi/notes/root") return MockResponse().setBody("""{"noteId":"root","childBranchIds":[]}""")
                if (request.path == "/etapi/create-note") {
                    val body = JSONObject(request.body.readUtf8())
                    remote[body.getString("noteId")] = body
                    return MockResponse().setResponseCode(201).setBody(JSONObject().put("note", meta(body)).toString())
                }
                val id = request.path.orEmpty().removePrefix("/etapi/notes/").substringBefore('/')
                val note = remote[id] ?: return MockResponse().setResponseCode(404)
                if (request.method == "PATCH") note.put("title", JSONObject(request.body.readUtf8()).getString("title"))
                if (request.path.orEmpty().endsWith("/content")) {
                    if (request.method == "PUT") note.put("content", request.body.readUtf8())
                    return MockResponse().setBody(note.getString("content"))
                }
                return MockResponse().setBody(meta(note).toString())
            }
        }
        server.start()
        val url = server.url("/").toString()
        previousServices = singleton.get(null)
        singleton.set(null, NotesServices(context, TriliumApi(url, "test-token"), url))
        WorkManagerTestInitHelper.initializeTestWorkManager(application, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build())
        model = NotesViewModel(application)
        models.put("notes", model)
        await { !model.state.value.loading }
    }

    @After fun stop() {
        models.clear()
        shadowOf(Looper.getMainLooper()).idle()
        singleton.set(null, previousServices)
        server.shutdown()
        directory.deleteRecursively()
    }

    @Test fun blankNewTitleRemainsUntouchedThroughBodyEditingAndSafeServerFallback() {
        model.create()
        await { !model.state.value.loading && model.state.value.document != null }
        val id = model.state.value.document!!.note.id
        assertEquals(id, model.state.value.newNoteId)
        assertEquals("", model.state.value.document!!.note.title)
        assertTrue(model.state.value.newNoteTitleUntouched)

        model.editHtml(id, "<p>Body before title</p>")
        await { !model.state.value.localWritePending }
        assertTrue(model.state.value.newNoteTitleUntouched)
        model.syncNow()
        await { model.state.value.document?.pending == false }
        assertEquals("Без названия", remote.getValue(id).getString("title"))
        assertEquals("Без названия", model.state.value.document!!.note.title)
        assertTrue(model.state.value.newNoteTitleUntouched)

        model.editTitle(id, "My title")
        await { !model.state.value.localWritePending }
        assertFalse(model.state.value.newNoteTitleUntouched)
        model.syncNow()
        await { model.state.value.document?.pending == false }
        assertEquals("My title", remote.getValue(id).getString("title"))
        assertEquals("<p>Body before title</p>", remote.getValue(id).getString("content"))
    }

    @Test fun firstTitleInputConsumesTheUntouchedFlagEvenWhenTheUserChoosesABlankTitle() {
        model.create()
        await { !model.state.value.loading && model.state.value.document != null }
        val id = model.state.value.document!!.note.id
        model.editTitle(id, "")
        assertFalse(model.state.value.newNoteTitleUntouched)
        assertEquals("", model.state.value.document!!.note.title)
    }

    @Test fun creatingFolderKeepsLibraryVisibleAndCanCreateInsideAnEmptyFolderOffline() {
        model.connectionUnavailable()
        model.createFolder("  Новая папка  ")
        await { !model.state.value.loading && model.state.value.items.size == 1 }
        val folder = model.state.value.items.single().note
        assertEquals("book", folder.type)
        assertEquals("Новая папка", folder.title)
        assertNull(model.state.value.document)

        model.enterFolder(folder)
        await { !model.state.value.loading }
        assertEquals(folder.id, model.state.value.folders.last().id)
        assertEquals(false, model.state.value.currentFolderHasContent)
        assertTrue(model.state.value.items.isEmpty())
        model.createFolder("Вложенная папка")
        await { !model.state.value.loading && model.state.value.items.size == 1 }
        assertEquals(listOf(folder.id), model.state.value.items.single().note.parentNoteIds)
        assertNull(model.state.value.document)
    }

    private fun meta(note: JSONObject) = JSONObject().put("noteId", note.getString("noteId"))
        .put("title", note.getString("title")).put("type", note.optString("type", "text")).put("mime", "text/html")
        .put("parentNoteIds", org.json.JSONArray().put("root")).put("childBranchIds", org.json.JSONArray())

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("ViewModel did not reach the expected state", condition())
    }
}
