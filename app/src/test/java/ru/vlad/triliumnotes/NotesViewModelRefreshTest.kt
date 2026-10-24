package ru.vlad.triliumnotes

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.lifecycle.ViewModelStore
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
import ru.vlad.triliumnotes.data.NotesServices
import ru.vlad.triliumnotes.data.TriliumApi
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class NotesViewModelRefreshTest {
    private val server = MockWebServer()
    private val models = ViewModelStore()
    private val singleton = NotesServices::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previousServices: Any? = null
    private var singletonReplaced = false
    private lateinit var directory: File
    private lateinit var model: NotesViewModel
    @Volatile private var branchAvailable = false
    @Volatile private var refreshEntered: CountDownLatch? = null
    @Volatile private var releaseRefresh: CountDownLatch? = null

    @Before fun start() {
        val application = RuntimeEnvironment.getApplication()
        val manager = application.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = manager.activeNetwork
        assertNotNull(activeNetwork)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(manager).setNetworkCapabilities(activeNetwork, capabilities)
        assertTrue(application.hasValidatedInternet())
        directory = Files.createTempDirectory("notes-refresh-test").toFile()
        val filesContext = object : ContextWrapper(application) {
            override fun getFilesDir(): File = directory
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/etapi/notes/root" -> {
                    refreshEntered?.countDown()
                    releaseRefresh?.await(5, TimeUnit.SECONDS)
                    MockResponse().setBody("""{"noteId":"root","childBranchIds":["branchGood","branchFail"]}""")
                }
                "/etapi/branches/branchGood" -> MockResponse().setBody("""{"branchId":"branchGood","noteId":"noteGood"}""")
                "/etapi/notes/noteGood" -> MockResponse().setBody("""{"noteId":"noteGood","type":"text","mime":"text/html"}""")
                "/etapi/branches/branchFail" -> if (branchAvailable)
                    MockResponse().setBody("""{"branchId":"branchFail","noteId":"noteFail"}""")
                    else MockResponse().setResponseCode(403)
                "/etapi/notes/noteFail" -> MockResponse().setBody("""{"noteId":"noteFail","type":"text","mime":"text/html"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val url = server.url("/").toString()
        previousServices = singleton.get(null)
        singleton.set(null, NotesServices(filesContext, TriliumApi(url, "test-token"), url))
        singletonReplaced = true
        model = NotesViewModel(application)
        models.put("notes", model)
    }

    @After fun stop() {
        releaseRefresh?.countDown()
        try {
            models.clear()
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            if (singletonReplaced) singleton.set(null, previousServices)
            server.shutdown()
            if (::directory.isInitialized) directory.deleteRecursively()
        }
    }

    @Test fun partialFailureBannerRemainsDuringRefreshAndClearsAfterSuccess() {
        await { !model.state.value.loading && model.state.value.error != null }
        val previousError = model.state.value.error
        assertEquals("Не удалось обновить часть списка. Уже загруженные заметки доступны.", previousError)
        assertEquals(1, model.state.value.items.size)
        branchAvailable = true
        refreshEntered = CountDownLatch(1)
        releaseRefresh = CountDownLatch(1)

        model.refreshFolder()
        await { refreshEntered!!.count == 0L }

        assertEquals(previousError, model.state.value.error)
        assertEquals(1, model.state.value.items.size)
        releaseRefresh!!.countDown()
        await { model.state.value.error == null && model.state.value.items.size == 2 }
        assertEquals(false, model.state.value.loading)
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
