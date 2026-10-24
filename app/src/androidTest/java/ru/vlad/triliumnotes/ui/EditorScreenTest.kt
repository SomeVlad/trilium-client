package ru.vlad.triliumnotes.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.NotesScreenState
import ru.vlad.triliumnotes.data.NoteDocument
import ru.vlad.triliumnotes.data.NoteMeta
import ru.vlad.triliumnotes.data.SyncState

/** Screen actions are tested here; body editing and HTML preservation use the editor harness. */
class EditorScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readOnlyProtectedNoteCanBeDeletedOnlyAfterConfirmation() {
        val document = syntheticNote("").let { it.copy(note = it.note.copy(isProtected = true)) }
        var deletes = 0
        var writes = 0
        compose.setContent {
            NotesTheme {
                EditorScreen(NotesScreenState(document = document), document, SyncState(), onBack = {},
                    onTitleChanged = { writes++ }, onHtmlChanged = { writes++ }, onSync = {},
                    onKeepConflictAsCopy = {}, onDelete = { deletes++ })
            }
        }
        compose.onNodeWithContentDescription("Редактировать заметку").assertDoesNotExist()
        compose.onNodeWithTag("note-actions-synthetic-note").performClick()
        compose.onNodeWithText("Удалить").performClick()
        compose.onNodeWithText("Удалить «Искусственная заметка»?").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertEquals(0, deletes); assertEquals(0, writes) }
        compose.onNodeWithTag("note-actions-synthetic-note").performClick()
        compose.onNodeWithText("Удалить").performClick()
        compose.onNodeWithTag("confirm-delete-note").performClick()
        compose.onNodeWithTag("delete-note-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, deletes); assertEquals(0, writes) }
    }

    @Test fun deletionWaitsUntilEditingAndLocalWriteHaveFinished() {
        val document = syntheticNote("const answer = 42").let {
            it.copy(note = it.note.copy(type = "code", mime = "application/javascript"))
        }
        var state by mutableStateOf(NotesScreenState(document = document, localWritePending = true))
        compose.setContent {
            NotesTheme {
                EditorScreen(state, document, SyncState(), onBack = {}, onTitleChanged = {}, onHtmlChanged = {},
                    onSync = {}, onKeepConflictAsCopy = {}, onDelete = {})
            }
        }
        compose.onNodeWithTag("note-actions-synthetic-note").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(localWritePending = false) }
        compose.onNodeWithContentDescription("Редактировать заметку").performClick()
        compose.onNodeWithTag("note-actions-synthetic-note").assertDoesNotExist()
        compose.onNodeWithText("Готово").performClick()
        compose.onNodeWithTag("note-actions-synthetic-note").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(deletingNoteId = document.note.id) }
        compose.onNodeWithTag("note-actions-synthetic-note").assertIsNotEnabled()
    }

    @Test fun durablePendingStatusIsCompactAndNeverClaimsAnUnfinishedLocalWriteIsSaved() {
        val document = syntheticNote("<p>Local draft</p>").copy(pending = true)
        var state by mutableStateOf(NotesScreenState(document = document, loading = false))
        var sends = 0
        compose.setContent {
            NotesTheme {
                EditorScreen(state, document, SyncState(pendingCount = 1), onBack = {},
                    onTitleChanged = {}, onHtmlChanged = {}, onSync = { sends++ }, onKeepConflictAsCopy = {})
            }
        }
        compose.onNodeWithText("Сохранено на телефоне").assertDoesNotExist()
        compose.onNodeWithTag("pending-note-status").performClick()
        compose.onNodeWithText("Изменения сохранены на телефоне.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, sends) }
        compose.onNodeWithText("Отправить").performClick()
        compose.runOnIdle { assertEquals(1, sends); state = state.copy(localWritePending = true) }
        compose.onNodeWithTag("pending-note-status").assertDoesNotExist()
        compose.onNodeWithText("Сохраняю…").assertIsDisplayed()
    }

    @Test fun failedLocalSaveHasAnExplicitRetryAndNeverShowsADurableStatus() {
        val document = syntheticNote("<p>Unsaved draft</p>").copy(pending = true)
        val state = NotesScreenState(document = document, loading = false, localWritePending = true,
            error = "Не удалось сохранить текст на телефоне. Не закрывайте заметку.")
        var retries = 0
        var sends = 0
        compose.setContent {
            NotesTheme {
                EditorScreen(state, document, SyncState(pendingCount = 1), onBack = {},
                    onTitleChanged = {}, onHtmlChanged = {}, onSync = { sends++ },
                    onKeepConflictAsCopy = {}, onRetryLocalSave = { retries++ })
            }
        }
        compose.onNodeWithText("Не сохранено").assertIsDisplayed()
        compose.onNodeWithTag("pending-note-status").assertDoesNotExist()
        compose.onNodeWithText("Повторить сохранение").performClick()
        compose.waitUntil(10_000) { retries == 1 }
        compose.runOnIdle { assertEquals(0, sends) }
        compose.onNodeWithContentDescription("Редактировать заметку").performClick()
        waitForMode("note-editor")
        compose.onNodeWithText(state.error!!).assertDoesNotExist()
        compose.onNodeWithText("Не сохранено").assertIsDisplayed()
        compose.onNodeWithContentDescription("Повторить сохранение").performClick()
        compose.waitUntil(10_000) { retries == 2 }
        compose.onNodeWithText("Готово").performClick()
        waitForMode("note-reader")
        compose.onNodeWithText(state.error!!).assertIsDisplayed()
    }

    @Test fun openingExistingHtmlIsReadModeAndDoesNotWrite() {
        val document = syntheticNote("<p>Обычный текст заметки.</p>")
        var writes = 0
        compose.setContent {
            NotesTheme {
                EditorScreen(NotesScreenState(document = document), document, SyncState(),
                    onBack = {}, onTitleChanged = { writes++ }, onHtmlChanged = { writes++ },
                    onSync = {}, onKeepConflictAsCopy = {})
            }
        }
        waitForMode("note-reader")
        compose.onNodeWithContentDescription("Редактировать заметку").assertIsDisplayed()
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
        compose.onNodeWithContentDescription("Открыть в Trilium").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun tablesAndImagesDoNotDisableHtmlEditing() {
        val document = syntheticNote("<table><tr><td>Ячейка</td></tr></table><p>Подпись</p>")
        var writes = 0
        compose.setContent {
            NotesTheme {
                EditorScreen(NotesScreenState(document = document), document, SyncState(),
                    onBack = {}, onTitleChanged = { writes++ }, onHtmlChanged = { writes++ },
                    onSync = {}, onKeepConflictAsCopy = {})
            }
        }
        compose.onNodeWithContentDescription("Редактировать заметку").performClick()
        waitForMode("note-editor")
        compose.onNodeWithText("Готово").performClick()
        waitForMode("note-reader")
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun editDoneAndBackChangeModeWithoutWritingOrLeavingNote() {
        val document = syntheticNote("<p>Текст для чтения.</p>")
        var writes = 0
        var backs = 0
        var syncs = 0
        compose.setContent {
            NotesTheme {
                EditorScreen(NotesScreenState(document = document), document, SyncState(),
                    onBack = { backs++ }, onTitleChanged = { writes++ }, onHtmlChanged = { writes++ },
                    onSync = { syncs++ }, onKeepConflictAsCopy = {})
            }
        }
        compose.onNodeWithContentDescription("Редактировать заметку").performClick()
        compose.onNodeWithText("Готово").performClick()
        waitForMode("note-reader")
        compose.onNodeWithContentDescription("Редактировать заметку").performClick()
        // The toolbar Back is deterministic even when the IME consumes hardware Back first.
        compose.onNodeWithContentDescription("Завершить редактирование").performClick()
        waitForMode("note-reader")
        compose.runOnIdle {
            assertEquals(0, writes)
            assertEquals(0, backs)
            assertEquals(2, syncs)
        }
        compose.onNodeWithContentDescription("Назад к заметкам").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun protectedHtmlShowsReasonAndCannotEnterEditMode() {
        val document = syntheticNote("<p>Защищённый текст.</p>").let {
            it.copy(note = it.note.copy(isProtected = true))
        }
        compose.setContent {
            NotesTheme {
                EditorScreen(NotesScreenState(document = document), document, SyncState(),
                    onBack = {}, onTitleChanged = {}, onHtmlChanged = {}, onSync = {}, onKeepConflictAsCopy = {})
            }
        }
        // Protected content never mounts a WebView: ETAPI cannot decrypt it.
        compose.onNodeWithTag("note-reader").assertDoesNotExist()
        compose.onNodeWithTag("note-editor").assertDoesNotExist()
        compose.onNodeWithContentDescription("Редактировать заметку").assertDoesNotExist()
        compose.onNodeWithText("Защищённая заметка").assertIsDisplayed()
        compose.onNodeWithText("Чтобы прочитать эту заметку, откройте её в Trilium и разблокируйте.").assertIsDisplayed()
        compose.onNodeWithText("Только чтение").assertDoesNotExist()
        compose.onNodeWithTag("toggle-special-source").assertDoesNotExist()
    }

    @Test fun builtInDocumentationUsesHtmlReaderWithoutEditAction() {
        val document = syntheticNote("<h2>Справка</h2><p>Содержимое справки Trilium.</p>").let {
            it.copy(note = it.note.copy(type = "doc"))
        }
        compose.setContent {
            NotesTheme {
                EditorScreen(NotesScreenState(document = document), document, SyncState(),
                    onBack = {}, onTitleChanged = {}, onHtmlChanged = {}, onSync = {}, onKeepConflictAsCopy = {})
            }
        }
        waitForMode("note-reader")
        compose.onNodeWithContentDescription("Редактировать заметку").assertDoesNotExist()
        compose.onNodeWithText("Только чтение").assertIsDisplayed()
    }

    @Test fun specialFormatsExplainTheirLimitAndRevealExactDataOnlyOnRequest() {
        val original = "{\n  \"nodes\": [{\"text\": \"Keep this exact data\"}]\n}"
        val document = syntheticNote(original).let { it.copy(note = it.note.copy(type = "canvas", mime = "application/json")) }
        var writes = 0
        var openedId: String? = null
        compose.setContent {
            NotesTheme {
                EditorScreen(NotesScreenState(document = document), document, SyncState(), onBack = {},
                    onTitleChanged = { writes++ }, onHtmlChanged = { writes++ }, onSync = {},
                    onKeepConflictAsCopy = {}, onOpenLink = { openedId = it.substringAfter("#root/") })
            }
        }
        compose.onNodeWithTag("special-note-source").assertDoesNotExist()
        compose.onNodeWithContentDescription("Редактировать заметку").assertDoesNotExist()
        compose.onNodeWithText("Показать исходные данные").performClick()
        compose.onNodeWithText(original).assertIsDisplayed()
        compose.onNodeWithText("Скрыть исходные данные").performClick()
        compose.onNodeWithTag("special-note-source").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, writes); assertEquals(null, openedId) }
        compose.onNodeWithText("Открыть в Trilium").performClick()
        compose.runOnIdle { assertEquals(document.note.id, openedId); assertEquals(0, writes) }
    }

    @Test fun codeUsesNativeSourceEditorAndCanEnterEditMode() {
        val document = syntheticNote("function test() { return 42; }").let {
            it.copy(note = it.note.copy(type = "code", mime = "application/javascript"))
        }
        compose.setContent {
            NotesTheme {
                EditorScreen(NotesScreenState(document = document), document, SyncState(),
                    onBack = {}, onTitleChanged = {}, onHtmlChanged = {}, onSync = {}, onKeepConflictAsCopy = {})
            }
        }
        waitForMode("note-source-reader")
        compose.onNodeWithContentDescription("Редактировать заметку").performClick()
        waitForMode("note-source-editor")
    }

    @Test fun newNoteStartsInEditMode() {
        val document = syntheticNote("<p></p>")
        compose.setContent {
            NotesTheme {
                EditorScreen(NotesScreenState(document = document, newNoteId = document.note.id),
                    document, SyncState(), onBack = {}, onTitleChanged = {},
                    onHtmlChanged = {}, onSync = {}, onKeepConflictAsCopy = {})
            }
        }
        waitForMode("note-editor")
        compose.onNodeWithText("Готово").assertIsDisplayed()
    }

    private fun waitForMode(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun syntheticNote(html: String) = NoteDocument(
        note = NoteMeta("synthetic-note", "Искусственная заметка", "text", "text/html",
            false, null, null, emptyList()),
        html = html,
    )
}
