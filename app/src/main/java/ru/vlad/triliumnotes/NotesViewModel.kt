package ru.vlad.triliumnotes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.vlad.triliumnotes.data.HiddenNoteException
import ru.vlad.triliumnotes.data.NoteDocument
import ru.vlad.triliumnotes.data.NoteMeta
import ru.vlad.triliumnotes.data.NotesRepository
import ru.vlad.triliumnotes.data.TreeItem
import ru.vlad.triliumnotes.data.TriliumApi
import ru.vlad.triliumnotes.data.loadFailureMessage
import ru.vlad.triliumnotes.data.isEditable
import ru.vlad.triliumnotes.data.canDelete
import ru.vlad.triliumnotes.data.NoteResources
import ru.vlad.triliumnotes.data.ResourceContent
import ru.vlad.triliumnotes.data.CachedAttachment
import ru.vlad.triliumnotes.data.NotesServices
import ru.vlad.triliumnotes.data.NotesSyncScheduler

data class Folder(val id: String, val title: String)
data class NotesScreenState(
    val folders: List<Folder> = listOf(Folder("root", "")),
    val items: List<TreeItem> = emptyList(),
    val query: String = "",
    val results: List<NoteMeta> = emptyList(),
    val document: NoteDocument? = null,
    val loading: Boolean = true,
    val searching: Boolean = false,
    val error: String? = null,
    val localWritePending: Boolean = false,
    val localSaveFailed: Boolean = false,
    val newNoteId: String? = null,
    val newNoteTitleUntouched: Boolean = false,
    val currentFolderNote: NoteMeta? = null,
    val currentFolderHasContent: Boolean? = null,
    val openingNoteId: String? = null,
    val fromCache: Boolean = false,
    val networkAvailable: Boolean = true,
    val deletingNoteId: String? = null,
)

class NotesViewModel(application: Application,
    private val services: NotesServices = NotesServices.get(application),
) : AndroidViewModel(application) {
    private fun text(id: Int) = AppLanguage.get(getApplication(), id)
    val serverUrl = services.serverUrl
    private val repository = services.repository
    private val resources = services.resources
    val offlineState = services.offline.state
    private var networkAvailable = application.hasValidatedInternet()
    private val mutable = MutableStateFlow(NotesScreenState(networkAvailable = networkAvailable))
    val state = mutable.asStateFlow()
    val syncState = repository.syncState
    fun loadResource(path: String): ResourceContent? = resources.load(path)
    suspend fun loadAttachment(note: NoteMeta): CachedAttachment = resources.loadNote(note)
    suspend fun loadLinkedAttachment(path: String): CachedAttachment = resources.loadLinkedAttachment(path)
    private var folderJob: Job? = null
    private var searchJob: Job? = null
    private var openJob: Job? = null
    private var syncJob: Job? = null
    private var editVersion = 0L
    private var navigationVersion = 0L
    private val noteHistory = mutableListOf<NoteMeta>()
    private data class Draft(val id: String, val title: String, val html: String, val version: Long)
    // Navigation is held until the latest draft is durable, so only the newest
    // pending edit for the currently open note needs to wait behind a disk write.
    private val writes = Channel<Draft>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            for (draft in writes) {
                try {
                    repository.saveDraft(draft.id, draft.title, draft.html)
                    mutable.update {
                        if (editVersion == draft.version && it.document?.note?.id == draft.id)
                            it.copy(localWritePending = false, localSaveFailed = false, error = it.error.takeUnless { _ -> it.localSaveFailed })
                        else it
                    }
                    scheduleSync()
                    NotesSyncScheduler.schedulePending(getApplication())
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutable.update {
                        if (editVersion == draft.version && it.document?.note?.id == draft.id) it.copy(error = text(R.string.msg_local_save_failed), localSaveFailed = true)
                        else it
                    }
                }
            }
        }
        refreshFolder()
        viewModelScope.launch {
            while (true) { syncNow(); delay(15_000) }
        }
        viewModelScope.launch {
            var wasRunning = false
            offlineState.collect { offline ->
                if (wasRunning && !offline.isRunning && mutable.value.document == null && mutable.value.query.isBlank()) {
                    val folder = mutable.value.folders.last().id
                    repository.cachedChildren(folder)?.let { items ->
                        mutable.update { it.copy(items = items,
                            error = if (offline.complete) null else it.error,
                            fromCache = if (offline.complete) false else it.fromCache) }
                    }
                }
                wasRunning = offline.isRunning
            }
        }
    }

    fun refreshFolder() {
        if (mutable.value.deletingNoteId != null) return
        val folder = mutable.value.folders.last()
        folderJob?.cancel()
        val cached = repository.cachedChildren(folder.id)
        val cachedDocument = repository.cachedNote(folder.id)
        val cachedMeta = repository.cachedMeta(folder.id)
            ?: mutable.value.currentFolderNote?.takeIf { it.id == folder.id }
        val knownContent = when {
            folder.id == "root" || cachedMeta?.type in setOf("book", "launcher", "contentWidget", "search", "noteMap") -> false
            cachedDocument != null -> folderHasContent(folder.id, cachedDocument.note, cachedDocument.html)
            else -> mutable.value.currentFolderHasContent.takeIf { mutable.value.currentFolderNote?.id == folder.id }
        }
        mutable.update { it.copy(items = cached ?: it.items, loading = cached == null,
            currentFolderNote = cachedMeta, currentFolderHasContent = knownContent) }
        if (!networkAvailable && cached != null) {
            mutable.update { it.copy(loading = false, error = null, fromCache = true) }
            return
        }
        folderJob = viewModelScope.launch {
            try {
                val result = repository.listChildrenResult(folder.id)
                val hasContent = when {
                    folder.id == "root" -> false
                    result.parent.type in setOf("book", "launcher", "contentWidget", "search", "noteMap") -> false
                    else -> try {
                        val cachedNote = repository.cachedNote(folder.id)
                        val sameVersion = cachedNote != null &&
                            (cachedNote.note.blobId != null || cachedNote.note.modifiedAt != null) &&
                            cachedNote.note.blobId == result.parent.blobId &&
                            cachedNote.note.modifiedAt == result.parent.modifiedAt &&
                            cachedNote.note.type == result.parent.type && cachedNote.note.mime == result.parent.mime &&
                            cachedNote.note.isProtected == result.parent.isProtected
                        val body = (if (cachedNote != null && (cachedNote.pending || result.fromCache || sameVersion))
                            cachedNote else repository.load(folder.id)).html
                        folderHasContent(folder.id, result.parent, body)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { knownContent }
                }
                if (mutable.value.folders.last().id == folder.id) mutable.update {
                    it.copy(items = result.items, loading = false, currentFolderNote = result.parent, currentFolderHasContent = hasContent, fromCache = result.fromCache,
                        error = if (result.failedBranchIds.isEmpty()) null else
                            text(R.string.msg_list_partial))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is HiddenNoteException && mutable.value.folders.last().id == folder.id) {
                    mutable.update { it.copy(items = emptyList(), currentFolderNote = null,
                        currentFolderHasContent = false, loading = false, error = null) }
                    if (mutable.value.folders.size > 1) back()
                    return@launch
                }
                if (mutable.value.folders.last().id == folder.id) {
                    mutable.update { it.copy(loading = false, error = loadFailureMessage(getApplication(), error)) }
                }
            }
        }
    }

    fun openCurrentFolder() {
        mutable.value.currentFolderNote?.let(::open)
    }

    fun refreshCurrentView() {
        val current = mutable.value
        if (current.deletingNoteId != null) return
        if (current.document != null) {
            if (!current.localWritePending) open(current.document.note, preserveHistory = true, forceRefresh = true)
        } else if (current.query.isNotBlank()) search(current.query)
        else refreshFolder()
    }

    fun enterFolder(note: NoteMeta) {
        if (mutable.value.localWritePending || mutable.value.deletingNoteId != null || repository.isHidden(note)) return
        repository.pinDocument(null)
        noteHistory.clear()
        navigationVersion++
        openJob?.cancel()
        searchJob?.cancel()
        mutable.update { it.copy(folders = it.folders + Folder(note.id, note.title), query = "", results = emptyList(), items = emptyList(), currentFolderNote = note, currentFolderHasContent = null, searching = false, openingNoteId = null, error = null, fromCache = false) }
        refreshFolder()
    }

    fun back() {
        if (mutable.value.deletingNoteId != null) return
        if (mutable.value.openingNoteId != null) {
            navigationVersion++
            openJob?.cancel()
            repository.pinDocument(null)
            mutable.update { it.copy(openingNoteId = null, loading = false, error = null) }
            if (noteHistory.isNotEmpty()) open(noteHistory.removeAt(noteHistory.lastIndex), preserveHistory = true)
        } else if (mutable.value.document != null) {
            if (mutable.value.localWritePending) return
            if (noteHistory.isNotEmpty()) {
                open(noteHistory.removeAt(noteHistory.lastIndex), preserveHistory = true)
                return
            }
            navigationVersion++
            openJob?.cancel()
            repository.pinDocument(null)
            mutable.update { it.copy(document = null, error = null, newNoteId = null) }
            refreshCurrentView()
            syncNow()
        } else if (mutable.value.query.isNotEmpty()) search("")
        else if (mutable.value.folders.size > 1) {
            navigationVersion++
            mutable.update { it.copy(folders = it.folders.dropLast(1), items = emptyList(), currentFolderHasContent = null, error = null, fromCache = false) }
            refreshFolder()
        }
    }

    fun search(query: String) {
        if (mutable.value.deletingNoteId != null) return
        searchJob?.cancel()
        // Refreshing the same result set must not unmount its rows or clamp the
        // saved scroll position while returning from a note or reconnecting.
        mutable.update { it.copy(query = query, searching = query.isNotBlank(),
            results = if (query == it.query) it.results else emptyList(), error = null) }
        if (query.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(300)
            try {
                val result = repository.searchResult(query, cacheOnly = !networkAvailable)
                if (mutable.value.query == query) mutable.update {
                    it.copy(results = result.notes, searching = false, fromCache = result.fromCache, error = null)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (mutable.value.query == query) mutable.update { it.copy(searching = false, error = text(R.string.msg_search_unavailable)) }
            }
        }
    }

    fun open(note: NoteMeta) = open(note, preserveHistory = false)

    private fun open(note: NoteMeta, preserveHistory: Boolean, forceRefresh: Boolean = false) {
        if (mutable.value.localWritePending || mutable.value.deletingNoteId != null || repository.isHidden(note)) return
        repository.pinDocument(note.id)
        if (!preserveHistory) noteHistory.clear()
        val request = ++navigationVersion
        openJob?.cancel()
        folderJob?.cancel()
        val cached = repository.cachedNote(note.id)
        // Open the cached body and its edit baseline together, without a network
        // wait. Only an explicit refresh replaces a clean document before mounting
        // its editor; pending drafts remain authoritative on either path.
        if (cached != null && (!forceRefresh || cached.pending || !networkAvailable)) {
            mutable.update { it.copy(document = cached, loading = false, error = null, localWritePending = false, openingNoteId = null) }
            return
        }
        mutable.update { it.copy(document = null, loading = true, error = null, localWritePending = false, openingNoteId = note.id) }
        val editAtOpen = editVersion
        openJob = viewModelScope.launch {
            try {
                val document = repository.load(note.id)
                if (request == navigationVersion && editVersion == editAtOpen) {
                    mutable.update { it.copy(document = document, loading = false, openingNoteId = null) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (request == navigationVersion) {
                    val previous = noteHistory.lastOrNull()?.let { repository.cachedNote(it.id) }
                    if (previous != null) noteHistory.removeAt(noteHistory.lastIndex)
                    repository.pinDocument(previous?.note?.id)
                    mutable.update { it.copy(document = previous, loading = false, openingNoteId = null,
                        error = loadFailureMessage(getApplication(), error)) }
                }
            }
        }
    }

    fun openById(noteId: String) {
        if (mutable.value.localWritePending || mutable.value.deletingNoteId != null || noteId == mutable.value.document?.note?.id || repository.isHidden(noteId)) return
        if (!noteId.matches(Regex("[A-Za-z0-9_]{4,32}"))) {
            mutable.update { it.copy(error = text(R.string.msg_note_link_invalid)) }
            return
        }
        val current = mutable.value.document?.note
        val meta = repository.cachedMeta(noteId)
        // The ID is enough for open(): cached content wins, otherwise the repository loads it.
        val target = meta ?: NoteMeta(noteId, "", "", "", false, null, null, emptyList())
        current?.let { noteHistory += it }
        open(target, preserveHistory = true)
    }

    fun create() {
        if (mutable.value.loading || mutable.value.deletingNoteId != null) return
        val parent = mutable.value.folders.last().id
        val request = ++navigationVersion
        mutable.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val note = repository.create(parent, "", "<p></p>")
                if (request == navigationVersion && mutable.value.folders.last().id == parent) {
                    repository.pinDocument(note.note.id)
                    mutable.update { it.copy(document = note, loading = false, newNoteId = note.note.id,
                        newNoteTitleUntouched = true) }
                }
                scheduleSync()
                NotesSyncScheduler.schedulePending(getApplication())
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(loading = false, error = text(R.string.msg_create_note_failed)) }
            }
        }
    }

    fun createFolder(title: String) {
        val current = mutable.value
        if (title.isBlank() || current.loading || current.localWritePending ||
            current.deletingNoteId != null || current.document != null) return
        val parent = current.folders.last().id
        val request = ++navigationVersion
        folderJob?.cancel()
        searchJob?.cancel()
        mutable.update { it.copy(loading = true, searching = false, error = null) }
        viewModelScope.launch {
            try {
                repository.createFolder(parent, title)
                if (request == navigationVersion && mutable.value.folders.last().id == parent) {
                    mutable.update { it.copy(items = repository.cachedChildren(parent).orEmpty(),
                        query = "", results = emptyList(), loading = false) }
                }
                scheduleSync()
                NotesSyncScheduler.schedulePending(getApplication())
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (request == navigationVersion) mutable.update {
                    it.copy(loading = false, error = text(R.string.msg_create_folder_failed))
                }
            }
        }
    }

    /** The list changes only after the removal intent has been written to disk. */
    fun delete(note: NoteMeta) {
        val before = mutable.value
        if (!note.canDelete || repository.isHidden(note) || before.deletingNoteId != null || before.localWritePending) return
        navigationVersion++
        openJob?.cancel()
        folderJob?.cancel()
        searchJob?.cancel()
        mutable.update { it.copy(deletingNoteId = note.id, error = null) }
        viewModelScope.launch {
            try {
                repository.delete(note.id)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(deletingNoteId = null, loading = false, searching = false,
                    error = text(R.string.msg_delete_local_failed)) }
                return@launch
            }
            noteHistory.removeAll { repository.isDeleted(it.id) }
            val current = mutable.value
            val folders = current.folders.takeWhile { !repository.isDeleted(it.id) }
                .ifEmpty { listOf(Folder("root", "")) }
            val folderChanged = folders != current.folders
            val document = current.document?.takeUnless { repository.isDeleted(it.note.id) }
            val folderId = folders.last().id
            repository.pinDocument(document?.note?.id)
            mutable.update { it.copy(
                folders = folders,
                document = document,
                items = repository.cachedChildren(folderId)
                    ?: it.items.filterNot { item -> repository.isDeleted(item.note.id) },
                query = if (folderChanged) "" else it.query,
                results = if (folderChanged) emptyList() else it.results.filterNot { meta -> repository.isDeleted(meta.id) },
                currentFolderNote = repository.cachedMeta(folderId),
                currentFolderHasContent = if (folderChanged) null else it.currentFolderHasContent,
                newNoteId = it.newNoteId?.takeUnless(repository::isDeleted),
                openingNoteId = null, loading = false, searching = false,
                deletingNoteId = null, error = null,
            ) }
            schedulePendingChanges()
            if (networkAvailable) syncNow()
            refreshCurrentView()
        }
    }

    fun editTitle(title: String) {
        val current = mutable.value
        if (current.newNoteId == current.document?.note?.id && current.newNoteTitleUntouched)
            mutable.update { it.copy(newNoteTitleUntouched = false) }
        edit(title = title)
    }
    fun editHtml(html: String) = edit(html = html)
    fun editTitle(noteId: String, title: String) {
        if (mutable.value.document?.note?.id == noteId) editTitle(title)
    }
    fun editHtml(noteId: String, html: String) {
        if (mutable.value.document?.note?.id == noteId) edit(html = html)
    }
    fun retryLocalSave() {
        val current = mutable.value
        val document = current.document ?: return
        if (!document.note.isEditable || !current.localWritePending || !current.localSaveFailed) return
        val version = ++editVersion
        mutable.update { it.copy(error = null, localSaveFailed = false) }
        writes.trySend(Draft(document.note.id, document.note.title, document.html, version))
    }

    private fun edit(title: String? = null, html: String? = null) {
        if (mutable.value.deletingNoteId != null) return
        val before = mutable.value.document ?: return
        if (!before.note.isEditable) return
        val changed = before.copy(note = before.note.copy(title = title ?: before.note.title), html = html ?: before.html, pending = true)
        if (changed.note.title == before.note.title && changed.html == before.html) return
        val version = ++editVersion
        mutable.update { it.copy(document = changed, localWritePending = true, localSaveFailed = false, error = null) }
        writes.trySend(Draft(changed.note.id, changed.note.title, changed.html, version))
    }

    private fun scheduleSync() {
        syncJob?.cancel()
        // Cancel only the waiting timer. A newer keystroke must never cancel a
        // request already writing the previous draft to the server.
        syncJob = viewModelScope.launch { delay(1_200); syncNow() }
    }

    fun syncNow() { viewModelScope.launch { performSync() } }

    private suspend fun performSync() {
        if (syncState.value.isSyncing) return
        if (syncState.value.pendingCount <= syncState.value.conflicts.size) return
        val pendingBefore = syncState.value.pendingCount
        try {
            val result = repository.syncPending()
            val current = mutable.value.document
            if (current != null && !mutable.value.localWritePending) {
                val persisted = repository.cachedNote(current.note.id)
                val sameTitle = persisted?.note?.title == current.note.title ||
                    (current.note.title.isBlank() && persisted?.note?.title == "Без названия")
                if (persisted != null && persisted.html == current.html && sameTitle) {
                    mutable.update { it.copy(document = persisted) }
                }
            }
            if (pendingBefore > result.pendingCount && current == null && mutable.value.query.isBlank()) {
                repository.cachedChildren(mutable.value.folders.last().id)?.let { items ->
                    mutable.update { it.copy(items = items) }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Repository publishes the connection status and retains every pending draft.
        }
    }

    fun appStarted() {
        networkAvailable = getApplication<Application>().hasValidatedInternet()
        mutable.update { it.copy(networkAvailable = networkAvailable) }
        NotesSyncScheduler.ensurePeriodic(getApplication())
        NotesSyncScheduler.ensureOfflineCopy(getApplication(), offlineState.value)
        schedulePendingChanges()
        syncNow()
        if (mutable.value.document == null && folderJob?.isActive != true && searchJob?.isActive != true)
            refreshCurrentView()
    }
    fun connectionAvailable() {
        networkAvailable = true
        mutable.update { it.copy(networkAvailable = true) }
        NotesSyncScheduler.ensureOfflineCopy(getApplication(), offlineState.value)
        schedulePendingChanges()
        syncNow()
        if (mutable.value.document == null && folderJob?.isActive != true && searchJob?.isActive != true)
            refreshCurrentView()
    }
    fun connectionUnavailable() {
        networkAvailable = false
        mutable.update { it.copy(networkAvailable = false, fromCache = if (it.document == null) true else it.fromCache) }
    }
    fun refreshAll() {
        if (mutable.value.deletingNoteId != null) return
        NotesSyncScheduler.scheduleOneTime(getApplication())
        refreshCurrentView()
        syncNow()
    }
    fun clearError() { mutable.update { it.copy(error = null) } }

    private fun schedulePendingChanges() {
        if (syncState.value.pendingCount > syncState.value.conflicts.size)
            NotesSyncScheduler.schedulePending(getApplication())
    }

    private fun folderHasContent(id: String, note: NoteMeta, body: String): Boolean {
        if (id == "root" || note.type in setOf("book", "launcher", "contentWidget", "search", "noteMap")) return false
        if (note.type !in setOf("text", "doc")) return body.isNotBlank()
        val html = org.jsoup.Jsoup.parseBodyFragment(body).body()
        return html.text().isNotBlank() || html.select("img,table,hr,input,svg,math,canvas,iframe,video,audio,object,embed,oembed,script,[data-url],[data-embed-type]").isNotEmpty()
    }

    fun keepConflictAsCopy() {
        val id = mutable.value.document?.note?.id ?: return
        if (mutable.value.localWritePending || mutable.value.deletingNoteId != null) return
        viewModelScope.launch {
            try {
                val copy = repository.saveConflictAsCopy(id)
                repository.pinDocument(copy.note.id)
                NotesSyncScheduler.schedulePending(getApplication())
                navigationVersion++
                mutable.update { it.copy(document = copy, error = null) }
                syncNow()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(error = text(R.string.msg_conflict_copy_failed)) }
            }
        }
    }

    override fun onCleared() {
        repository.pinDocument(null)
        super.onCleared()
    }

}
