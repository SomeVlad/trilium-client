package ru.vlad.triliumnotes.ui

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.NotesScreenState
import ru.vlad.triliumnotes.R
import ru.vlad.triliumnotes.data.NoteDocument
import ru.vlad.triliumnotes.data.NoteMeta
import ru.vlad.triliumnotes.data.SyncState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The production handoff must finish the real editor before an incoming action replaces it. */
class EditorHandoffAcceptanceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val document = NoteDocument(NoteMeta("handoff060", "Synthetic draft", "text", "text/html",
        false, null, null, emptyList()), "<p>Original draft</p>")

    @Before fun language() { AppLanguage.select(context, "ru") }

    @Test fun incomingShareSeesLatestHtmlBeforeReplacingEditor() = assertHandoff("Shared capture")
    @Test fun quickWidgetRequestSeesLatestHtmlBeforeCreatingAnotherNote() = assertHandoff("Quick note")

    private fun assertHandoff(request: String) {
        val handoff = EditorHandoff()
        val latest = AtomicReference<String>()
        var shown by mutableStateOf(true)
        var bodyBeforeNavigation: String? = null
        var delivered = 0
        compose.setContent { NotesTheme {
            if (shown) EditorScreen(NotesScreenState(document = document, loading = false), document, SyncState(),
                onBack = {}, onTitleChanged = {}, onHtmlChanged = { latest.set(it) }, onSync = {},
                onKeepConflictAsCopy = {}, editorHandoff = handoff)
        } }
        compose.waitUntil(30_000) { js("Boolean(window.pocket?.ready)") == "true" }
        compose.onNodeWithContentDescription(AppLanguage.get(context, R.string.edit_note)).performClick()
        compose.waitUntil(30_000) { js("Boolean(window.pocket?.editor && !window.pocket.editor.isReadOnly)") == "true" }
        js("(()=>{const e=window.pocket.editor;e.model.change(w=>w.insertText(' $request',w.createPositionAt(e.model.document.getRoot().getChild(0),'end')));return true})()")
        compose.runOnIdle { handoff.run {
            bodyBeforeNavigation = latest.get()
            delivered++
            shown = false
        } }
        compose.waitUntil(10_000) { delivered == 1 }
        assertTrue("The original draft must be delivered before navigation", bodyBeforeNavigation?.contains("Original draft") == true)
        assertTrue("The latest edit must be delivered before navigation", bodyBeforeNavigation?.contains(request) == true)
        compose.runOnIdle { assertNull("Unmounted editor must release its handoff", handoff.finish) }
    }

    @Test fun disposedEditorCannotInterceptAnotherIncomingAction() {
        val handoff = EditorHandoff()
        var shown by mutableStateOf(true)
        var delivered = 0
        compose.setContent { NotesTheme {
            if (shown) EditorScreen(NotesScreenState(document = document, loading = false), document, SyncState(),
                {}, {}, {}, {}, {}, editorHandoff = handoff)
        } }
        compose.runOnIdle { assertNotNull(handoff.finish); shown = false }
        compose.runOnIdle { assertNull(handoff.finish); handoff.run { delivered++ } }
        assertEquals(1, delivered)
    }

    private fun js(expression: String): String {
        var result = ""
        val done = CountDownLatch(1)
        compose.runOnIdle {
            fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            requireNotNull(WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find))
                .evaluateJavascript(expression) { result = it; done.countDown() }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return result
    }
}
