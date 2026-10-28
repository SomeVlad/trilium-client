package ru.vlad.triliumnotes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import ru.vlad.triliumnotes.data.AttachmentMeta

/** List operations are owned by the caller, so editor flushing and refreshed content share the normal save pipeline. */
@Composable
fun AttachmentManagerDialog(
    attachments: List<AttachmentMeta>,
    onRename: (AttachmentMeta, String) -> Unit,
    onDelete: (AttachmentMeta) -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    error: String? = null,
    onOpen: (AttachmentMeta) -> Unit = {},
    onRetry: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var renaming by remember { mutableStateOf<AttachmentMeta?>(null) }
    var deleting by remember { mutableStateOf<AttachmentMeta?>(null) }
    var title by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(AppLanguage.get(context, R.string.editor_attachments)) },
        text = { Column {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                onRetry?.let { retry -> TextButton(onClick = retry, enabled = !busy,
                    modifier = Modifier.testTag("attachment-manager-retry")) {
                    Text(AppLanguage.get(context, R.string.retry))
                } }
            }
            if (attachments.isEmpty() && !busy) Text(AppLanguage.get(context, R.string.editor_no_attachments))
            LazyColumn {
                items(attachments, key = { it.id }) { attachment ->
                    ListItem(headlineContent = { TextButton(onClick = { onOpen(attachment) }) { Text(attachment.title) } },
                        supportingContent = { Text(attachment.mime) }, trailingContent = { Row {
                            IconButton(onClick = { renaming = attachment; title = attachment.title }, enabled = !busy,
                                modifier = Modifier.testTag("attachment-rename-${attachment.id}")) {
                                Icon(Icons.Outlined.Edit, AppLanguage.get(context, R.string.editor_rename_attachment))
                            }
                            IconButton(onClick = { deleting = attachment }, enabled = !busy,
                                modifier = Modifier.testTag("attachment-delete-${attachment.id}")) {
                                Icon(Icons.Outlined.Delete, AppLanguage.get(context, R.string.editor_delete_attachment))
                            }
                        } })
                }
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(AppLanguage.get(context, R.string.close)) } })
    renaming?.let { attachment ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text(AppLanguage.get(context, R.string.editor_rename_attachment)) },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true,
                modifier = Modifier.testTag("attachment-title")) },
            confirmButton = { TextButton(enabled = title.isNotBlank() && title.none { it.isISOControl() }, onClick = {
                onRename(attachment, title.trim()); renaming = null
            }) { Text(AppLanguage.get(context, R.string.editor_apply)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(AppLanguage.get(context, R.string.cancel)) } })
    }
    deleting?.let { attachment ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(AppLanguage.get(context, R.string.editor_delete_attachment)) },
            text = { Text(AppLanguage.get(context, R.string.editor_delete_attachment_confirmation, attachment.title)) },
            confirmButton = { TextButton(onClick = { onDelete(attachment); deleting = null }) {
                Text(AppLanguage.get(context, R.string.editor_delete_attachment))
            } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(AppLanguage.get(context, R.string.cancel)) } })
    }
}
