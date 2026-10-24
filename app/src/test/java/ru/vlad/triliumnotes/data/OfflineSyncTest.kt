package ru.vlad.triliumnotes.data

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class OfflineSyncTest {
    private val server = MockWebServer()
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var api: TriliumApi
    private val requests = mutableListOf<String>()
    private var failBody = false
    private var failRoot = false
    private var version = "v1"
    private var fileMime = "application/pdf"
    private var attachmentBlob = "attachmentV1"
    private var bodyOverride: String? = null
    private var allowWrites = false
    private var mutationCount = 0
    private var blockedBody: CountDownLatch? = null
    private var releaseBody: CountDownLatch? = null

    private fun meta(id: String): JSONObject = JSONObject()
        .put("noteId", id).put("title", id).put("type", if (id == "fileAAAA") "file" else "text")
        .put("mime", if (id == "fileAAAA") fileMime else "text/html")
        .put("blobId", version).put("utcDateModified", version).put("isProtected", false)
        .put("parentNoteIds", JSONArray(if (id == "root") emptyList<String>() else listOf("root")))
        .put("childBranchIds", JSONArray(if (id == "root") listOf("branchText", "branchFile", "branchHidden") else emptyList<String>()))

    @Before fun start() {
        directory = Files.createTempDirectory("offline-sync-test").toFile()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = directory
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                synchronized(requests) { requests += path }
                if (failRoot && path == "/etapi/notes/root") return MockResponse().setResponseCode(503)
                if (request.method != "GET") {
                    mutationCount++
                    if (allowWrites && request.method == "PUT") {
                        bodyOverride = request.body.readUtf8()
                        version = "v2"
                        return MockResponse().setBody("{}")
                    }
                    return MockResponse().setResponseCode(503)
                }
                if (path.startsWith("/etapi/branches/")) {
                    val id = when (path.substringAfterLast('/')) {
                        "branchText" -> "textAAAA"
                        "branchFile" -> "fileAAAA"
                        else -> "_hidden"
                    }
                    return ok(JSONObject().put("branchId", path.substringAfterLast('/')).put("noteId", id).put("notePosition", 1))
                }
                if (path == "/etapi/attachments/attachAAAA/content") return MockResponse().setHeader("Content-Type", "image/png").setBody("image-data")
                val id = path.removePrefix("/etapi/notes/").substringBefore('/')
                if (path.endsWith("/attachments")) return MockResponse().setBody(if (id == "textAAAA")
                    JSONArray().put(JSONObject().put("attachmentId", "attachAAAA").put("ownerId", id).put("role", "image")
                        .put("mime", "image/png").put("title", "Image").put("position", 0).put("blobId", attachmentBlob)).toString() else "[]")
                if (path.endsWith("/content")) {
                    if (failBody && id == "textAAAA") return MockResponse().setResponseCode(503)
                    val body = if (id == "fileAAAA") "pdf-data" else bodyOverride ?: "<p>$version</p><img src=\"api/attachments/attachAAAA/image/pic.png\">"
                    if (id == "textAAAA") {
                        blockedBody?.let { latch ->
                            blockedBody = null
                            latch.countDown()
                            check(releaseBody!!.await(10, TimeUnit.SECONDS))
                        }
                    }
                    return MockResponse().setHeader("Content-Type", if (id == "fileAAAA") fileMime else "text/html")
                        .setBody(body)
                }
                return ok(meta(id))
            }
        }
        server.start()
        api = TriliumApi(server.url("/").toString(), "test-token")
    }

    @After fun stop() { server.shutdown(); directory.deleteRecursively() }
    private fun ok(json: JSONObject) = MockResponse().setBody(json.toString())
    private fun coordinator(repository: NotesRepository) = OfflineSync(repository, api, NoteResources(context, api, server.url("/").toString()), context)

    @Test fun fullCopyIncludesBodiesBinaryAndAttachmentsAndSurvivesRepositoryRestart() = runBlocking {
        val repository = NotesRepository(context, api)
        val result = coordinator(repository).refreshAll()
        assertTrue(result.toString(), result.complete)
        assertEquals(3, result.completed)
        assertEquals(3, result.total)
        assertFalse(requests.any { it.contains("_hidden") })
        val reloaded = NotesRepository(context, api)
        assertTrue(coordinator(reloaded).state.value.available)
        assertEquals(2, reloaded.cachedChildren("root")!!.size)
        assertTrue(reloaded.cachedNote("textAAAA")!!.html.contains("v1"))
        assertEquals("pdf-data", File(directory, "note-resources/note_fileAAAA.data").readText())
        assertEquals("image-data", File(directory, "note-resources/attachment_attachAAAA.data").readText())
        requests.clear()
        File(directory, "note-resources").listFiles().orEmpty().filter { it.extension == "data" }.forEach {
            assertTrue(it.setLastModified(System.currentTimeMillis() - 3_600_000))
        }
        assertTrue(coordinator(reloaded).refreshAll().complete)
        assertFalse(requests.any { it.endsWith("/content") })
    }

    @Test fun changedCatalogAttachmentBlobRefreshesBytesWithoutRefetchingNoteBodies() = runBlocking {
        val repository = NotesRepository(context, api)
        val sync = coordinator(repository)
        assertTrue(sync.refreshAll().complete)
        requests.clear()
        attachmentBlob = "attachmentV2"
        assertTrue(sync.refreshAll().complete)
        assertEquals(listOf("/etapi/attachments/attachAAAA/content"), requests.filter { it.endsWith("/content") })
    }

    @Test fun failedRefreshKeepsBodyAndSuccessfulReconnectFetchesNewVersion() = runBlocking {
        val repository = NotesRepository(context, api)
        val sync = coordinator(repository)
        assertTrue(sync.refreshAll().complete)
        version = "v2"
        failBody = true
        assertFalse(sync.refreshAll().complete)
        assertTrue(sync.state.value.available)
        assertTrue(repository.cachedNote("textAAAA")!!.html.contains("v1"))
        failBody = false
        assertTrue(sync.refreshAll().complete)
        assertTrue(repository.cachedNote("textAAAA")!!.html.contains("v2"))
    }

    @Test fun wholeFolderOfflineFallbackKeepsRowsWithoutInventedBranchFailures() = runBlocking {
        val repository = NotesRepository(context, api)
        val sync = coordinator(repository)
        assertTrue(sync.refreshAll().complete)
        failRoot = true
        requests.clear()
        val cached = repository.listChildrenResult("root")
        assertTrue(cached.fromCache)
        assertTrue(cached.failedBranchIds.isEmpty())
        assertEquals(2, cached.items.size)
        val result = sync.refreshAll()
        assertFalse(result.complete)
        assertTrue(result.available)
        assertEquals(2, requests.size)
    }

    @Test fun textFilesAlsoHavePersistentBinaryCacheForNativeAttachmentViewer() = runBlocking {
        fileMime = "text/plain"
        val repository = NotesRepository(context, api)
        assertTrue(coordinator(repository).refreshAll().complete)
        assertEquals("pdf-data", repository.cachedNote("fileAAAA")!!.html)
        assertEquals("pdf-data", File(directory, "note-resources/note_fileAAAA.data").readText())
    }

    @Test fun cacheOnlySearchUsesDownloadedBodiesWithoutAnyNetworkRequest() = runBlocking {
        val repository = NotesRepository(context, api)
        assertTrue(coordinator(repository).refreshAll().complete)
        requests.clear()
        val result = repository.searchResult("v1", cacheOnly = true)
        assertTrue(result.fromCache)
        assertTrue(result.notes.any { it.id == "textAAAA" })
        assertTrue(requests.isEmpty())
    }

    @Test fun pinnedEditorKeepsItsOriginalBaselineAndDetectsLaterRemoteChange() = runBlocking {
        val repository = NotesRepository(context, api)
        assertTrue(coordinator(repository).refreshAll().complete)
        val displayed = repository.cachedNote("textAAAA")!!
        repository.pinDocument("textAAAA")
        version = "v2"
        repository.prefetch(parseNote(meta("textAAAA")))
        assertEquals(displayed.html, repository.cachedNote("textAAAA")!!.html)
        repository.saveDraft("textAAAA", "textAAAA", displayed.html + "<p>local edit</p>")
        val synced = repository.syncPending()
        assertTrue("Remote change must create a conflict", "textAAAA" in synced.conflicts)
        assertEquals(0, mutationCount)
        assertTrue(repository.cachedNote("textAAAA")!!.html.contains("local edit"))
        repository.pinDocument(null)
    }

    @Test fun pinnedUncachedOpeningNoteCannotMarkOfflineCopyComplete() = runBlocking {
        val repository = NotesRepository(context, api)
        repository.pinDocument("textAAAA")
        val sync = coordinator(repository)
        assertFalse(sync.refreshAll().complete)
        assertFalse(repository.offlineCopyAvailable)
        repository.pinDocument(null)
        assertTrue(sync.refreshAll().complete)
        assertTrue(repository.offlineCopyAvailable)
    }

    @Test fun prefetchAndOrdinaryLoadPreservePendingDraftAndConflictBaseline() = runBlocking {
        val repository = NotesRepository(context, api)
        assertTrue(coordinator(repository).refreshAll().complete)
        repository.saveDraft("textAAAA", "Local title", "<p>local draft</p>")
        val before = JSONObject(File(directory, "trilium-cache.json").readText())
        val baseline = before.getJSONObject("notes").getJSONObject("textAAAA").toString()
        version = "v2"
        repository.prefetch(parseNote(meta("textAAAA")))
        repository.load("textAAAA")
        val after = JSONObject(File(directory, "trilium-cache.json").readText())
        assertEquals(baseline, after.getJSONObject("notes").getJSONObject("textAAAA").toString())
        assertEquals(before.getJSONObject("pending").toString(), after.getJSONObject("pending").toString())
        assertEquals("<p>local draft</p>", repository.cachedNote("textAAAA")!!.html)
    }

    @Test fun oldInflightLoadCannotOverwriteDraftAlreadyConfirmedByConcurrentSync() = runBlocking {
        val repository = NotesRepository(context, api)
        assertTrue(coordinator(repository).refreshAll().complete)
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        blockedBody = arrived
        releaseBody = release
        val oldLoad = async(Dispatchers.IO) { repository.load("textAAAA") }
        try {
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
            repository.saveDraft("textAAAA", "textAAAA", "<p>confirmed draft</p>")
            allowWrites = true
            assertEquals(0, repository.syncPending().pendingCount)
        } finally {
            release.countDown()
        }
        oldLoad.await()
        assertEquals("<p>confirmed draft</p>", repository.cachedNote("textAAAA")!!.html)
        assertFalse(repository.cachedNote("textAAAA")!!.pending)
    }
}
