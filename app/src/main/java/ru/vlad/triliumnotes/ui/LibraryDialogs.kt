package ru.vlad.triliumnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import ru.vlad.triliumnotes.data.NoteMeta
import ru.vlad.triliumnotes.data.TreeItem
import ru.vlad.triliumnotes.data.TrashItem
import ru.vlad.triliumnotes.data.NoteSort
import ru.vlad.triliumnotes.data.serializeReorderedGroups
import ru.vlad.triliumnotes.data.sortTreeItems

enum class LibraryAction { MOVE, COPY, CLONE }
data class LibraryActionRequest(val action: LibraryAction, val notes: List<NoteMeta>, val sourceParentId: String?)
data class NoteDestination(val note: NoteMeta, val path: String)

@Composable
fun DestinationDialog(title: String, destinations: List<NoteDestination>, onPick: (NoteMeta) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.statusBarsPadding().navigationBarsPadding().imePadding().testTag("destination-dialog"),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text(title) }, text = {
            Column(Modifier.heightIn(max = 380.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true,
                    placeholder = { Text(AppLanguage.get(context, R.string.search_all_notes)) }, modifier = Modifier.fillMaxWidth().testTag("destination-query"))
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f, fill = false).testTag("destination-list")) {
                    val matches = destinations.filter { it.note.title.contains(query, true) || it.path.contains(query, true) }
                    if (matches.isEmpty()) item { Text(AppLanguage.get(context, R.string.library_no_destinations), Modifier.padding(vertical = 16.dp)) }
                    items(matches, key = { it.note.id }) { destination ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(destination.note) }.padding(vertical = 12.dp)
                            .testTag("destination-${destination.note.id}")) {
                            Text(if (destination.note.id == "root") AppLanguage.get(context, R.string.notes) else destination.note.title,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (destination.path.isNotBlank()) Text(destination.path, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("destination-cancel")) { Text(AppLanguage.get(context, R.string.cancel)) } })
}

@Composable
fun ReorderDialog(items: List<TreeItem>, onSave: (List<String>) -> Unit, onDismiss: () -> Unit,
    pinned: Set<String> = emptySet(),
) {
    val context = LocalContext.current
    var order by remember(items, pinned) { mutableStateOf(sortTreeItems(items, NoteSort.MANUAL, pinned)) }
    fun canMove(index: Int, delta: Int): Boolean = index + delta in order.indices &&
        (order[index].note.id in pinned) == (order[index + delta].note.id in pinned)
    fun move(index: Int, delta: Int) {
        if (!canMove(index, delta)) return
        order = order.toMutableList().apply { add(index + delta, removeAt(index)) }
    }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("reorder-dialog"),
        title = { Text(AppLanguage.get(context, R.string.library_reorder)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(order, key = { it.branchId }) { item ->
                    val index = order.indexOf(item)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(item.note.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (item.note.id in pinned) Icon(Icons.Outlined.PushPin, AppLanguage.get(context, R.string.library_pinned),
                            Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = { move(index, -1) }, enabled = canMove(index, -1),
                            modifier = Modifier.testTag("reorder-up-${item.branchId}")) {
                            Icon(Icons.Outlined.ArrowUpward, AppLanguage.get(context, R.string.library_move_up))
                        }
                        IconButton(onClick = { move(index, 1) }, enabled = canMove(index, 1),
                            modifier = Modifier.testTag("reorder-down-${item.branchId}")) {
                            Icon(Icons.Outlined.ArrowDownward, AppLanguage.get(context, R.string.library_move_down))
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { onSave(serializeReorderedGroups(items, order, pinned)) }) { Text(AppLanguage.get(context, R.string.library_save_order)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppLanguage.get(context, R.string.cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashSheet(items: List<TrashItem>, loading: Boolean, error: String?, busy: Boolean,
    onRestore: (String) -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f).padding(horizontal = 20.dp)) {
            Text(AppLanguage.get(context, R.string.library_trash), style = MaterialTheme.typography.titleLarge)
            if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = onRetry, enabled = !busy) { Text(AppLanguage.get(context, R.string.retry)) }
            }
            if (!loading && items.isEmpty() && error == null) Text(AppLanguage.get(context, R.string.library_trash_empty), Modifier.padding(vertical = 24.dp))
            LazyColumn(Modifier.weight(1f).testTag("trash-list")) {
                items(items, key = { it.noteId }) { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (item.restoring || !item.canRestore) Text(AppLanguage.get(context, if (item.restoring) R.string.library_restore_queued else R.string.library_restore_parent), style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { onRestore(item.noteId) }, enabled = item.canRestore && !busy) {
                            Text(AppLanguage.get(context, R.string.library_restore))
                        }
                    }
                }
            }
        }
    }
}
