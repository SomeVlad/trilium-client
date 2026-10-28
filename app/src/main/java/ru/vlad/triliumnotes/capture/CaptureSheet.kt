@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ru.vlad.triliumnotes.capture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
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
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val titleIntoView = remember { BringIntoViewRequester() }
    var titleFocused by remember { mutableStateOf(false) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val imeHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(titleFocused, viewportHeight, imeHeight) {
        if (titleFocused) {
            withFrameNanos { }
            titleIntoView.bringIntoView()
        }
    }
    fun label(id: Int, vararg args: Any) = AppLanguage.get(context, id, *args)
    val locked = capture.targetNoteId != null
    var title by rememberSaveable(capture.id) { mutableStateOf(capture.targetTitle ?: capture.title) }
    var existing by rememberSaveable(capture.id) { mutableStateOf(locked && !capture.createNew) }
    var selectedFolder by rememberSaveable(capture.id) { mutableStateOf(capture.targetParentId ?: choices.firstOrNull { it.isFolder }?.id ?: "root") }
    var selectedNote by rememberSaveable(capture.id) { mutableStateOf<String?>(if (locked && !capture.createNew) capture.targetNoteId else null) }
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val targets = choices.filter { it.isFolder != existing }
    val selectedId = if (existing) selectedNote else selectedFolder
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("capture_sheet")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).onSizeChanged { viewportHeight = it.height }
                .verticalScroll(rememberScrollState())
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
                    label = { Text(label(R.string.capture_note_title)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().bringIntoViewRequester(titleIntoView)
                        .onFocusChanged { titleFocused = it.isFocused }.testTag("capture_note_title"))
                OutlinedCard(onClick = { focus.clearFocus(); keyboard?.hide(); query = ""; expanded = true }, enabled = !busy && !locked,
                    modifier = Modifier.fillMaxWidth().testTag("capture-destination")) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(label(if (existing) R.string.capture_destination_note else R.string.capture_destination_folder),
                                style = MaterialTheme.typography.labelMedium)
                            Text(targets.firstOrNull { it.id == selectedId }?.title
                                ?: if (!existing && selectedFolder == "root") label(R.string.capture_root) else label(R.string.capture_select_note),
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded)
                    }
                }
                if (locked) Text(label(R.string.capture_destination_locked), style = MaterialTheme.typography.bodySmall)
            }
            if (!accountMatches || error != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("capture-feedback"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!accountMatches) Text(label(R.string.capture_account_changed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    if (error != null) Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            // Keep explicit delivery actions above the keyboard while the form can scroll.
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                if (onDiscard != null) TextButton(onClick = onDiscard, enabled = !busy) { Text(label(R.string.capture_discard), maxLines = 1) }
                TextButton(onClick = onDismiss, enabled = !busy) { Text(label(R.string.capture_later), maxLines = 1) }
                Button(onClick = {
                    if (locked) onSave(capture.targetTitle ?: title, capture.targetParentId,
                        if (capture.createNew) null else capture.targetNoteId)
                    else onSave(title, if (existing) null else selectedFolder, if (existing) selectedNote else null)
                },
                    enabled = !busy && accountMatches && (!existing || selectedNote != null), modifier = Modifier.testTag("capture_save")) {
                    Text(label(R.string.capture_save), maxLines = 1)
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        }
    }
    if (expanded && !busy && !locked) {
        val options = if (!existing && targets.none { it.id == "root" })
            listOf(CaptureDestination("root", label(R.string.capture_root), true)) + targets else targets
        val matches = options.distinctBy { it.id }.filter { it.title.contains(query, ignoreCase = true) }
        AlertDialog(onDismissRequest = { expanded = false },
            properties = DialogProperties(decorFitsSystemWindows = false),
            modifier = Modifier.statusBarsPadding().navigationBarsPadding().imePadding().testTag("capture-target-picker"),
            title = { Text(label(if (existing) R.string.capture_select_note else R.string.library_destination)) },
            text = { Column(Modifier.heightIn(max = 360.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true,
                    placeholder = { Text(label(R.string.capture_find_target)) },
                    modifier = Modifier.fillMaxWidth().testTag("capture-target-query"))
                if (matches.isEmpty()) Text(label(R.string.nothing_found), Modifier.padding(vertical = 16.dp))
                LazyColumn(Modifier.weight(1f, fill = false).testTag("capture-target-list")) {
                    items(matches, key = { it.id }) { target ->
                        Text(target.title, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (existing) selectedNote = target.id else selectedFolder = target.id
                                expanded = false
                            }.padding(vertical = 14.dp).testTag("capture-target-${target.id}"))
                    }
                }
            } }, confirmButton = {}, dismissButton = {
                TextButton(onClick = { expanded = false }, modifier = Modifier.testTag("capture-target-cancel")) { Text(label(R.string.cancel)) }
            })
    }
}
