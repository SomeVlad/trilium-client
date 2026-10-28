package ru.vlad.triliumnotes.data

import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

data class SyncState(
    val pendingCount: Int = 0,
    val isSyncing: Boolean = false,
    val conflicts: Set<String> = emptySet(),
    val error: String? = null,
)

data class SearchResult(val notes: List<NoteMeta>, val fromCache: Boolean = false)

class NotesRepository(private val context: Context, private val api: TriliumApi) {
    private fun text(id: Int, vararg args: Any) = AppLanguage.get(context, id, *args)
    private val file = AtomicFile(File(context.filesDir, "trilium-cache.json"))
    private val offlineMarker = File(context.filesDir, "offline-copy-complete")
    val offlineCopyAvailable: Boolean get() = cacheError == null && file.baseFile.isFile && offlineMarker.isFile

    suspend fun markOfflineCopyAvailable() = withContext(Dispatchers.IO) {
        stateMutex.withLock {
            val marker = AtomicFile(offlineMarker)
            val output = marker.startWrite()
            try {
                output.write("1".toByteArray())
                marker.finishWrite(output)
            } catch (error: Exception) {
                marker.failWrite(output)
                throw error
            }
        }
    }
    private val stateMutex = Mutex()
    private val syncMutex = Mutex()

    private val cacheRead = runCatching { readStore() }
    private val cacheError = if (cacheRead.isFailure) text(R.string.msg_cache_damaged) else null
    @Volatile private var store: Store = cacheRead.getOrDefault(Store())
    @Volatile private var activeDocumentId: String? = null
    fun pinDocument(noteId: String?) { activeDocumentId = noteId }
    private val mutableSyncState = MutableStateFlow(store.toSyncState(error = cacheError))
    val syncState: StateFlow<SyncState> = mutableSyncState

    fun cachedChildren(parentId: String): List<TreeItem>? {
        val snapshot = store
        val visible = visibility(snapshot)
        val deleted = deletionClosure(snapshot)
        if (parentId in deleted || visible.isHidden(parentId)) return emptyList()
        val cached = snapshot.children[parentId]
        val hasLocalCreate = snapshot.pending.values.any {
            it.kind == "create" && it.parentId == parentId
        }
        return if (cached != null || hasLocalCreate)
            withDrafts(snapshot, parentId, cached.orEmpty()).filterNot { it.note.id in deleted || visible.isHidden(it.note.id) } else null
    }

    fun hasPendingEdits(noteId: String): Boolean = store.pending[noteId]?.kind in setOf("create", "update", "copy-update", "restore")
    fun cachedVisibleNotes(): List<NoteMeta> {
        val snapshot = store
        val visible = visibility(snapshot)
        val deleted = deletionClosure(snapshot)
        return snapshot.notes.values.asSequence().map { it.meta }
            .filterNot { it.isProtected || it.id in deleted || visible.isHidden(it.id) }
            .map { note -> snapshot.pending[note.id]?.let { note.copy(title = it.title) } ?: note }.toList()
    }
    fun cachedVisibleDocuments(): List<NoteDocument> {
        val snapshot = store
        val visible = visibility(snapshot)
        val deleted = deletionClosure(snapshot)
        return snapshot.notes.values.mapNotNull { stored ->
            if (stored.html == null || stored.meta.isProtected || stored.meta.id in deleted || visible.isHidden(stored.meta.id)) null
            else {
                val pending = snapshot.pending[stored.meta.id]
                NoteDocument(if (pending == null) stored.meta else stored.meta.copy(title = pending.title),
                    pending?.html ?: stored.html, pending != null, pending?.conflict == true, pending?.remoteMissing == true)
            }
        }
    }

    /** New trash pauses resource work; legacy irreversible tombstones retain their old handling. */
    fun isRecoverablyDeleted(noteId: String): Boolean {
        val snapshot = store
        val recoverableRoots = snapshot.trash.keys.intersect(snapshot.deleted)
        if (recoverableRoots.isEmpty() || noteId !in deletionClosure(snapshot)) return false
        return noteId !in deletionClosure(snapshot, seeds = snapshot.deleted - recoverableRoots)
    }

    fun isDeleted(noteId: String): Boolean = noteId in deletionClosure(store)
    internal fun isLocalCreate(noteId: String): Boolean = store.pending[noteId]?.kind == "create"
    fun isHidden(noteId: String): Boolean = isDeleted(noteId) || visibility(store).isHidden(noteId)
    fun isHidden(note: NoteMeta): Boolean {
        val snapshot = store
        return note.id in deletionClosure(snapshot, extra = listOf(note)) || visibility(snapshot, listOf(note)).isHidden(note.id)
    }

    fun cachedNote(noteId: String): NoteDocument? = if (isHidden(noteId)) null else document(store, noteId)

    fun cachedMeta(noteId: String): NoteMeta? = if (isHidden(noteId)) null else store.notes[noteId]?.meta

    suspend fun listChildren(parentId: String): List<TreeItem> {
        return listChildrenResult(parentId).items
    }

    suspend fun listChildrenResult(parentId: String): ChildrenResult {
        if (isDeleted(parentId)) throw ApiException(404)
        if (isHidden(parentId)) throw HiddenNoteException()
        return try {
            val result = api.getChildrenResult(parentId)
            rememberVisibilityMetadata(listOf(result.parent) + result.items.map { it.note })
            if (isHidden(parentId)) throw HiddenNoteException()
            val retained = store.children[parentId].orEmpty().filter { it.branchId in result.failedBranchIds }
            val fetched = (result.items + retained).sortedWith(compareBy<TreeItem> { it.position }.thenBy { it.branchId })
            change { next ->
                if (parentId in next.deleted) return@change
                val available = fetched.filterNot { it.note.id in next.deleted }
                if (next.notes[parentId]?.html == null) next.notes[parentId] = StoredNote(result.parent, null)
                next.children[parentId] = overlayTree(next, parentId, available)
                if (result.failedBranchIds.isEmpty()) next.completeChildren += parentId
                available.forEach { item ->
                    val old = next.notes[item.note.id]
                    // A metadata-only tree refresh cannot update the edit baseline of
                    // a cached document without fetching its content at the same time.
                    if (old == null) next.notes[item.note.id] = StoredNote(item.note, null)
                }
            }
            val currentVisibility = visibility(store)
            val hiddenBranches = fetched.filter { currentVisibility.isHidden(it.note.id) }.mapTo(mutableSetOf()) { it.branchId }
            result.copy(items = cachedChildren(parentId).orEmpty(),
                failedBranchIds = result.failedBranchIds.filterNot { it in hiddenBranches })
        } catch (error: Exception) {
            if (error is CancellationException || error is HiddenNoteException) throw error
            val cached = cachedChildren(parentId) ?: throw offlineError(error)
            val parent = cachedMeta(parentId) ?: NoteMeta(parentId, "", "", "", false,
                null, null, cached.map { it.branchId })
            ChildrenResult(parent, cached, fromCache = true)
        }
    }

    suspend fun load(noteId: String): NoteDocument {
        if (isDeleted(noteId)) throw ApiException(404)
        if (isHidden(noteId)) throw HiddenNoteException()
        val baseline = store.notes[noteId]
        return try {
            val meta = api.getNote(noteId)
            rememberVisibilityMetadata(listOf(meta))
            if (isHidden(meta)) throw HiddenNoteException()
            val html = when {
                meta.isProtected -> ""
                meta.type == "doc" && meta.docName != null -> api.getDocContent(meta.docName)
                meta.hasTextContent -> api.getContent(noteId)
                else -> ""
            }
            change { next ->
                // An edit can become durable while the network request is in flight.
                // Its original server version remains the conflict baseline.
                if (noteId !in next.deleted && noteId !in next.pending && sameBaseline(baseline, next.notes[noteId])) {
                    next.notes[noteId] = StoredNote(meta, html)
                }
            }
            if (isDeleted(noteId)) throw ApiException(404)
            requireNotNull(cachedNote(noteId))
        } catch (error: Exception) {
            if (error is CancellationException || error is HiddenNoteException) throw error
            cachedNote(noteId) ?: throw offlineError(error)
        }
    }

    /** Strict background fetch: preserve pending edits and reuse unchanged bodies. */
    suspend fun prefetch(note: NoteMeta): NoteDocument? {
        if (isHidden(note)) return null
        if (isDeleted(note.id)) rememberVisibilityMetadata(listOf(note))
        if (note.id == activeDocumentId) return cachedNote(note.id)
        val snapshot = store
        val old = snapshot.notes[note.id]
        if (note.id in snapshot.pending) return document(snapshot, note.id)
        if ((note.blobId != null || note.modifiedAt != null) && old?.html != null && old.meta.blobId == note.blobId &&
            old.meta.modifiedAt == note.modifiedAt && old.meta.type == note.type &&
            old.meta.mime == note.mime && old.meta.isProtected == note.isProtected) {
            return document(snapshot, note.id)
        }
        val html = when {
            note.isProtected -> ""
            note.type == "doc" && note.docName != null -> api.getDocContent(note.docName)
            note.hasTextContent -> api.getContent(note.id)
            else -> ""
        }
        change { next ->
            if (note.id !in next.deleted && note.id != activeDocumentId && note.id !in next.pending && sameBaseline(old, next.notes[note.id])) {
                next.notes[note.id] = StoredNote(note, html)
            }
        }
        return cachedNote(note.id)
    }

    private fun sameBaseline(before: StoredNote?, current: StoredNote?): Boolean {
        // Metadata-only discovery may add a previously unknown note or update its
        // parentage. A concurrently confirmed edit changes the body or baseline.
        if (before == null) return current?.html == null
        if (current == null || before.html != current.html) return false
        return before.meta.copy(parentNoteIds = emptyList()) == current.meta.copy(parentNoteIds = emptyList())
    }

    suspend fun search(query: String): List<NoteMeta> = searchResult(query).notes

    suspend fun searchResult(query: String, cacheOnly: Boolean = false): SearchResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return SearchResult(emptyList())
        if (cacheOnly) return cachedSearch(trimmed)
        return try {
            val results = api.search(trimmed)
            rememberVisibilityMetadata(results)
            val snapshot = store
            val merged = results.map { meta -> snapshot.pending[meta.id]?.let { meta.copy(title = it.title) } ?: meta }
                .associateByTo(linkedMapOf()) { it.id }
            snapshot.pending.values.filter { draft ->
                draft.title.contains(trimmed, ignoreCase = true) || draft.html.contains(trimmed, ignoreCase = true)
            }.forEach { draft ->
                snapshot.notes[draft.noteId]?.let { merged[draft.noteId] = it.meta.copy(title = draft.title) }
            }
            val visible = visibility(snapshot, results)
            val deleted = deletionClosure(snapshot)
            SearchResult(merged.values.filterNot { it.id in deleted || visible.isHidden(it.id) })
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            cachedSearch(trimmed)
        }
    }

    private fun cachedSearch(query: String): SearchResult {
        val snapshot = store
        val visible = visibility(snapshot)
        val deleted = deletionClosure(snapshot)
        return SearchResult(snapshot.notes.values.asSequence()
                .filter { note ->
                    val draft = snapshot.pending[note.meta.id]
                    (draft?.title ?: note.meta.title).contains(query, ignoreCase = true) ||
                        (draft?.html ?: note.html)?.contains(query, ignoreCase = true) == true
                }
                .map { note ->
                    snapshot.pending[note.meta.id]?.let { note.meta.copy(title = it.title) } ?: note.meta
                }
                .filterNot { it.id in deleted || visible.isHidden(it.id) }
                .toList(), fromCache = true)
    }

    /** Returns only after the complete draft and outbox entry are on disk. */
    suspend fun saveDraft(noteId: String, title: String, html: String): NoteDocument {
        change { next ->
            require(noteId !in deletionClosure(next)) { "Note is deleted" }
            val stored = next.notes[noteId] ?: error("Open the note first")
            require(stored.meta.isEditable) {
                "This note cannot be edited"
            }
            require(stored.html != null) { "Load the note content first" }
            val old = next.pending[noteId]
            next.counter++
            next.pending[noteId] = Pending(
                kind = old?.kind ?: "update",
                noteId = noteId,
                branchId = old?.branchId,
                parentId = old?.parentId ?: stored.meta.parentNoteIds.firstOrNull(),
                title = title,
                html = html,
                // A create baseline is an attempted-POST marker. Typing into
                // an unsent local note must not manufacture that marker.
                baseTitle = if (old?.kind == "create") old.baseTitle else old?.baseTitle ?: stored.meta.title,
                baseHash = if (old?.kind == "create") old.baseHash else old?.baseHash ?: digest(stored.html),
                revision = next.counter,
                conflict = old?.conflict ?: false,
                noteType = old?.noteType ?: stored.meta.type,
                noteMime = old?.noteMime ?: stored.meta.mime,
                remoteMissing = old?.remoteMissing ?: false,
            )
        }
        return requireNotNull(document(store, noteId))
    }

    /** Merge into the current durable draft under the same lock as normal edits. */
    suspend fun appendAttachmentReference(upload: PendingAttachmentUpload, attachment: AttachmentMeta): NoteDocument {
        require(attachment.ownerId == upload.noteId && upload.contentUploaded)
        change { next ->
            val noteId = upload.noteId
            require(noteId !in deletionClosure(next)) { "Note is deleted" }
            val stored = next.notes[noteId] ?: throw IOException("Open the note first")
            require(stored.meta.isEditable)
            val old = next.pending[noteId]
            if (old?.conflict == true) throw AttachmentInsertionDeferred()
            val body = old?.html ?: stored.html ?: throw IOException("Load the note content first")
            // A crash can happen after this write and before the upload entry is removed.
            // Both native and legacy Trilium URLs include the attachment ID as a segment.
            val marker = Regex("(?:api/attachments/(?:[A-Za-z0-9_]{4,32}/)?|etapi/attachments/)${Regex.escape(attachment.id)}(?:/|[\\\"'<>\\s)])")
            if (marker.containsMatchIn(body)) return@change
            val markdown = stored.meta.mime.substringBefore(';').trim().lowercase().let {
                it.startsWith("text/") && it.endsWith("markdown")
            }
            val updated = if (upload.insertionAnchor == null) body + attachmentReference(upload, attachment, markdown)
                else replaceAttachmentPlaceholder(body, upload, attachment, markdown) ?: throw AttachmentInsertionDeferred()
            next.counter++
            next.pending[noteId] = Pending(
                kind = old?.kind ?: "update", noteId = noteId,
                branchId = old?.branchId,
                parentId = old?.parentId ?: stored.meta.parentNoteIds.firstOrNull(),
                title = old?.title ?: stored.meta.title,
                html = updated,
                baseTitle = if (old?.kind == "create") old.baseTitle else old?.baseTitle ?: stored.meta.title,
                baseHash = if (old?.kind == "create") old.baseHash else old?.baseHash ?: digest(requireNotNull(stored.html)),
                revision = next.counter,
                noteType = old?.noteType ?: stored.meta.type,
                noteMime = old?.noteMime ?: stored.meta.mime,
                remoteMissing = old?.remoteMissing ?: false,
            )
        }
        return requireNotNull(document(store, upload.noteId))
    }

    suspend fun rewriteAttachmentReferences(noteId: String, attachmentId: String, newTitle: String?): NoteDocument {
        val current = cachedNote(noteId) ?: throw IOException("Open the note first")
        val markdown = current.note.mime.substringBefore(';').trim().lowercase().endsWith("markdown")
        return transformDraft(noteId) { body -> ru.vlad.triliumnotes.data.rewriteAttachmentReferences(body, attachmentId, newTitle, markdown) }
    }

    private suspend fun transformDraft(noteId: String, transform: (String) -> String): NoteDocument {
        change { next ->
            require(noteId !in deletionClosure(next))
            val stored = requireNotNull(next.notes[noteId])
            require(stored.meta.isEditable && stored.html != null)
            val old = next.pending[noteId]
            val updated = transform(old?.html ?: stored.html)
            if (updated == (old?.html ?: stored.html)) return@change
            next.counter++
            next.pending[noteId] = Pending(old?.kind ?: "update", noteId, old?.branchId,
                old?.parentId ?: stored.meta.parentNoteIds.firstOrNull(), old?.title ?: stored.meta.title,
                updated,
                if (old?.kind == "create") old.baseTitle else old?.baseTitle ?: stored.meta.title,
                if (old?.kind == "create") old.baseHash else old?.baseHash ?: digest(stored.html),
                next.counter, old?.conflict ?: false, old?.noteType ?: stored.meta.type,
                old?.noteMime ?: stored.meta.mime, old?.remoteMissing ?: false)
        }
        return requireNotNull(cachedNote(noteId))
    }

    /** A capture ID is committed atomically with its text and survives process death. */
    suspend fun appendCapturedText(noteId: String, captureId: String, html: String): NoteDocument {
        require(captureId.matches(Regex("[A-Za-z0-9_-]{4,128}")))
        change { next ->
            val marker = "$noteId:$captureId"
            if (marker in next.captures) return@change
            require(noteId !in deletionClosure(next))
            val stored = requireNotNull(next.notes[noteId])
            require(stored.meta.isEditable && stored.html != null)
            val old = next.pending[noteId]
            next.counter++
            next.pending[noteId] = Pending(old?.kind ?: "update", noteId, old?.branchId,
                old?.parentId ?: stored.meta.parentNoteIds.firstOrNull(), old?.title ?: stored.meta.title,
                (old?.html ?: stored.html) + html,
                if (old?.kind == "create") old.baseTitle else old?.baseTitle ?: stored.meta.title,
                if (old?.kind == "create") old.baseHash else old?.baseHash ?: digest(stored.html),
                next.counter, old?.conflict ?: false, old?.noteType ?: stored.meta.type,
                old?.noteMime ?: stored.meta.mime, old?.remoteMissing ?: false)
            next.captures += marker
        }
        return requireNotNull(cachedNote(noteId))
    }

    suspend fun createWithId(parentId: String, title: String, html: String, noteId: String, branchId: String): NoteDocument {
        require(noteId.matches(Regex("[A-Za-z0-9_]{4,32}")) && !noteId.startsWith('_') && branchId.matches(Regex("[A-Za-z0-9_]{4,65}")))
        return stageCreate(parentId, title, html, "text", "text/html", noteId, branchId)
    }

    /** Stages a note with stable client IDs, so a lost create response cannot duplicate it. */
    suspend fun create(parentId: String, title: String, html: String): NoteDocument =
        stageCreate(parentId, title, html, "text", "text/html")

    /** A native book remains a navigable folder even before it has any children. */
    suspend fun createFolder(parentId: String, title: String): NoteDocument {
        require(title.isNotBlank()) { "Enter a folder name" }
        return stageCreate(parentId, title.trim(), "", "book", "text/html")
    }

    private suspend fun stageCreate(parentId: String, title: String, html: String,
        type: String, mime: String, noteId: String = newId(), branchId: String = newId()): NoteDocument {
        change { next ->
            require(parentId !in deletionClosure(next)) { "Parent note is deleted" }
            if (noteId in next.notes) return@change
            next.counter++
            val meta = NoteMeta(noteId, title, type, mime, false,
                null, null, emptyList(), listOf(parentId))
            next.notes[noteId] = StoredNote(meta, "")
            next.pending[noteId] = Pending("create", noteId, branchId, parentId,
                title, html, null, null, next.counter, noteType = type, noteMime = mime)
            next.children[noteId] = emptyList()
            next.completeChildren += noteId
            next.attachments[noteId] = emptyList()
        }
        return requireNotNull(document(store, noteId))
    }

    /** Atomically hide the whole note and queue its remote deletion, replacing any edit. */
    suspend fun delete(noteId: String) {
        change { next ->
            if (noteId in next.deleted) return@change
            val meta = next.notes[noteId]?.meta ?: error("Open the note first")
            require(meta.canDelete && !visibility(next).isHidden(noteId)) { "This note cannot be deleted" }
            val previous = next.pending[noteId]
            next.deleted += noteId
            // Only the explicitly deleted note is final. A cached descendant may
            // have acquired another parent on a different client while we were offline.
            // Retain its body and draft, hidden by parentage, until fresh metadata
            // establishes whether it survived. Keep legacy edges for that same closure.
            next.notes[noteId] = StoredNote(meta, null)
            next.pending.remove(noteId)
            // A create marked before POST may already exist even if its response was lost.
            if (previous?.kind != "create" || previous.baseHash != null) {
                next.counter++
                next.pending[noteId] = Pending("delete", noteId, null, null,
                    "", "", null, null, next.counter)
            }
        }
    }

    /** Null means an unknown catalog; an empty list is a confirmed attachment-free note. */
    fun cachedAttachments(noteId: String): List<AttachmentMeta>? =
        if (isHidden(noteId)) null else store.attachments[noteId]?.toList()

    /** Catalog completeness, including an empty list, is required for lossless offline copying. */
    suspend fun rememberAttachments(noteId: String, items: List<AttachmentMeta>) = change { next ->
        require(items.all { it.ownerId == noteId })
        next.attachments[noteId] = items.toList()
    }

    suspend fun moveBranch(branchId: String, fromParentId: String, targetParentId: String) =
        stageBranch("move", branchId, fromParentId, targetParentId)

    suspend fun cloneBranch(branchId: String, fromParentId: String, targetParentId: String) =
        stageBranch("clone", branchId, fromParentId, targetParentId)

    private suspend fun stageBranch(kind: String, branchId: String, fromParentId: String, targetParentId: String) {
        change { next ->
            val item = next.children[fromParentId]?.firstOrNull { it.branchId == branchId }
                ?: withDrafts(next, fromParentId, next.children[fromParentId].orEmpty()).firstOrNull { it.branchId == branchId }
                ?: throw TreeOperationException(R.string.tree_load_first)
            require(item.note.canDelete && !visibility(next).isHidden(item.note.id))
            require(targetParentId !in deletionClosure(next) && item.note.id !in deletionClosure(next))
            if (fromParentId == targetParentId) return@change
            checkCachedCycle(next, item.note.id, targetParentId)
            if (withDrafts(next, targetParentId, next.children[targetParentId].orEmpty()).any { it.note.id == item.note.id })
                throw TreeOperationException(R.string.tree_already_there)
            next.counter++
            val op = TreeOperation(newId(), kind, item.note.id, fromParentId, targetParentId,
                branchId, item.prefix, nextTreePosition(next, targetParentId), item.isExpanded, revision = next.counter)
            next.treePending[op.id] = op
            applyPlacement(next, op, "${targetParentId}_${item.note.id}")
        }
    }

    /** Accepts the complete current sibling list, so omissions cannot silently reorder other notes. */
    suspend fun reorderChildren(parentId: String, branchIds: List<String>) {
        change { next ->
            val current = withDrafts(next, parentId, next.children[parentId].orEmpty())
                .filterNot { it.note.id in deletionClosure(next) || visibility(next).isHidden(it.note.id) }
            require(branchIds.size == branchIds.distinct().size && branchIds.toSet() == current.map { it.branchId }.toSet())
            require(parentId !in deletionClosure(next))
            next.treePending.entries.removeAll { it.value.kind == "order" && it.value.targetParentId == parentId }
            next.counter++
            val byId = current.associateBy { it.branchId }
            val op = TreeOperation(newId(), "order", parentId, targetParentId = parentId,
                order = branchIds, orderNotes = branchIds.map { requireNotNull(byId[it]).note.id }, revision = next.counter)
            next.treePending[op.id] = op
            val hidden = next.children[parentId].orEmpty().filter { it.branchId !in byId }
            next.children[parentId] = branchIds.mapIndexed { index, id -> requireNotNull(byId[id]).copy(position = (index + 1) * 10) } + hidden
        }
    }

    /** Reject unknown ancestor paths: offline absence is not proof that no clone cycle exists. */
    private fun checkCachedCycle(snapshot: Store, noteId: String, targetId: String) {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>().apply { add(targetId) }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id == noteId) throw TreeOperationException(R.string.tree_cycle)
            if (id == "root" || id.startsWith('_') || !visited.add(id)) continue
            val meta = snapshot.notes[id]?.meta ?: throw TreeOperationException(R.string.tree_load_first)
            if (visibility(snapshot).isHidden(id)) throw HiddenNoteException()
            val parents = meta.parentNoteIds.ifEmpty {
                snapshot.children.filterValues { items -> items.any { it.note.id == id } }.keys.toList()
            }
            if (parents.isEmpty()) throw TreeOperationException(R.string.tree_load_first)
            parents.forEach(queue::add)
        }
    }

    private suspend fun checkRemoteCycle(noteId: String, targetId: String) {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>().apply { add(targetId) }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id == noteId) throw TreeOperationException(R.string.tree_cycle)
            if (id == "root" || id.startsWith('_') || !visited.add(id)) continue
            val note = api.getNote(id)
            if (note.id.startsWith('_')) throw HiddenNoteException()
            note.parentNoteIds.forEach(queue::add)
        }
    }

    private fun nextTreePosition(snapshot: Store, parentId: String): Int =
        (snapshot.children[parentId].orEmpty().maxOfOrNull { it.position } ?: 0).coerceAtMost(Int.MAX_VALUE - 20) + 10

    private fun applyPlacement(next: Store, op: TreeOperation, actualBranchId: String) {
        val meta = next.notes[op.noteId]?.meta ?: return
        val oldParents = meta.parentNoteIds
        val parents = (if (op.kind == "move") oldParents - op.fromParentId else oldParents) + op.targetParentId
        val updated = meta.copy(parentNoteIds = parents.distinct())
        next.notes[op.noteId] = requireNotNull(next.notes[op.noteId]).copy(meta = updated)
        if (op.kind == "move") {
            next.children[op.fromParentId] = next.children[op.fromParentId].orEmpty().filterNot { it.note.id == op.noteId }
            // A local create must retain its original parent until the branch operation is confirmed remotely.
        }
        val item = TreeItem(actualBranchId, updated, op.prefix, op.position, op.expanded)
        next.children[op.targetParentId] = (next.children[op.targetParentId].orEmpty().filterNot { it.note.id == op.noteId } + item)
            .sortedWith(compareBy<TreeItem> { it.position }.thenBy { it.branchId })
    }

    private fun overlayTree(next: Store, parentId: String, remote: List<TreeItem>): List<TreeItem> {
        var result = remote
        next.treePending.values.sortedBy { it.revision }.forEach { op ->
            if (op.kind in setOf("move", "clone")) {
                if (op.kind == "move" && parentId == op.fromParentId) result = result.filterNot { it.note.id == op.noteId }
                if (parentId == op.targetParentId) next.notes[op.noteId]?.meta?.let { meta ->
                    val staged = next.children[parentId].orEmpty().firstOrNull { it.note.id == op.noteId }
                        ?: TreeItem("${parentId}_${op.noteId}", meta, op.prefix, op.position, op.expanded)
                    result = result.filterNot { it.note.id == op.noteId } + staged
                }
            }
            if (op.kind == "copy") {
                val ids = op.copyNodes.mapTo(mutableSetOf()) { it.id }
                val staged = next.children[parentId].orEmpty().filter { it.note.id in ids }
                staged.forEach { item -> if (result.none { it.note.id == item.note.id }) result = result + item }
            }
            if (op.kind == "order" && op.targetParentId == parentId) {
                val indices = op.order.withIndex().associate { it.value to (it.index + 1) * 10 }
                result = result.map { item -> indices[item.branchId]?.let { item.copy(position = it) } ?: item }
            }
        }
        return result.sortedWith(compareBy<TreeItem> { it.position }.thenBy { it.branchId })
    }

    fun cachedTrash(): List<TrashItem> {
        val snapshot = store
        val items = snapshot.remoteTrash.toMutableMap()
        val deleted = deletionClosure(snapshot)
        snapshot.trash.forEach { (id, entry) ->
            val restoring = snapshot.pending[id]?.kind == "restore"
            val available = when {
                entry.localOnly || !entry.deleteAttempted -> true
                snapshot.remoteTrash[id] != null -> requireNotNull(snapshot.remoteTrash[id]).canRestore
                else -> snapshot.notes[id]?.meta?.parentNoteIds.orEmpty().any {
                    it !in weakParents && it !in deleted
                }
            }
            items[id] = TrashItem(id, entry.title, entry.deletedAt, canRestore = !restoring && available,
                localOnly = entry.localOnly, pending = snapshot.pending[id]?.kind in setOf("delete", "restore"), restoring = restoring)
        }
        return items.values.sortedWith(compareByDescending<TrashItem> { it.deletedAt.orEmpty() }.thenBy { it.noteId })
    }

    suspend fun listTrash(): List<TrashItem> {
        try {
            val remote = api.nativeTrash()
            change { next ->
                next.remoteTrash.clear()
                remote.forEach { if (next.pending[it.noteId]?.kind != "restore") next.remoteTrash[it.noteId] = it }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (cachedTrash().isEmpty()) throw offlineError(error) }
        return cachedTrash()
    }

    /** New deletions retain the original body and draft; legacy delete tombstones stay unchanged. */
    suspend fun trash(noteId: String) {
        change { next ->
            if (noteId in next.deleted) return@change
            val note = next.notes[noteId] ?: throw TreeOperationException(R.string.tree_load_first)
            require(note.meta.canDelete && !visibility(next).isHidden(noteId))
            val previous = next.pending[noteId]
            val localOnly = previous?.kind == "create" && previous.baseHash == null &&
                next.treePending.values.none { it.kind == "copy" && it.copyNodes.any { node -> node.id == noteId } }
            val entry = TrashRecord(previous?.title ?: note.meta.title, java.time.Instant.now().toString(),
                previous, localOnly)
            next.trash[noteId] = entry
            next.deleted += noteId
            next.pending.remove(noteId)
            if (!localOnly) {
                next.counter++
                next.pending[noteId] = Pending("delete", noteId, null, null, "", "", null, null, next.counter)
            }
        }
    }

    suspend fun restore(noteId: String) {
        change { next ->
            val entry = next.trash[noteId]
            val remote = next.remoteTrash[noteId]
            require(entry != null || remote?.canRestore == true) { "This note cannot be restored" }
            if (entry != null && (entry.localOnly || !entry.deleteAttempted)) {
                next.deleted.remove(noteId)
                next.pending.remove(noteId)
                entry.draft?.let { next.pending[noteId] = it }
                next.trash.remove(noteId)
                next.remoteTrash.remove(noteId)
            } else {
                next.counter++
                next.pending[noteId] = Pending("restore", noteId, null, null, "", "", null, null, next.counter)
                // Keep remote restores in trash until confirmed; their retained drafts cannot be overwritten by edits.
                next.deleted += noteId
                next.remoteTrash.remove(noteId)
                if (entry == null) next.trash[noteId] = TrashRecord(requireNotNull(remote).title,
                    remote.deletedAt, null, false, true)
            }
        }
    }

    private suspend fun syncRestore(snapshot: Pending) {
        val exists = try { api.getNote(snapshot.noteId) } catch (error: ApiException) {
            if (error.status != 404) throw error
            null
        }
        if (exists == null) api.etapiRequest("POST", listOf("notes", snapshot.noteId, "undelete"))
        val remote = api.getNote(snapshot.noteId)
        change { next ->
            if (next.pending[snapshot.noteId]?.revision != snapshot.revision) return@change
            next.deleted.remove(snapshot.noteId)
            val restored = next.trash.remove(snapshot.noteId)
            next.pending.remove(snapshot.noteId)
            restored?.draft?.let { next.pending[snapshot.noteId] = it }
            val old = next.notes[snapshot.noteId]
            next.notes[snapshot.noteId] = StoredNote(remote, old?.html)
            next.remoteTrash.remove(snapshot.noteId)
        }
    }

    /** Bulk callers validate every selection before staging the first copy. This performs no writes. */
    suspend fun validateCopySubtree(noteId: String, targetParentId: String) {
        prepareCopySubtree(noteId, targetParentId, validateOnly = true)
    }

    suspend fun copySubtree(noteId: String, targetParentId: String): NoteDocument =
        requireNotNull(prepareCopySubtree(noteId, targetParentId, validateOnly = false))

    /** Snapshot a complete subtree before queuing anything; originals and their resources are never changed. */
    private suspend fun prepareCopySubtree(noteId: String, targetParentId: String, validateOnly: Boolean): NoteDocument? = withContext(Dispatchers.IO) {
        val baseline = store
        require(noteId !in deletionClosure(baseline) && targetParentId !in deletionClosure(baseline))
        if (visibility(baseline).isHidden(noteId) || visibility(baseline).isHidden(targetParentId)) throw HiddenNoteException()
        if (targetParentId != "root" && baseline.notes[targetParentId] == null) throw TreeOperationException(R.string.tree_load_first)
        val discovered = linkedMapOf<String, String>()
        val edges = mutableListOf<Pair<String, TreeItem>>()
        val queue = ArrayDeque<Pair<String, String>>().apply { add(noteId to targetParentId) }
        while (queue.isNotEmpty()) {
            val (id, parent) = queue.removeFirst()
            if (id in discovered) continue
            val note = baseline.notes[id] ?: throw TreeOperationException(R.string.tree_copy_download_first)
            if (!note.meta.canDelete || note.meta.isProtected || note.meta.type == "doc")
                throw TreeOperationException(R.string.tree_copy_protected)
            if (note.html == null || id !in baseline.attachments ||
                (id !in baseline.completeChildren && (id !in baseline.children ||
                    !baseline.children[id].orEmpty().map { it.branchId }.containsAll(note.meta.childBranchIds))))
                throw TreeOperationException(R.string.tree_copy_download_first)
            if (baseline.pending[id]?.conflict == true) throw TreeOperationException(R.string.tree_copy_conflict)
            val body = baseline.pending[id]?.html ?: note.html
            if (body.contains("#pocket-upload-")) throw TreeOperationException(R.string.tree_copy_upload_first)
            discovered[id] = parent
            val children = withDrafts(baseline, id, baseline.children[id].orEmpty())
                .filterNot { it.note.id in deletionClosure(baseline) || visibility(baseline).isHidden(it.note.id) }
            children.forEach { item -> edges += id to item; queue.add(item.note.id to id) }
        }
        val knownAttachmentIds = baseline.attachments.values.flatten().mapTo(mutableSetOf()) { it.id }
        discovered.keys.forEach { id ->
            val source = requireNotNull(baseline.notes[id])
            val body = baseline.pending[id]?.html ?: source.html.orEmpty()
            val markup = if (source.meta.mime.substringBefore(';').trim().endsWith("markdown"))
                org.commonmark.renderer.html.HtmlRenderer.builder().build().render(org.commonmark.parser.Parser.builder().build().parse(body)) else body
            org.jsoup.Jsoup.parseBodyFragment(markup).select("[href], [src], [poster]").forEach { element ->
                listOf("href", "src", "poster").forEach { attribute ->
                    val resource = resolveResourceTarget(element.attr(attribute), api.serverUrl)
                    if (resource?.attachment == true && resource.id !in knownAttachmentIds)
                        throw TreeOperationException(R.string.tree_copy_download_first)
                }
            }
        }
        val ids = discovered.keys.associateWith { newId() }
        val nodes = discovered.map { (id, parent) ->
            val source = requireNotNull(baseline.notes[id])
            CopyNode(id, requireNotNull(ids[id]), ids[parent] ?: parent,
                baseline.pending[id]?.title ?: source.meta.title, source.meta.type, source.meta.mime,
                source.meta.attributes.filter { it.noteId.isBlank() || it.noteId == id }.map { attribute ->
                    attribute.copy(id = newId(), noteId = requireNotNull(ids[id]),
                        value = if (attribute.type == "relation") ids[attribute.value] ?: attribute.value else attribute.value)
                })
        }
        val resources = mutableListOf<CopyResource>()
        val resourceSources = mutableMapOf<String, File>()
        val resourceDir = File(context.filesDir, "note-resources")
        fun prepareResource(resource: CopyResource, version: String) {
            val sourceKey = (if (resource.attachment) "attachment_" else "note_") + resource.originalId
            val source = File(resourceDir, "$sourceKey.data")
            val storedVersion = File(resourceDir, "$sourceKey.version")
            if (!source.isFile || !File(resourceDir, "$sourceKey.mime").isFile ||
                !storedVersion.isFile || storedVersion.readText() != version)
                throw TreeOperationException(R.string.tree_copy_download_first)
            resources += resource
            resourceSources[resource.fileName] = source
        }
        nodes.forEach { node ->
            val source = requireNotNull(baseline.notes[node.originalId])
            if (!source.meta.hasTextContent || source.meta.type in setOf("image", "file")) {
                prepareResource(CopyResource(node.originalId, node.id, node.id, false, node.mime, node.title),
                    source.meta.blobId ?: source.meta.modifiedAt.orEmpty())
            }
            baseline.attachments[node.originalId].orEmpty().forEach { attachment ->
                prepareResource(CopyResource(attachment.id, newId(), node.id, true, attachment.mime,
                    attachment.title, attachment.role, attachment.position), attachment.blobId ?:
                    listOf(source.meta.blobId.orEmpty(), source.meta.modifiedAt.orEmpty()).joinToString(":"))
            }
        }
        if (validateOnly) return@withContext null
        val idMap = ids + resources.filter { it.attachment }.associate { it.originalId to it.localId }
        val opId = newId()
        val folder = File(context.filesDir, "tree-copies/$opId").apply { mkdirs() }
        try {
            resources.forEach { resource ->
                copyAtomic(requireNotNull(resourceSources[resource.fileName]), File(folder, resource.fileName))
            }
            // Prepare every viewer file before the atomic metadata commit makes the copy visible.
            resources.forEach { resource ->
                val key = (if (resource.attachment) "attachment_" else "note_") + resource.localId
                copyAtomic(File(folder, resource.fileName), File(resourceDir, "$key.data"))
                writeAtomicText(File(resourceDir, "$key.mime"), resource.mime)
                writeAtomicText(File(resourceDir, "$key.version"), if (resource.attachment) ":" else "")
            }
            change { next ->
                if (discovered.keys.any { baseline.notes[it] != next.notes[it] || baseline.pending[it] != next.pending[it] ||
                        baseline.children[it] != next.children[it] || baseline.attachments[it] != next.attachments[it] })
                    throw TreeOperationException(R.string.tree_changed)
                require(targetParentId !in deletionClosure(next))
                nodes.forEach { node ->
                    val source = requireNotNull(baseline.notes[node.originalId])
                    val body = remapCopyContent(baseline.pending[node.originalId]?.html ?: source.html.orEmpty(), idMap, node.type, api.serverUrl)
                    val childEdges = edges.filter { it.first == node.originalId }
                    val meta = source.meta.copy(id = node.id, title = node.title, blobId = null, modifiedAt = null,
                        childBranchIds = childEdges.map { "${node.id}_${requireNotNull(ids[it.second.note.id])}" },
                        parentNoteIds = (edges.filter { it.second.note.id == node.originalId }.map { requireNotNull(ids[it.first]) } + node.parentId).distinct(),
                        attributes = node.attributes)
                    next.notes[node.id] = StoredNote(meta, "")
                    next.counter++
                    next.pending[node.id] = Pending("create", node.id, "${node.parentId}_${node.id}", node.parentId,
                        node.title, body, null, null, next.counter, noteType = node.type, noteMime = node.mime)
                    next.children[node.id] = childEdges.map { (_, edge) ->
                        edge.copy(branchId = "${node.id}_${requireNotNull(ids[edge.note.id])}",
                            note = requireNotNull(baseline.notes[edge.note.id]).meta.copy(id = requireNotNull(ids[edge.note.id]),
                                blobId = null, modifiedAt = null, parentNoteIds = listOf(node.id)))
                    }
                    next.completeChildren += node.id
                    next.attachments[node.id] = resources.filter { it.attachment && it.ownerId == node.id }.map {
                        AttachmentMeta(it.localId, node.id, it.role, it.mime, it.title, it.position)
                    }
                }
                val root = nodes.first()
                next.children[targetParentId] = next.children[targetParentId].orEmpty() + TreeItem(
                    "${targetParentId}_${root.id}", requireNotNull(next.notes[root.id]).meta, "",
                    nextTreePosition(next, targetParentId), false)
                next.counter++
                next.treePending[opId] = TreeOperation(opId, "copy", root.id, targetParentId = targetParentId,
                    revision = next.counter, copyNodes = nodes, copyEdges = edges.map { (parent, edge) ->
                        TreeBranch("${requireNotNull(ids[parent])}_${requireNotNull(ids[edge.note.id])}",
                            requireNotNull(ids[edge.note.id]), requireNotNull(ids[parent]), edge.prefix, edge.position, edge.isExpanded)
                    }, copyResources = resources)
            }

        } catch (error: Throwable) {
            if (opId !in store.treePending) {
                folder.deleteRecursively()
                resources.forEach { resource ->
                    val key = (if (resource.attachment) "attachment_" else "note_") + resource.localId
                    listOf("data", "mime", "version").forEach { suffix -> AtomicFile(File(resourceDir, "$key.$suffix")).delete() }
                }
            }
            throw error
        }
        requireNotNull(cachedNote(requireNotNull(ids[noteId])))
    }

    private suspend fun syncCopy(snapshot: TreeOperation) {
        var operation = store.treePending[snapshot.id] ?: return
        val folder = File(context.filesDir, "tree-copies/${operation.id}")
        // Stable note IDs plus an empty shell baseline recover a lost create response.
        operation.copyNodes.forEach { planned ->
            if (planned.shellCreated) return@forEach
            val existing = try { api.getNote(planned.id) } catch (error: ApiException) {
                if (error.status != 404) throw error
                null
            }
            if (existing == null) api.createNote(planned.id, planned.parentId, serverTitle(planned.title), "", planned.type, planned.mime)
            else if (existing.type != planned.type || existing.mime != planned.mime ||
                existing.title != serverTitle(planned.title) || planned.parentId !in existing.parentNoteIds)
                throw TreeOperationException(R.string.tree_changed)
            change { next ->
                val current = next.treePending[operation.id] ?: return@change
                next.treePending[operation.id] = current.copy(copyNodes = current.copyNodes.map {
                    if (it.id == planned.id) it.copy(shellCreated = true) else it })
            }
        }
        operation = store.treePending[snapshot.id] ?: return
        operation.copyEdges.forEach { api.placeBranch(it) }
        operation.copyNodes.forEach { node -> node.attributes.forEach { attribute ->
            val existing = try { api.etapiRequest("GET", listOf("attributes", attribute.id)); true }
                catch (error: ApiException) { if (error.status != 404) throw error; false }
            if (!existing) api.etapiRequest("POST", listOf("attributes"), attribute.toJson().toString())
        } }
        operation.copyResources.forEach { planned ->
            var resource = store.treePending[operation.id]?.copyResources?.first { it.localId == planned.localId } ?: return
            val content = File(folder, resource.fileName)
            if (!content.isFile) throw TreeOperationException(R.string.tree_copy_download_first)
            if (resource.attachment) {
                if (resource.remoteId == null) {
                    val marker = ".trilium-android-copy-${operation.id}-${resource.localId}"
                    val attachment = api.getAttachments(resource.ownerId).firstOrNull { it.title == marker }
                        ?: api.createAttachment(resource.ownerId, resource.role, resource.mime, marker, resource.position)
                    resource = resource.copy(remoteId = attachment.id)
                    saveCopyResource(operation.id, resource)
                }
                if (!resource.uploaded) {
                    api.putAttachmentContent(requireNotNull(resource.remoteId), content)
                    resource = resource.copy(uploaded = true)
                    saveCopyResource(operation.id, resource)
                }
                api.patchAttachmentTitle(requireNotNull(resource.remoteId), resource.title)
            } else if (!resource.uploaded) {
                api.etapiPutFile(listOf("notes", resource.ownerId, "content"), content)
                resource = resource.copy(remoteId = resource.ownerId, uploaded = true)
                saveCopyResource(operation.id, resource)
            }
        }
        operation = store.treePending[snapshot.id] ?: return
        val remap = operation.copyResources.filter { it.attachment }.associate { it.localId to requireNotNull(it.remoteId) }
        val remoteNotes = operation.copyNodes.associate { it.id to api.getNote(it.id) }
        val remoteCatalogs = operation.copyNodes.associate { it.id to api.getAttachments(it.id) }
        operation.copyResources.filter { it.attachment }.forEach { resource ->
            val key = "attachment_${requireNotNull(resource.remoteId)}"
            val directory = File(context.filesDir, "note-resources").apply { mkdirs() }
            copyAtomic(File(folder, resource.fileName), File(directory, "$key.data"))
            writeAtomicText(File(directory, "$key.mime"), resource.mime)
            val attachment = remoteCatalogs[resource.ownerId].orEmpty().firstOrNull { it.id == resource.remoteId }
            val owner = requireNotNull(remoteNotes[resource.ownerId])
            val version = attachment?.blobId ?: listOf(owner.blobId.orEmpty(), owner.modifiedAt.orEmpty()).joinToString(":")
            writeAtomicText(File(directory, "$key.version"), version)
        }
        operation.copyResources.filterNot { it.attachment }.forEach { resource ->
            val owner = requireNotNull(remoteNotes[resource.ownerId])
            writeAtomicText(File(context.filesDir, "note-resources/note_${resource.ownerId}.version"), owner.blobId ?: owner.modifiedAt.orEmpty())
        }
        change { next ->
            operation.copyNodes.forEach { node ->
                val remote = requireNotNull(remoteNotes[node.id])
                val old = next.notes[node.id] ?: return@forEach
                next.notes[node.id] = old.copy(meta = remote)
                val pending = next.pending[node.id]
                if (pending?.kind == "create") {
                    if (remote.hasTextContent && remote.type !in setOf("file", "image")) {
                        next.pending[node.id] = pending.copy(kind = "copy-update", html = remapTreeReferences(pending.html, remap, api.serverUrl),
                            baseTitle = remote.title, baseHash = digest(""))
                    } else {
                        // Binary bytes have already been streamed; no text PUT may overwrite them.
                        next.pending.remove(node.id)
                    }
                }
                next.trash[node.id]?.let { entry ->
                    if (entry.draft?.kind == "create") next.trash[node.id] = entry.copy(draft =
                        if (!remote.hasTextContent || remote.type in setOf("file", "image")) null else entry.draft.copy(
                            kind = "copy-update", html = remapTreeReferences(entry.draft.html, remap, api.serverUrl),
                            baseTitle = remote.title, baseHash = digest("")))
                }
                next.attachments[node.id] = remoteCatalogs[node.id].orEmpty()
            }
        }

        // The normal note outbox now owns final text bodies and any edits made during the copy.
    }

    private suspend fun saveCopyResource(operationId: String, resource: CopyResource) = change { next ->
        val operation = next.treePending[operationId] ?: return@change
        next.treePending[operationId] = operation.copy(copyResources = operation.copyResources.map {
            if (it.localId == resource.localId) resource else it })
    }

    private fun copyAtomic(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        val atomic = AtomicFile(destination)
        val output = atomic.startWrite()
        try {
            source.inputStream().use { it.copyTo(output) }
            atomic.finishWrite(output)
        } catch (error: Throwable) { atomic.failWrite(output); throw error }
    }

    private fun writeAtomicText(destination: File, value: String) {
        val atomic = AtomicFile(destination)
        val output = atomic.startWrite()
        try { output.write(value.toByteArray()); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }

    private suspend fun syncTreeOperation(operation: TreeOperation) {
        when (operation.kind) {
            "move", "clone" -> {
                checkRemoteCycle(operation.noteId, operation.targetParentId)
                api.getNote(operation.noteId)
                val target = api.placeBranch(TreeBranch("${operation.targetParentId}_${operation.noteId}",
                    operation.noteId, operation.targetParentId, operation.prefix, operation.position, operation.expanded))
                // Never remove the source until the new placement is confirmed to exist.
                if (operation.kind == "move") {
                    val sourceParent = try { api.getChildrenResult(operation.fromParentId) } catch (error: ApiException) {
                        if (error.status != 404) throw error
                        null // The confirmed target already protects a note whose old parent disappeared.
                    }
                    if (sourceParent?.failedBranchIds?.isNotEmpty() == true) throw IOException("Incomplete branch list")
                    sourceParent?.items.orEmpty().filter { it.note.id == operation.noteId }.forEach { api.removeBranch(it.branchId) }
                }
                val remote = api.getNote(operation.noteId)
                change { next ->
                    next.notes[remote.id]?.let { next.notes[remote.id] = it.copy(meta = it.meta.copy(parentNoteIds = remote.parentNoteIds)) }
                    replaceBranchId(next, "${operation.targetParentId}_${operation.noteId}", target.id)
                    if (operation.kind == "move") next.pending[operation.noteId]?.let { draft ->
                        if (draft.kind in setOf("create", "update", "copy-update") && draft.parentId == operation.fromParentId)
                            next.pending[operation.noteId] = draft.copy(parentId = operation.targetParentId)
                    }
                }
            }
            "order" -> {
                val current = api.getChildrenResult(operation.targetParentId)
                if (current.failedBranchIds.isNotEmpty()) throw IOException("Incomplete branch list")
                val local = store.children[operation.targetParentId].orEmpty().associateBy { it.branchId }
                operation.order.forEachIndexed { index, id ->
                    val noteId = operation.orderNotes.getOrNull(index) ?: local[id]?.note?.id
                    val branch = current.items.firstOrNull { it.branchId == id || (noteId != null && it.note.id == noteId) }
                        ?: throw TreeOperationException(R.string.tree_changed)
                    api.positionBranch(branch.branchId, (index + 1) * 10)
                }
                api.refreshOrdering(operation.targetParentId)
            }
            "copy" -> syncCopy(operation)
        }
        change { next -> next.treePending.remove(operation.id) }
        if (operation.kind == "copy") File(context.filesDir, "tree-copies/${operation.id}").deleteRecursively()
    }

    private fun replaceBranchId(next: Store, oldId: String, newId: String) {
        if (oldId == newId) return
        next.children.replaceAll { _, items -> items.map { if (it.branchId == oldId) it.copy(branchId = newId) else it } }
        next.treePending.replaceAll { _, op -> op.copy(branchId = if (op.branchId == oldId) newId else op.branchId,
            order = op.order.map { if (it == oldId) newId else it }) }
    }

    suspend fun syncPending(): SyncState = syncMutex.withLock {
        if (cacheError != null) return@withLock store.toSyncState(error = cacheError)
        mutableSyncState.value = store.toSyncState(isSyncing = true)
        var lastError: String? = null
        val controlSnapshots = store.pending.values.filter { it.kind in setOf("delete", "restore") }.sortedBy { it.revision }
        suspend fun syncDocuments(onlyIds: Set<String>? = null) {
            val copyIds = store.treePending.values.filter { it.kind == "copy" }.flatMap { it.copyNodes }.mapTo(mutableSetOf()) { it.id }
            val snapshots = store.pending.values.toList().sortedBy { it.revision }
            for (pending in snapshots) {
                val current = store.pending[pending.noteId]
                if (current == null || current.kind != pending.kind || current.revision != pending.revision || pending.conflict ||
                    pending.kind in setOf("delete", "restore") || pending.noteId in copyIds || isDeleted(pending.noteId) ||
                    (onlyIds != null && pending.noteId !in onlyIds)) continue
                try {
                    if (pending.kind == "create") syncCreate(pending) else syncUpdate(pending)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    lastError = safeMessage(error)
                }
            }
        }
        try {
            syncDocuments()
            val copiedIds = store.treePending.values.filter { it.kind == "copy" }.flatMap { it.copyNodes }.mapTo(mutableSetOf()) { it.id }
            for (operation in store.treePending.values.toList().sortedBy { it.revision }) {
                try { syncTreeOperation(store.treePending[operation.id] ?: continue) }
                catch (error: Exception) {
                    if (error is CancellationException) throw error
                    lastError = safeMessage(error)
                    // Dependent branch operations must retain their staging order on retry.
                    break
                }
            }
            // Copy operations have prepared their attachment IDs and binary bytes.
            if (store.treePending.isEmpty() && copiedIds.isNotEmpty()) syncDocuments(copiedIds)
            for (pending in controlSnapshots) {
                val current = store.pending[pending.noteId]
                if (current?.kind != pending.kind || current.revision != pending.revision) continue
                // Moving/copying a note must complete before deleting its original source.
                if (store.treePending.isNotEmpty()) continue
                try {
                    if (pending.kind == "restore") syncRestore(pending) else syncDelete(pending)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    lastError = if (pending.kind == "delete") text(R.string.msg_delete_failed, safeMessage(error)) else safeMessage(error)
                }
            }
        } finally {
            mutableSyncState.value = store.toSyncState(error = lastError)
        }
        mutableSyncState.value
    }

    /** Stages a separate note and clears the conflicted draft in one atomic disk write. */
    suspend fun saveConflictAsCopy(noteId: String): NoteDocument {
        val copyId = newId()
        val branchId = newId()
        change { next ->
            val pending = next.pending[noteId]
                ?: error("No pending edit")
            require(pending.conflict) { "No conflict found" }
            val parentId = if (pending.remoteMissing) "root" else
                pending.parentId ?: error("Unknown parent note")
            require(noteId !in deletionClosure(next) && parentId !in deletionClosure(next)) { "Note is deleted" }
            next.counter++
            val copyTitle = text(R.string.msg_conflict_copy_title, pending.title)
            val meta = NoteMeta(copyId, copyTitle, pending.noteType, pending.noteMime, false,
                null, null, emptyList(), listOf(parentId))
            next.notes[copyId] = StoredNote(meta, "")
            next.pending[copyId] = Pending("create", copyId, branchId, parentId,
                copyTitle, pending.html, null, null, next.counter,
                noteType = pending.noteType, noteMime = pending.noteMime)
            next.pending.remove(noteId)
            if (pending.remoteMissing) {
                // A recovered copy must not leave a phantom server-deleted note in local search.
                next.notes.remove(noteId)
                next.children.remove(noteId)
                next.children.replaceAll { _, items -> items.filterNot { it.note.id == noteId } }
            }
        }
        return requireNotNull(document(store, copyId))
    }

    private suspend fun syncCreate(pending: Pending) {
        val existing = try {
            api.getNote(pending.noteId)
        } catch (error: ApiException) {
            if (error.status == 404) null else throw error
        }
        val remote: NoteMeta
        val remoteHtml: String
        if (existing == null) {
            val title = serverTitle(pending.title)
            if (!markCreateAttempt(pending, title)) return
            val staged = store.notes[pending.noteId]?.meta ?: return
            remote = try { api.createNote(pending.noteId,
                requireNotNull(pending.parentId), title, pending.html, staged.type, staged.mime) }
            catch (error: ApiException) {
                if (error.status != 404) throw error
                markRemoteMissing(pending.noteId)
                return
            }
            remoteHtml = pending.html
        } else {
            val html = api.getContent(pending.noteId)
            val matchesCurrent = existing.title == serverTitle(pending.title) && html == pending.html
            val matchesAttempt = existing.title == pending.baseTitle && digest(html) == pending.baseHash
            if ((!matchesCurrent && !matchesAttempt) || pending.parentId !in existing.parentNoteIds) {
                markConflict(pending.noteId, existing, html)
                return
            }
            remote = existing
            remoteHtml = html
        }
        commitCreated(pending, remote, remoteHtml)
    }

    private suspend fun syncDelete(pending: Pending) {
        var stillCurrent = false
        change { next ->
            if (next.pending[pending.noteId]?.revision == pending.revision && next.pending[pending.noteId]?.kind == "delete") {
                stillCurrent = true
                next.trash[pending.noteId]?.let { next.trash[pending.noteId] = it.copy(deleteAttempted = true) }
            }
        }
        if (!stillCurrent) return
        try {
            api.deleteNote(pending.noteId)
        } catch (error: ApiException) {
            if (error.status != 404) throw error
        }
        change { next ->
            if (next.pending[pending.noteId]?.revision == pending.revision) next.pending.remove(pending.noteId)
        }
    }

    private suspend fun syncUpdate(pending: Pending) {
        val remote = try { api.getNote(pending.noteId) }
        catch (error: ApiException) {
            if (error.status != 404) throw error
            markRemoteMissing(pending.noteId)
            return
        }
        val supportsContent = remote.isEditable || (pending.kind == "copy-update" && remote.hasTextContent &&
            !remote.isProtected && remote.type !in setOf("file", "image"))
        if (!supportsContent || remote.type != pending.noteType || remote.mime != pending.noteMime) {
            markConflict(pending.noteId, remote, "")
            return
        }
        val remoteHtml = try { api.getContent(pending.noteId) }
        catch (error: ApiException) {
            if (error.status != 404) throw error
            markRemoteMissing(pending.noteId)
            return
        }
        val htmlHash = digest(remoteHtml)
        val contentChangedElsewhere = htmlHash != pending.baseHash && remoteHtml != pending.html
        val titleChangedElsewhere = remote.title != pending.baseTitle && remote.title != serverTitle(pending.title)
        if (contentChangedElsewhere || titleChangedElsewhere) {
            markConflict(pending.noteId, remote, remoteHtml)
            return
        }
        if (store.pending[pending.noteId]?.kind !in setOf("update", "copy-update") || isDeleted(pending.noteId)) return
        if (remoteHtml != pending.html) api.putContent(pending.noteId, pending.html)
        if (isDeleted(pending.noteId)) return
        if (remote.title != serverTitle(pending.title)) api.patchTitle(pending.noteId, serverTitle(pending.title))
        commitSynced(pending, api.getNote(pending.noteId), pending.html)
    }

    private suspend fun markRemoteMissing(noteId: String) = change { next ->
        next.pending[noteId]?.takeIf { it.kind in setOf("update", "copy-update", "create") }?.let {
            next.pending[noteId] = it.copy(conflict = true, remoteMissing = true)
        }
    }

    private suspend fun markCreateAttempt(snapshot: Pending, title: String): Boolean {
        var marked = false
        change { next ->
            if (snapshot.noteId in deletionClosure(next)) return@change
            next.pending[snapshot.noteId]?.takeIf { it.kind == "create" }?.let { current ->
                marked = true
                next.pending[snapshot.noteId] = current.copy(
                    baseTitle = title, baseHash = digest(snapshot.html),
                )
            }
        }
        return marked
    }

    private suspend fun commitCreated(snapshot: Pending, remote: NoteMeta, html: String) {
        change { next ->
            if (snapshot.noteId in next.deleted) return@change
            next.notes[snapshot.noteId] = StoredNote(remote, html)
            snapshot.parentId?.let { parentId ->
                val children = next.children[parentId].orEmpty()
                if (children.none { it.note.id == snapshot.noteId }) {
                    val nextPosition = (children.maxOfOrNull { it.position } ?: 0)
                        .coerceAtMost(Int.MAX_VALUE - 1010) + 10
                    next.children[parentId] = children + TreeItem(
                        branchId = requireNotNull(snapshot.branchId),
                        note = remote,
                        prefix = "",
                        position = nextPosition,
                        isExpanded = false,
                    )
                }
            }
            next.pending[snapshot.noteId]?.let { current ->
                if (current.kind != "create") return@let
                if (serverTitle(current.title) == remote.title && current.html == html) {
                    next.pending.remove(snapshot.noteId)
                } else {
                    next.pending[snapshot.noteId] = current.copy(
                        kind = "update", baseTitle = remote.title,
                        baseHash = digest(html), conflict = false,
                    )
                }
            }
        }
    }

    private suspend fun markConflict(noteId: String, remote: NoteMeta, html: String) {
        change { next ->
            if (noteId in next.deleted) return@change
            next.notes[noteId] = StoredNote(remote, html)
            next.pending[noteId]?.let { next.pending[noteId] = it.copy(conflict = true) }
        }
    }

    private suspend fun commitSynced(snapshot: Pending, remote: NoteMeta, html: String) {
        change { next ->
            if (snapshot.noteId in next.deleted) return@change
            next.notes[snapshot.noteId] = StoredNote(remote, html)
            val current = next.pending[snapshot.noteId]
            if (current != null && current.revision == snapshot.revision) {
                next.pending.remove(snapshot.noteId)
            } else if (current != null && current.kind == snapshot.kind) {
                next.pending[snapshot.noteId] = current.copy(
                    kind = current.kind, baseTitle = serverTitle(snapshot.title), baseHash = digest(html),
                    conflict = false,
                )
            }
        }
    }

    private suspend fun change(block: (Store) -> Unit) = withContext(Dispatchers.IO) {
        if (cacheError != null) throw IOException(cacheError)
        stateMutex.withLock {
            val next = store.copy(
                notes = store.notes.toMutableMap(),
                children = store.children.toMutableMap(),
                pending = store.pending.toMutableMap(),
                deleted = store.deleted.toMutableSet(),
                treePending = store.treePending.toMutableMap(), trash = store.trash.toMutableMap(),
                attachments = store.attachments.toMutableMap(), completeChildren = store.completeChildren.toMutableSet(),
                captures = store.captures.toMutableSet(), remoteTrash = store.remoteTrash.toMutableMap(),
            )
            block(next)
            next.treePending.values.sortedBy { it.revision }.forEach { operation ->
                if (operation.kind == "copy") operation.copyNodes.forEach { node ->
                    next.notes[node.id]?.let { stored ->
                        val parents = (operation.copyEdges.filter { it.noteId == node.id }.map { it.parentId } + node.parentId).distinct()
                        next.notes[node.id] = stored.copy(meta = stored.meta.copy(parentNoteIds = parents))
                    }
                }
                if (operation.kind in setOf("move", "clone")) next.notes[operation.noteId]?.let { stored ->
                    val parents = (if (operation.kind == "move") stored.meta.parentNoteIds - operation.fromParentId
                        else stored.meta.parentNoteIds) + operation.targetParentId
                    next.notes[operation.noteId] = stored.copy(meta = stored.meta.copy(parentNoteIds = parents.distinct()))
                }
            }
            val excluded = deletionClosure(next)
            next.pending.replaceAll { id, draft ->
                if (id in excluded || draft.parentId !in excluded) draft else {
                    val parents = next.notes[id]?.meta?.parentNoteIds.orEmpty().ifEmpty {
                        next.children.filterValues { items -> items.any { it.note.id == id } }.keys.toList()
                    }
                    val parent = parents.firstOrNull { it !in excluded && it !in weakParents }
                    if (parent == null) draft else draft.copy(parentId = parent)
                }
            }
            if (next != store) writeStore(next)
            store = next
            mutableSyncState.value = next.toSyncState(isSyncing = mutableSyncState.value.isSyncing)
        }
    }

    /** Refresh parentage only from metadata already received; never crawl remote ancestors. */
    private suspend fun rememberVisibilityMetadata(notes: Collection<NoteMeta>) {
        if (notes.isEmpty()) return
        change { next -> notes.forEach { meta ->
            if (meta.id in next.deleted) return@forEach
            val old = next.notes[meta.id]
            // Keep the edit baseline intact; only visibility parentage is refreshed in a loaded document.
            next.notes[meta.id] = if (old?.html == null) StoredNote(meta, null)
                else old.copy(meta = old.meta.copy(parentNoteIds = meta.parentNoteIds))
        } }
    }

    private fun visibility(snapshot: Store, extra: Collection<NoteMeta> = emptyList()): HiddenNotes {
        val known = snapshot.notes.mapValuesTo(mutableMapOf()) { it.value.meta }
        snapshot.children.values.flatten().forEach { if (it.note.id !in known) known[it.note.id] = it.note }
        extra.forEach { known[it.id] = it }
        return HiddenNotes(known.values, snapshot.children)
    }

    private fun document(snapshot: Store, noteId: String): NoteDocument? {
        if (noteId in deletionClosure(snapshot)) return null
        val stored = snapshot.notes[noteId] ?: return null
        val html = stored.html ?: return null
        val pending = snapshot.pending[noteId]
        return NoteDocument(
            note = if (pending == null) stored.meta else stored.meta.copy(title = pending.title),
            html = pending?.html ?: html,
            pending = pending != null,
            conflict = pending?.conflict == true,
            remoteMissing = pending?.remoteMissing == true,
        )
    }

    private fun withDrafts(snapshot: Store, parentId: String, children: List<TreeItem>): List<TreeItem> {
        val existing = children.map { item ->
            val pending = snapshot.pending[item.note.id]
            if (pending == null) item else item.copy(note = item.note.copy(title = pending.title))
        }
        val known = existing.mapTo(mutableSetOf()) { it.note.id }
        val local = snapshot.pending.values.filter {
            it.kind == "create" && it.parentId == parentId && it.noteId !in known &&
                snapshot.treePending.values.none { operation -> operation.kind == "move" && operation.noteId == it.noteId && operation.fromParentId == parentId }
        }.sortedBy { it.revision }.mapIndexed { index, pending ->
            val meta = requireNotNull(snapshot.notes[pending.noteId]).meta.copy(title = pending.title)
            TreeItem(requireNotNull(pending.branchId), meta, "", Int.MAX_VALUE - 1000 + index, false)
        }
        return existing + local
    }

    private fun readStore(): Store {
        val bytes = try { file.openRead().use { it.readBytes() } }
        catch (_: FileNotFoundException) { return Store() }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        val notes = mutableMapOf<String, StoredNote>()
        val notesJson = json.optJSONObject("notes") ?: JSONObject()
        for (id in notesJson.keys()) {
            val item = notesJson.getJSONObject(id)
            notes[id] = StoredNote(parseNote(item.getJSONObject("meta")),
                if (item.isNull("html")) null else item.getString("html"))
        }
        val children = mutableMapOf<String, List<TreeItem>>()
        val childrenJson = json.optJSONObject("children") ?: JSONObject()
        for (parent in childrenJson.keys()) {
            val array = childrenJson.getJSONArray(parent)
            children[parent] = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                TreeItem(item.getString("branchId"), parseNote(item.getJSONObject("note")),
                    item.optString("prefix"), item.getInt("position"), item.optBoolean("isExpanded"))
            }
        }
        val pending = mutableMapOf<String, Pending>()
        val pendingJson = json.optJSONObject("pending") ?: JSONObject()
        for (id in pendingJson.keys()) pending[id] = Pending.fromJson(pendingJson.getJSONObject(id))
        val deletedJson = json.optJSONArray("deleted") ?: JSONArray()
        val deleted = (0 until deletedJson.length()).mapTo(mutableSetOf()) { deletedJson.getString(it) }
        val treePending = json.optJSONArray("treePending").objects().map(TreeOperation::parse).associateByTo(linkedMapOf()) { it.id }
        val trash = mutableMapOf<String, TrashRecord>()
        val trashJson = json.optJSONObject("trash") ?: JSONObject()
        for (id in trashJson.keys()) trash[id] = TrashRecord.parse(trashJson.getJSONObject(id))
        val attachments = mutableMapOf<String, List<AttachmentMeta>>()
        val attachmentsJson = json.optJSONObject("attachmentCatalog") ?: JSONObject()
        for (id in attachmentsJson.keys()) attachments[id] = attachmentsJson.getJSONArray(id).objects().map(::parseStoredAttachment)
        val remoteTrash = json.optJSONArray("remoteTrash").objects().map { item ->
            TrashItem(item.getString("noteId"), item.getString("title"),
                item.optString("deletedAt").takeIf { it.isNotBlank() && it != "null" }, item.optBoolean("canRestore"))
        }.associateByTo(mutableMapOf()) { it.noteId }
        return Store(notes, children, pending, json.optLong("counter"), deleted, treePending, trash, attachments,
            json.optJSONArray("completeChildren").strings().toMutableSet(), json.optJSONArray("captures").strings().toMutableSet(), remoteTrash)
    }

    private fun writeStore(next: Store) {
        val json = JSONObject().put("counter", next.counter).put("deleted", JSONArray(next.deleted.toList()))
        val notes = JSONObject()
        next.notes.forEach { (id, note) ->
            notes.put(id, JSONObject().put("meta", note.meta.toJson())
                .put("html", note.html ?: JSONObject.NULL))
        }
        val children = JSONObject()
        next.children.forEach { (parent, items) ->
            val array = JSONArray()
            items.forEach { item ->
                array.put(JSONObject().put("branchId", item.branchId).put("note", item.note.toJson())
                    .put("prefix", item.prefix).put("position", item.position)
                    .put("isExpanded", item.isExpanded))
            }
            children.put(parent, array)
        }
        val pending = JSONObject()
        next.pending.forEach { (id, item) -> pending.put(id, item.toJson()) }
        val trash = JSONObject()
        next.trash.forEach { (id, entry) -> trash.put(id, entry.toJson()) }
        val attachments = JSONObject()
        next.attachments.forEach { (id, entries) -> attachments.put(id, JSONArray(entries.map { it.toStoredJson() })) }
        val bytes = json.put("notes", notes).put("children", children)
            .put("pending", pending).put("treePending", JSONArray(next.treePending.values.map { it.toJson() }))
            .put("trash", trash).put("attachmentCatalog", attachments)
            .put("completeChildren", JSONArray(next.completeChildren.toList())).put("captures", JSONArray(next.captures.toList()))
            .put("remoteTrash", JSONArray(next.remoteTrash.values.map { item -> JSONObject().put("noteId", item.noteId)
                .put("title", item.title).put("deletedAt", item.deletedAt).put("canRestore", item.canRestore) }))
            .toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun offlineError(error: Exception): IOException =
        IOException(safeMessage(error), error)

    private fun safeMessage(error: Exception): String = when (error) {
        is TreeOperationException -> text(error.resource)
        is HiddenNoteException -> text(R.string.msg_hidden_notes)
        is UnexpectedHtmlResponseException -> loadFailureMessage(context, error)
        is ApiException -> when (error.status) {
            401, 403 -> text(R.string.msg_sync_forbidden)
            404 -> text(R.string.msg_sync_missing)
            else -> text(R.string.msg_sync_http, error.status)
        }
        is IOException -> text(R.string.msg_sync_offline)
        else -> text(R.string.msg_sync_response)
    }
}

private fun deletionClosure(
    snapshot: Store,
    seeds: Set<String> = snapshot.deleted,
    extra: Collection<NoteMeta> = emptyList(),
): Set<String> {
    val deleted = seeds.toMutableSet()
    val parents = snapshot.notes.mapValuesTo(mutableMapOf()) { it.value.meta.parentNoteIds.toSet() }
    extra.forEach { parents[it.id] = it.parentNoteIds.toSet() }
    val edges = mutableMapOf<String, MutableSet<String>>()
    snapshot.children.forEach { (parent, items) -> items.forEach {
        if (parent !in weakParents) edges.getOrPut(it.note.id) { mutableSetOf() } += parent
    } }
    edges.forEach { (id, known) -> if (parents[id].isNullOrEmpty()) parents[id] = known }
    parents.replaceAll { _, known -> known.filterNot { it in weakParents }.toSet() }
    var changed: Boolean
    do {
        changed = false
        parents.forEach { (id, known) ->
            if (id != "root" && known.isNotEmpty() && known.all { it in deleted } && deleted.add(id)) changed = true
        }
    } while (changed)
    return deleted
}

private data class TrashRecord(val title: String, val deletedAt: String?, val draft: Pending?,
    val localOnly: Boolean, val deleteAttempted: Boolean = false) {
    fun toJson() = JSONObject().put("title", title).put("deletedAt", deletedAt).put("draft", draft?.toJson())
        .put("localOnly", localOnly).put("deleteAttempted", deleteAttempted)
    companion object {
        fun parse(json: JSONObject) = TrashRecord(json.getString("title"),
            json.optString("deletedAt").takeIf { it.isNotBlank() && it != "null" },
            json.optJSONObject("draft")?.let(Pending::fromJson), json.optBoolean("localOnly"), json.optBoolean("deleteAttempted"))
    }
}

private fun AttachmentMeta.toStoredJson(): JSONObject = JSONObject().put("attachmentId", id).put("ownerId", ownerId)
    .put("role", role).put("mime", mime).put("title", title).put("position", position).put("blobId", blobId)
private fun parseStoredAttachment(json: JSONObject) = AttachmentMeta(json.getString("attachmentId"), json.getString("ownerId"),
    json.getString("role"), json.getString("mime"), json.getString("title"), json.getInt("position"),
    json.optString("blobId").takeIf { it.isNotBlank() && it != "null" })

private data class StoredNote(val meta: NoteMeta, val html: String?)

private data class Store(
    val notes: MutableMap<String, StoredNote> = mutableMapOf(),
    val children: MutableMap<String, List<TreeItem>> = mutableMapOf(),
    val pending: MutableMap<String, Pending> = mutableMapOf(),
    var counter: Long = 0,
    val deleted: MutableSet<String> = mutableSetOf(),
    val treePending: MutableMap<String, TreeOperation> = linkedMapOf(),
    val trash: MutableMap<String, TrashRecord> = mutableMapOf(),
    val attachments: MutableMap<String, List<AttachmentMeta>> = mutableMapOf(),
    val completeChildren: MutableSet<String> = mutableSetOf(),
    val captures: MutableSet<String> = mutableSetOf(),
    val remoteTrash: MutableMap<String, TrashItem> = mutableMapOf(),
) {
    fun toSyncState(isSyncing: Boolean = false, error: String? = null): SyncState {
        val excluded = deletionClosure(this)
        val active = pending.values.filter { it.kind in setOf("delete", "restore") || it.noteId !in excluded }
        return SyncState(
            pendingCount = active.size + treePending.size,
            isSyncing = isSyncing,
            conflicts = active.filter { it.conflict }.mapTo(mutableSetOf()) { it.noteId },
            error = error,
        )
    }
}

private data class Pending(
    val kind: String,
    val noteId: String,
    val branchId: String?,
    val parentId: String?,
    val title: String,
    val html: String,
    val baseTitle: String?,
    val baseHash: String?,
    val revision: Long,
    val conflict: Boolean = false,
    val noteType: String = "text",
    val noteMime: String = "text/html",
    val remoteMissing: Boolean = false,
) {
    fun toJson() = JSONObject().put("kind", kind).put("noteId", noteId)
        .put("branchId", branchId ?: JSONObject.NULL).put("parentId", parentId ?: JSONObject.NULL)
        .put("title", title).put("html", html)
        .put("baseTitle", baseTitle ?: JSONObject.NULL)
        .put("baseHash", baseHash ?: JSONObject.NULL)
        .put("revision", revision).put("conflict", conflict)
        .put("noteType", noteType).put("noteMime", noteMime).put("remoteMissing", remoteMissing)

    companion object {
        fun fromJson(json: JSONObject) = Pending(
            kind = json.getString("kind"),
            noteId = json.getString("noteId"),
            branchId = if (json.isNull("branchId")) null else json.getString("branchId"),
            parentId = if (json.isNull("parentId")) null else json.getString("parentId"),
            title = json.getString("title"), html = json.getString("html"),
            baseTitle = if (json.isNull("baseTitle")) null else json.getString("baseTitle"),
            baseHash = if (json.isNull("baseHash")) null else json.getString("baseHash"),
            revision = json.getLong("revision"), conflict = json.optBoolean("conflict"),
            noteType = json.optString("noteType", "text"),
            noteMime = json.optString("noteMime", "text/html"),
            remoteMissing = json.optBoolean("remoteMissing"),
        )
    }
}

private fun NoteMeta.toJson(): JSONObject = JSONObject()
    .put("noteId", id).put("title", title).put("type", type).put("mime", mime)
    .put("isProtected", isProtected).put("blobId", blobId)
    .put("utcDateModified", modifiedAt)
    .put("childBranchIds", JSONArray(childBranchIds))
    .put("parentNoteIds", JSONArray(parentNoteIds))
    .put("docName", docName ?: JSONObject.NULL)
    .put("attributes", JSONArray(attributes.map { it.toJson() }))

private val weakParents = setOf("_share", "_lbBookmarks")

private fun newId(): String = UUID.randomUUID().toString().replace("-", "")

private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun serverTitle(title: String): String = title.ifBlank { "Без названия" }
