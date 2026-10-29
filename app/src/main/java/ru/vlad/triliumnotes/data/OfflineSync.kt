package ru.vlad.triliumnotes.data

import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import android.content.Context
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import ru.vlad.triliumnotes.diagrams.DiagramPackages

data class OfflineState(
    val isRunning: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val resources: Int = 0,
    val error: String? = null,
    val complete: Boolean = false,
    val available: Boolean = false,
    val packagesOnly: Boolean = false,
)

/** One sequential crawl shared by the foreground and the background worker. */
class OfflineSync(
    private val repository: NotesRepository,
    private val api: TriliumApi,
    private val resources: NoteResources,
    private val context: Context,
    private val diagrams: DiagramPackages = DiagramPackages.get(context),
) {
    private fun text(id: Int) = AppLanguage.get(context, id)
    private val mutex = Mutex()
    private fun missingPackages() = diagrams.needsFor(repository.cachedVisibleNotes()).filterNot { diagrams.isReady(it) }
    private fun copyAvailable() = repository.offlineCopyAvailable && missingPackages().isEmpty()
    private fun packageUpgradeNeeded() = repository.offlineCopyAvailable && missingPackages().isNotEmpty()
    private val mutable = MutableStateFlow(OfflineState(available = copyAvailable(), packagesOnly = packageUpgradeNeeded()))
    val state = mutable.asStateFlow()

    /** Recheck local files on resume without refetching notes or contacting the package host. */
    fun refreshReadiness() {
        if (state.value.isRunning) return
        val previous = state.value
        val missing = packageUpgradeNeeded()
        val available = copyAvailable()
        val recovered = previous.packagesOnly && available
        // A viewer can finish installing the only missing part of an offline copy.
        // Keep genuine note/resource failures visible and scheduled for a full retry.
        mutable.value = previous.copy(available = available,
            packagesOnly = missing && (previous.error == null || previous.packagesOnly),
            complete = if (recovered) true else previous.complete,
            error = if (recovered) null else previous.error)
    }

    suspend fun refreshAll(packagesOnly: Boolean = false): OfflineState {
        if (!mutex.tryLock()) return state.value
        mutable.value = OfflineState(isRunning = true, total = 1, available = copyAvailable(), packagesOnly = packageUpgradeNeeded())
        var notesPhaseComplete = packagesOnly && repository.offlineCopyAvailable
        try {
            // Old APKs saved a complete note copy while keeping renderers inside the APK.
            // Upgrading that copy needs only the missing packages, not another ETAPI crawl.
            if (packagesOnly && repository.offlineCopyAvailable) {
                for (kind in missingPackages()) diagrams.ensure(kind)
                mutable.value = OfflineState(complete = true, available = copyAvailable())
                return state.value
            }
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
                            packagesOnly = false, error = text(R.string.msg_offline_disconnected))
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
            // This marker records the note/resource copy. Public readiness below also
            // requires every renderer, allowing a failed package to retry on its own.
            if (failures == 0) {
                repository.markOfflineCopyAvailable()
                notesPhaseComplete = true
            }
            for (kind in diagrams.needsFor(repository.cachedVisibleNotes())) {
                try { diagrams.ensure(kind) }
                catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failures++
                }
            }
            mutable.value = mutable.value.copy(isRunning = false, complete = failures == 0, available = copyAvailable(),
                packagesOnly = notesPhaseComplete && packageUpgradeNeeded(),
                error = if (failures == 0) null else text(R.string.msg_offline_partial))
            return state.value
        } catch (cancelled: CancellationException) {
            mutable.value = mutable.value.copy(isRunning = false, complete = false,
                available = copyAvailable(), packagesOnly = notesPhaseComplete && packageUpgradeNeeded())
            throw cancelled
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(isRunning = false, complete = false,
                available = copyAvailable(), packagesOnly = notesPhaseComplete && packageUpgradeNeeded(), error = text(R.string.msg_offline_failed))
            return state.value
        } finally {
            mutex.unlock()
        }
    }
}
