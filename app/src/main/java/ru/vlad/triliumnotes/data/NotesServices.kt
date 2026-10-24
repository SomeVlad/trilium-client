package ru.vlad.triliumnotes.data

import android.content.Context
import ru.vlad.triliumnotes.BuildConfig

/** A process-wide store: foreground edits and background sync must share the same snapshot. */
class NotesServices internal constructor(context: Context, val api: TriliumApi, serverUrl: String) {
    val repository = NotesRepository(context, api)
    val resources = NoteResources(context, api, serverUrl)
    val offline = OfflineSync(repository, api, resources)

    companion object {
        @Volatile private var instance: NotesServices? = null

        fun get(context: Context): NotesServices = instance ?: synchronized(this) {
            instance ?: NotesServices(
                context.applicationContext,
                TriliumApi(BuildConfig.TRILIUM_BASE_URL, BuildConfig.TRILIUM_TOKEN),
                BuildConfig.TRILIUM_BASE_URL,
            ).also { instance = it }
        }
    }
}
