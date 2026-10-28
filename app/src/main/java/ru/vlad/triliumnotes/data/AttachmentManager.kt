package ru.vlad.triliumnotes.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import java.io.File
import java.io.IOException

data class AttachmentManagerState(val pendingCount: Int = 0, val isSyncing: Boolean = false, val error: String? = null)

/** Account-scoped metadata cache and durable operations; rename never changes binary identity. */
class AttachmentManager(private val context: Context, private val api: TriliumApi, private val repository: NotesRepository) {
    private val file = AtomicFile(File(context.filesDir, "attachment-manager.json"))
    private val mutex = Mutex()
    private val syncMutex = Mutex()
    private val catalog = mutableMapOf<String, List<AttachmentMeta>>()
    private val pending = linkedMapOf<String, Operation>()
    // Superseded permanent deletions remain recoverable metadata, outside the runnable queue.
    private val retained = linkedMapOf<String, Operation>()
    private var storageError = false
    private val mutableState = MutableStateFlow(AttachmentManagerState())
    val state = mutableState.asStateFlow()
    @Volatile var syncGate: (suspend (suspend () -> Unit) -> Unit)? = null
    private data class Operation(val noteId: String, val attachmentId: String, val title: String?)

    init {
        try {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                val json = JSONObject(file.readFully().toString(Charsets.UTF_8))
                val cached = json.optJSONObject("catalog") ?: JSONObject()
                cached.keys().forEach { noteId ->
                    val items = cached.getJSONArray(noteId)
                    catalog[noteId] = (0 until items.length()).map { parse(items.getJSONObject(it)) }
                }
                readOperations(json.optJSONArray("pending"), pending)
                readOperations(json.optJSONArray("retained"), retained)
            }
        } catch (_: Exception) {
            // Preserve the damaged file for recovery; it must not prevent opening existing notes.
            storageError = true
            catalog.clear(); pending.clear(); retained.clear()
        }
        mutableState.value = AttachmentManagerState(activeCount(), error = storageMessage())
    }

    suspend fun refreshPendingCount(): Int = withContext(Dispatchers.IO) {
        mutex.withLock { activeCount().also { mutableState.value = mutableState.value.copy(pendingCount = it,
            error = if (it == 0) storageMessage() else mutableState.value.error) } }
    }

    private fun activeCount(): Int = pending.values.count { !repository.isRecoverablyDeleted(it.noteId) }
    private fun storageMessage(): String? = if (storageError)
        AppLanguage.get(context, R.string.editor_attachment_storage_error) else null

    suspend fun list(noteId: String): List<AttachmentMeta> = withContext(Dispatchers.IO) {
        require(!repository.isHidden(noteId) && !repository.isDeleted(noteId))
        try {
            val remote = api.getAttachments(noteId)
            require(remote.all { it.ownerId == noteId })
            repository.rememberAttachments(noteId, remote)
            mutex.withLock { catalog[noteId] = remote; if (!storageError) save() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            mutex.withLock {
                if (noteId !in catalog) {
                    val syncedCatalog = repository.cachedAttachments(noteId) ?: throw error
                    catalog[noteId] = syncedCatalog
                    if (!storageError) save()
                }
            }
        }
        mutex.withLock { overlay(noteId) }
    }

    suspend fun rename(noteId: String, attachmentId: String, title: String) = enqueue(noteId, attachmentId, uploadTitle(title))
    /** Caller confirms broken links in other notes and flushes/locks the active editor before enqueue. */
    suspend fun delete(noteId: String, attachmentId: String) = enqueue(noteId, attachmentId, null)

    private suspend fun enqueue(noteId: String, attachmentId: String, title: String?) = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            validate(noteId, attachmentId)
            require(repository.cachedNote(noteId)?.let { it.note.isEditable && !it.conflict } == true)
            mutex.withLock {
                require(catalog[noteId].orEmpty().any { it.id == attachmentId && it.ownerId == noteId })
                val previous = pending[attachmentId]
                pending[attachmentId] = Operation(noteId, attachmentId, title)
                try { save() } catch (error: Throwable) {
                    if (previous == null) pending.remove(attachmentId) else pending[attachmentId] = previous
                    throw error
                }
                mutableState.value = mutableState.value.copy(pendingCount = activeCount(), error = null)
            }
            // The outbox is committed before the reference edit, making crash recovery idempotent.
            repository.rewriteAttachmentReferences(noteId, attachmentId, title)
        }
    }

    /** Each replay is guarded from editor handoff through reference sync and remote metadata mutation. */
    suspend fun syncPending(): Int = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val operations = mutex.withLock { pending.values.toList() }
            var failed = false
            mutableState.value = AttachmentManagerState(activeCount(), isSyncing = true, error = storageMessage())
            try {
                for (op in operations) {
                    if (repository.isRecoverablyDeleted(op.noteId)) continue
                    try {
                        val replay: suspend () -> Unit = replay@{
                            if (repository.isRecoverablyDeleted(op.noteId)) return@replay
                            if (repository.isDeleted(op.noteId)) { retain(op); return@replay }
                            if (repository.isHidden(op.noteId)) return@replay
                            val document = repository.cachedNote(op.noteId) ?: return@replay
                            if (document.remoteMissing) { retain(op); return@replay }
                            if (document.conflict) return@replay
                            repository.rewriteAttachmentReferences(op.noteId, op.attachmentId, op.title)
                            repository.syncPending()
                            if (repository.hasPendingEdits(op.noteId)) return@replay
                            val synced = repository.cachedNote(op.noteId) ?: throw AttachmentInsertionDeferred()
                            if (op.title == null && containsAttachmentReference(synced.html, op.attachmentId,
                                    synced.note.mime.contains("markdown", true)))
                                throw IOException("Attachment still has references in the owner note")
                            val remoteCatalog = api.getAttachments(op.noteId)
                            repository.rememberAttachments(op.noteId, remoteCatalog)
                            val remote = remoteCatalog.firstOrNull { it.id == op.attachmentId }
                            if (remote != null) {
                                require(remote.ownerId == op.noteId)
                                if (op.title == null) api.etapiRequest("DELETE", listOf("attachments", op.attachmentId))
                                else api.patchAttachmentTitle(op.attachmentId, op.title)
                            } else if (op.title != null) throw IOException("Attachment is missing")
                            val confirmed = api.getAttachments(op.noteId)
                            require(confirmed.all { it.ownerId == op.noteId })
                            repository.rememberAttachments(op.noteId, confirmed)
                            mutex.withLock {
                                if (pending[op.attachmentId] == op) {
                                    val oldCatalog = catalog[op.noteId]
                                    catalog[op.noteId] = confirmed
                                    pending.remove(op.attachmentId)
                                    try { save() } catch (error: Throwable) {
                                        if (oldCatalog == null) catalog.remove(op.noteId) else catalog[op.noteId] = oldCatalog
                                        pending[op.attachmentId] = op
                                        throw error
                                    }
                                }
                            }
                        }
                        val gate = syncGate
                        if (gate == null) replay() else gate(replay)
                    } catch (_: AttachmentInsertionDeferred) {
                        // An active editor owns a newer buffer; retry after its normal flush.
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                }
            } finally {
                withContext(NonCancellable) { mutex.withLock {
                    mutableState.value = AttachmentManagerState(activeCount(), error = storageMessage() ?: if (failed)
                        AppLanguage.get(context, R.string.editor_attachment_retry) else null)
                } }
            }
            mutex.withLock { activeCount() }
        }
    }

    private suspend fun retain(op: Operation) = mutex.withLock {
        if (pending[op.attachmentId] != op) return@withLock
        pending.remove(op.attachmentId); retained[op.attachmentId] = op
        try { save() } catch (error: Throwable) {
            retained.remove(op.attachmentId); pending[op.attachmentId] = op; throw error
        }
    }

    suspend fun pendingCount(): Int = mutex.withLock { activeCount() }

    private fun overlay(noteId: String): List<AttachmentMeta> = catalog[noteId].orEmpty().mapNotNull { item ->
        val operation = pending[item.id]
        if (operation == null) item else operation.title?.let { item.copy(title = it) }
    }.sortedWith(compareBy<AttachmentMeta> { it.position }.thenBy { it.title.lowercase() })

    private fun validate(noteId: String, attachmentId: String) {
        require(noteId.matches(Regex("[A-Za-z0-9_]{4,32}")) && attachmentId.matches(Regex("[A-Za-z0-9_]{4,32}")))
    }

    private fun readOperations(array: JSONArray?, destination: MutableMap<String, Operation>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val json = array.getJSONObject(i)
            val op = Operation(json.getString("noteId"), json.getString("id"), if (json.isNull("title")) null else json.getString("title"))
            validate(op.noteId, op.attachmentId)
            destination[op.attachmentId] = op
        }
    }

    private fun operationsJson(operations: Collection<Operation>) = JSONArray(operations.map {
        JSONObject().put("noteId", it.noteId).put("id", it.attachmentId).put("title", it.title ?: JSONObject.NULL)
    })

    private fun save() {
        if (storageError) throw IOException("Attachment operations file needs recovery")
        val cached = JSONObject()
        catalog.forEach { (note, items) -> cached.put(note, JSONArray(items.map { item -> JSONObject()
            .put("id", item.id).put("owner", item.ownerId).put("role", item.role).put("mime", item.mime)
            .put("title", item.title).put("position", item.position).put("blobId", item.blobId) })) }
        val json = JSONObject().put("catalog", cached).put("pending", operationsJson(pending.values))
            .put("retained", operationsJson(retained.values))
        val output = file.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }

    private fun parse(json: JSONObject): AttachmentMeta = AttachmentMeta(json.getString("id"), json.getString("owner"),
        json.getString("role"), json.getString("mime"), json.getString("title"), json.getInt("position"),
        json.optString("blobId").takeUnless { it.isBlank() || it == "null" }).also { validate(it.ownerId, it.id) }
}
