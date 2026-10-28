package ru.vlad.triliumnotes.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.data.NoteMeta
import java.io.File

/** Exercises actual keyboard insets and scrollable, nonprivate destination data. */
class PickerImeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun destinationDialogKeepsCancelVisibleAndSelectsScrolledIdentity() = checkPicker(link = false)
    @Test fun noteLinkPickerKeepsCancelVisibleAndSelectsScrolledIdentity() = checkPicker(link = true)

    private fun checkPicker(link: Boolean) {
        val prefix = if (link) "note-link" else "destination"
        val notes = (0 until 30).map { index -> NoteMeta("ime-note-$index",
            "Работа / Команда / Проект $index / Материалы и заметки для обсуждения", "text", "text/html", false, null, null, emptyList()) }
        val picked = mutableListOf<NoteMeta>()
        var visible by mutableStateOf(true)
        var dismissals = 0
        compose.setContent { NotesTheme {
            if (visible) {
                val onPick: (NoteMeta) -> Unit = { picked += it; visible = false }
                val onDismiss = { dismissals++; visible = false }
                if (link) NoteLinkPicker(notes, onPick, onDismiss)
                else DestinationDialog("Выберите папку", notes.map { NoteDestination(it, "Заметки / Личное / Поездки / Проекты и материалы") }, onPick, onDismiss)
            }
        } }
        compose.onNodeWithTag("$prefix-query").performClick().performTextReplacement("Проект")
        compose.assertPickerCancelAboveIme("$prefix-cancel")
        saveReview(prefix, "before-scroll")
        compose.onNodeWithTag("$prefix-cancel").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, dismissals); assertTrue(picked.isEmpty()); visible = true }
        compose.onNodeWithTag("$prefix-query").performClick().performTextReplacement("Проект")
        val targetTag = "$prefix-${notes[27].id}"
        compose.onNodeWithTag(targetTag).assertDoesNotExist()
        compose.assertPickerCancelAboveIme("$prefix-cancel")
        compose.onNodeWithTag("$prefix-list").performScrollToNode(hasTestTag(targetTag))
        compose.assertPickerCancelAboveIme("$prefix-cancel")
        saveReview(prefix, "after-scroll")
        compose.runOnIdle { assertTrue(picked.isEmpty()) }
        compose.onNodeWithTag(targetTag).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(notes[27]), picked) }
    }

    private fun saveReview(prefix: String, state: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "picker-ime-review").apply { mkdirs() }
        val geometry = buildString {
            for (tag in listOf("$prefix-query", "$prefix-list", "$prefix-cancel")) {
                val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
                appendLine("$tag size=${node.size} boundsInWindow=${node.boundsInWindow} boundsInRoot=${node.boundsInRoot}")
            }
            compose.runOnIdle {
                WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() }.forEach { window ->
                    appendLine("window=${window.javaClass.name} size=${window.width}x${window.height} imeVisible=${window.rootWindowInsets?.isVisible(WindowInsets.Type.ime())} ime=${window.rootWindowInsets?.getInsets(WindowInsets.Type.ime())}")
                }
            }
        }
        File(output, "$prefix-$state.txt").writeText(geometry)
        requireNotNull(instrumentation.uiAutomation.takeScreenshot()).also { bitmap ->
            try { File(output, "$prefix-$state.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
    }
}

/** Waits for the focused dialog's real IME insets to settle, then checks the entire Cancel button. */
internal fun ComposeContentTestRule.assertPickerCancelAboveIme(cancelTag: String) {
    var lastInsets: Pair<Int, Int>? = null
    var lastChange = SystemClock.uptimeMillis()
    var geometry = "No focused dialog window with IME"
    try {
        waitUntil(10_000) {
            var ready = false
            val cancel = onNodeWithTag(cancelTag).fetchSemanticsNode()
            runOnIdle {
                val window = WindowInspector.getGlobalWindowViews().lastOrNull {
                    it.hasWindowFocus() && it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                }
                val bottom = window?.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
                val current = bottom to (window?.height ?: 0)
                if (current != lastInsets) { lastInsets = current; lastChange = SystemClock.uptimeMillis() }
                val bounds = cancel.boundsInWindow
                geometry = "tag=$cancelTag size=${cancel.size} boundsInWindow=$bounds boundsInRoot=${cancel.boundsInRoot} windowHeight=${current.second} imeBottom=$bottom focusedWindow=${window?.javaClass?.name}"
                ready = bottom > 0 && SystemClock.uptimeMillis() - lastChange >= 200 &&
                    bounds.top >= 0f && bounds.bottom <= current.second - bottom &&
                    bounds.height >= cancel.size.height - 1f && bounds.width >= cancel.size.width - 1f
            }
            ready
        }
    } catch (failure: Throwable) {
        runCatching {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val output = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "picker-ime-diagnostics").apply { mkdirs() }
            File(output, "$cancelTag.txt").writeText(geometry)
            requireNotNull(instrumentation.uiAutomation.takeScreenshot()).also { bitmap ->
                try { File(output, "$cancelTag.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
        }.exceptionOrNull()?.let { failure.addSuppressed(it) }
        throw AssertionError(geometry, failure)
    }
}
