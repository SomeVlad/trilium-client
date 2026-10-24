package ru.vlad.triliumnotes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.data.NoteMeta

class NoteRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wholeRowOpensFolderOrEditorBasedOnChildren() {
        var folderOpens = 0
        var editorOpens = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    NoteRow(note("folder", listOf("child")), "",
                        onOpen = { editorOpens++ }, onFolder = { folderOpens++ })
                    NoteRow(note("leaf", emptyList()), "",
                        onOpen = { editorOpens++ }, onFolder = { folderOpens++ })
                }
            }
        }

        compose.onNodeWithTag("note-row-folder").assertHasClickAction().performTouchInput {
            click(Offset(8f, height / 2f))
            click(Offset(width - 8f, height / 2f))
        }
        assertEquals(2, folderOpens)
        assertEquals(0, editorOpens)
        compose.onNodeWithTag("note-row-leaf").assertHasClickAction().performTouchInput {
            click(Offset(8f, height / 2f))
            click(Offset(width - 8f, height / 2f))
        }
        assertEquals(2, folderOpens)
        assertEquals(2, editorOpens)
    }

    private fun note(id: String, children: List<String>) = NoteMeta(
        id = id,
        title = id,
        type = "text",
        mime = "text/html",
        isProtected = false,
        blobId = null,
        modifiedAt = null,
        childBranchIds = children,
    )
}
