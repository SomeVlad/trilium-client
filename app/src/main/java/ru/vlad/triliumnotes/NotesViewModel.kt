package ru.vlad.triliumnotes

import android.app.Application
import android.net.Uri
import ru.vlad.triliumnotes.data.*
import ru.vlad.triliumnotes.capture.*
import ru.vlad.triliumnotes.ui.LibraryAction
import ru.vlad.triliumnotes.ui.NoteDestination
import java.util.UUID
import org.jsoup.nodes.Entities
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import ru.vlad.triliumnotes.data.AttachmentMeta
import ru.vlad.triliumnotes.data.PendingAttachmentUpload
import ru.vlad.triliumnotes.data.AttachmentInsertionDeferred
import ru.vlad.triliumnotes.data.NoteRevision
import ru.vlad.triliumnotes.data.RevisionDocument
import ru.vlad.triliumnotes.data.NoteHistory
import ru.vlad.triliumnotes.data.ApiException
import ru.vlad.triliumnotes.ui.NoteHistoryState
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
    val editorEpoch: Long = 0,
    val operationBusy: Boolean = false,
    val uploadStaging: Boolean = false,
    val librarySection: LibrarySection = LibrarySection.NOTES,
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
    val uploadState = services.uploads.state
    val attachmentEditState = services.attachments.state
    val libraryPreferences = services.libraryPreferences.state
    val accountId = accountIdFor(services.serverUrl)
    private val mutableTrash = MutableStateFlow<List<TrashItem>?>(null)
    val trashState = mutableTrash.asStateFlow()
    private val mutableAttachments = MutableStateFlow<List<AttachmentMeta>?>(null)
    val attachmentList = mutableAttachments.asStateFlow()
    var attachmentListOwner: String? = null
        private set
    private val mutablePanelError = MutableStateFlow<String?>(null)
    val panelError = mutablePanelError.asStateFlow()
    private val history = NoteHistory(services.api)
    private val mutableHistory = MutableStateFlow<NoteHistoryState?>(null)
    val historyState = mutableHistory.asStateFlow()
    var historyNote: NoteMeta? = null
        private set
    private var historyJob: Job? = null
    private var editingNoteId: String? = null
    private val insertionHandler: suspend (PendingAttachmentUpload, AttachmentMeta) -> Unit = { upload, attachment ->
        applyUploadedAttachment(upload, attachment)
    }
    private val metadataSyncGate: suspend (suspend () -> Unit) -> Unit = { operation ->
        withContext(Dispatchers.Main.immediate) {
            if (editingNoteId != null || mutable.value.localWritePending || mutable.value.operationBusy ||
                mutable.value.openingNoteId != null || mutable.value.deletingNoteId != null) throw AttachmentInsertionDeferred()
            mutable.update { it.copy(operationBusy = true) }
            try { withContext(Dispatchers.IO) { operation() } }
            finally {
                val current = mutable.value.document
                val saved = current?.let { repository.cachedNote(it.note.id) }
                if (saved != null && current != null && (saved.html != current.html || saved.note.title != current.note.title))
                    mutable.update { it.copy(document = saved, editorEpoch = it.editorEpoch + 1) }
                mutable.update { it.copy(operationBusy = false) }
            }
        }
    }
    fun loadResource(path: String): ResourceContent? = resources.load(path)
    suspend fun loadAttachment(note: NoteMeta): CachedAttachment = resources.loadNote(note)
    suspend fun loadLinkedAttachment(path: String): CachedAttachment = resources.loadLinkedAttachment(path)
    private var folderJob: Job? = null
    private var searchJob: Job? = null
    private var openJob: Job? = null
    private var syncJob: Job? = null
    private var activeSyncJob: Job? = null
    private var syncRequested = false
    private var editVersion = 0L
    private var navigationVersion = 0L
    private val noteHistory = mutableListOf<NoteMeta>()
    private data class Draft(val id: String, val title: String, val html: String, val version: Long)
    // Navigation is held until the latest draft is durable, so only the newest
    // pending edit for the currently open note needs to wait behind a disk write.
    private val writes = Channel<Draft>(Channel.CONFLATED)

    init {
        services.attachmentInsertionHandler = insertionHandler
        services.attachments.syncGate = metadataSyncGate
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
        if (mutable.value.deletingNoteId != null || mutable.value.operationBusy) return
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
        if (current.deletingNoteId != null || current.operationBusy) return
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
        mutable.update { it.copy(librarySection = LibrarySection.NOTES, folders = it.folders + Folder(note.id, note.title), query = "", results = emptyList(), items = emptyList(), currentFolderNote = note, currentFolderHasContent = null, searching = false, openingNoteId = null, error = null, fromCache = false) }
        recordOpened(note.id)
        refreshFolder()
    }

    fun back() {
        if (mutable.value.deletingNoteId != null || mutable.value.operationBusy) return
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
        else if (mutable.value.librarySection != LibrarySection.NOTES) {
            mutable.update { it.copy(librarySection = LibrarySection.NOTES) }
        }
        else if (mutable.value.folders.size > 1) {
            navigationVersion++
            mutable.update { it.copy(folders = it.folders.dropLast(1), items = emptyList(), currentFolderHasContent = null, error = null, fromCache = false) }
            refreshFolder()
        }
    }

    fun search(query: String) {
        if (mutable.value.deletingNoteId != null || mutable.value.operationBusy) return
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
        if (mutable.value.localWritePending || mutable.value.operationBusy || mutable.value.deletingNoteId != null || repository.isHidden(note)) return
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
            if (!forceRefresh) recordOpened(note.id)
            return
        }
        mutable.update { it.copy(document = null, loading = true, error = null, localWritePending = false, openingNoteId = note.id) }
        val editAtOpen = editVersion
        openJob = viewModelScope.launch {
            try {
                val document = repository.load(note.id)
                if (request == navigationVersion && editVersion == editAtOpen) {
                    mutable.update { it.copy(document = document, loading = false, openingNoteId = null) }
                    if (!forceRefresh) recordOpened(note.id)
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
                repository.trash(note.id)
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
        if (mutable.value.deletingNoteId != null || mutable.value.operationBusy) return
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

    fun syncNow() {
        syncRequested = true
        if (activeSyncJob?.isActive == true) return
        activeSyncJob = viewModelScope.launch {
            do { syncRequested = false; performSync() } while (syncRequested)
        }
    }

    private suspend fun performSync() {
        services.uploads.refreshPendingCount()
        services.attachments.refreshPendingCount()
        if (syncState.value.isSyncing) return
        if (syncState.value.pendingCount <= syncState.value.conflicts.size && uploadState.value.pendingCount == 0 && attachmentEditState.value.pendingCount == 0) return
        val pendingBefore = syncState.value.pendingCount
        try {
            repository.syncPending()
            services.uploads.syncPending()
            // Rewriting attachment references must not race a buffer owned by the editor.
            if (editingNoteId == null && !mutable.value.localWritePending && !mutable.value.operationBusy) services.attachments.syncPending()
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
        mutableHistory.update { it?.copy(allowRestore = true) }
        NotesSyncScheduler.ensureOfflineCopy(getApplication(), offlineState.value)
        schedulePendingChanges()
        syncNow()
        if (mutable.value.document == null && folderJob?.isActive != true && searchJob?.isActive != true)
            refreshCurrentView()
    }
    fun connectionUnavailable() {
        networkAvailable = false
        mutable.update { it.copy(networkAvailable = false, fromCache = if (it.document == null) true else it.fromCache) }
        mutableHistory.update { it?.copy(allowRestore = false) }
    }
    fun refreshAll() {
        if (mutable.value.deletingNoteId != null || mutable.value.operationBusy) return
        NotesSyncScheduler.scheduleOneTime(getApplication())
        refreshCurrentView()
        syncNow()
    }
    fun clearError() { mutable.update { it.copy(error = null) } }

    private fun schedulePendingChanges() {
        if (syncState.value.pendingCount > syncState.value.conflicts.size || uploadState.value.pendingCount > 0 || attachmentEditState.value.pendingCount > 0)
            NotesSyncScheduler.schedulePending(getApplication())
    }

    fun setEditing(noteId: String, editing: Boolean) {
        if (editing) editingNoteId = noteId
        else if (editingNoteId == noteId) {
            editingNoteId = null
            syncNow()
        }
    }

    fun stageAttachment(noteId: String, uri: Uri, inlineImage: Boolean, insertionAnchor: String? = null, onStaged: (Boolean) -> Unit = {}) {
        if (mutable.value.uploadStaging) return
        mutable.update { it.copy(uploadStaging = true, error = null) }
        viewModelScope.launch {
            var staged = false
            try {
                withTimeout(30_000) { state.first { !it.operationBusy && it.deletingNoteId == null } }
                awaitLocalWrites()
                services.uploads.stage(uri, noteId, inlineImage, insertionAnchor)
                staged = true
                val segments = uri.pathSegments
                if (uri.scheme == "content" && uri.authority == "${getApplication<Application>().packageName}.files" &&
                    segments.size == 2 && segments[0] == "capture_inbox" &&
                    segments[1].matches(Regex("camera-[0-9a-fA-F-]{36}\\.jpg"))) {
                    withContext(Dispatchers.IO) { java.io.File(getApplication<Application>().filesDir, "capture-inbox/${segments[1]}").delete() }
                }
                NotesSyncScheduler.schedulePending(getApplication())
                syncNow()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(error = text(R.string.msg_upload_failed)) }
            } finally { mutable.update { it.copy(uploadStaging = false) }; onStaged(staged) }
        }
    }

    private suspend fun applyUploadedAttachment(upload: PendingAttachmentUpload, attachment: AttachmentMeta) =
        withContext(Dispatchers.Main.immediate) {
            if (editingNoteId != null || mutable.value.localWritePending || mutable.value.operationBusy ||
                mutable.value.openingNoteId != null || mutable.value.deletingNoteId != null)
                throw AttachmentInsertionDeferred()
            mutable.update { it.copy(operationBusy = true) }
            try {
                val document = repository.appendAttachmentReference(upload, attachment)
                if (mutable.value.document?.note?.id == upload.noteId) mutable.update {
                    it.copy(document = document, editorEpoch = it.editorEpoch + 1,
                        newNoteId = null, newNoteTitleUntouched = false, error = null)
                }
                NotesSyncScheduler.schedulePending(getApplication())
            } finally { mutable.update { it.copy(operationBusy = false) } }
        }

    private suspend fun awaitLocalWrites() {
        val current = withTimeout(10_000) { state.first { !it.localWritePending || it.localSaveFailed } }
        check(!current.localSaveFailed && !current.localWritePending) { "Local draft is not saved" }
    }

    fun prepareAttachmentPick(ready: () -> Unit, failed: () -> Unit) {
        viewModelScope.launch {
            try { awaitLocalWrites(); ready() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(error = text(R.string.msg_local_save_failed)) }
                failed()
            }
        }
    }

    fun cleanupAttachmentAnchors(noteId: String, activePicker: String?) {
        if (editingNoteId != null || mutable.value.operationBusy || mutable.value.localWritePending || mutable.value.uploadStaging) return
        val current = repository.cachedNote(noteId) ?: return
        if (!current.note.isEditable || !current.html.contains("#pocket-upload-")) return
        mutable.update { it.copy(operationBusy = true) }
        viewModelScope.launch {
            try {
                val active = services.uploads.pendingAnchors() + listOfNotNull(activePicker)
                val body = clearAbandonedAttachmentPlaceholders(current.html, active, current.note.mime.contains("markdown", true))
                if (body != current.html) {
                    val document = repository.saveDraft(noteId, current.note.title, body)
                    mutable.update { if (it.document?.note?.id == noteId) it.copy(document = document, editorEpoch = it.editorEpoch + 1) else it }
                    schedulePendingChanges()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(error = text(R.string.msg_local_save_failed)) }
            } finally { mutable.update { it.copy(operationBusy = false) } }
        }
    }

    fun openHistory(noteId: String) {
        val note = repository.cachedMeta(noteId) ?: return
        if (mutableHistory.value?.working == true) return
        historyJob?.cancel()
        historyNote = note
        mutableHistory.value = NoteHistoryState(allowRestore = mutable.value.networkAvailable)
        historyJob = viewModelScope.launch {
            try {
                awaitLocalWrites()
                val revisions = history.list(noteId)
                mutableHistory.update { it?.copy(revisions = revisions, loading = false, error = null) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableHistory.update { it?.copy(loading = false, error = text(
                    if (error is ApiException && error.status in setOf(404, 405, 501)) R.string.history_unavailable
                    else R.string.history_load_error)) }
            }
        }
    }

    fun selectRevision(revision: NoteRevision) {
        val note = historyNote ?: return
        if (revision.noteId != note.id || mutableHistory.value?.working == true) return
        historyJob?.cancel()
        mutableHistory.update { it?.copy(selected = revision, preview = null, previewLoading = true, error = null) }
        historyJob = viewModelScope.launch {
            try {
                val preview = history.preview(note.id, revision.id)
                mutableHistory.update { it?.copy(preview = preview, previewLoading = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableHistory.update { it?.copy(previewLoading = false, error = text(R.string.history_preview_unavailable)) }
            }
        }
    }

    fun retryHistory() {
        val selected = mutableHistory.value?.selected
        if (selected != null) selectRevision(selected) else historyNote?.let { openHistory(it.id) }
    }

    fun historyBack() {
        if (mutableHistory.value?.working == true) return
        historyJob?.cancel()
        if (mutableHistory.value?.selected != null)
            mutableHistory.update { it?.copy(selected = null, preview = null, previewLoading = false, error = null) }
        else closeHistory()
    }

    fun closeHistory() {
        if (mutableHistory.value?.working == true) return
        historyJob?.cancel()
        mutableHistory.value = null
        historyNote = null
    }

    fun saveCurrentVersion() = writeHistory(null)
    fun restoreRevision(revision: RevisionDocument) = writeHistory(revision)

    private fun writeHistory(restoring: RevisionDocument?) {
        val note = historyNote ?: return
        if (mutableHistory.value?.working == true || mutable.value.operationBusy) return
        if (mutable.value.uploadStaging) {
            mutableHistory.update { it?.copy(error = text(R.string.history_sync_first)) }
            return
        }
        if (restoring != null && (!restoring.revision.canRestoreTo(note) ||
                mutableHistory.value?.preview != restoring)) return
        historyJob?.cancel()
        mutableHistory.update { it?.copy(working = true, error = null) }
        mutable.update { it.copy(operationBusy = true) }
        historyJob = viewModelScope.launch {
            var staged = false
            try {
                awaitLocalWrites()
                // Current edits must be on the server BEFORE the protective snapshot.
                // A failure or conflict aborts restoration without replacing local content.
                if (services.uploads.hasPending(note.id) || attachmentEditState.value.pendingCount > 0) {
                    mutableHistory.update { it?.copy(error = text(R.string.history_sync_first)) }
                    return@launch
                }
                repository.syncPending()
                val current = repository.cachedNote(note.id) ?: error("Note is unavailable")
                if (current.conflict || current.remoteMissing) {
                    mutableHistory.update { it?.copy(error = text(R.string.history_conflict_first)) }
                    return@launch
                }
                if (current.pending) {
                    mutableHistory.update { it?.copy(error = text(R.string.history_sync_first)) }
                    return@launch
                }
                if (restoring != null && !restoring.revision.canRestoreTo(services.api.getNote(note.id)))
                    error("Note type or protection changed")
                history.saveCurrentRevision(note.id,
                    if (restoring != null) text(R.string.history_before_restore) else "")
                if (restoring != null) {
                    val restored = repository.saveDraft(note.id, restoring.revision.title, restoring.content)
                    staged = true
                    if (mutable.value.document?.note?.id == note.id) mutable.update {
                        it.copy(document = restored, editorEpoch = it.editorEpoch + 1,
                            newNoteId = null, newNoteTitleUntouched = false, error = null)
                    }
                    NotesSyncScheduler.schedulePending(getApplication())
                    repository.syncPending()
                    val persisted = repository.cachedNote(note.id)
                    if (persisted != null && mutable.value.document?.note?.id == note.id)
                        mutable.update { it.copy(document = persisted) }
                    mutableHistory.value = null
                    historyNote = null
                } else {
                    val revisions = history.list(note.id)
                    mutableHistory.update { it?.copy(revisions = revisions, selected = null, preview = null, loading = false) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableHistory.update { it?.copy(error = text(if (restoring == null)
                    R.string.history_save_error else R.string.history_restore_error)) }
                if (staged) NotesSyncScheduler.schedulePending(getApplication())
            } finally {
                mutableHistory.update { it?.copy(working = false) }
                mutable.update { it.copy(operationBusy = false) }
            }
        }
    }

    fun cachedNotes(): List<NoteMeta> = repository.cachedVisibleNotes()
    fun cachedDocument(id: String): NoteDocument? = repository.cachedNote(id)
    fun backlinks(id: String): List<NoteMeta> = noteBacklinks(id, repository.cachedVisibleDocuments())
    fun destinations(): List<NoteDestination> {
        val notes = cachedNotes().associateBy { it.id }
        return notes.values.filter { !it.isProtected && it.type !in setOf("search", "launcher", "contentWidget", "webView") }
            .map { note ->
                val seen = mutableSetOf(note.id)
                val path = mutableListOf<String>()
                var parent = note.parentNoteIds.firstOrNull()
                while (parent != null && parent != "root" && seen.add(parent)) {
                    val item = notes[parent] ?: break
                    path.add(0, item.title)
                    parent = item.parentNoteIds.firstOrNull()
                }
                NoteDestination(note, path.joinToString(" / "))
            }.sortedWith(compareBy<NoteDestination> { it.note.id != "root" }.thenBy { it.path }.thenBy { it.note.title.lowercase() })
    }
    fun sourceParents(note: NoteMeta): List<NoteMeta> = note.parentNoteIds.mapNotNull { parent ->
        repository.cachedMeta(parent)?.takeIf { repository.cachedChildren(parent)?.any { it.note.id == note.id } == true }
    }
    fun collectionNotes(): List<NoteMeta> {
        val notes = cachedNotes().associateBy { it.id }
        val preferences = libraryPreferences.value
        return when (mutable.value.librarySection) {
            LibrarySection.NOTES -> emptyList()
            LibrarySection.FAVORITES -> preferences.favorites.mapNotNull(notes::get).sortedBy { it.title.lowercase() }
            LibrarySection.RECENT -> preferences.recent.mapNotNull(notes::get)
        }.filterNot { it.id == "root" }
    }
    fun selectSection(section: LibrarySection) {
        if (mutable.value.operationBusy) return
        searchJob?.cancel()
        mutable.update { it.copy(librarySection = section, query = "", searching = false, results = emptyList(), error = null) }
    }
    fun toggleFavorite(id: String) = preferenceChange { services.libraryPreferences.toggleFavorite(id) }
    fun selectSort(sort: NoteSort) = preferenceChange { services.libraryPreferences.sort(mutable.value.folders.last().id, sort) }
    private fun recordOpened(id: String) { if (id != "root") preferenceChange { services.libraryPreferences.opened(id) } }
    private fun preferenceChange(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(error = text(R.string.msg_library_preferences_failed)) }
            }
        }
    }
    private class PartialLibraryOperation(val completed: Int, val total: Int, cause: Exception) : Exception(cause)
    private fun libraryError(error: Exception): String = when (error) {
        is TreeOperationException -> AppLanguage.get(getApplication(), error.resource)
        is PartialLibraryOperation -> AppLanguage.get(getApplication(), R.string.msg_library_partial, error.completed, error.total)
        else -> text(R.string.msg_library_failed)
    }

    fun organize(action: LibraryAction, notes: List<NoteMeta>, targetParentId: String, sourceParentId: String?) = mutateLibrary {
        val selected = notes.distinctBy { it.id }
        if (action == LibraryAction.COPY) selected.forEach { repository.validateCopySubtree(it.id, targetParentId) }
        var completed = 0
        try { selected.forEach { note ->
            if (action == LibraryAction.COPY) repository.copySubtree(note.id, targetParentId)
            else {
                val parent = requireNotNull(sourceParentId ?: sourceParents(note).singleOrNull()?.id
                    ?: (if (action == LibraryAction.CLONE) sourceParents(note).firstOrNull()?.id else null)) {
                    "Choose the source folder"
                }
                val branch = repository.cachedChildren(parent)?.firstOrNull { it.note.id == note.id }
                    ?: error("Source branch is unavailable")
                if (action == LibraryAction.MOVE) repository.moveBranch(branch.branchId, parent, targetParentId)
                else repository.cloneBranch(branch.branchId, parent, targetParentId)
            }
            completed++
        } } catch (error: Exception) {
            if (error is CancellationException || completed == 0) throw error
            schedulePendingChanges()
            throw PartialLibraryOperation(completed, selected.size, error)
        }
    }

    fun reorder(branchIds: List<String>) = mutateLibrary {
        val parent = mutable.value.folders.last().id
        repository.reorderChildren(parent, branchIds)
        services.libraryPreferences.sort(parent, NoteSort.MANUAL)
    }
    fun trashMany(notes: List<NoteMeta>) = mutateLibrary { notes.distinctBy { it.id }.forEach { repository.trash(it.id) } }
    fun restoreFromTrash(id: String) = mutateLibrary {
        repository.restore(id)
        mutableTrash.value = repository.cachedTrash()
    }
    private fun mutateLibrary(block: suspend () -> Unit) {
        if (mutable.value.operationBusy || mutable.value.localWritePending || mutable.value.deletingNoteId != null) return
        mutable.update { it.copy(operationBusy = true, error = null) }
        mutablePanelError.value = null
        viewModelScope.launch {
            try { awaitLocalWrites(); block(); schedulePendingChanges() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                val message = libraryError(error)
                mutablePanelError.value = message
                mutable.update { it.copy(error = message) }
            } finally {
                val folders = mutable.value.folders.takeWhile { !repository.isDeleted(it.id) }.ifEmpty { listOf(Folder("root", "")) }
                mutable.update { it.copy(operationBusy = false, folders = folders,
                    document = it.document?.takeUnless { doc -> repository.isDeleted(doc.note.id) },
                    items = repository.cachedChildren(folders.last().id).orEmpty(),
                    results = it.results.filterNot { note -> repository.isDeleted(note.id) }) }
                if (mutableTrash.value != null) mutableTrash.value = repository.cachedTrash()
                syncNow()
            }
        }
    }
    fun openTrash() {
        mutablePanelError.value = null
        mutableTrash.value = repository.cachedTrash()
        viewModelScope.launch {
            try { mutableTrash.value = repository.listTrash() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutablePanelError.value = loadFailureMessage(getApplication(), error)
            }
        }
    }
    fun closeTrash() { if (!mutable.value.operationBusy) mutableTrash.value = null }

    fun openAttachmentManager(noteId: String) {
        if (mutable.value.operationBusy) return
        attachmentListOwner = noteId
        mutablePanelError.value = null
        mutableAttachments.value = emptyList()
        viewModelScope.launch {
            try {
                awaitLocalWrites()
                val items = services.attachments.list(noteId)
                if (attachmentListOwner == noteId) mutableAttachments.value = items
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (attachmentListOwner == noteId) mutablePanelError.value = loadFailureMessage(getApplication(), error)
            }
        }
    }
    fun retryAttachmentManager() {
        attachmentListOwner?.let(::openAttachmentManager)
        syncNow()
    }
    fun closeAttachmentManager() {
        if (!mutable.value.operationBusy) { mutableAttachments.value = null; attachmentListOwner = null }
    }
    fun changeAttachment(attachment: AttachmentMeta, title: String?) {
        val owner = attachmentListOwner ?: return
        if (attachment.ownerId != owner || mutable.value.operationBusy) return
        mutable.update { it.copy(operationBusy = true) }
        mutablePanelError.value = null
        viewModelScope.launch {
            try {
                awaitLocalWrites()
                if (title == null) services.attachments.delete(owner, attachment.id)
                else services.attachments.rename(owner, attachment.id, title)
                repository.cachedNote(owner)?.let { document ->
                    mutable.update { if (it.document?.note?.id == owner) it.copy(document = document, editorEpoch = it.editorEpoch + 1) else it }
                }
                mutableAttachments.value = services.attachments.list(owner)
                schedulePendingChanges()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutablePanelError.value = text(R.string.msg_library_failed)
            } finally { mutable.update { it.copy(operationBusy = false) }; syncNow() }
        }
    }
    fun quickCreate(request: String, onHandled: () -> Unit) {
        if (mutable.value.operationBusy || mutable.value.localWritePending) return
        mutable.update { it.copy(operationBusy = true) }
        viewModelScope.launch {
            try {
                val seed = UUID.nameUUIDFromBytes(request.toByteArray()).toString().replace("-", "")
                val note = repository.createWithId("root", "", "<p></p>", "Q" + seed.take(31), "B" + seed.take(31))
                repository.pinDocument(note.note.id)
                mutable.update { it.copy(document = note, loading = false, newNoteId = note.note.id, newNoteTitleUntouched = true) }
                services.libraryPreferences.opened(note.note.id)
                onHandled()
                schedulePendingChanges()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(error = text(R.string.msg_create_note_failed)) }
            } finally { mutable.update { it.copy(operationBusy = false) }; syncNow() }
        }
    }
    fun saveCapture(inbox: CaptureInbox, capture: PendingCapture, title: String, parentId: String?, existingId: String?) {
        if (mutable.value.operationBusy || (capture.accountId != null && capture.accountId != accountId)) return
        mutable.update { it.copy(operationBusy = true, error = null) }
        mutablePanelError.value = null
        viewModelScope.launch {
            try {
                awaitLocalWrites()
                val seed = UUID.nameUUIDFromBytes(capture.id.toByteArray()).toString().replace("-", "")
                val target = capture.targetNoteId ?: existingId ?: "S" + seed.take(31)
                val newNote = if (capture.targetNoteId != null) capture.createNew else existingId == null
                val parent = if (capture.targetNoteId != null) capture.targetParentId else parentId
                val chosenTitle = capture.targetTitle ?: title.ifBlank { capture.text.lineSequence().firstOrNull().orEmpty().take(80) }
                withContext(Dispatchers.IO) { inbox.bindTarget(capture.id, accountId, target,
                    if (newNote) parent ?: "root" else null, if (newNote) chosenTitle else null) }
                val current = if (newNote) repository.createWithId(parent ?: "root", chosenTitle, "<p></p>", target, "B" + seed.take(31))
                    else repository.cachedNote(target) ?: repository.load(target)
                require(current.note.isEditable && !current.conflict && !current.remoteMissing)
                val markdown = current.note.mime.contains("markdown", true)
                require(markdown || current.note.mime.substringBefore(';') == "text/html")
                if (!capture.textApplied) {
                    val body = if (markdown) "\n\n" + capture.text + "\n" else
                        if (capture.text.isEmpty()) "" else "<p>" + Entities.escape(capture.text).replace("\n", "<br>") + "</p>"
                    repository.appendCapturedText(target, capture.id, body)
                    withContext(Dispatchers.IO) { inbox.markTextApplied(capture.id) }
                }
                for (attachment in capture.attachments) {
                    if (attachment.id in capture.appliedAttachmentIds) continue
                    val uploadId = UUID.nameUUIDFromBytes((capture.id + ":" + attachment.id).toByteArray()).toString()
                    services.uploads.stageFile(inbox.attachmentFile(capture.id, attachment.id), target,
                        attachment.title, attachment.mime, attachment.mime.startsWith("image/"), uploadId)
                    withContext(Dispatchers.IO) { inbox.markAttachmentApplied(capture.id, attachment.id) }
                }
                withContext(Dispatchers.IO) { inbox.complete(capture.id) }
                repository.pinDocument(target)
                services.libraryPreferences.opened(target)
                mutable.update { it.copy(document = repository.cachedNote(target), loading = false,
                    editorEpoch = it.editorEpoch + 1, newNoteId = null) }
                schedulePendingChanges()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutablePanelError.value = text(R.string.msg_library_failed)
            } finally { mutable.update { it.copy(operationBusy = false) }; syncNow() }
        }
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
        if (services.attachmentInsertionHandler === insertionHandler) services.attachmentInsertionHandler = null
        if (services.attachments.syncGate === metadataSyncGate) services.attachments.syncGate = null
        repository.pinDocument(null)
        super.onCleared()
    }

}
