package ru.vlad.triliumnotes.editor

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R

/** UI state only: finding and navigation never write the note's source. */
@Stable
class NoteSearchState {
    var query by mutableStateOf("")
    var matchCount by mutableStateOf(0)
        private set
    /** Zero based; -1 when there are no results. */
    var currentMatch by mutableStateOf(-1)
        private set
    internal var navigation: ((Boolean) -> Unit)? = null

    fun next() = move(true)
    fun previous() = move(false)

    private fun move(forward: Boolean) {
        if (matchCount == 0) return
        navigation?.let { it(forward); return }
        currentMatch = Math.floorMod(currentMatch + if (forward) 1 else -1, matchCount)
    }

    internal fun updateResults(count: Int, current: Int = if (count > 0) 0 else -1) {
        matchCount = count
        currentMatch = if (count > 0) current.coerceIn(0, count - 1) else -1
    }
}

@Composable
fun rememberNoteSearchState(noteKey: String): NoteSearchState = remember(noteKey) { NoteSearchState() }

@Composable
fun NoteSearchBar(state: NoteSearchState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = state.query, onValueChange = { state.query = it }, singleLine = true,
                placeholder = { Text(AppLanguage.get(context, R.string.note_search_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { state.next() }),
                modifier = Modifier.weight(1f).focusRequester(focus).testTag("note-search-query"),
            )
            Text(AppLanguage.get(context, R.string.note_search_count, state.currentMatch + 1, state.matchCount),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 6.dp).testTag("note-search-count"))
            IconButton(onClick = state::previous, enabled = state.matchCount > 0) {
                Icon(Icons.Default.KeyboardArrowUp, AppLanguage.get(context, R.string.note_search_previous))
            }
            IconButton(onClick = state::next, enabled = state.matchCount > 0) {
                Icon(Icons.Default.KeyboardArrowDown, AppLanguage.get(context, R.string.note_search_next))
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, AppLanguage.get(context, R.string.note_search_close))
            }
        }
    }
}

/** Literal, case-insensitive matching keeps offsets in the original UTF-16 text. */
internal fun noteSearchMatches(content: String, query: String): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val matches = mutableListOf<IntRange>()
    var offset = 0
    while (offset <= content.length - query.length) {
        val start = content.indexOf(query, startIndex = offset, ignoreCase = true)
        if (start < 0) break
        matches += start until start + query.length
        offset = start + query.length
    }
    return matches
}
