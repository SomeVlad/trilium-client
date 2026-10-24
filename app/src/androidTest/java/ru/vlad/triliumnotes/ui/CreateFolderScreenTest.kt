package ru.vlad.triliumnotes.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.NotesScreenState
import ru.vlad.triliumnotes.data.NoteMeta
import ru.vlad.triliumnotes.data.SyncState

class CreateFolderScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun plusMenuCreatesNamedFolderAndKeepsNewNoteAction() {
        val folders = mutableListOf<String>()
        var notes = 0
        compose.setContent {
            NotesTheme {
                LibraryScreen(NotesScreenState(loading = false, networkAvailable = false), SyncState(),
                    onBack = {}, onSearch = {}, onRefresh = {}, onSync = {}, onCreate = { notes++ },
                    onOpen = {}, onFolder = {}, onOpenFolderContent = {}, onCreateFolder = { folders += it })
            }
        }
        compose.onNodeWithTag("create-note").performClick()
        compose.onNodeWithText("Новая заметка").assertIsDisplayed()
        compose.onNodeWithTag("new-folder-menu").performClick()
        compose.onNodeWithTag("confirm-create-folder").assertIsNotEnabled()
        compose.onNodeWithTag("new-folder-title").performTextInput("  Проекты  ")
        compose.onNodeWithTag("confirm-create-folder").performClick()
        compose.runOnIdle { assertEquals(listOf("Проекты"), folders); assertEquals(0, notes) }
        compose.onNodeWithTag("create-note").performClick()
        compose.onNodeWithTag("new-note-menu").performClick()
        compose.runOnIdle { assertEquals(1, notes) }
    }

    @Test fun cancellingFolderDialogDoesNotCreateAnything() {
        val folders = mutableListOf<String>()
        compose.setContent {
            NotesTheme {
                LibraryScreen(NotesScreenState(loading = false), SyncState(),
                    onBack = {}, onSearch = {}, onRefresh = {}, onSync = {}, onCreate = {},
                    onOpen = {}, onFolder = {}, onOpenFolderContent = {}, onCreateFolder = { folders += it })
            }
        }
        compose.onNodeWithTag("create-note").performClick()
        compose.onNodeWithTag("new-folder-menu").performClick()
        compose.onNodeWithTag("new-folder-title").performTextInput("Не сохранять")
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithTag("new-folder-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), folders) }
    }

    @Test fun emptyNativeBookOpensAsFolder() {
        var folders = 0
        var notes = 0
        compose.setContent {
            NotesTheme {
                NoteRow(NoteMeta("emptyBook", "Пустая папка", "book", "text/html", false,
                    null, null, emptyList()), "", onOpen = { notes++ }, onFolder = { folders++ })
            }
        }
        compose.onNodeWithTag("note-row-emptyBook").performClick()
        compose.runOnIdle { assertEquals(1, folders); assertEquals(0, notes) }
    }
}
