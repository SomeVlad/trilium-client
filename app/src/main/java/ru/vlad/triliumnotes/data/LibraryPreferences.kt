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
)

/** Device preferences scoped to the same server account as the note cache. */
class LibraryPreferences(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "library-preferences.json"))
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()

    suspend fun toggleFavorite(id: String) = update {
        it.copy(favorites = if (id in it.favorites) it.favorites - id else it.favorites + id)
    }
    suspend fun opened(id: String) = update { it.copy(recent = (listOf(id) + it.recent.filterNot { old -> old == id }).take(100)) }
    suspend fun sort(folder: String, order: NoteSort) = update { it.copy(sorts = it.sorts + (folder to order)) }

    private suspend fun update(transform: (LibraryPreferencesState) -> LibraryPreferencesState) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = transform(mutable.value)
            if (next == mutable.value) return@withLock
            val json = JSONObject().put("favorites", JSONArray(next.favorites.toList()))
                .put("recent", JSONArray(next.recent)).put("sorts", JSONObject(next.sorts.mapValues { it.value.name }))
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
            sorts.keys().asSequence().mapNotNull { key -> runCatching { key to NoteSort.valueOf(sorts.getString(key)) }.getOrNull() }.toMap())
    }.getOrDefault(LibraryPreferencesState())
}
