package ru.vlad.triliumnotes.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class NoteSort { MANUAL, TITLE, MODIFIED }
enum class LibrarySection { NOTES, FAVORITES, RECENT }
data class LibraryPreferencesState(
    val favorites: Set<String> = emptySet(),
    val recent: List<String> = emptyList(),
    val sorts: Map<String, NoteSort> = emptyMap(),
    val pinned: Set<String> = emptySet(),
)

/** Reorder existing folder rows only; a pin follows the note across its branches. */
fun sortTreeItems(items: List<TreeItem>, sort: NoteSort, pinned: Set<String> = emptySet()): List<TreeItem> {
    val ordered = when (sort) {
        NoteSort.MANUAL -> items
        NoteSort.TITLE -> items.sortedWith(compareBy<TreeItem> { it.note.title.lowercase() }.thenBy { it.branchId })
        NoteSort.MODIFIED -> items.sortedWith(compareByDescending<TreeItem> { it.note.modifiedAt.orEmpty() }.thenBy { it.branchId })
    }
    return ordered.filter { it.note.id in pinned } + ordered.filterNot { it.note.id in pinned }
}

/** Persist within-group moves while preserving the original slots hidden by pinned-first display. */
fun serializeReorderedGroups(original: List<TreeItem>, reordered: List<TreeItem>, pinned: Set<String>): List<String> {
    val pinnedRows = reordered.filter { it.note.id in pinned }.iterator()
    val ordinaryRows = reordered.filterNot { it.note.id in pinned }.iterator()
    return original.map { item ->
        (if (item.note.id in pinned) pinnedRows.next() else ordinaryRows.next()).branchId
    }
}

/** Device preferences scoped to the same server account as the note cache. */
class LibraryPreferences(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "library-preferences.json"))
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()

    suspend fun toggleFavorite(id: String) = update {
        it.copy(favorites = if (id in it.favorites) it.favorites - id else it.favorites + id)
    }
    suspend fun togglePinned(id: String) = update {
        it.copy(pinned = if (id in it.pinned) it.pinned - id else it.pinned + id)
    }
    suspend fun opened(id: String) = update { it.copy(recent = (listOf(id) + it.recent.filterNot { old -> old == id }).take(100)) }
    suspend fun sort(folder: String, order: NoteSort) = update { it.copy(sorts = it.sorts + (folder to order)) }

    private suspend fun update(transform: (LibraryPreferencesState) -> LibraryPreferencesState) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = transform(mutable.value)
            if (next == mutable.value) return@withLock
            val json = JSONObject().put("favorites", JSONArray(next.favorites.toList()))
                .put("recent", JSONArray(next.recent)).put("sorts", JSONObject(next.sorts.mapValues { it.value.name }))
                .put("pinned", JSONArray(next.pinned.toList()))
            val output = file.startWrite()
            try { output.write(json.toString().toByteArray()); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
            mutable.value = next
        }
    }

    private fun read(): LibraryPreferencesState = runCatching {
        val json = JSONObject(file.readFully().toString(Charsets.UTF_8))
        fun strings(key: String) = json.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val sorts = json.optJSONObject("sorts") ?: JSONObject()
        LibraryPreferencesState(strings("favorites").toSet(), strings("recent").distinct().take(100),
            sorts.keys().asSequence().mapNotNull { key -> runCatching { key to NoteSort.valueOf(sorts.getString(key)) }.getOrNull() }.toMap(),
            strings("pinned").toSet())
    }.getOrDefault(LibraryPreferencesState())
}
