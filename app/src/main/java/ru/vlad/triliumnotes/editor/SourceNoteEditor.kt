package ru.vlad.triliumnotes.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Code retains its source; Markdown uses the visual editor with Markdown input and output. */
@Composable
fun SourceNoteEditor(
    noteKey: String,
    title: String,
    initialContent: String,
    mime: String = "text/plain",
    readOnly: Boolean = false,
    isEditing: Boolean = true,
    modifier: Modifier = Modifier,
    onTitleChanged: (String) -> Unit = {},
    onContentChanged: (String) -> Unit,
    controller: RichNoteEditorController? = null,
    onOpenNote: (String) -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    resourceLoader: (String) -> EditorResource? = { null },
) {
    key(noteKey) {
        var content by remember { mutableStateOf(initialContent) }
        var localTitle by remember { mutableStateOf(title) }
        val editable = isEditing && !readOnly
        if (mime.contains("markdown", ignoreCase = true)) {
            Box(modifier.testTag(if (editable) "note-source-editor" else "note-source-reader")) {
                RichNoteEditor(noteKey = "$noteKey-markdown-preview", title = localTitle, initialHtml = content,
                    initialMarkdown = content, isEditing = isEditing, readOnly = readOnly, controller = controller,
                    modifier = Modifier.fillMaxSize(), onTitleChanged = { localTitle = it; onTitleChanged(it) },
                    onHtmlChanged = { content = it; onContentChanged(it) },
                    onOpenNote = onOpenNote, onOpenLink = onOpenLink, resourceLoader = resourceLoader)
            }
        } else {
            val style = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onBackground, fontFamily = FontFamily.Monospace,
                fontSize = 15.sp, lineHeight = 24.sp,
            )
            Column(modifier.verticalScroll(rememberScrollState()).testTag(if (editable) "note-source-editor" else "note-source-reader")
                .padding(horizontal = 22.dp, vertical = 16.dp)) {
                BasicTextField(
                    value = localTitle, onValueChange = { localTitle = it; onTitleChanged(it) },
                    readOnly = !editable, textStyle = MaterialTheme.typography.headlineLarge.copy(color = style.color),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp).testTag("note-title"),
                )
                if (editable) BasicTextField(
                    value = content, onValueChange = { content = it; onContentChanged(it) },
                    textStyle = style, cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().testTag("source-editor"),
                ) else SelectionContainer { Text(content, style = style, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}
