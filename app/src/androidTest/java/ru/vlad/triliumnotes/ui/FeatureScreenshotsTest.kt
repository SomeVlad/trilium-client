package ru.vlad.triliumnotes.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.view.inspector.WindowInspector
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.NotesScreenState
import ru.vlad.triliumnotes.data.NoteDocument
import ru.vlad.triliumnotes.data.NoteMeta
import ru.vlad.triliumnotes.data.NoteRevision
import ru.vlad.triliumnotes.data.RevisionDocument
import ru.vlad.triliumnotes.data.SyncState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Isolated presentation fixtures: no NotesServices, server, credentials or owner documents. */
class FeatureScreenshotsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureSearchAndHistoryInBothLanguages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var language by mutableStateOf("ru")
        var historyVisible by mutableStateOf(false)
        var historyState by mutableStateOf(NoteHistoryState(loading = false))
        val note = NoteMeta("fixtureA", "", "text", "text/html", false, null, null, emptyList())
        val revision = NoteRevision("revisionA", note.id, "План прогулки / Weekend plan", "text", "text/html",
            "2026-10-24 16:00:00.000Z", null, description = "Сохранённый план / Saved plan")
        val preview = RevisionDocument(revision, "<h2>План прогулки / Weekend plan</h2><p>Сначала парк, затем книжный магазин.</p>")
        AppLanguage.select(context, "ru")
        compose.setContent {
            key(language, historyVisible) {
                CompositionLocalProvider(LocalContext provides AppLanguage.context(context)) {
                    NotesTheme {
                        val title = if (language == "ru") "Идеи на выходные" else "Weekend plans"
                        if (historyVisible) NoteHistorySheet(note.copy(title = title), historyState,
                            onSelect = { historyState = historyState.copy(selected = it, preview = preview) },
                            onRestore = {}, onSaveRevision = {}, onRetry = {},
                            onBack = { historyState = historyState.copy(selected = null, preview = null) }, onDismiss = {})
                        else {
                            val html = if (language == "ru")
                                "<h2>Небольшой план</h2><p>Первая мысль — выбрать новую книгу.</p><p>Вторая мысль — прогуляться в парке.</p>"
                            else "<h2>A small plan</h2><p>The first idea is to choose a new book.</p><p>The second idea is to walk in the park.</p>"
                            val document = NoteDocument(note.copy(title = title), html)
                            EditorScreen(NotesScreenState(document = document, loading = false, networkAvailable = false),
                                document, SyncState(), onBack = {}, onTitleChanged = {}, onHtmlChanged = {}, onSync = {},
                                onKeepConflictAsCopy = {}, onHistory = {})
                        }
                    }
                }
            }
        }
        try {
            for (locale in listOf("ru", "en")) {
                compose.runOnIdle {
                    AppLanguage.select(context, locale)
                    historyVisible = false
                    language = locale
                }
                compose.waitUntil(30_000) { js("Boolean(window.pocket?.ready)") == "true" }
                compose.onNodeWithTag("note-actions-${note.id}").performClick()
                compose.onNodeWithTag("note-find").performClick()
                compose.onNodeWithTag("note-search-query").performTextInput(if (locale == "ru") "мысль" else "idea")
                compose.waitUntil(10_000) {
                    runCatching { compose.onNodeWithText("1/2").fetchSemanticsNode(); true }.getOrDefault(false)
                }
                save("search-$locale.png")
                compose.onNodeWithContentDescription(if (locale == "ru") "Закрыть поиск" else "Close search").performClick()
                compose.runOnIdle {
                    historyState = NoteHistoryState(revisions = listOf(revision), loading = false)
                    historyVisible = true
                }
                compose.onNodeWithTag("history-revisions").assertIsDisplayed()
                save("history-list-$locale.png")
                compose.onNodeWithTag("history-revision-${revision.id}").performClick()
                compose.waitUntil(30_000) { js("Boolean(window.pocket?.ready)") == "true" }
                save("history-preview-$locale.png")
                compose.onNodeWithTag("history-restore").performClick()
                compose.onNodeWithTag("history-restore-confirmation").assertIsDisplayed()
                save("history-confirm-$locale.png")
                compose.onNodeWithText(if (locale == "ru") "Отмена" else "Cancel").performClick()
            }
        } finally { AppLanguage.select(context, "ru") }
    }

    private fun save(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(350)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "feature-review").apply { mkdirs() }
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, name).outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }

    private fun js(expression: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        compose.runOnIdle {
            fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            val roots = if (Build.VERSION.SDK_INT >= 29) WindowInspector.getGlobalWindowViews()
                else ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).map { it.window.decorView }
            requireNotNull(roots.firstNotNullOfOrNull(::find)).evaluateJavascript(expression) { result = it; latch.countDown() }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return result
    }
}
