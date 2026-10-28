package ru.vlad.triliumnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import ru.vlad.triliumnotes.data.*

@Composable
fun NoteDiffView(before: RevisionDocument, current: NoteDocument, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var result by remember(before, current.html) { mutableStateOf<NoteDiff?>(null) }
    LaunchedEffect(before, current.html) {
        result = withContext(Dispatchers.Default) { compareNoteContent(before.content, current.html, before.revision.mime) }
    }
    val diff = result
    if (diff == null) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val colors = MaterialTheme.colorScheme
    LazyColumn(modifier.testTag("note-version-diff"), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Text(AppLanguage.get(context, R.string.diff_direction), Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        if (before.revision.title != current.note.title) {
            item { DiffRow(DiffLine(DiffKind.REMOVED, before.revision.title)) }
            item { DiffRow(DiffLine(DiffKind.ADDED, current.note.title)) }
        }
        when {
            diff.tooLarge -> item { Text(AppLanguage.get(context, R.string.diff_too_large), Modifier.padding(20.dp)) }
            diff.formattingOnly -> item { Text(AppLanguage.get(context, R.string.diff_formatting), Modifier.padding(20.dp)) }
            diff.lines.all { it.kind == DiffKind.SAME } && before.revision.title == current.note.title ->
                item { Text(AppLanguage.get(context, R.string.diff_unchanged), Modifier.padding(20.dp)) }
        }
        itemsIndexed(diff.lines) { index, line -> DiffRow(line, Modifier.testTag("diff-line-$index")) }
    }
}

@Composable
private fun DiffRow(line: DiffLine, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val background = when (line.kind) {
        DiffKind.ADDED -> if (dark) Color(0xFF173E2B) else Color(0xFFE0F3E6)
        DiffKind.REMOVED -> colors.errorContainer
        DiffKind.SAME -> colors.surface
    }
    val foreground = when (line.kind) {
        DiffKind.ADDED -> if (dark) Color(0xFFBEF2CE) else Color(0xFF174C2C)
        DiffKind.REMOVED -> colors.onErrorContainer
        DiffKind.SAME -> colors.onSurface
    }
    Row(modifier.fillMaxWidth().background(background).padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(when (line.kind) { DiffKind.ADDED -> "+"; DiffKind.REMOVED -> "−"; DiffKind.SAME -> " " },
            Modifier.width(24.dp), color = foreground)
        SelectionContainer { Text(line.text.ifEmpty { " " }, color = foreground, style = MaterialTheme.typography.bodyMedium) }
    }
}
