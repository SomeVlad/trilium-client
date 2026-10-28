@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.vlad.triliumnotes.capture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R

data class CaptureDestination(val id: String, val title: String, val isFolder: Boolean)

/** Stateless delivery boundary: Save is the only action that authorizes writing to accountLabel. */
@Composable
fun CaptureSheet(
    capture: PendingCapture,
    accountLabel: String,
    choices: List<CaptureDestination>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (title: String, parentId: String?, existingNoteId: String?) -> Unit,
    onDiscard: (() -> Unit)? = null,
    accountMatches: Boolean = true,
) {
    val context = LocalContext.current
    fun label(id: Int, vararg args: Any) = AppLanguage.get(context, id, *args)
    val locked = capture.targetNoteId != null
    var title by rememberSaveable(capture.id) { mutableStateOf(capture.targetTitle ?: capture.title) }
    var existing by rememberSaveable(capture.id) { mutableStateOf(locked && !capture.createNew) }
    var selectedFolder by rememberSaveable(capture.id) { mutableStateOf(capture.targetParentId ?: choices.firstOrNull { it.isFolder }?.id ?: "root") }
    var selectedNote by rememberSaveable(capture.id) { mutableStateOf<String?>(if (locked && !capture.createNew) capture.targetNoteId else null) }
    var expanded by remember { mutableStateOf(false) }
    val targets = choices.filter { it.isFolder != existing }
    val selectedId = if (existing) selectedNote else selectedFolder
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("capture_sheet")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label(R.string.capture_title), style = MaterialTheme.typography.titleLarge)
                Text(label(R.string.capture_account, accountLabel), style = MaterialTheme.typography.bodySmall)
                if (capture.text.isNotBlank()) Text(capture.text.take(500), maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (capture.attachments.isNotEmpty()) Text(label(R.string.capture_files, capture.attachments.size))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = !busy && !locked) { existing = false }) {
                    RadioButton(selected = !existing, onClick = { existing = false }, enabled = !busy && !locked)
                    Text(label(R.string.capture_new_note))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = !busy && !locked) { existing = true }) {
                    RadioButton(selected = existing, onClick = { existing = true }, enabled = !busy && !locked)
                    Text(label(R.string.capture_existing_note))
                }
                if (!existing) OutlinedTextField(value = title, onValueChange = { title = it }, enabled = !busy && !locked,
                    label = { Text(label(R.string.capture_note_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("capture_note_title"))
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (!busy && !locked) expanded = it }) {
                    OutlinedTextField(value = choices.firstOrNull { it.id == selectedId }?.title
                        ?: if (!existing && selectedFolder == "root") label(R.string.capture_root) else label(R.string.capture_select_note),
                        onValueChange = {}, readOnly = true, enabled = !busy && !locked,
                        label = { Text(label(if (existing) R.string.capture_destination_note else R.string.capture_destination_folder)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        if (!existing && targets.none { it.id == "root" }) DropdownMenuItem(text = { Text(label(R.string.capture_root)) },
                            onClick = { selectedFolder = "root"; expanded = false })
                        targets.forEach { target -> DropdownMenuItem(text = { Text(target.title) }, onClick = {
                            if (existing) selectedNote = target.id else selectedFolder = target.id
                            expanded = false
                        }) }
                    }
                }
                if (locked) Text(label(R.string.capture_destination_locked), style = MaterialTheme.typography.bodySmall)
                if (!accountMatches) Text(label(R.string.capture_account_changed), color = MaterialTheme.colorScheme.error)
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
            // Keep explicit delivery actions above the keyboard while the form can scroll.
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                if (onDiscard != null) TextButton(onClick = onDiscard, enabled = !busy) { Text(label(R.string.capture_discard)) }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(label(R.string.capture_later)) }
                Button(onClick = {
                    if (locked) onSave(capture.targetTitle ?: title, capture.targetParentId,
                        if (capture.createNew) null else capture.targetNoteId)
                    else onSave(title, if (existing) null else selectedFolder, if (existing) selectedNote else null)
                },
                    enabled = !busy && accountMatches && (!existing || selectedNote != null), modifier = Modifier.testTag("capture_save")) {
                    Text(label(R.string.capture_save))
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        }
    }
}
