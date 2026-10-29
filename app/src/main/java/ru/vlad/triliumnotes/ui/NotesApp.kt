@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.vlad.triliumnotes.ui

import android.content.Context
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.vlad.triliumnotes.Folder
import ru.vlad.triliumnotes.NotesScreenState
import ru.vlad.triliumnotes.NotesViewModel
import ru.vlad.triliumnotes.data.*
import ru.vlad.triliumnotes.capture.*
import ru.vlad.triliumnotes.diagrams.DiagramViewer
import ru.vlad.triliumnotes.diagrams.DiagramPackages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.UUID
import ru.vlad.triliumnotes.data.CachedAttachment
import ru.vlad.triliumnotes.data.AttachmentUploadState
import ru.vlad.triliumnotes.data.NoteDocument
import ru.vlad.triliumnotes.data.NoteMeta
import ru.vlad.triliumnotes.data.SyncState
import ru.vlad.triliumnotes.data.OfflineState
import ru.vlad.triliumnotes.data.resolveResourceTarget
import ru.vlad.triliumnotes.data.isEditable
import ru.vlad.triliumnotes.data.canDelete
import ru.vlad.triliumnotes.editor.rememberRichNoteEditorController
import ru.vlad.triliumnotes.editor.RichNoteEditor
import ru.vlad.triliumnotes.editor.SourceNoteEditor
import ru.vlad.triliumnotes.editor.EditorResource
import ru.vlad.triliumnotes.editor.NoteSearchBar
import ru.vlad.triliumnotes.editor.rememberNoteSearchState

@Composable
fun NotesApp(model: NotesViewModel, onConnectionSettings: () -> Unit = {},
    captureInbox: ru.vlad.triliumnotes.capture.CaptureInbox? = null,
    quickNoteRequest: String? = null, onQuickNoteHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by model.state.collectAsStateWithLifecycle()
    val noteSync by model.syncState.collectAsStateWithLifecycle()
    val offline by model.offlineState.collectAsStateWithLifecycle()
    val upload by model.uploadState.collectAsStateWithLifecycle()
    val history by model.historyState.collectAsStateWithLifecycle()
    val preferences by model.libraryPreferences.collectAsStateWithLifecycle()
    val metadataEdits by model.attachmentEditState.collectAsStateWithLifecycle()
    val sync = noteSync.copy(pendingCount = noteSync.pendingCount + metadataEdits.pendingCount,
        isSyncing = noteSync.isSyncing || metadataEdits.isSyncing, error = noteSync.error ?: metadataEdits.error)
    val trash by model.trashState.collectAsStateWithLifecycle()
    val attachmentList by model.attachmentList.collectAsStateWithLifecycle()
    val panelError by model.panelError.collectAsStateWithLifecycle()
    val captures by (captureInbox?.entries ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<PendingCapture>()) }).collectAsStateWithLifecycle()
    var dismissedCaptures by rememberSaveable { mutableStateOf(listOf<String>()) }
    var pendingAction by remember { mutableStateOf<LibraryActionRequest?>(null) }
    var showBacklinks by remember { mutableStateOf<String?>(null) }
    val editorHandoff = remember(model) { EditorHandoff() }
    LaunchedEffect(quickNoteRequest, state.operationBusy, state.localWritePending) {
        if (quickNoteRequest != null && !state.operationBusy && !state.localWritePending)
            editorHandoff.run { model.quickCreate(quickNoteRequest, onQuickNoteHandled) }
    }
    fun startAction(action: LibraryAction, notes: List<NoteMeta>, sourceParent: String?) {
        pendingAction = LibraryActionRequest(action, notes, sourceParent)
    }
    var attachmentOwner by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerAnchor by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var attachmentUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var linkError by remember { mutableStateOf<String?>(null) }
    fun clearPicker() { attachmentOwner = null; pickerAnchor = null; pickerAccount = null }
    val attachmentPicker = rememberAttachmentPickerController(onPicked = { uri, inlineImage ->
        val owner = attachmentOwner
        if (owner != null && pickerAccount == model.accountId) {
            model.stageAttachment(owner, uri, inlineImage, pickerAnchor) { success ->
                clearPicker()
                if (!success) model.cleanupAttachmentAnchors(owner, null)
            }
        } else {
            linkError = AppLanguage.get(context, R.string.capture_account_changed)
            clearPicker()
        }
    }, onCancelled = {
        val owner = attachmentOwner
        clearPicker()
        owner?.let { model.cleanupAttachmentAnchors(it, null) }
    })
    fun openLink(value: String) {
        if (resolveResourceTarget(value, model.serverUrl) != null) {
            attachmentUrl = value
            return
        }
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("http", "https", "mailto", "tel")) return
        val action = when (scheme) {
            "tel" -> Intent.ACTION_DIAL
            "mailto" -> Intent.ACTION_SENDTO
            else -> Intent.ACTION_VIEW
        }
        try { context.startActivity(Intent(action, uri)) }
        catch (_: ActivityNotFoundException) { linkError = AppLanguage.get(context, R.string.link_no_app) }
        catch (_: SecurityException) { linkError = AppLanguage.get(context, R.string.link_open_error) }
    }
    BackHandler(state.operationBusy || state.document != null || state.openingNoteId != null || state.folders.size > 1 || state.query.isNotEmpty() || state.librarySection != LibrarySection.NOTES) {
        if (!state.operationBusy) model.back()
    }
    val document = state.document
    val libraryState = rememberSaveableStateHolder()
    if (document == null) {
        val listState = rememberLibraryListState(libraryState, state)
        LibraryScreen(state, sync, onBack = model::back, onSearch = model::search,
            onRefresh = model::refreshAll, onSync = model::syncNow, onCreate = model::create,
            onOpen = model::open, onFolder = model::enterFolder, onOpenFolderContent = model::openCurrentFolder,
            offline = offline, uploadState = upload,
            onOpenConflict = { sync.conflicts.firstOrNull()?.let(model::openById) }, onDelete = model::delete,
            listState = listState, onCreateFolder = model::createFolder,
            onConnectionSettings = onConnectionSettings,
            preferences = preferences, collectionNotes = model.collectionNotes(), onSection = model::selectSection,
            onFavorite = model::toggleFavorite, onOrganize = ::startAction, onSort = model::selectSort,
            onReorder = model::reorder, onTrashMany = model::trashMany, onTrash = model::openTrash,
            captureCount = captures.size, onCaptures = { dismissedCaptures = emptyList() })
    } else {
        NoteEditorHost(document.note.id) {
            EditorScreen(state, document, sync, onBack = model::back,
                onTitleChanged = { model.editTitle(document.note.id, it) },
                onHtmlChanged = { model.editHtml(document.note.id, it) },
                onSync = model::syncNow, onKeepConflictAsCopy = model::keepConflictAsCopy,
                onOpenNote = model::openById, onOpenLink = ::openLink,
                resourceLoader = { path -> model.loadResource(path)?.let { EditorResource(it.mimeType, it.bytes) } },
                attachmentLoader = model::loadAttachment, onRetryLocalSave = model::retryLocalSave,
                onDelete = { model.delete(document.note) }, serverUrl = model.serverUrl,
                uploadState = upload, onHistory = { model.openHistory(document.note.id) },
                editorHandoff = editorHandoff, attachmentPickerController = attachmentPicker,
                externalAttachmentAnchor = pickerAnchor, onAttachmentAnchor = { pickerAnchor = it },
                onAttachmentPickStarted = { attachmentOwner = document.note.id; pickerAccount = model.accountId },
                onAttachmentAtCursor = { uri, inlineImage, anchor ->
                    attachmentOwner?.let { owner -> model.stageAttachment(owner, uri, inlineImage, anchor) }
                    attachmentOwner = null
                }, onPrepareAttachment = model::prepareAttachmentPick,
                onCancelAttachment = { model.cleanupAttachmentAnchors(document.note.id, null) },
                onRecoverAttachment = { model.cleanupAttachmentAnchors(document.note.id, it) }, onEditingChanged = { model.setEditing(document.note.id, it) },
                favorite = document.note.id in preferences.favorites, onFavorite = { model.toggleFavorite(document.note.id) },
                onOrganize = { action ->
                    val from = state.folders.last().id.takeIf { parent -> model.sourceParents(document.note).any { it.id == parent } }
                    startAction(action, listOf(document.note), from)
                }, linkCandidates = model.cachedNotes(), onBacklinks = { showBacklinks = document.note.id },
                onAttachmentManager = { model.openAttachmentManager(document.note.id) })
        }
    }
    history?.let { historyState ->
        model.historyNote?.let { note ->
            NoteHistorySheet(note, historyState, onSelect = model::selectRevision,
                onRestore = model::restoreRevision, onSaveRevision = model::saveCurrentVersion,
                onRetry = model::retryHistory, onBack = model::historyBack, onDismiss = model::closeHistory,
                resourceLoader = { path -> model.loadResource(path)?.let { EditorResource(it.mimeType, it.bytes) } },
                serverUrl = model.serverUrl, currentDocument = model.cachedDocument(note.id))
        }
    }
    attachmentUrl?.let { url ->
        val target = resolveResourceTarget(url, model.serverUrl)
        if (target != null) {
            val name = Uri.decode(Uri.parse(url).path.orEmpty().substringAfterLast('/'))
                .takeUnless { it.isBlank() || it == "download" || it == "content" } ?: AppLanguage.get(context, R.string.attachment)
            val note = NoteMeta(target.id, name, "file", "application/octet-stream", false, null, null, emptyList())
            Dialog(onDismissRequest = { attachmentUrl = null },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                Scaffold(topBar = {
                    TopAppBar(title = { Text(AppLanguage.get(context, R.string.attachment), style = MaterialTheme.typography.titleMedium) },
                        navigationIcon = { IconButton(onClick = { attachmentUrl = null }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, AppLanguage.get(context, R.string.back_to_note))
                        } })
                }) { padding ->
                    AttachmentViewer(note, { model.loadLinkedAttachment(url) }, Modifier.fillMaxSize().padding(padding))
                }
            }
        }
    }
    pendingAction?.let { request ->
        val origins = if (request.action == LibraryAction.MOVE && request.sourceParentId == null && request.notes.size == 1)
            model.sourceParents(request.notes.single()) else emptyList()
        if (origins.size > 1) {
            DestinationDialog(AppLanguage.get(context, R.string.library_origin), origins.map { NoteDestination(it, "") },
                onPick = { pendingAction = request.copy(sourceParentId = it.id) }, onDismiss = { pendingAction = null })
        } else {
            val excluded = request.notes.map { it.id }.toSet()
            DestinationDialog(AppLanguage.get(context, R.string.library_destination), model.destinations().filterNot { it.note.id in excluded },
                onPick = { target ->
                    pendingAction = null
                    model.organize(request.action, request.notes, target.id, request.sourceParentId ?: origins.singleOrNull()?.id)
                }, onDismiss = { pendingAction = null })
        }
    }
    showBacklinks?.let { id -> BacklinksDialog(model.backlinks(id), onOpen = { showBacklinks = null; model.openById(it) }, onDismiss = { showBacklinks = null }) }
    trash?.let { entries -> TrashSheet(entries, false, panelError, state.operationBusy,
        onRestore = model::restoreFromTrash, onRetry = model::openTrash, onDismiss = model::closeTrash) }
    attachmentList?.let { entries -> AttachmentManagerDialog(entries,
        onRename = { attachment, title -> model.changeAttachment(attachment, title) },
        onDelete = { model.changeAttachment(it, null) }, onDismiss = model::closeAttachmentManager,
        busy = state.operationBusy, error = panelError ?: metadataEdits.error, onRetry = model::retryAttachmentManager,
        onOpen = { openLink("api/attachments/${it.ownerId}/${it.id}/${Uri.encode(it.title)}") }) }
    captures.firstOrNull { it.id !in dismissedCaptures }?.let { capture ->
        val choices = model.destinations().flatMap { destination ->
            val note = destination.note
            val title = if (note.id == "root") AppLanguage.get(context, R.string.notes)
                else listOf(destination.path, note.title).filter { it.isNotBlank() }.joinToString(" / ")
            buildList {
                add(CaptureDestination(note.id, title, true))
                if (note.isEditable && (note.mime.substringBefore(';') == "text/html" || note.mime.contains("markdown", true)))
                    add(CaptureDestination(note.id, title, false))
            }
        }
        CaptureSheet(capture, model.serverUrl, choices, state.operationBusy, panelError,
            onDismiss = { dismissedCaptures = dismissedCaptures + capture.id },
            onSave = { title, parent, existing -> captureInbox?.let { inbox -> editorHandoff.run { model.saveCapture(inbox, capture, title, parent, existing) } } },
            onDiscard = { scope.launch { withContext(Dispatchers.IO) { captureInbox?.discard(capture.id) } } },
            accountMatches = capture.accountId == null || capture.accountId == model.accountId)
    }
    linkError?.let { message ->
        AlertDialog(onDismissRequest = { linkError = null }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { linkError = null }) { Text(AppLanguage.get(context, R.string.close)) } })
    }
}

/** Keep picker registrations and toolbar state alive when only the editor body is replaced. */
@Composable
internal fun NoteEditorHost(noteId: String, content: @Composable () -> Unit) {
    key(noteId) { content() }
}

/** The root label belongs to the UI; all other folder names belong to the user. */
internal fun folderDisplayTitle(context: Context, folder: Folder): String =
    if (folder.id == "root") AppLanguage.get(context, R.string.notes) else folder.title

/** A separate saved scroll position for every folder path and exact search query. */
internal fun libraryStateKey(state: NotesScreenState): String =
    "library:" + state.librarySection.name + ":" + state.folders.joinToString("/") { Uri.encode(it.id) } + "?" + Uri.encode(state.query)

/** Scope only the list state: changing a search query must keep the search field focused. */
@Composable
internal fun rememberLibraryListState(holder: SaveableStateHolder, state: NotesScreenState): LazyListState {
    val key = libraryStateKey(state)
    val savedList = remember(key) { arrayOfNulls<LazyListState>(1) }
    holder.SaveableStateProvider(key) { savedList[0] = rememberLazyListState() }
    return requireNotNull(savedList[0])
}

@Composable
internal fun LibraryScreen(
    state: NotesScreenState, sync: SyncState,
    onBack: () -> Unit, onSearch: (String) -> Unit, onRefresh: () -> Unit,
    onSync: () -> Unit, onCreate: () -> Unit, onOpen: (NoteMeta) -> Unit,
    onFolder: (NoteMeta) -> Unit, onOpenFolderContent: () -> Unit,
    offline: OfflineState = OfflineState(),
    onOpenConflict: (() -> Unit)? = null,
    onDelete: ((NoteMeta) -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    onCreateFolder: ((String) -> Unit)? = null,
    onConnectionSettings: (() -> Unit)? = null,
    uploadState: AttachmentUploadState = AttachmentUploadState(),
    preferences: LibraryPreferencesState = LibraryPreferencesState(), collectionNotes: List<NoteMeta> = emptyList(),
    onSection: (LibrarySection) -> Unit = {}, onFavorite: ((String) -> Unit)? = null,
    onOrganize: ((LibraryAction, List<NoteMeta>, String?) -> Unit)? = null,
    onSort: (NoteSort) -> Unit = {}, onReorder: ((List<String>) -> Unit)? = null,
    onTrashMany: ((List<NoteMeta>) -> Unit)? = null, onTrash: (() -> Unit)? = null,
    captureCount: Int = 0, onCaptures: () -> Unit = {},
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val searching = state.query.isNotBlank()
    val collection = state.librarySection != LibrarySection.NOTES && !searching
    val busy = state.loading || state.searching || state.operationBusy
    val order = preferences.sorts[state.folders.last().id] ?: NoteSort.MANUAL
    val listed = when (order) {
        NoteSort.MANUAL -> state.items
        NoteSort.TITLE -> state.items.sortedWith(compareBy<TreeItem> { it.note.title.lowercase() }.thenBy { it.branchId })
        NoteSort.MODIFIED -> state.items.sortedWith(compareByDescending<TreeItem> { it.note.modifiedAt.orEmpty() }.thenBy { it.branchId })
    }
    val visibleNotes = if (searching) state.results else if (collection) collectionNotes else listed.map { it.note }
    val hasVisibleItems = visibleNotes.isNotEmpty()
    val inFolder = state.folders.size > 1
    val folder = state.folders.last()
    var deleteTarget by remember { mutableStateOf<NoteMeta?>(null) }
    var selectionMode by rememberSaveable(folder.id, state.librarySection, state.query) { mutableStateOf(false) }
    var selectedIds by rememberSaveable(folder.id, state.librarySection, state.query) { mutableStateOf(listOf<String>()) }
    var bulkDelete by remember { mutableStateOf(false) }
    var actionsExpanded by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    var showReorder by remember { mutableStateOf(false) }
    val selected = visibleNotes.filter { it.id in selectedIds }
    LaunchedEffect(visibleNotes.map { it.id }, busy) {
        if (!busy && selectedIds.isNotEmpty()) {
            selectedIds = selectedIds.filter { id -> visibleNotes.any { it.id == id } }
            if (selectedIds.isEmpty()) selectionMode = false
        }
    }
    fun toggleSelected(id: String) { selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
    fun organize(action: LibraryAction, notes: List<NoteMeta>) {
        onOrganize?.invoke(action, notes, folder.id.takeIf { !collection && !searching })
    }
    BackHandler(selectionMode) { selectionMode = false; selectedIds = emptyList() }
    var createMenuExpanded by remember { mutableStateOf(false) }
    var showFolderDialog by rememberSaveable { mutableStateOf(false) }
    var folderTitle by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissKeyboard() { focusManager.clearFocus(); keyboard?.hide() }
    fun openNote(note: NoteMeta) { dismissKeyboard(); onOpen(note) }
    fun openFolder(note: NoteMeta) { dismissKeyboard(); onFolder(note) }
    fun clearSearch() { onSearch(""); dismissKeyboard() }
    Scaffold(
        containerColor = colors.background,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = if (inFolder) 4.dp else 22.dp, end = 8.dp, top = 10.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (inFolder || selectionMode || collection) {
                        IconButton(onClick = {
                            dismissKeyboard()
                            if (selectionMode) { selectionMode = false; selectedIds = emptyList() }
                            else if (collection) onSection(LibrarySection.NOTES) else onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, AppLanguage.get(context, R.string.back))
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        val ancestors = state.folders.drop(1).dropLast(1)
                        if (ancestors.isNotEmpty()) {
                            Text(ancestors.joinToString(" / ") { it.title },
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(3.dp))
                        }
                        Text(if (selectionMode) AppLanguage.get(context, R.string.library_selected, selected.size)
                            else if (collection) AppLanguage.get(context, if (state.librarySection == LibrarySection.FAVORITES) R.string.library_favorites else R.string.library_recent)
                            else folderDisplayTitle(context, folder), style = MaterialTheme.typography.titleLarge,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (!selectionMode && !inFolder && onConnectionSettings != null) {
                        IconButton(onClick = { dismissKeyboard(); onConnectionSettings() },
                            enabled = !busy && !state.localWritePending && state.deletingNoteId == null,
                            modifier = Modifier.testTag("connection-settings")) {
                            Icon(Icons.Outlined.Settings, AppLanguage.get(context, R.string.connection_settings))
                        }
                    }
                    Box {
                        IconButton(onClick = { actionsExpanded = true }, enabled = !busy,
                            modifier = Modifier.testTag("library-actions")) {
                            Icon(Icons.Outlined.MoreVert, AppLanguage.get(context, R.string.library_actions))
                        }
                        DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                            if (selectionMode) {
                                DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.library_select_all)) },
                                    onClick = { selectedIds = visibleNotes.filter { it.canDelete }.map { it.id }; actionsExpanded = false })
                                for ((action, label) in listOf(LibraryAction.MOVE to R.string.library_move, LibraryAction.COPY to R.string.library_copy, LibraryAction.CLONE to R.string.library_clone)) {
                                    DropdownMenuItem(text = { Text(AppLanguage.get(context, label)) }, enabled = selected.isNotEmpty(),
                                        onClick = { actionsExpanded = false; organize(action, selected) })
                                }
                                DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.delete)) }, enabled = selected.isNotEmpty(),
                                    colors = MenuDefaults.itemColors(textColor = colors.error),
                                    onClick = { actionsExpanded = false; bulkDelete = true })
                            } else {
                                if (!searching && !collection && onTrashMany != null && visibleNotes.any { it.canDelete }) DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.library_select)) },
                                    onClick = { dismissKeyboard(); selectionMode = true; actionsExpanded = false })
                                if (!searching && !collection) DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.library_sort)) },
                                    onClick = { actionsExpanded = false; sortExpanded = true })
                                if (onTrash != null) DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.library_trash)) },
                                    onClick = { actionsExpanded = false; onTrash() })
                                if (captureCount > 0) DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.library_receiving) + " ($captureCount)") },
                                    onClick = { actionsExpanded = false; onCaptures() })
                            }
                        }
                        DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                            for ((sort, label) in listOf(NoteSort.MANUAL to R.string.library_sort_manual, NoteSort.TITLE to R.string.library_sort_title, NoteSort.MODIFIED to R.string.library_sort_modified)) {
                                DropdownMenuItem(text = { Text(AppLanguage.get(context, label)) },
                                    leadingIcon = if (order == sort) ({ Icon(Icons.Outlined.Check, null) }) else null,
                                    onClick = { sortExpanded = false; onSort(sort) })
                            }
                            if (onReorder != null && listed.size > 1) DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.library_reorder)) },
                                onClick = { sortExpanded = false; showReorder = true })
                        }
                    }
                    if (!selectionMode) SyncStatusControl(sync, offline, state.networkAvailable, state.fromCache,
                        onRefresh = { dismissKeyboard(); onRefresh() },
                        isViewBusy = busy, viewError = state.error,
                        onOpenConflict = onOpenConflict?.let { openConflict -> { dismissKeyboard(); openConflict() } })
                }
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.6f))
            }
        },
        floatingActionButton = {
            if (!selectionMode) Box {
                FloatingActionButton(onClick = {
                    if (!busy && state.deletingNoteId == null) {
                        dismissKeyboard()
                        if (onCreateFolder == null) onCreate() else createMenuExpanded = true
                    }
                },
                    modifier = Modifier.testTag("create-note").semantics { if (busy) disabled() },
                    containerColor = if (busy) colors.surfaceVariant else colors.primaryContainer,
                    contentColor = if (busy) colors.onSurfaceVariant else colors.onPrimaryContainer,
                    shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Outlined.Add, contentDescription = AppLanguage.get(context, R.string.create_note_or_folder))
                }
                DropdownMenu(expanded = createMenuExpanded && !busy,
                    onDismissRequest = { createMenuExpanded = false }) {
                    DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.new_note)) },
                        leadingIcon = { Icon(Icons.Outlined.NoteAdd, null) },
                        modifier = Modifier.testTag("new-note-menu"),
                        onClick = { createMenuExpanded = false; onCreate() })
                    DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.new_folder)) },
                        leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                        modifier = Modifier.testTag("new-folder-menu"),
                        onClick = {
                            createMenuExpanded = false
                            folderTitle = ""
                            showFolderDialog = true
                        })
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("note-list"), state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 100.dp)) {
            if (!inFolder && !searching && !selectionMode) {
                item(key = "collections") {
                    FlowRow(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((section, label) in listOf(LibrarySection.NOTES to R.string.notes, LibrarySection.FAVORITES to R.string.library_favorites, LibrarySection.RECENT to R.string.library_recent)) {
                            FilterChip(selected = state.librarySection == section, onClick = { onSection(section) },
                                label = { Text(AppLanguage.get(context, label), maxLines = 1) }, modifier = Modifier.testTag("section-${section.name}"))
                        }
                    }
                }
            }
            if (uploadState.pendingCount > 0 || uploadState.error != null) {
                item(key = "attachment-uploads") { AttachmentUploadStatus(uploadState, onSync) }
            }
            if (offline.isRunning && !offline.available) {
                item(key = "offline-progress") { InitialOfflineProgress(offline, Modifier.padding(bottom = 12.dp)) }
            }
            if (!selectionMode) item(key = "search") {
                TextField(value = state.query, onValueChange = onSearch,
                    modifier = Modifier.fillMaxWidth().testTag("note-search"), singleLine = true,
                    placeholder = { Text(AppLanguage.get(context, R.string.search_all_notes), style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null, modifier = Modifier.size(21.dp)) },
                    trailingIcon = if (state.query.isNotEmpty()) ({
                        IconButton(onClick = ::clearSearch) { Icon(Icons.Outlined.Close, AppLanguage.get(context, R.string.clear_search)) }
                    }) else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { dismissKeyboard() }),
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = colors.surfaceVariant, unfocusedContainerColor = colors.surfaceVariant,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )
                Spacer(Modifier.height(20.dp))
            }
            if (state.error != null) {
                item(key = "load-error") { MessageCard(state.error, onRetry = onRefresh) }
            }
            if (searching || (inFolder && !collection)) {
                item(key = "list-heading") {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("library-section-heading"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (searching) AppLanguage.get(context, R.string.search_results) else AppLanguage.get(context, R.string.child_notes),
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                        val count = visibleNotes.size
                        if (!busy && (state.error == null || count > 0))
                            Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            if (busy && !hasVisibleItems) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(25.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.height(14.dp))
                            Text(if (state.searching) AppLanguage.get(context, R.string.searching_notes) else AppLanguage.get(context, R.string.loading),
                                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                    }
                }
            } else if (searching && state.error != null && state.results.isEmpty()) {
                item(key = "reset-failed-search") {
                    TextButton(onClick = ::clearSearch) { Text(AppLanguage.get(context, R.string.reset_search)) }
                }
            } else if (visibleNotes.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (state.error != null) Icons.Outlined.CloudOff else if (searching) Icons.Outlined.SearchOff else Icons.Outlined.Description, null,
                            Modifier.size(32.dp), tint = colors.onSurfaceVariant)
                        Spacer(Modifier.height(14.dp))
                        Text(if (state.error != null) AppLanguage.get(context, R.string.no_notes_to_show) else if (searching) AppLanguage.get(context, R.string.nothing_found) else if (collection) AppLanguage.get(context, if (state.librarySection == LibrarySection.FAVORITES) R.string.library_empty_favorites else R.string.library_empty_recent) else if (inFolder) AppLanguage.get(context, R.string.no_child_notes) else AppLanguage.get(context, R.string.no_notes_yet),
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        if (searching) TextButton(onClick = ::clearSearch) { Text(AppLanguage.get(context, R.string.reset_search)) }
                        else if (state.error == null && !collection) TextButton(onClick = onCreate) { Text(AppLanguage.get(context, R.string.create_note)) }
                    }
                }
            } else if (searching || collection) {
                items(visibleNotes, key = { "result-${it.id}" }) { note ->
                    NoteRow(note, "", onOpen = { openNote(note) }, onFolder = { openFolder(note) },
                        onDelete = onDelete?.let { { dismissKeyboard(); deleteTarget = note } },
                        deleteEnabled = !state.operationBusy && state.deletingNoteId == null,
                        favorite = note.id in preferences.favorites, onFavorite = onFavorite?.let { { it(note.id) } },
                        onOrganize = onOrganize?.let { { action -> organize(action, listOf(note)) } })
                }
            } else {
                items(listed, key = { "branch-${it.branchId}" }) { item ->
                    NoteRow(item.note, item.prefix, onOpen = { openNote(item.note) }, onFolder = { openFolder(item.note) },
                        onDelete = onDelete?.let { { dismissKeyboard(); deleteTarget = item.note } },
                        deleteEnabled = !state.operationBusy && state.deletingNoteId == null,
                        selected = if (selectionMode) item.note.id in selectedIds else null,
                        onSelect = { toggleSelected(item.note.id) },
                        onLongSelect = if (onTrashMany != null) ({ dismissKeyboard(); selectionMode = true; toggleSelected(item.note.id) }) else null,
                        favorite = item.note.id in preferences.favorites, onFavorite = onFavorite?.let { { it(item.note.id) } },
                        onOrganize = onOrganize?.let { { action -> organize(action, listOf(item.note)) } })
                }
            }
            if (!searching && !collection && !selectionMode && inFolder && state.currentFolderHasContent == true) {
                item(key = "folder-content") {
                    FolderContentRow(state.currentFolderNote, enabled = !busy,
                        onOpen = { dismissKeyboard(); onOpenFolderContent() })
                }
            }
        }
    }
    if (showReorder) ReorderDialog(state.items, onSave = { showReorder = false; onReorder?.invoke(it) }, onDismiss = { showReorder = false })
    if (bulkDelete) AlertDialog(onDismissRequest = { bulkDelete = false },
        title = { Text(AppLanguage.get(context, R.string.library_trash_title)) },
        text = { Text(AppLanguage.get(context, R.string.library_bulk_trash_message, selected.size)) },
        confirmButton = { TextButton(enabled = selected.isNotEmpty() && !busy, modifier = Modifier.testTag("confirm-delete-selected"),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
            onClick = { bulkDelete = false; onTrashMany?.invoke(selected); selectionMode = false; selectedIds = emptyList() }) {
            Text(AppLanguage.get(context, R.string.delete))
        } },
        dismissButton = { TextButton(onClick = { bulkDelete = false }) { Text(AppLanguage.get(context, R.string.cancel)) } })
    if (showFolderDialog) {
        val titleFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { titleFocus.requestFocus() }
        AlertDialog(onDismissRequest = { showFolderDialog = false },
            modifier = Modifier.testTag("new-folder-dialog"),
            title = { Text(AppLanguage.get(context, R.string.new_folder)) },
            text = {
                OutlinedTextField(value = folderTitle, onValueChange = { folderTitle = it },
                    label = { Text(AppLanguage.get(context, R.string.folder_name)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(titleFocus).testTag("new-folder-title"),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (folderTitle.isNotBlank() && !busy) {
                            showFolderDialog = false
                            dismissKeyboard()
                            onCreateFolder?.invoke(folderTitle.trim())
                        }
                    }))
            },
            confirmButton = {
                TextButton(enabled = folderTitle.isNotBlank() && !busy,
                    modifier = Modifier.testTag("confirm-create-folder"), onClick = {
                        showFolderDialog = false
                        dismissKeyboard()
                        onCreateFolder?.invoke(folderTitle.trim())
                    }) { Text(AppLanguage.get(context, R.string.create)) }
            },
            dismissButton = { TextButton(onClick = { showFolderDialog = false }) { Text(AppLanguage.get(context, R.string.cancel)) } })
    }
    deleteTarget?.let { note ->
        DeleteNoteDialog(note, onDismiss = { deleteTarget = null }, onConfirm = {
            deleteTarget = null
            onDelete?.invoke(note)
        })
    }
}

@Composable
internal fun FolderContentRow(note: NoteMeta?, enabled: Boolean = true, onOpen: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().testTag("folder-content").clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, onClickLabel = AppLanguage.get(context, R.string.open_note_content), onClick = onOpen)
        .heightIn(min = 68.dp).padding(vertical = 13.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(39.dp).clip(RoundedCornerShape(11.dp)).background(colors.surfaceVariant),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Article, null, Modifier.size(23.dp), tint = colors.onSurfaceVariant)
        }
        Spacer(Modifier.width(13.dp))
        Text(if (note?.type == "text") AppLanguage.get(context, R.string.note_text) else AppLanguage.get(context, R.string.note_content),
            modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
    }
}

internal fun noteKind(context: Context, note: NoteMeta): String = when {
    note.isProtected -> AppLanguage.get(context, R.string.kind_protected)
    note.type == "text" -> AppLanguage.get(context, R.string.kind_text)
    note.type == "doc" -> AppLanguage.get(context, R.string.kind_doc)
    note.type == "code" -> AppLanguage.get(context, R.string.kind_code, note.mime.substringAfterLast('/'))
    note.type == "image" -> AppLanguage.get(context, R.string.format_image)
    note.type == "file" -> attachmentFormat(context, note.mime, note.title)
    note.type == "book" -> AppLanguage.get(context, R.string.kind_folder)
    note.type == "canvas" -> AppLanguage.get(context, R.string.kind_canvas)
    note.type == "mermaid" -> AppLanguage.get(context, R.string.kind_mermaid)
    note.type == "relationMap" -> AppLanguage.get(context, R.string.kind_relation_map)
    note.type == "noteMap" -> AppLanguage.get(context, R.string.kind_note_map)
    note.type == "search" -> AppLanguage.get(context, R.string.kind_search)
    note.type == "launcher" -> AppLanguage.get(context, R.string.kind_launcher)
    note.type == "contentWidget" -> AppLanguage.get(context, R.string.kind_widget)
    note.type == "webView" -> AppLanguage.get(context, R.string.kind_web)
    else -> AppLanguage.get(context, R.string.kind_special)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoteRow(note: NoteMeta, prefix: String, onOpen: () -> Unit, onFolder: () -> Unit,
    onDelete: (() -> Unit)? = null, deleteEnabled: Boolean = true,
    selected: Boolean? = null, onSelect: (() -> Unit)? = null, onLongSelect: (() -> Unit)? = null,
    favorite: Boolean = false, onFavorite: (() -> Unit)? = null,
    onOrganize: ((LibraryAction) -> Unit)? = null,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val folder = note.childBranchIds.isNotEmpty() || note.type == "book"
    Row(Modifier.fillMaxWidth().testTag("note-row-${note.id}").clip(RoundedCornerShape(12.dp))
        .combinedClickable(onClickLabel = if (folder) AppLanguage.get(context, R.string.open_child_notes) else AppLanguage.get(context, R.string.open_note),
            onLongClick = if (deleteEnabled && note.canDelete) onLongSelect else null,
            onClick = { if (selected != null) onSelect?.invoke() else if (folder) onFolder() else onOpen() })
        .heightIn(min = 68.dp).padding(vertical = 13.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (selected != null) Checkbox(checked = selected, onCheckedChange = { onSelect?.invoke() })
        else Box(Modifier.size(39.dp).clip(RoundedCornerShape(11.dp)).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(if (folder) Icons.Outlined.FolderOpen else when (note.type) {
                "code", "mermaid" -> Icons.Outlined.Code
                "image" -> Icons.Outlined.Image
                "file" -> Icons.Outlined.AttachFile
                "search" -> Icons.Outlined.Search
                "relationMap", "noteMap", "canvas" -> Icons.Outlined.AccountTree
                else -> Icons.Outlined.Description
            }, null,
                Modifier.size(20.dp), tint = if (folder) colors.primary else colors.onSurfaceVariant)
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            val displayTitle = note.title.ifBlank { AppLanguage.get(context, R.string.untitled) }
            Text(if (prefix.isEmpty()) displayTitle else "$prefix · $displayTitle",
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (folder || note.type != "text" || note.isProtected) {
                Spacer(Modifier.height(3.dp))
                Text(if (folder) AppLanguage.get(context, if (note.isProtected) R.string.child_count_protected else R.string.child_count, note.childBranchIds.size) else noteKind(context, note),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
        if (selected == null && ((onDelete != null && note.canDelete) || onFavorite != null)) {
            NoteActionsMenu(note, onDelete, deleteEnabled, favorite = favorite, onFavorite = onFavorite, onOrganize = onOrganize)
        } else if (selected == null && folder) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.ChevronRight, null, tint = colors.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.65f), modifier = Modifier.padding(start = 54.dp))
}

@Composable
internal fun EditorScreen(
    state: NotesScreenState, document: NoteDocument, sync: SyncState,
    onBack: () -> Unit, onTitleChanged: (String) -> Unit, onHtmlChanged: (String) -> Unit,
    onSync: () -> Unit, onKeepConflictAsCopy: () -> Unit,
    onOpenNote: (String) -> Unit = {}, onOpenLink: (String) -> Unit = {},
    resourceLoader: (String) -> EditorResource? = { null },
    attachmentLoader: suspend (NoteMeta) -> CachedAttachment = { throw IllegalStateException() },
    onRetryLocalSave: () -> Unit = {},
    onDelete: (() -> Unit)? = null,
    serverUrl: String = "",
    onHistory: (() -> Unit)? = null,
    onAttachment: ((Uri, Boolean) -> Unit)? = null,
    onAttachmentAtCursor: ((Uri, Boolean, String?) -> Unit)? = null,
    onPrepareAttachment: ((() -> Unit, () -> Unit) -> Unit) = { ready, _ -> ready() },
    onCancelAttachment: () -> Unit = {}, onRecoverAttachment: (String?) -> Unit = {},
    favorite: Boolean = false, onFavorite: (() -> Unit)? = null,
    onOrganize: ((LibraryAction) -> Unit)? = null,
    linkCandidates: List<NoteMeta> = emptyList(), onBacklinks: (() -> Unit)? = null,
    onAttachmentManager: (() -> Unit)? = null,
    onAttachmentPickStarted: () -> Unit = {},
    attachmentPickerController: AttachmentPickerController? = null,
    externalAttachmentAnchor: String? = null, onAttachmentAnchor: (String?) -> Unit = {},
    editorHandoff: EditorHandoff? = null,
    onEditingChanged: (Boolean) -> Unit = {},
    uploadState: AttachmentUploadState = AttachmentUploadState(),
    diagramPackages: DiagramPackages? = null,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val initialHtml = remember(document.note.id, state.editorEpoch) { document.html }
    val htmlNote = document.note.type == "doc" ||
        (document.note.type == "text" && document.note.mime.substringBefore(';') == "text/html")
    val attachment = document.note.type == "image" || document.note.type == "file"
    val readOnly = !document.note.isEditable
    val folderContent = document.note.type == "book" || document.note.childBranchIds.isNotEmpty()
    val conflict = document.conflict || document.note.id in sync.conflicts
    val localSaveFailed = state.localWritePending && state.localSaveFailed
    var editing by rememberSaveable(document.note.id) { mutableStateOf(state.newNoteId == document.note.id && !readOnly) }
    val diagram = !document.note.isProtected && (document.note.type == "canvas" || (document.note.type == "mermaid" && !editing))
    // Only this newly created, untouched title can hide the server's generated fallback.
    // Existing titles are user content and always pass through unchanged.
    val untouchedNewTitle = state.newNoteId == document.note.id && state.newNoteTitleUntouched
    val editorTitle = if (untouchedNewTitle) "" else document.note.title
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val editorController = rememberRichNoteEditorController()
    val searchState = rememberNoteSearchState(document.note.id)
    var searchVisible by rememberSaveable(document.note.id) { mutableStateOf(false) }
    val searchable = !document.note.isProtected && !attachment && (htmlNote || !readOnly)
    val canAttach = !readOnly && !conflict && (htmlNote || document.note.mime.contains("markdown", ignoreCase = true))
    val latestEditingChanged = rememberUpdatedState(onEditingChanged)
    LaunchedEffect(editing) { latestEditingChanged.value(editing) }
    DisposableEffect(document.note.id) { onDispose { latestEditingChanged.value(false) } }
    var flushing by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember(document.note.id) { mutableStateOf(false) }
    var showLinkPicker by remember { mutableStateOf(false) }
    var attachmentAnchor by rememberSaveable(document.note.id) { mutableStateOf<String?>(null) }
    var awaitingAttachmentStage by rememberSaveable { mutableStateOf(false) }
    var sawAttachmentStaging by remember { mutableStateOf(false) }
    LaunchedEffect(state.uploadStaging) {
        if (state.uploadStaging) sawAttachmentStaging = true
        else if (sawAttachmentStaging && awaitingAttachmentStage) {
            attachmentAnchor = null; awaitingAttachmentStage = false; sawAttachmentStaging = false
        }
    }
    val activeAnchor = if (attachmentPickerController != null) externalAttachmentAnchor else attachmentAnchor
    LaunchedEffect(editing, state.uploadStaging, state.operationBusy, state.localWritePending, activeAnchor) {
        if (!editing && !state.uploadStaging && onAttachmentAtCursor != null) onRecoverAttachment(activeAnchor)
    }
    fun finishEditing(after: () -> Unit = onSync) {
        if (flushing || state.operationBusy) return
        flushing = true
        editorController.flush {
            focusManager.clearFocus()
            keyboard?.hide()
            editing = false
            latestEditingChanged.value(false)
            flushing = false
            after()
        }
    }
    val latestFinish = rememberUpdatedState<(()->Unit)->Unit> { after -> finishEditing(after) }
    DisposableEffect(editorHandoff) {
        val action: (()->Unit)->Unit = { after -> latestFinish.value(after) }
        editorHandoff?.finish = action
        onDispose { if (editorHandoff?.finish === action) editorHandoff.finish = null }
    }
    fun closeSearch() {
        searchVisible = false
        focusManager.clearFocus()
        keyboard?.hide()
    }
    fun retrySaving() {
        if (flushing || state.operationBusy) return
        flushing = true
        editorController.flush {
            flushing = false
            onRetryLocalSave()
        }
    }
    BackHandler(editing || searchVisible || flushing || state.operationBusy) {
        if (!state.operationBusy && !flushing) {
            if (searchVisible) closeSearch() else finishEditing()
        }
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(title = {
                Column {
                    if (diagram) Text(document.note.title.ifBlank { AppLanguage.get(context, R.string.untitled) },
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("diagram-title"))
                    val status = when {
                        localSaveFailed -> AppLanguage.get(context, R.string.not_saved)
                        flushing || state.localWritePending -> AppLanguage.get(context, R.string.saving)
                        conflict -> AppLanguage.get(context, R.string.another_version)
                        document.note.isProtected -> AppLanguage.get(context, R.string.kind_protected)
                        readOnly && htmlNote -> AppLanguage.get(context, R.string.read_only)
                        readOnly && !attachment && !diagram -> noteKind(context, document.note)
                        else -> ""
                    }
                    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.labelSmall,
                        color = if (localSaveFailed) colors.error else colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }, navigationIcon = {
                IconButton(onClick = { if (searchVisible) closeSearch() else if (editing) finishEditing() else onBack() },
                    enabled = !state.operationBusy && !flushing && (editing || !state.localWritePending)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, if (editing) AppLanguage.get(context, R.string.finish_editing) else AppLanguage.get(context, R.string.back_to_notes))
                }
            }, actions = {
                if (editing && localSaveFailed) {
                    TextButton(onClick = ::retrySaving, enabled = !flushing && !state.operationBusy,
                        modifier = Modifier.semantics { contentDescription = AppLanguage.get(context, R.string.retry_save) }) {
                        Text(AppLanguage.get(context, R.string.retry))
                    }
                }
                if (document.pending && !flushing && !state.operationBusy && !state.localWritePending && !conflict) {
                    PendingNoteStatusControl(sync, state.networkAvailable, onSync)
                }
                if (canAttach && (onAttachment != null || onAttachmentAtCursor != null)) {
                    AttachmentPicker(enabled = !flushing && !state.operationBusy && !state.uploadStaging && state.deletingNoteId == null,
                        visible = editing,
                        onPickStarted = { onAttachmentPickStarted(); closeSearch() },
                        preparePick = { launch ->
                            if (editing && onAttachmentAtCursor != null) {
                                val anchor = UUID.randomUUID().toString()
                                editorController.saveSelection {
                                    editorController.reserveAttachment(anchor, AppLanguage.get(context, R.string.attachment)) { reserved ->
                                        if (reserved) {
                                            attachmentAnchor = anchor; onAttachmentAnchor(anchor)
                                            onPrepareAttachment({ finishEditing(launch) }, { attachmentAnchor = null; onAttachmentAnchor(null); onCancelAttachment() })
                                        }
                                    }
                                }
                            } else finishEditing(launch)
                        },
                        onPicked = { uri, inlineImage ->
                            if (onAttachmentAtCursor != null) {
                                awaitingAttachmentStage = true
                                onAttachmentAtCursor(uri, inlineImage, attachmentAnchor)
                            } else onAttachment?.invoke(uri, inlineImage)
                        }, onCancelled = { attachmentAnchor = null; onAttachmentAnchor(null); onCancelAttachment() },
                        controller = attachmentPickerController)
                }
                if (!readOnly) {
                    if (editing) TextButton(onClick = { finishEditing() }, enabled = !flushing && !state.operationBusy) {
                        Text(AppLanguage.get(context, R.string.done))
                    } else IconButton(onClick = {
                        closeSearch()
                        editing = true
                        latestEditingChanged.value(true)
                    }, enabled = !flushing && !state.operationBusy) {
                        Icon(Icons.Outlined.Edit, AppLanguage.get(context, R.string.edit_note))
                    }
                }
                NoteActionsMenu(document.note,
                    onDelete = if (!editing && !folderContent) onDelete?.let { { showDeleteConfirmation = true } } else null,
                    enabled = !flushing && !state.operationBusy && state.deletingNoteId == null,
                    deleteEnabled = !state.localWritePending,
                    onSearch = if (searchable) ({ finishEditing { searchVisible = true } }) else null,
                    onHistory = if (searchable && onHistory != null) ({
                        closeSearch()
                        finishEditing(onHistory)
                    }) else null,
                    favorite = favorite, onFavorite = onFavorite,
                    onOrganize = onOrganize?.let { action -> { kind -> finishEditing { action(kind) } } },
                    onInsertLink = if (canAttach && editing) ({ editorController.saveSelection { saved -> if (saved) showLinkPicker = true } }) else null,
                    onBacklinks = onBacklinks?.let { { finishEditing(it) } },
                    onAttachments = if (canAttach) onAttachmentManager?.let { { finishEditing(it) } } else null)
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background))
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searchVisible) NoteSearchBar(searchState, onClose = ::closeSearch)
            if (state.uploadStaging) Text(AppLanguage.get(context, R.string.msg_upload_staging),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
            if (uploadState.pendingCount > 0 || uploadState.error != null) {
                AttachmentUploadStatus(uploadState, onSync, enabled = !state.operationBusy)
            }
            if (conflict) {
                MessageCard(if (document.remoteMissing)
                    AppLanguage.get(context, R.string.conflict_remote_missing)
                    else AppLanguage.get(context, R.string.conflict_remote_changed),
                    AppLanguage.get(context, R.string.save_copy),
                    if (state.operationBusy) null else onKeepConflictAsCopy, Modifier.padding(horizontal = 20.dp))
            }
            state.error?.takeUnless { editing && localSaveFailed }?.let {
                MessageCard(it, action = AppLanguage.get(context, R.string.retry_save),
                    onRetry = if (state.localWritePending) ::retrySaving else null,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
            key(document.note.id, state.editorEpoch) {
                if (diagram) {
                    DiagramViewer(document.note.id, document.note.type, document.html, Modifier.fillMaxWidth().weight(1f), packages = diagramPackages)
                } else if (document.note.isProtected || (readOnly && !htmlNote && !attachment)) {
                    SpecialNoteContent(document.note, initialHtml, onOpenLink, Modifier.fillMaxWidth().weight(1f), serverUrl)
                } else if (attachment) {
                    AttachmentViewer(document.note, attachmentLoader, Modifier.fillMaxWidth().weight(1f))
                } else if (htmlNote) {
                    RichNoteEditor(
                        noteKey = document.note.id,
                        title = editorTitle,
                        initialFocusTitle = untouchedNewTitle,
                        initialHtml = initialHtml,
                        controller = editorController,
                        readOnly = readOnly || state.operationBusy,
                        isEditing = editing && !state.operationBusy,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onTitleChanged = { if (!readOnly && !state.operationBusy) onTitleChanged(it) },
                        onHtmlChanged = { if (!readOnly && !state.operationBusy) onHtmlChanged(it) },
                        onOpenNote = { if (!state.operationBusy && !flushing) onOpenNote(it) },
                        onOpenLink = { if (!state.operationBusy && !flushing) onOpenLink(it) },
                        resourceLoader = resourceLoader,
                        serverUrl = serverUrl,
                        searchState = if (searchVisible) searchState else null,
                    )
                } else {
                    if (document.note.type == "mermaid") {
                        Text(AppLanguage.get(context, R.string.diagram_source), Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                    SourceNoteEditor(
                        noteKey = document.note.id,
                        title = document.note.title,
                        initialContent = initialHtml,
                        controller = editorController,
                        mime = document.note.mime,
                        readOnly = readOnly || state.operationBusy,
                        isEditing = editing && !state.operationBusy,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onTitleChanged = { if (!readOnly && !state.operationBusy) onTitleChanged(it) },
                        onContentChanged = { if (!readOnly && !state.operationBusy) onHtmlChanged(it) },
                        onOpenNote = { if (!state.operationBusy && !flushing) onOpenNote(it) },
                        onOpenLink = { if (!state.operationBusy && !flushing) onOpenLink(it) },
                        resourceLoader = resourceLoader,
                        serverUrl = serverUrl,
                        searchState = if (searchVisible) searchState else null,
                    )
                }
            }
        }
    }
    if (showLinkPicker) NoteLinkPicker(linkCandidates.filterNot { it.id == document.note.id },
        onPick = { target -> showLinkPicker = false; editorController.insertNoteLink(target.id, target.title) },
        onDismiss = { showLinkPicker = false })
    if (showDeleteConfirmation) {
        DeleteNoteDialog(document.note, onDismiss = { showDeleteConfirmation = false }, onConfirm = {
            showDeleteConfirmation = false
            if (!state.operationBusy && !state.localWritePending) onDelete?.invoke()
        })
    }
}

@Composable
private fun NoteActionsMenu(
    note: NoteMeta, onDelete: (() -> Unit)?, enabled: Boolean = true,
    deleteEnabled: Boolean = true, onSearch: (() -> Unit)? = null, onHistory: (() -> Unit)? = null,
    favorite: Boolean = false, onFavorite: (() -> Unit)? = null,
    onOrganize: ((LibraryAction) -> Unit)? = null, onInsertLink: (() -> Unit)? = null,
    onBacklinks: (() -> Unit)? = null, onAttachments: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val hasDelete = onDelete != null && note.canDelete
    if (!hasDelete && onSearch == null && onHistory == null && onFavorite == null && onBacklinks == null) return
    var expanded by remember(note.id) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.testTag("note-actions-${note.id}")) {
            Icon(Icons.Outlined.MoreVert, AppLanguage.get(context, R.string.note_actions, note.title.ifBlank { AppLanguage.get(context, R.string.untitled) }))
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            if (onSearch != null) DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.note_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.testTag("note-find"),
                onClick = { expanded = false; onSearch() })
            if (onHistory != null) DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.history_title)) },
                leadingIcon = { Icon(Icons.Outlined.History, null) }, modifier = Modifier.testTag("note-history"),
                onClick = { expanded = false; onHistory() })
            if (onFavorite != null) DropdownMenuItem(text = { Text(AppLanguage.get(context, if (favorite) R.string.library_favorite_remove else R.string.library_favorite_add)) },
                leadingIcon = { Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, null) },
                modifier = Modifier.testTag("note-favorite"), onClick = { expanded = false; onFavorite() })
            if (onInsertLink != null) DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.editor_insert_note_link)) },
                leadingIcon = { Icon(Icons.Outlined.Link, null) }, modifier = Modifier.testTag("note-insert-link"), onClick = { expanded = false; onInsertLink() })
            if (onBacklinks != null) DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.editor_backlinks)) },
                leadingIcon = { Icon(Icons.Outlined.Link, null) }, onClick = { expanded = false; onBacklinks() })
            if (onAttachments != null) DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.editor_attachments)) },
                leadingIcon = { Icon(Icons.Outlined.AttachFile, null) }, modifier = Modifier.testTag("note-attachments"), onClick = { expanded = false; onAttachments() })
            if (onOrganize != null && note.canDelete) for ((action, label) in listOf(LibraryAction.MOVE to R.string.library_move, LibraryAction.COPY to R.string.library_copy, LibraryAction.CLONE to R.string.library_clone)) {
                DropdownMenuItem(text = { Text(AppLanguage.get(context, label)) }, enabled = deleteEnabled,
                    onClick = { expanded = false; onOrganize(action) })
            }
            if (hasDelete) DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.delete)) },
                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error, leadingIconColor = MaterialTheme.colorScheme.error),
                leadingIcon = { Icon(Icons.Outlined.Delete, null) }, enabled = deleteEnabled,
                onClick = { expanded = false; onDelete?.invoke() })
        }
    }
}

@Composable
private fun AttachmentUploadStatus(state: AttachmentUploadState, onRetry: () -> Unit, enabled: Boolean = true) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 5.dp).testTag("attachment-upload-status"),
        verticalAlignment = Alignment.CenterVertically) {
        if (state.isSyncing) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(state.error ?: AppLanguage.get(context, R.string.msg_upload_pending, state.pendingCount),
            style = MaterialTheme.typography.labelSmall,
            color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        if (state.error != null) TextButton(onClick = onRetry, enabled = enabled && !state.isSyncing) {
            Text(AppLanguage.get(context, R.string.retry))
        }
    }
}

@Composable
private fun DeleteNoteDialog(note: NoteMeta, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("delete-note-dialog"),
        title = { Text(AppLanguage.get(context, R.string.library_trash_title)) },
        text = { Text(note.title + "\n\n" + AppLanguage.get(context, R.string.library_trash_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm-delete-note")) {
                Text(AppLanguage.get(context, R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppLanguage.get(context, R.string.cancel)) } })
}

/** Preserve special formats without presenting their internal data as a rendered document. */
@Composable
private fun SpecialNoteContent(note: NoteMeta, source: String, onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier, serverUrl: String = "") {
    val context = LocalContext.current
    var showSource by rememberSaveable(note.id) { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(22.dp).testTag("special-note")) {
        Text(note.title.ifBlank { AppLanguage.get(context, R.string.untitled) }, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(22.dp))
        Text(when {
            note.isProtected -> AppLanguage.get(context, R.string.protected_note_help)
            source.isBlank() -> AppLanguage.get(context, R.string.special_section_help)
            else -> AppLanguage.get(context, R.string.special_note_help)
        }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            onOpenLink("${serverUrl.trimEnd('/')}/#root/${Uri.encode(note.id)}")
        }) {
            Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(AppLanguage.get(context, R.string.open_in_trilium))
        }
        if (!note.isProtected && source.isNotBlank()) {
            TextButton(onClick = { showSource = !showSource }, modifier = Modifier.testTag("toggle-special-source")) {
                Text(if (showSource) AppLanguage.get(context, R.string.hide_source) else AppLanguage.get(context, R.string.show_source))
            }
            if (showSource) {
                SelectionContainer {
                    Text(source, Modifier.fillMaxWidth().padding(top = 12.dp).testTag("special-note-source"),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
    }
}

@Composable
private fun MessageCard(
    message: String, action: String? = null, onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Surface(modifier.fillMaxWidth().padding(vertical = 9.dp), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(13.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onRetry != null) TextButton(onClick = onRetry, contentPadding = PaddingValues(top = 6.dp, end = 9.dp)) {
                Text(action ?: AppLanguage.get(context, R.string.retry), fontWeight = FontWeight.Medium)
            }
        }
    }
}
