package ru.vlad.triliumnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import ru.vlad.triliumnotes.data.AttachmentMeta

/** List operations are owned by the caller, so editor flushing and refreshed content share the normal save pipeline. */
@OptIn(ExperimentalMaterial3Api::class)
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
    var actionsFor by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("attachment-manager-sheet")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(AppLanguage.get(context, R.string.editor_attachments), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss, enabled = !busy) {
                    Icon(Icons.Outlined.Close, AppLanguage.get(context, R.string.close))
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { message ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    onRetry?.let { retry -> TextButton(onClick = retry, enabled = !busy,
                        modifier = Modifier.testTag("attachment-manager-retry")) {
                        Text(AppLanguage.get(context, R.string.retry))
                    } }
                }
            }
            if (attachments.isEmpty() && !busy && error == null) {
                Text(AppLanguage.get(context, R.string.editor_no_attachments), Modifier.padding(22.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(attachments, key = { it.id }) { attachment ->
                    ListItem(modifier = Modifier.testTag("attachment-open-${attachment.id}")
                        .clickable(enabled = !busy) { onOpen(attachment) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        headlineContent = { Text(attachment.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(attachmentFormat(context, attachment.mime, attachment.title)) },
                        trailingContent = { Box {
                            IconButton(onClick = { actionsFor = attachment.id }, enabled = !busy,
                                modifier = Modifier.testTag("attachment-actions-${attachment.id}")) {
                                Icon(Icons.Outlined.MoreVert, AppLanguage.get(context, R.string.editor_attachment_actions, attachment.title))
                            }
                            DropdownMenu(expanded = actionsFor == attachment.id && !busy, onDismissRequest = { actionsFor = null }) {
                                DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.editor_rename_attachment)) },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                    modifier = Modifier.testTag("attachment-rename-${attachment.id}"),
                                    onClick = { actionsFor = null; renaming = attachment; title = attachment.title })
                                DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.editor_delete_attachment), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    modifier = Modifier.testTag("attachment-delete-${attachment.id}"),
                                    onClick = { actionsFor = null; deleting = attachment })
                            }
                        } })
                }
            }
        }
    }
    renaming?.let { attachment ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text(AppLanguage.get(context, R.string.editor_rename_attachment)) },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true,
                modifier = Modifier.testTag("attachment-title")) },
            confirmButton = { TextButton(enabled = !busy && title.isNotBlank() && title.none { it.isISOControl() }, onClick = {
                onRename(attachment, title.trim()); renaming = null
            }) { Text(AppLanguage.get(context, R.string.editor_apply)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(AppLanguage.get(context, R.string.cancel)) } })
    }
    deleting?.let { attachment ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(AppLanguage.get(context, R.string.editor_delete_attachment)) },
            text = { Text(AppLanguage.get(context, R.string.editor_delete_attachment_confirmation, attachment.title)) },
            confirmButton = { TextButton(enabled = !busy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = { onDelete(attachment); deleting = null }) {
                Text(AppLanguage.get(context, R.string.editor_delete_attachment))
            } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(AppLanguage.get(context, R.string.cancel)) } })
    }
}
