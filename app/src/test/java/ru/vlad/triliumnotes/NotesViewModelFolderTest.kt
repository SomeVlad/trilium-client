package ru.vlad.triliumnotes

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import ru.vlad.triliumnotes.data.NoteMeta
import ru.vlad.triliumnotes.data.NotesServices
import ru.vlad.triliumnotes.data.TriliumApi
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class NotesViewModelFolderTest {
    private val server = MockWebServer()
    private val models = ViewModelStore()
    private val singleton = NotesServices::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val folderJob = NotesViewModel::class.java.getDeclaredField("folderJob").apply { isAccessible = true }
    private var previousServices: Any? = null
    private var singletonReplaced = false
    private lateinit var directory: File
    private lateinit var model: NotesViewModel
    private lateinit var services: NotesServices
    private val contentRequests = AtomicInteger()
    @Volatile private var folderVersion = "versionA"
    @Volatile private var folderBody = "<p>Saved body</p>"
    @Volatile private var metadataUnavailable = false
    @Volatile private var contentUnavailable = false
    @Volatile private var metadataEntered: CountDownLatch? = null
    @Volatile private var releaseMetadata: CountDownLatch? = null

    @Before fun start() {
        val application = RuntimeEnvironment.getApplication()
        val manager = application.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = manager.activeNetwork
        assertNotNull(activeNetwork)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(manager).setNetworkCapabilities(activeNetwork, capabilities)
        assertTrue(application.hasValidatedInternet())
        directory = Files.createTempDirectory("notes-folder-test").toFile()
        val filesContext = object : ContextWrapper(application) {
            override fun getFilesDir(): File = directory
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/etapi/notes/root" -> MockResponse().setBody("""{"noteId":"root","type":"text","mime":"text/html","childBranchIds":["folderBranch"]}""")
                "/etapi/branches/folderBranch" -> MockResponse().setBody("""{"branchId":"folderBranch","parentNoteId":"root","noteId":"folder"}""")
                "/etapi/notes/folder" -> {
                    metadataEntered?.countDown()
                    releaseMetadata?.await(5, TimeUnit.SECONDS)
                    if (metadataUnavailable) MockResponse().setResponseCode(503)
                    else MockResponse().setBody("""{"noteId":"folder","title":"Folder","type":"text","mime":"text/html","blobId":"$folderVersion","childBranchIds":[],"parentNoteIds":["root"]}""")
                }
                "/etapi/notes/folder/content" -> {
                    contentRequests.incrementAndGet()
                    if (contentUnavailable) MockResponse().setResponseCode(503)
                    else MockResponse().setBody(folderBody)
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val url = server.url("/").toString()
        services = NotesServices(filesContext, TriliumApi(url, "test-token"), url)
        previousServices = singleton.get(null)
        singleton.set(null, services)
        singletonReplaced = true
        model = NotesViewModel(application)
        models.put("notes", model)
        await { !model.state.value.loading && model.state.value.items.size == 1 }
    }

    @After fun stop() {
        releaseMetadata?.countDown()
        try {
            models.clear()
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            if (singletonReplaced) singleton.set(null, previousServices)
            server.shutdown()
            if (::directory.isInitialized) directory.deleteRecursively()
        }
    }

    @Test fun cachedEmptyBodyIsKnownImmediatelyBeforeFolderHttpCompletes() {
        assertCachedPresenceBeforeHttp("<p><br></p>", false)
    }

    @Test fun cachedBodyIsKnownImmediatelyBeforeFolderHttpCompletes() {
        assertCachedPresenceBeforeHttp("<p>Saved body</p>", true)
    }

    @Test fun repeatedFolderEntryReusesTheBodyWhenMetadataVersionIsUnchanged() {
        val folder = cacheFolder()
        assertEquals(1, contentRequests.get())
        model.enterFolder(folder)
        awaitFolder()
        assertEquals(true, model.state.value.currentFolderHasContent)
        assertEquals(1, contentRequests.get())

        model.back()
        await { model.state.value.folders.size == 1 && !model.state.value.loading }
        model.enterFolder(folder)
        awaitFolder()

        assertEquals(true, model.state.value.currentFolderHasContent)
        assertEquals(1, contentRequests.get())
    }

    @Test fun changedMetadataVersionFetchesTheNewBodyAndUpdatesItsPresence() {
        val folder = cacheFolder()
        folderVersion = "versionB"
        folderBody = "<p><br></p>"

        model.enterFolder(folder)
        awaitFolder()

        assertEquals(2, contentRequests.get())
        assertEquals(false, model.state.value.currentFolderHasContent)
        assertEquals(folderBody, services.repository.cachedNote("folder")?.html)
        assertEquals("versionB", services.repository.cachedMeta("folder")?.blobId)
    }

    @Test fun failedMetadataOrBodyRefreshPreservesKnownCachedBodyPresence() {
        val folder = cacheFolder()
        metadataUnavailable = true
        model.enterFolder(folder)
        awaitFolder()
        assertEquals(true, model.state.value.currentFolderHasContent)
        assertEquals("<p>Saved body</p>", services.repository.cachedNote("folder")?.html)

        metadataUnavailable = false
        folderVersion = "versionB"
        contentUnavailable = true
        model.refreshFolder()
        awaitFolder()

        assertEquals(2, contentRequests.get())
        assertEquals(true, model.state.value.currentFolderHasContent)
        assertEquals("<p>Saved body</p>", services.repository.cachedNote("folder")?.html)
        assertEquals("versionA", services.repository.cachedMeta("folder")?.blobId)
    }

    private fun assertCachedPresenceBeforeHttp(body: String, expected: Boolean) {
        folderBody = body
        val folder = cacheFolder()
        metadataEntered = CountDownLatch(1)
        releaseMetadata = CountDownLatch(1)

        model.enterFolder(folder)

        assertEquals(expected, model.state.value.currentFolderHasContent)
        await { metadataEntered!!.count == 0L }
        assertEquals(expected, model.state.value.currentFolderHasContent)
        assertEquals(1, contentRequests.get())
        releaseMetadata!!.countDown()
        awaitFolder()
        assertEquals(expected, model.state.value.currentFolderHasContent)
    }

    private fun cacheFolder(): NoteMeta = runBlocking {
        val result = services.repository.listChildrenResult("folder")
        assertNotNull(services.repository.prefetch(result.parent))
        result.parent
    }

    private fun awaitFolder() = await {
        model.state.value.folders.last().id == "folder" &&
            (folderJob.get(model) as? Job)?.isActive != true && !model.state.value.loading
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("ViewModel did not reach the expected state", condition())
    }
}
