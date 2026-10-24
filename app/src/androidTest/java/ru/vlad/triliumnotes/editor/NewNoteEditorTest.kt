package ru.vlad.triliumnotes.editor

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.NotesScreenState
import ru.vlad.triliumnotes.data.NoteDocument
import ru.vlad.triliumnotes.data.NoteMeta
import ru.vlad.triliumnotes.data.SyncState
import ru.vlad.triliumnotes.ui.EditorScreen
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** New-note entry uses real Android input; opening/recreating a WebView never writes a title. */
class NewNoteEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun coldNewNoteFocusesEmptyTitleShowsKeyboardAndEnterContinuesInBody() {
        val title = AtomicReference<String>()
        val body = AtomicReference<String>()
        compose.setContent {
            MaterialTheme {
                EditorScreen(NotesScreenState(newNoteId = "new", newNoteTitleUntouched = true), document(""), SyncState(),
                    onBack = {}, onTitleChanged = { title.set(it) }, onHtmlChanged = { body.set(it) },
                    onSync = {}, onKeepConflictAsCopy = {})
            }
        }
        ready()
        compose.waitUntil(15_000) { js("Boolean(window.pocketOptions.keyboardVisible)") == "true" }
        assertEquals("\"title\"", js("document.activeElement.id"))
        assertEquals("\"\"", js("document.getElementById('title').value"))
        assertNull(title.get())
        assertNull(body.get())

        val input = InstrumentationRegistry.getInstrumentation()
        input.sendStringSync("Weekend")
        compose.waitUntil(5_000) { title.get() == "Weekend" }
        input.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(5_000) { js("document.activeElement.classList.contains('ck-content')") == "true" }
        input.sendStringSync("First line")
        compose.waitUntil(5_000) { body.get()?.contains("First line") == true }
        assertEquals("Weekend", title.get())
        assertEquals("\"Weekend\"", js("document.getElementById('title').value"))
    }

    @Test fun syncFallbackAndWebViewRecreationKeepOnlyTheUntouchedNewTitleEmpty() {
        var generation by mutableStateOf(0)
        var current by mutableStateOf(document(""))
        var untouched by mutableStateOf(true)
        val titles = AtomicInteger()
        compose.setContent {
            MaterialTheme { key(generation) {
                EditorScreen(NotesScreenState(newNoteId = "new", newNoteTitleUntouched = untouched), current, SyncState(),
                    onBack = {}, onTitleChanged = {
                        titles.incrementAndGet(); untouched = false; current = current.copy(note = current.note.copy(title = it))
                    }, onHtmlChanged = {}, onSync = {}, onKeepConflictAsCopy = {})
            } }
        }
        ready()
        compose.runOnIdle {
            current = current.copy(note = current.note.copy(title = "Без названия"), pending = false)
            generation++
        }
        ready()
        assertEquals("\"\"", js("document.getElementById('title').value"))
        assertEquals(0, titles.get())
        InstrumentationRegistry.getInstrumentation().sendStringSync("My title")
        compose.waitUntil(5_000) { current.note.title == "My title" }
        val writes = titles.get()
        compose.runOnIdle { generation++ }
        ready()
        assertEquals("\"My title\"", js("document.getElementById('title').value"))
        assertEquals(writes, titles.get())
        assertEquals("true", js("document.activeElement.classList.contains('ck-content')"))
    }

    @Test fun existingTitlesArePreservedWhenEnteringEditModeAndRecreatingTheEditor() {
        var generation by mutableStateOf(0)
        var title by mutableStateOf("Без названия")
        val writes = AtomicInteger()
        compose.setContent {
            MaterialTheme { key(generation) {
                EditorScreen(NotesScreenState(), document(title), SyncState(), onBack = {},
                    onTitleChanged = { writes.incrementAndGet() }, onHtmlChanged = {}, onSync = {}, onKeepConflictAsCopy = {})
            } }
        }
        ready()
        assertEquals("\"Без названия\"", js("document.getElementById('title').value"))
        compose.onNodeWithContentDescription("Редактировать заметку").performClick()
        compose.waitUntil(30_000) {
            js("Boolean(window.pocket.editor && !window.pocket.editor.isReadOnly && document.activeElement.classList.contains('ck-content'))") == "true"
        }
        assertEquals("true", js("document.activeElement.classList.contains('ck-content')"))
        assertEquals(0, writes.get())

        compose.runOnIdle { title = "Новая заметка"; generation++ }
        ready()
        assertEquals("\"Новая заметка\"", js("document.getElementById('title').value"))
        assertEquals(0, writes.get())
    }

    private fun document(title: String) = NoteDocument(
        NoteMeta("new", title, "text", "text/html", false, null, null, emptyList()), "<p></p>", pending = true)

    private fun ready() {
        compose.waitForIdle()
        compose.waitUntil(30_000) { js("Boolean(window.pocket?.ready)") == "true" }
        compose.waitUntil(30_000) { js("Boolean(!window.pocketOptions.editing || window.pocket.editor)") == "true" }
        // Wait for the bridge's ready→DOM-focus handshake as well as CKEditor initialization.
        compose.waitUntil(5_000) {
            js("Boolean(!window.pocketOptions.editing || document.activeElement === document.getElementById('title') || document.activeElement.classList.contains('ck-content'))") == "true"
        }
    }

    private fun js(expression: String): String {
        val latch = CountDownLatch(1)
        val value = AtomicReference<String>()
        compose.runOnIdle {
            fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            val webView = requireNotNull(find(activity.window.decorView))
            webView.evaluateJavascript(expression) { value.set(it); latch.countDown() }
        }
        assertTrue("WebView JavaScript callback timed out", latch.await(10, TimeUnit.SECONDS))
        return value.get()
    }
}
