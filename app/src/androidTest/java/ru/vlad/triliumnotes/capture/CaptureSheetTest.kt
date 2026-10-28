package ru.vlad.triliumnotes.capture

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import ru.vlad.triliumnotes.ui.NotesTheme

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
