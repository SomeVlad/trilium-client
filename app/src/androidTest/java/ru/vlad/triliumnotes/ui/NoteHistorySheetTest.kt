package ru.vlad.triliumnotes.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import ru.vlad.triliumnotes.data.NoteMeta
import ru.vlad.triliumnotes.data.NoteRevision
import ru.vlad.triliumnotes.data.RevisionDocument

class NoteHistorySheetTest {
    @get:Rule val compose = createComposeRule()
    private val note = NoteMeta("noteAAAA", "Current title", "code", "application/javascript",
        false, "blobAAAA", null, emptyList())
    private val revision = NoteRevision("revisionAAAA", note.id, "Past title", "code", note.mime,
        "2026-10-24 16:00:00.000Z", null, description = "Named version")
    private val preview = RevisionDocument(revision, "const answer = 42;")

    @Test fun selectingAListedRevisionOnlyRequestsPreview() {
        var selections = 0
        var saves = 0
        var restores = 0
        compose.setContent {
            NotesTheme {
                NoteHistorySheet(note, NoteHistoryState(revisions = listOf(revision), loading = false),
                    onSelect = { assertEquals(revision, it); selections++ },
                    onRestore = { restores++ }, onSaveRevision = { saves++ }, onRetry = {}, onBack = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("Named version").assertIsDisplayed()
        compose.onNodeWithTag("history-revision-${revision.id}").performClick()
        compose.runOnIdle {
            assertEquals(1, selections)
            assertEquals(0, saves)
            assertEquals(0, restores)
        }
    }

    @Test fun readOnlyPreviewRequiresSeparateRestoreConfirmation() {
        var restores = 0
        compose.setContent {
            NotesTheme {
                NoteHistorySheet(note, NoteHistoryState(loading = false, selected = revision, preview = preview),
                    onSelect = {}, onRestore = { assertEquals(preview, it); restores++ },
                    onSaveRevision = {}, onRetry = {}, onBack = {}, onDismiss = {})
            }
        }
        assertPreviewTextIsDisplayed()
        compose.onNodeWithTag("note-source-editor").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, restores) }
        compose.onNodeWithTag("history-restore").performClick()
        compose.onNodeWithTag("history-restore-confirmation").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, restores) }
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        compose.onNodeWithTag("history-restore-confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, restores) }
        compose.onNodeWithTag("history-restore").performClick()
        compose.onNodeWithTag("history-confirm-restore").performClick()
        compose.runOnIdle { assertEquals(1, restores) }
    }

    @Test fun unavailableConnectionLeavesPreviewReadableAndRestoreDisabled() {
        compose.setContent {
            NotesTheme {
                NoteHistorySheet(note, NoteHistoryState(loading = false, selected = revision,
                    preview = preview, allowRestore = false), onSelect = {}, onRestore = {},
                    onSaveRevision = {}, onRetry = {}, onBack = {}, onDismiss = {})
            }
        }
        assertPreviewTextIsDisplayed()
        compose.onNodeWithTag("history-restore").assertIsNotEnabled()
        compose.onNodeWithTag("history-restore-confirmation").assertDoesNotExist()
    }

    @Test fun incompatibleRevisionTypeCannotBeRestored() {
        compose.setContent {
            NotesTheme {
                NoteHistorySheet(note.copy(type = "text", mime = "text/html"),
                    NoteHistoryState(loading = false, selected = revision, preview = preview),
                    onSelect = {}, onRestore = {}, onSaveRevision = {}, onRetry = {}, onBack = {}, onDismiss = {})
            }
        }
        compose.onNodeWithTag("history-restore").assertIsNotEnabled()
    }

    private fun label(id: Int): String =
        AppLanguage.get(InstrumentationRegistry.getInstrumentation().targetContext, id)

    private fun assertPreviewTextIsDisplayed() {
        compose.onNodeWithTag("history-read-only-preview").assertIsDisplayed()
        compose.onNodeWithTag("note-source-reader").assertIsDisplayed()
        compose.onNodeWithTag("source-reader").assertIsDisplayed().assertTextEquals(preview.content)
    }
}
