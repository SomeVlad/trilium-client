package ru.vlad.triliumnotes.capture

import android.graphics.Bitmap
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import ru.vlad.triliumnotes.ui.NotesTheme
import ru.vlad.triliumnotes.ui.assertPickerCancelAboveIme
import java.io.File

/** The selector only emits explicit user decisions; no account or network services are created. */
class CaptureSheetTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = AppLanguage.get(context, id)
    private val fixture = PendingCapture("fixture", "Shared idea", "https://example.org/", emptyList())
    @Test fun newNoteSaveIncludesTitleAndChosenFolderAndRequiresTap() {
        val decisions = mutableListOf<Triple<String, String?, String?>>()
        compose.setContent { NotesTheme {
            CaptureSheet(fixture, "https://synthetic.invalid", listOf(CaptureDestination("folder01", "Research", true)),
                busy = false, error = null, onDismiss = {}, onSave = { title, parent, existing -> decisions += Triple(title, parent, existing) })
        } }
        compose.onNodeWithText("https://example.org/").assertIsDisplayed()
        // No sheet expansion gesture is needed to reach the explicit delivery action.
        compose.onNodeWithTag("capture_save").assertIsDisplayed()
        compose.onNodeWithTag("capture_note_title").assertIsDisplayed()
        compose.runOnIdle { assertTrue(decisions.isEmpty()) }
        compose.onNodeWithTag("capture_note_title").performClick()
        compose.onNodeWithTag("capture_note_title").performTextReplacement("Saved idea")
        compose.onNodeWithTag("capture_save").assertIsDisplayed()
        compose.onNodeWithTag("capture_save").performClick()
        compose.runOnIdle { assertEquals(listOf(Triple("Saved idea", "folder01", null)), decisions) }
    }
    @Test fun selectingExistingNoteCannotWriteBeforeDestinationSelection() {
        val decisions = mutableListOf<Triple<String, String?, String?>>()
        compose.setContent { NotesTheme {
            CaptureSheet(fixture, "https://synthetic.invalid", listOf(CaptureDestination("note0001", "Existing target", false)),
                busy = false, error = null, onDismiss = {}, onSave = { title, parent, existing -> decisions += Triple(title, parent, existing) })
        } }
        compose.onNodeWithText(label(R.string.capture_existing_note)).performClick()
        compose.onNodeWithTag("capture_save").assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.capture_select_note)).performClick()
        compose.onNodeWithText("Existing target").performClick()
        compose.onNodeWithTag("capture_save").performClick()
        compose.runOnIdle { assertEquals("note0001", decisions.single().third); assertNull(decisions.single().second) }
    }
    @Test fun destinationSearchPreservesExactExistingIdAndCancellationDoesNotSave() {
        val decisions = mutableListOf<Triple<String, String?, String?>>()
        val choices = (0 until 40).map { index ->
            CaptureDestination("note-$index", "Library / Travel $index / Reading", false)
        }
        val selected = choices[37]
        compose.setContent { NotesTheme {
            CaptureSheet(fixture, "https://synthetic.invalid", choices, busy = false, error = null,
                onDismiss = {}, onSave = { title, parent, existing -> decisions += Triple(title, parent, existing) })
        } }
        compose.onNodeWithText(label(R.string.capture_existing_note)).performClick()
        compose.onNodeWithTag("capture_save").assertIsNotEnabled()
        compose.onNodeWithTag("capture-destination").performClick()
        compose.onNodeWithTag("capture-target-picker").assertIsDisplayed()
        compose.onNodeWithTag("capture-target-query").performTextReplacement("travel 37 / reading")
        compose.onNodeWithTag("capture-target-${selected.id}").assertIsDisplayed().performClick()
        compose.onNodeWithTag("capture-target-picker").assertDoesNotExist()
        compose.onNodeWithText(selected.title).assertIsDisplayed()
        compose.onNodeWithTag("capture_save").assertIsEnabled()
        compose.runOnIdle { assertTrue(decisions.isEmpty()) }
        compose.onNodeWithTag("capture-destination").performClick()
        compose.onNodeWithTag("capture-target-query").performTextReplacement("no matching destination")
        compose.onNodeWithText(label(R.string.nothing_found)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        compose.onNodeWithTag("capture-target-picker").assertDoesNotExist()
        compose.onNodeWithText(selected.title).assertIsDisplayed()
        compose.runOnIdle { assertTrue(decisions.isEmpty()) }
        compose.onNodeWithTag("capture_save").performClick()
        compose.runOnIdle { assertEquals(listOf(Triple(fixture.title, null, selected.id)), decisions) }
    }

    @Test fun destinationPickerKeepsCancelAboveRealKeyboardAndScrollsLongResults() {
        val decisions = mutableListOf<Triple<String, String?, String?>>()
        val choices = (0 until 30).map { index ->
            CaptureDestination("long-note-$index", "Работа / Команда / Проект $index / Материалы и заметки для обсуждения", false)
        }
        val selected = choices[27]
        compose.setContent { NotesTheme {
            CaptureSheet(fixture, "https://notes.example.org", choices, busy = false, error = null,
                onDismiss = {}, onSave = { title, parent, existing -> decisions += Triple(title, parent, existing) })
        } }
        try {
            compose.onNodeWithText(label(R.string.capture_existing_note)).performClick()
            compose.onNodeWithTag("capture-destination").performClick()
            compose.onNodeWithTag("capture-target-query").performClick().performTextReplacement("Проект")
            compose.assertPickerCancelAboveIme("capture-target-cancel")
            compose.onNodeWithTag("capture-target-cancel").assertIsDisplayed().performClick()
            compose.onNodeWithTag("capture-target-picker").assertDoesNotExist()
            compose.onNodeWithTag("capture_save").assertIsNotEnabled()
            compose.runOnIdle { assertTrue(decisions.isEmpty()) }
            compose.onNodeWithTag("capture-destination").performClick()
            compose.onNodeWithTag("capture-target-query").performClick().performTextReplacement("Проект")
            compose.onNodeWithTag("capture-target-${selected.id}").assertDoesNotExist()
            compose.onNodeWithTag("capture-target-list").performScrollToNode(hasTestTag("capture-target-${selected.id}"))
            compose.onNodeWithTag("capture-target-${selected.id}").assertIsDisplayed().performClick()
            compose.onNodeWithText(selected.title).assertIsDisplayed()
            compose.onNodeWithTag("capture_save").assertIsEnabled()
            compose.runOnIdle { assertTrue(decisions.isEmpty()) }
            compose.onNodeWithTag("capture_save").performClick()
            compose.runOnIdle { assertEquals(listOf(Triple(fixture.title, null, selected.id)), decisions) }
        } catch (failure: Throwable) {
            runCatching { saveImeDiagnostics("target-picker-ime") }.exceptionOrNull()?.let { failure.addSuppressed(it) }
            throw failure
        }
    }

    @Test fun shortUrlTitleRemainsFullyVisibleAboveSaveWithRealKeyboard() {
        assertTitleVisibleWithIme(fixture.copy(title = "Идея прогулки", text = "https://example.org/weekend"), "short-url")
    }

    @Test fun longContextTitleRemainsFullyVisibleAboveSaveWithRealKeyboard() {
        assertTitleVisibleWithIme(fixture.copy(title = "Идея прогулки", text = "Shared context\n".repeat(20)), "long-context")
    }

    private fun assertTitleVisibleWithIme(capture: PendingCapture, caseName: String) {
        compose.setContent { NotesTheme {
            CaptureSheet(capture, "https://notes.example.org",
                listOf(CaptureDestination("folder01", "Планы на выходные", true)), busy = false, error = null,
                onDismiss = {}, onSave = { _, _, _ -> }, onDiscard = {})
        } }
        try {
            // Repeat the reviewed path, including focus clearing when the destination picker opens.
            compose.onNodeWithTag("capture-destination").performClick()
            compose.onNodeWithText(label(R.string.cancel)).performClick()
            compose.onNodeWithTag("capture_note_title").performClick()
            compose.waitUntil(10_000) {
                var visible = false
                compose.runOnIdle {
                    visible = WindowInspector.getGlobalWindowViews().any {
                        it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                    }
                }
                visible
            }
            compose.waitUntil(5_000) {
                val title = compose.onNodeWithTag("capture_note_title").fetchSemanticsNode()
                val save = compose.onNodeWithTag("capture_save").fetchSemanticsNode()
                val bounds = title.boundsInRoot
                title.config.getOrNull(SemanticsProperties.Focused) == true &&
                    bounds.top >= 0f && bounds.bottom <= save.boundsInRoot.top &&
                    bounds.height >= title.size.height - 1f && bounds.width >= title.size.width - 1f
            }
            compose.onNodeWithTag("capture_note_title").assertIsDisplayed().assertIsFocused()
            compose.onNodeWithTag("capture_save").assertIsDisplayed()
        } catch (failure: Throwable) {
            runCatching { saveImeDiagnostics(caseName) }.exceptionOrNull()?.let { failure.addSuppressed(it) }
            throw failure
        }
    }

    private fun saveImeDiagnostics(caseName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "capture-ime-diagnostics").apply { mkdirs() }
        val geometry = buildString {
            appendLine("case=$caseName density=${context.resources.displayMetrics.density} fontScale=${context.resources.configuration.fontScale}")
            for (tag in listOf("capture_sheet", "capture_note_title", "capture_save", "capture-destination",
                "capture-target-picker", "capture-target-query", "capture-target-list", "capture-target-cancel")) {
                appendLine(runCatching {
                    val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
                    "$tag: size=${node.size}, boundsInRoot=${node.boundsInRoot}, boundsInWindow=${node.boundsInWindow}, focused=${node.config.getOrNull(SemanticsProperties.Focused)}"
                }.getOrElse { "$tag: $it" })
            }
            compose.runOnIdle {
                WindowInspector.getGlobalWindowViews().forEachIndexed { index, view ->
                    val location = IntArray(2).also { view.getLocationOnScreen(it) }
                    val insets = view.rootWindowInsets
                    appendLine("window[$index]: ${view.javaClass.name} size=${view.width}x${view.height}, screen=${location.contentToString()}, focused=${view.findFocus()?.javaClass?.name}, imeVisible=${insets?.isVisible(WindowInsets.Type.ime())}, ime=${insets?.getInsets(WindowInsets.Type.ime())}, systemBars=${insets?.getInsets(WindowInsets.Type.systemBars())}")
                }
            }
            appendLine(compose.onNodeWithTag("capture_sheet").printToString(maxDepth = 30))
            appendLine(runCatching { compose.onNodeWithTag("capture-target-picker").printToString(maxDepth = 30) }.getOrDefault("Picker is closed"))
        }
        File(output, "$caseName.txt").writeText(geometry)
        requireNotNull(instrumentation.uiAutomation.takeScreenshot()).also { bitmap ->
            try { File(output, "$caseName.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }
    @Test fun boundCaptureCannotBeSavedToAnotherAccount() {
        var saves = 0
        val bound = fixture.copy(accountId = "first-account", targetNoteId = "note0001", targetTitle = "Chosen target")
        compose.setContent { NotesTheme {
            CaptureSheet(bound, "https://different-synthetic.invalid", listOf(CaptureDestination("note0001", "Chosen target", false)),
                busy = false, error = null, onDismiss = {}, onSave = { _, _, _ -> saves++ }, accountMatches = false)
        } }
        compose.onNodeWithTag("capture_save").assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.capture_account_changed)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, saves) }
    }
    @Test fun laterLeavesInboxDecisionUncommitted() {
        var dismissed = false
        var saves = 0
        compose.setContent { NotesTheme {
            CaptureSheet(fixture, "https://synthetic.invalid", emptyList(), false, null,
                onDismiss = { dismissed = true }, onSave = { _, _, _ -> saves++ })
        } }
        compose.onNodeWithText(label(R.string.capture_later)).performClick()
        compose.runOnIdle { assertTrue(dismissed); assertEquals(0, saves) }
    }
}
