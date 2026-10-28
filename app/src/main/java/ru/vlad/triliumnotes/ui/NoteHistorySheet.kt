package ru.vlad.triliumnotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import ru.vlad.triliumnotes.data.NoteMeta
import ru.vlad.triliumnotes.data.NoteDocument
import ru.vlad.triliumnotes.data.NoteRevision
import ru.vlad.triliumnotes.data.RevisionDocument
import ru.vlad.triliumnotes.editor.EditorResource
import ru.vlad.triliumnotes.editor.RichNoteEditor
import ru.vlad.triliumnotes.editor.SourceNoteEditor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class NoteHistoryState(
    val revisions: List<NoteRevision> = emptyList(),
    val loading: Boolean = true,
    val selected: NoteRevision? = null,
    val preview: RevisionDocument? = null,
    val previewLoading: Boolean = false,
    val error: String? = null,
    val working: Boolean = false,
    val allowRestore: Boolean = true,
)

/** Preview is always read-only; restoration is an explicit, separately confirmed action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteHistorySheet(
    note: NoteMeta,
    state: NoteHistoryState,
    onSelect: (NoteRevision) -> Unit,
    onRestore: (RevisionDocument) -> Unit,
    onSaveRevision: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    resourceLoader: (String) -> EditorResource? = { null },
    serverUrl: String = "",
    currentDocument: NoteDocument? = null,
) {
    val context = LocalContext.current
    fun label(id: Int): String = AppLanguage.get(context, id)
    var confirmRestore by remember(note.id, state.selected?.id) { mutableStateOf(false) }
    var comparing by remember(note.id, state.selected?.id) { mutableStateOf(false) }
    val preview = state.preview?.takeIf { it.revision.id == state.selected?.id && it.revision.noteId == note.id }
    val canRestore = preview != null && preview.revision.canRestoreTo(note) && state.allowRestore && !state.working
    BackHandler(state.selected != null && !state.working) { onBack() }
    ModalBottomSheet(
        onDismissRequest = { if (!state.working) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("note-history-sheet"),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (state.selected != null) IconButton(onClick = onBack, enabled = !state.working) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, label(R.string.back))
                }
                Text(label(R.string.history_title), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onDismiss, enabled = !state.working) {
                    Icon(Icons.Default.Close, label(R.string.close))
                }
            }
            // The read-only preview already renders its title. Keep the compact title for list/diff/loading only.
            if (preview == null || comparing) Text(state.selected?.title ?: note.title,
                Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (state.working) {
                Row(Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text(label(R.string.history_saving), style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.error?.let { error ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRetry, enabled = !state.working) { Text(label(R.string.retry)) }
                }
            }
            if (state.selected == null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onSaveRevision, enabled = !state.loading && !state.working,
                        modifier = Modifier.testTag("history-save-current")) {
                        Text(label(R.string.history_save_current))
                    }
                }
                HorizontalDivider()
                when {
                    state.loading -> HistoryLoading(Modifier.weight(1f))
                    state.revisions.isEmpty() && state.error == null -> Box(
                        Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(label(R.string.history_empty), style = MaterialTheme.typography.bodyLarge)
                    }
                    else -> LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("history-revisions")) {
                        items(state.revisions, key = { it.id }) { revision ->
                            Column(Modifier.fillMaxWidth().clickable(enabled = !state.working) { onSelect(revision) }
                                .padding(horizontal = 22.dp, vertical = 14.dp).testTag("history-revision-${revision.id}")) {
                                Text(formatHistoryDate(revision.createdAt, AppLanguage.language(context))
                                    ?: label(R.string.history_unknown_date), style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(4.dp))
                                Text(revision.title, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (revision.description.isNotBlank()) Text(revision.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                Text(formatHistoryDate(state.selected.createdAt, AppLanguage.language(context))
                    ?: label(R.string.history_unknown_date), Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.selected.description.isNotBlank()) Text(state.selected.description,
                    Modifier.padding(horizontal = 22.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(label(R.string.history_read_only), Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { confirmRestore = true }, enabled = canRestore,
                        modifier = Modifier.testTag("history-restore")) { Text(label(R.string.history_restore)) }
                }
                HorizontalDivider()
                if (preview != null && currentDocument != null) {
                    TextButton(onClick = { comparing = !comparing },
                        modifier = Modifier.padding(horizontal = 12.dp).testTag("history-compare")) {
                        Text(label(if (comparing) R.string.diff_preview else R.string.diff_compare))
                    }
                }
                when {
                    state.previewLoading -> HistoryLoading(Modifier.weight(1f))
                    comparing && preview != null && currentDocument != null ->
                        NoteDiffView(preview, currentDocument, Modifier.fillMaxWidth().weight(1f))
                    preview != null -> RevisionPreview(preview, Modifier.fillMaxWidth().weight(1f), resourceLoader, serverUrl)
                    state.error == null -> Text(label(R.string.history_preview_unavailable), Modifier.padding(22.dp))
                }
            }
        }
    }
    if (confirmRestore && preview != null) {
        AlertDialog(onDismissRequest = { confirmRestore = false },
            modifier = Modifier.testTag("history-restore-confirmation"),
            title = { Text(label(R.string.history_restore_title)) },
            text = { Text(label(R.string.history_restore_message)) },
            confirmButton = {
                TextButton(onClick = { confirmRestore = false; onRestore(preview) }, enabled = canRestore,
                    modifier = Modifier.testTag("history-confirm-restore")) { Text(label(R.string.history_restore)) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(label(R.string.cancel)) } })
    }
}

@Composable
private fun HistoryLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun RevisionPreview(
    document: RevisionDocument,
    modifier: Modifier,
    resourceLoader: (String) -> EditorResource?,
    serverUrl: String,
) {
    val revision = document.revision
    val key = "history-${revision.noteId}-${revision.id}"
    // Keep the preview tag on its container so the reader retains its own tag.
    Box(modifier.testTag("history-read-only-preview")) {
        if (revision.type == "text" && revision.mime.substringBefore(';') == "text/html") {
            RichNoteEditor(noteKey = key, title = revision.title, initialHtml = document.content,
                readOnly = true, isEditing = false, modifier = Modifier.fillMaxSize(),
                onHtmlChanged = {}, resourceLoader = resourceLoader, serverUrl = serverUrl)
        } else {
            SourceNoteEditor(noteKey = key, title = revision.title, initialContent = document.content,
                mime = revision.mime, readOnly = true, isEditing = false,
                modifier = Modifier.fillMaxSize(), onContentChanged = {},
                resourceLoader = resourceLoader, serverUrl = serverUrl)
        }
    }
}

internal fun formatHistoryDate(value: String?, language: String): String? {
    if (value.isNullOrBlank()) return null
    return runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(Locale.forLanguageTag(language)).withZone(ZoneId.systemDefault())
            .format(Instant.parse(value.replaceFirst(' ', 'T')))
    }.getOrElse { value }
}
