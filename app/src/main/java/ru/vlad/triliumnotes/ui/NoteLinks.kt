package ru.vlad.triliumnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import ru.vlad.triliumnotes.data.NoteMeta

/** Candidates come from the same visible-note cache as navigation; selection returns the real note identity. */
@Composable
fun NoteLinkPicker(notes: List<NoteMeta>, onPick: (NoteMeta) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(AppLanguage.get(context, R.string.editor_insert_note_link)) },
        text = { Column {
            OutlinedTextField(query, { query = it }, singleLine = true,
                label = { Text(AppLanguage.get(context, R.string.editor_find_note)) }, modifier = Modifier.testTag("note-link-query"))
            LazyColumn { items(notes.filter { it.id != "root" && !it.isProtected && it.title.contains(query, true) }.distinctBy { it.id }, key = { it.id }) { note ->
                ListItem(headlineContent = { Text(note.title) }, modifier = Modifier.fillMaxWidth()
                    .testTag("note-link-${note.id}").clickable { onPick(note) })
            } }
        } }, confirmButton = {}, dismissButton = {
            TextButton(onClick = onDismiss) { Text(AppLanguage.get(context, R.string.cancel)) }
        })
}

@Composable
fun BacklinksDialog(notes: List<NoteMeta>, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(AppLanguage.get(context, R.string.editor_backlinks)) },
        text = { if (notes.isEmpty()) Text(AppLanguage.get(context, R.string.editor_no_backlinks)) else LazyColumn {
            items(notes, key = { it.id }) { note -> ListItem(headlineContent = { Text(note.title) },
                modifier = Modifier.clickable { onOpen(note.id) }.testTag("backlink-${note.id}")) }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(AppLanguage.get(context, R.string.close)) } })
}
