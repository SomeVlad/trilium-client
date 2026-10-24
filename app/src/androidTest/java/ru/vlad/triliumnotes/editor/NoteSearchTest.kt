package ru.vlad.triliumnotes.editor

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import ru.vlad.triliumnotes.NotesScreenState
import ru.vlad.triliumnotes.data.NoteDocument
import ru.vlad.triliumnotes.data.NoteMeta
import ru.vlad.triliumnotes.data.SyncState
import ru.vlad.triliumnotes.ui.EditorScreen

/** Offline search exercises rendered documents and Compose text without touching saved source. */
class NoteSearchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editorMenuSearchFlushesEditingAndKeepsTheSameWebView() {
        val document = NoteDocument(NoteMeta("search-note", "Title", "text", "text/html", false,
            null, null, emptyList()), "<p>Original</p>")
        val writes = AtomicInteger()
        var editing = false
        compose.setContent { MaterialTheme {
            EditorScreen(NotesScreenState(document = document), document, SyncState(), onBack = {},
                onTitleChanged = {}, onHtmlChanged = { writes.incrementAndGet() }, onSync = {},
                onKeepConflictAsCopy = {}, onEditingChanged = { editing = it })
        } }
        compose.waitUntil(30_000) { js("Boolean(window.pocket?.ready)") == "true" }
        compose.onNodeWithContentDescription("Редактировать заметку").performClick()
        compose.waitUntil(30_000) { js("Boolean(window.pocket?.editor)") == "true" }
        js("window.searchKept=42;window.pocket.editor.setData('<p>Latest latest</p>')")
        compose.waitUntil(5_000) { writes.get() > 0 }
        compose.onNodeWithTag("note-actions-search-note").performClick()
        compose.onNodeWithTag("note-find").performClick()
        compose.onNodeWithTag("note-search-query").assertIsDisplayed().performTextInput("latest")
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithText("1/2").fetchSemanticsNode(); true }.getOrDefault(false)
        }
        val beforeNavigation = writes.get()
        assertFalse(editing)
        assertEquals("42", js("window.searchKept"))
        compose.onNodeWithContentDescription("Следующее совпадение").performClick()
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithText("2/2").fetchSemanticsNode(); true }.getOrDefault(false)
        }
        compose.onNodeWithContentDescription("Закрыть поиск").performClick()
        compose.onNodeWithTag("note-search-query").assertDoesNotExist()
        assertEquals(beforeNavigation, writes.get())
    }

    @Test fun richSearchFindsCurrentUnsavedTextAndNeverSavesItsHighlights() {
        val state = NoteSearchState()
        val controller = RichNoteEditorController()
        val writes = AtomicInteger()
        var editing by mutableStateOf(true)
        var search by mutableStateOf<NoteSearchState?>(null)
        compose.setContent { MaterialTheme {
            RichNoteEditor("current", "<p>Original</p>", isEditing = editing, controller = controller,
                searchState = search, modifier = Modifier.fillMaxSize(), onHtmlChanged = { writes.incrementAndGet() })
        } }
        compose.waitUntil(30_000) { js("Boolean(window.pocket?.editor)") == "true" }
        js("window.pocket.editor.setData('<p>Latest latest</p>')")
        compose.waitUntil(5_000) { writes.get() > 0 }
        val flushed = CountDownLatch(1)
        compose.runOnIdle { controller.flush { editing = false; search = state; flushed.countDown() } }
        assertTrue(flushed.await(10, TimeUnit.SECONDS))
        compose.waitUntil(5_000) { js("window.pocket.editor.isReadOnly") == "true" }
        val beforeSearch = writes.get()
        compose.runOnIdle { state.query = "latest" }
        compose.waitUntil(10_000) { state.matchCount == 2 }
        compose.runOnIdle { state.next() }
        compose.waitUntil(10_000) { state.currentMatch == 1 }
        compose.runOnIdle { state.previous() }
        compose.waitUntil(10_000) { state.currentMatch == 0 }
        compose.runOnIdle { state.query = "Original" }
        compose.waitUntil(10_000) { state.matchCount == 0 }
        compose.runOnIdle { search = null }
        compose.waitForIdle()
        assertEquals(beforeSearch, writes.get())
        assertEquals("<p>Latest latest</p>", JSONTokener(js("JSON.parse(window.pocket.snapshot()).html")).nextValue())
    }

    @Test fun markdownSearchUsesRenderedReadingViewOffline() {
        val state = NoteSearchState()
        val writes = AtomicInteger()
        compose.setContent { MaterialTheme {
            SourceNoteEditor("markdown", "Title", "# Uniqueheading\n\n**Uniqueheading**", mime = "text/markdown",
                isEditing = false, searchState = state, modifier = Modifier.fillMaxSize(),
                onContentChanged = { writes.incrementAndGet() })
        } }
        compose.waitUntil(30_000) { js("Boolean(window.pocket?.ready)") == "true" }
        compose.runOnIdle { state.query = "Uniqueheading" }
        compose.waitUntil(10_000) { state.matchCount == 2 }
        assertEquals("false", js("Boolean(window.pocket.editor)"))
        assertEquals(0, writes.get())
    }

    @Test fun sourceSearchHighlightsAndScrollsToLastMatchWithoutChangingText() {
        val state = NoteSearchState()
        val writes = AtomicInteger()
        val source = (0..150).joinToString("\n") { if (it == 0 || it == 150) "needle $it" else "line $it" }
        compose.setContent { MaterialTheme {
            SourceNoteEditor("source", "Title", source, mime = "application/json", isEditing = false,
                searchState = state, modifier = Modifier.fillMaxSize(), onContentChanged = { writes.incrementAndGet() })
        } }
        compose.runOnIdle { state.query = "needle" }
        compose.waitUntil(5_000) { state.matchCount == 2 }
        compose.runOnIdle { state.previous() }
        compose.waitForIdle()
        val text = compose.onNodeWithTag("source-reader").fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals(source, text.text)
        assertEquals(2, text.spanStyles.size)
        assertTrue(compose.onNodeWithTag("note-source-reader").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        assertEquals(0, writes.get())
    }

    private fun js(expression: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        compose.runOnIdle {
            fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            requireNotNull(find(activity.window.decorView)).evaluateJavascript(expression) { result = it; latch.countDown() }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return result
    }
}
