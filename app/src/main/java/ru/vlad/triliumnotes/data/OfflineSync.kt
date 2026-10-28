package ru.vlad.triliumnotes.data

import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import android.content.Context
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

data class OfflineState(
    val isRunning: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val resources: Int = 0,
    val error: String? = null,
    val complete: Boolean = false,
    val available: Boolean = false,
)

/** One sequential crawl shared by the foreground and the background worker. */
class OfflineSync(
    private val repository: NotesRepository,
    private val api: TriliumApi,
    private val resources: NoteResources,
    private val context: Context,
) {
    private fun text(id: Int) = AppLanguage.get(context, id)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(OfflineState(available = repository.offlineCopyAvailable))
    val state = mutable.asStateFlow()

    suspend fun refreshAll(): OfflineState {
        if (!mutex.tryLock()) return state.value
        mutable.value = OfflineState(isRunning = true, total = 1, available = repository.offlineCopyAvailable)
        try {
            val sync = repository.syncPending()
            var failures = if (sync.error != null) 1 else 0
            val queue = ArrayDeque<String>().apply { add("root") }
            val discovered = mutableSetOf("root")
            val validatedAttachmentIds = mutableSetOf<String>()
            val inlineBodies = mutableListOf<Pair<String, Boolean>>()
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                if (repository.isHidden(id)) continue
                try {
                    val tree = repository.listChildrenResult(id)
                    if (tree.fromCache) {
                        mutable.value = mutable.value.copy(isRunning = false, complete = false,
                            error = text(R.string.msg_offline_disconnected))
                        return state.value
                    }
                    if (tree.failedBranchIds.isNotEmpty()) failures++
                    val note = tree.parent
                    tree.items.forEach { item ->
                        if (!repository.isHidden(item.note) && discovered.add(item.note.id)) queue.add(item.note.id)
                    }
                    mutable.value = mutable.value.copy(total = discovered.size)
                    val document = repository.prefetch(note)
                        ?: throw IOException("This note has not been saved for offline access yet")
                    if (!note.isProtected) {
                        if (note.type in setOf("image", "file") || !note.hasTextContent) {
                            resources.prefetchNote(note)
                            mutable.value = mutable.value.copy(resources = state.value.resources + 1)
                        }
                        // Attachments can exist even when they are not linked from the body.
                        val catalog = api.getAttachments(id)
                        repository.rememberAttachments(id, catalog)
                        catalog.forEach { attachment ->
                            resources.prefetchAttachment(attachment, note)
                            validatedAttachmentIds += attachment.id
                            mutable.value = mutable.value.copy(resources = state.value.resources + 1)
                        }
                        if (note.hasTextContent) {
                            inlineBodies += document.html to note.mime.substringBefore(';').trim().lowercase().let {
                                it.startsWith("text/") && it.endsWith("markdown")
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failures++
                }
                mutable.value = mutable.value.copy(completed = state.value.completed + 1)
            }
            // Resolve cross-note references after every owner's catalog has validated
            // its attachment versions, so inline TTLs cannot redownload unchanged bytes.
            for ((body, markdown) in inlineBodies) {
                try {
                    val count = resources.prefetchInline(body, validatedAttachmentIds, markdown)
                    mutable.value = mutable.value.copy(resources = state.value.resources + count)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failures++
                }
            }
            if (failures == 0) repository.markOfflineCopyAvailable()
            mutable.value = mutable.value.copy(isRunning = false, complete = failures == 0, available = repository.offlineCopyAvailable,
                error = if (failures == 0) null else text(R.string.msg_offline_partial))
            return state.value
        } catch (cancelled: CancellationException) {
            mutable.value = mutable.value.copy(isRunning = false, complete = false)
            throw cancelled
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(isRunning = false, complete = false,
                error = text(R.string.msg_offline_failed))
            return state.value
        } finally {
            mutex.unlock()
        }
    }
}
