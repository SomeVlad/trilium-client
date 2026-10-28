package ru.vlad.triliumnotes

import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runners.model.MultipleFailureException
import ru.vlad.triliumnotes.capture.CaptureInbox
import ru.vlad.triliumnotes.capture.QuickNoteWidget
import ru.vlad.triliumnotes.data.NotesServices
import ru.vlad.triliumnotes.data.NotesSyncScheduler
import ru.vlad.triliumnotes.data.TriliumApi
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Actual single-top Activity routing; all service responses and created files are synthetic. */
class MainActivityIntentRoutingTest {
    // MainActivity is the only Activity owner; a second Compose host would share its task.
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext
    private val singleton = NotesServices::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previous: Any? = null
    private var installed = false
    private lateinit var directory: File
    private lateinit var services: NotesServices
    private val session = "routing-${UUID.randomUUID()}"
    private var scenario: ActivityScenario<MainActivity>? = null
    private var scenarioLaunchIntent: Intent? = null
    private var captureId: String? = null

    @Before fun syntheticServices() {
        previous = singleton.get(null)
        assertTrue("Routing acceptance requires an empty capture inbox", CaptureInbox(app).entries.value.isEmpty())
        directory = File(app.cacheDir, session).apply { mkdirs() }
        val isolated = object : ContextWrapper(app) {
            override fun getFilesDir(): File = directory
            override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val body = when (path) {
                "/etapi/notes/root" -> """{"noteId":"root","title":"Routing fixture","type":"text","mime":"text/html","blobId":"routing-v1","childBranchIds":[]}"""
                "/etapi/notes/root/content" -> "<p></p>"
                "/etapi/notes/root/attachments" -> "[]"
                else -> "{}"
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (path.startsWith("/etapi/notes/root")) 200 else 503).message("Synthetic routing response")
                .body(body.toResponseBody()).build()
        }.build()
        services = NotesServices(isolated, TriliumApi("https://routing.invalid", "synthetic-token", client), "https://routing.invalid", session)
        singleton.set(null, services)
        installed = true
        scenario = ActivityScenario.launch(MainActivity::class.java)
        requireNotNull(scenario).onActivity { scenarioLaunchIntent = Intent(it.intent) }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("connection-settings").fetchSemanticsNodes().any { isEnabled().matches(it) } }
    }

    @After fun cleanup() {
        val failures = mutableListOf<Throwable>()
        fun attempt(block: () -> Unit) {
            try { block() } catch (failure: Throwable) { failures += failure }
        }
        try {
            // MainActivity.setIntent changes its filter identity after a real single-top delivery.
            // ActivityScenario then ignores lifecycle events unless its launch identity is restored.
            attempt { scenario?.onActivity { activity -> scenarioLaunchIntent?.let { activity.intent = Intent(it) } } }
            attempt { scenario?.close() }
        } finally {
            attempt { captureId?.let { CaptureInbox(app).discard(it) } }
            attempt {
                WorkManager.getInstance(app).cancelAllWorkByTag(NotesSyncScheduler.SESSION_TAG_PREFIX + session)
                    .result.get(10, TimeUnit.SECONDS)
            }
            attempt { if (::services.isInitialized) services.api.close() }
            attempt { if (installed) singleton.set(null, previous) }
            attempt { if (::directory.isInitialized) directory.deleteRecursively() }
        }
        MultipleFailureException.assertEmpty(failures)
    }

    @Test fun widgetLeavesRestoredSettingsAndCreatesWithoutRequiringBack() {
        compose.onNodeWithTag("connection-settings").performClick()
        requireNotNull(scenario).recreate()
        compose.onNodeWithTag("connection-disconnect").assertIsDisplayed()
        deliver(Intent.ACTION_MAIN)
        compose.onNodeWithTag("connection-disconnect").assertIsDisplayed()
        deliver(QuickNoteWidget.ACTION_QUICK_NOTE)
        compose.waitUntil(15_000) {
            var created = false
            requireNotNull(scenario).onActivity { activity -> created = notes(activity)?.state?.value?.newNoteId != null }
            created
        }
        compose.onNodeWithTag("connection-disconnect").assertDoesNotExist()
    }

    @Test fun newlyDeliveredShareLeavesSettingsButOrdinaryReturnWithOldCaptureKeepsThem() {
        compose.onNodeWithTag("connection-settings").performClick()
        captureId = UUID.randomUUID().toString()
        CaptureInbox(app).receive(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Synthetic routed share"), requireNotNull(captureId))
        // CaptureShareActivity sends this plain single-top launch after its durable inbox write.
        deliver(null)
        compose.onNodeWithTag("capture_sheet").assertIsDisplayed()
        compose.onNodeWithTag("connection-disconnect").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, services.repository.syncState.value.pendingCount) }
        compose.onNodeWithText(AppLanguage.get(app, R.string.capture_later)).performClick()
        compose.onNodeWithTag("connection-settings").performClick()
        deliver(Intent.ACTION_MAIN)
        compose.onNodeWithTag("connection-disconnect").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, services.repository.syncState.value.pendingCount) }
    }

    private fun deliver(action: String?) {
        requireNotNull(scenario).onActivity { activity ->
            activity.startActivity(Intent(activity, MainActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        instrumentation.waitForIdleSync()
        compose.waitForIdle()
    }

    private fun notes(activity: MainActivity): NotesViewModel? = MainActivity::class.java.getDeclaredField("model")
        .apply { isAccessible = true }.get(activity) as? NotesViewModel
}
