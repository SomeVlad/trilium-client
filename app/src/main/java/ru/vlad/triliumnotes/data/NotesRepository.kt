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

    fun isDeleted(noteId: String): Boolean = noteId in deletionClosure(store)
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
                next.children[parentId] = available
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

    /** Stages a note with stable client IDs, so a lost create response cannot duplicate it. */
    suspend fun create(parentId: String, title: String, html: String): NoteDocument =
        stageCreate(parentId, title, html, "text", "text/html")

    /** A native book remains a navigable folder even before it has any children. */
    suspend fun createFolder(parentId: String, title: String): NoteDocument {
        require(title.isNotBlank()) { "Enter a folder name" }
        return stageCreate(parentId, title.trim(), "", "book", "text/html")
    }

    private suspend fun stageCreate(parentId: String, title: String, html: String,
        type: String, mime: String): NoteDocument {
        val noteId = newId()
        val branchId = newId()
        change { next ->
            require(parentId !in deletionClosure(next)) { "Parent note is deleted" }
            next.counter++
            val meta = NoteMeta(noteId, title, type, mime, false,
                null, null, emptyList(), listOf(parentId))
            next.notes[noteId] = StoredNote(meta, "")
            next.pending[noteId] = Pending("create", noteId, branchId, parentId,
                title, html, null, null, next.counter, noteType = type, noteMime = mime)
            if (type == "book") next.children[noteId] = emptyList()
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

    suspend fun syncPending(): SyncState = syncMutex.withLock {
        if (cacheError != null) return@withLock store.toSyncState(error = cacheError)
        mutableSyncState.value = store.toSyncState(isSyncing = true)
        val snapshots = store.pending.values.toList().sortedBy { it.revision }
        var lastError: String? = null
        try {
            for (pending in snapshots) {
                val current = store.pending[pending.noteId]
                if (current == null || current.kind != pending.kind || current.revision != pending.revision || pending.conflict) continue
                if (pending.kind != "delete" && isDeleted(pending.noteId)) continue
                try {
                    when (pending.kind) {
                        "delete" -> syncDelete(pending)
                        "create" -> syncCreate(pending)
                        else -> syncUpdate(pending)
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    lastError = if (pending.kind == "delete")
                        text(R.string.msg_delete_failed, safeMessage(error)) else safeMessage(error)
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
        if (!remote.isEditable || remote.type != pending.noteType || remote.mime != pending.noteMime) {
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
        if (store.pending[pending.noteId]?.kind != "update" || isDeleted(pending.noteId)) return
        if (remoteHtml != pending.html) api.putContent(pending.noteId, pending.html)
        if (isDeleted(pending.noteId)) return
        if (remote.title != serverTitle(pending.title)) api.patchTitle(pending.noteId, serverTitle(pending.title))
        commitSynced(pending, api.getNote(pending.noteId), pending.html)
    }

    private suspend fun markRemoteMissing(noteId: String) = change { next ->
        next.pending[noteId]?.takeIf { it.kind == "update" || it.kind == "create" }?.let {
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
                    kind = "update", baseTitle = serverTitle(snapshot.title), baseHash = digest(html),
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
            )
            block(next)
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
            it.kind == "create" && it.parentId == parentId && it.noteId !in known
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
        return Store(notes, children, pending, json.optLong("counter"), deleted)
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
        val bytes = json.put("notes", notes).put("children", children)
            .put("pending", pending).toString().toByteArray(Charsets.UTF_8)
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

private data class StoredNote(val meta: NoteMeta, val html: String?)

private data class Store(
    val notes: MutableMap<String, StoredNote> = mutableMapOf(),
    val children: MutableMap<String, List<TreeItem>> = mutableMapOf(),
    val pending: MutableMap<String, Pending> = mutableMapOf(),
    var counter: Long = 0,
    val deleted: MutableSet<String> = mutableSetOf(),
) {
    fun toSyncState(isSyncing: Boolean = false, error: String? = null): SyncState {
        val excluded = deletionClosure(this)
        val active = pending.values.filter { it.kind == "delete" || it.noteId !in excluded }
        return SyncState(
            pendingCount = active.size,
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

private val weakParents = setOf("_share", "_lbBookmarks")

private fun newId(): String = UUID.randomUUID().toString().replace("-", "")

private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun serverTitle(title: String): String = title.ifBlank { "Без названия" }
