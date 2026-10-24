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
import ru.vlad.triliumnotes.data.CachedAttachment
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

@Composable
fun NotesApp(model: NotesViewModel, onConnectionSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    val sync by model.syncState.collectAsStateWithLifecycle()
    val offline by model.offlineState.collectAsStateWithLifecycle()
    var attachmentUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var linkError by remember { mutableStateOf<String?>(null) }
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
    BackHandler(state.document != null || state.openingNoteId != null || state.folders.size > 1 || state.query.isNotEmpty()) { model.back() }
    val document = state.document
    val libraryState = rememberSaveableStateHolder()
    if (document == null) {
        val listState = rememberLibraryListState(libraryState, state)
        LibraryScreen(state, sync, onBack = model::back, onSearch = model::search,
            onRefresh = model::refreshAll, onSync = model::syncNow, onCreate = model::create,
            onOpen = model::open, onFolder = model::enterFolder, onOpenFolderContent = model::openCurrentFolder,
            offline = offline,
            onOpenConflict = { sync.conflicts.firstOrNull()?.let(model::openById) }, onDelete = model::delete,
            listState = listState, onCreateFolder = model::createFolder,
            onConnectionSettings = onConnectionSettings)
    } else {
        key(document.note.id) {
            EditorScreen(state, document, sync, onBack = model::back,
                onTitleChanged = { model.editTitle(document.note.id, it) },
                onHtmlChanged = { model.editHtml(document.note.id, it) },
                onSync = model::syncNow, onKeepConflictAsCopy = model::keepConflictAsCopy,
                onOpenNote = model::openById, onOpenLink = ::openLink,
                resourceLoader = { path -> model.loadResource(path)?.let { EditorResource(it.mimeType, it.bytes) } },
                attachmentLoader = model::loadAttachment, onRetryLocalSave = model::retryLocalSave,
                onDelete = { model.delete(document.note) }, serverUrl = model.serverUrl)
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
    linkError?.let { message ->
        AlertDialog(onDismissRequest = { linkError = null }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { linkError = null }) { Text(AppLanguage.get(context, R.string.close)) } })
    }
}

/** The root label belongs to the UI; all other folder names belong to the user. */
internal fun folderDisplayTitle(context: Context, folder: Folder): String =
    if (folder.id == "root") AppLanguage.get(context, R.string.notes) else folder.title

/** A separate saved scroll position for every folder path and exact search query. */
internal fun libraryStateKey(state: NotesScreenState): String =
    "library:" + state.folders.joinToString("/") { Uri.encode(it.id) } + "?" + Uri.encode(state.query)

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
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val searching = state.query.isNotBlank()
    val busy = state.loading || state.searching
    val hasVisibleItems = if (searching) state.results.isNotEmpty() else state.items.isNotEmpty()
    val inFolder = state.folders.size > 1
    val folder = state.folders.last()
    var deleteTarget by remember { mutableStateOf<NoteMeta?>(null) }
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
                    if (inFolder) {
                        IconButton(onClick = { dismissKeyboard(); onBack() }) {
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
                        Text(folderDisplayTitle(context, folder), style = MaterialTheme.typography.titleLarge,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (!inFolder && onConnectionSettings != null) {
                        IconButton(onClick = { dismissKeyboard(); onConnectionSettings() },
                            enabled = !busy && !state.localWritePending && state.deletingNoteId == null,
                            modifier = Modifier.testTag("connection-settings")) {
                            Icon(Icons.Outlined.Settings, AppLanguage.get(context, R.string.connection_settings))
                        }
                    }
                    if (inFolder) state.currentFolderNote?.let { note ->
                        NoteActionsMenu(note, onDelete?.let { { dismissKeyboard(); deleteTarget = note } },
                            enabled = !busy && state.deletingNoteId == null)
                    }
                    SyncStatusControl(sync, offline, state.networkAvailable, state.fromCache,
                        onRefresh = { dismissKeyboard(); onRefresh() },
                        isViewBusy = busy, viewError = state.error,
                        onOpenConflict = onOpenConflict?.let { openConflict -> { dismissKeyboard(); openConflict() } })
                }
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.6f))
            }
        },
        floatingActionButton = {
            Box {
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
            if (offline.isRunning && !offline.available) {
                item(key = "offline-progress") { InitialOfflineProgress(offline, Modifier.padding(bottom = 12.dp)) }
            }
            item(key = "search") {
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
            if (searching || inFolder) {
                item(key = "list-heading") {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("library-section-heading"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (searching) AppLanguage.get(context, R.string.search_results) else AppLanguage.get(context, R.string.child_notes),
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                        val count = if (searching) state.results.size else state.items.size
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
            } else if ((searching && state.results.isEmpty()) || (!searching && state.items.isEmpty())) {
                item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (state.error != null) Icons.Outlined.CloudOff else if (searching) Icons.Outlined.SearchOff else Icons.Outlined.Description, null,
                            Modifier.size(32.dp), tint = colors.onSurfaceVariant)
                        Spacer(Modifier.height(14.dp))
                        Text(if (state.error != null) AppLanguage.get(context, R.string.no_notes_to_show) else if (searching) AppLanguage.get(context, R.string.nothing_found) else if (inFolder) AppLanguage.get(context, R.string.no_child_notes) else AppLanguage.get(context, R.string.no_notes_yet),
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        if (searching) TextButton(onClick = ::clearSearch) { Text(AppLanguage.get(context, R.string.reset_search)) }
                        else if (state.error == null) TextButton(onClick = onCreate) { Text(AppLanguage.get(context, R.string.create_note)) }
                    }
                }
            } else if (searching) {
                items(state.results, key = { "result-${it.id}" }) { note ->
                    NoteRow(note, "", onOpen = { openNote(note) }, onFolder = { openFolder(note) },
                        onDelete = onDelete?.let { { dismissKeyboard(); deleteTarget = note } },
                        deleteEnabled = state.deletingNoteId == null)
                }
            } else {
                items(state.items, key = { "branch-${it.branchId}" }) { item ->
                    NoteRow(item.note, item.prefix, onOpen = { openNote(item.note) }, onFolder = { openFolder(item.note) },
                        onDelete = onDelete?.let { { dismissKeyboard(); deleteTarget = item.note } },
                        deleteEnabled = state.deletingNoteId == null)
                }
            }
            if (!searching && inFolder && state.currentFolderHasContent == true) {
                item(key = "folder-content") {
                    FolderContentRow(state.currentFolderNote, enabled = !busy,
                        onOpen = { dismissKeyboard(); onOpenFolderContent() })
                }
            }
        }
    }
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

@Composable
internal fun NoteRow(note: NoteMeta, prefix: String, onOpen: () -> Unit, onFolder: () -> Unit,
    onDelete: (() -> Unit)? = null, deleteEnabled: Boolean = true) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val folder = note.childBranchIds.isNotEmpty() || note.type == "book"
    Row(Modifier.fillMaxWidth().testTag("note-row-${note.id}").clip(RoundedCornerShape(12.dp))
        .clickable(onClickLabel = if (folder) AppLanguage.get(context, R.string.open_child_notes) else AppLanguage.get(context, R.string.open_note),
            onClick = if (folder) onFolder else onOpen)
        .heightIn(min = 68.dp).padding(vertical = 13.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(39.dp).clip(RoundedCornerShape(11.dp)).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
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
        if (onDelete != null && note.canDelete) {
            NoteActionsMenu(note, onDelete, deleteEnabled)
        } else if (folder) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
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
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val initialHtml = remember { document.html }
    val htmlNote = document.note.type == "doc" ||
        (document.note.type == "text" && document.note.mime.substringBefore(';') == "text/html")
    val attachment = document.note.type == "image" || document.note.type == "file"
    val readOnly = !document.note.isEditable
    val conflict = document.conflict || document.note.id in sync.conflicts
    val localSaveFailed = state.localWritePending && state.localSaveFailed
    var editing by rememberSaveable(document.note.id) { mutableStateOf(state.newNoteId == document.note.id && !readOnly) }
    // Only this newly created, untouched title can hide the server's generated fallback.
    // Existing titles are user content and always pass through unchanged.
    val untouchedNewTitle = state.newNoteId == document.note.id && state.newNoteTitleUntouched
    val editorTitle = if (untouchedNewTitle) "" else document.note.title
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val editorController = rememberRichNoteEditorController()
    var flushing by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember(document.note.id) { mutableStateOf(false) }
    fun finishEditing() {
        if (flushing) return
        flushing = true
        editorController.flush {
            focusManager.clearFocus()
            keyboard?.hide()
            editing = false
            flushing = false
            onSync()
        }
    }
    fun retrySaving() {
        if (flushing) return
        flushing = true
        editorController.flush {
            flushing = false
            onRetryLocalSave()
        }
    }
    BackHandler(editing) { finishEditing() }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val status = when {
                        localSaveFailed -> AppLanguage.get(context, R.string.not_saved)
                        flushing || state.localWritePending -> AppLanguage.get(context, R.string.saving)
                        conflict -> AppLanguage.get(context, R.string.another_version)
                        document.note.isProtected -> AppLanguage.get(context, R.string.kind_protected)
                        readOnly && htmlNote -> AppLanguage.get(context, R.string.read_only)
                        readOnly && !attachment -> noteKind(context, document.note)
                        else -> ""
                    }
                    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.labelSmall,
                        color = if (localSaveFailed) colors.error else colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }, navigationIcon = {
                IconButton(onClick = { if (editing) finishEditing() else onBack() }, enabled = !flushing && (editing || !state.localWritePending)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, if (editing) AppLanguage.get(context, R.string.finish_editing) else AppLanguage.get(context, R.string.back_to_notes))
                }
            }, actions = {
                if (editing && localSaveFailed) {
                    TextButton(onClick = ::retrySaving, enabled = !flushing,
                        modifier = Modifier.semantics { contentDescription = AppLanguage.get(context, R.string.retry_save) }) {
                        Text(AppLanguage.get(context, R.string.retry))
                    }
                }
                if (document.pending && !flushing && !state.localWritePending && !conflict) {
                    PendingNoteStatusControl(sync, state.networkAvailable, onSync)
                }
                if (!readOnly) {
                    if (editing) TextButton(onClick = ::finishEditing, enabled = !flushing) { Text(AppLanguage.get(context, R.string.done)) }
                    else IconButton(onClick = { editing = true }) { Icon(Icons.Outlined.Edit, AppLanguage.get(context, R.string.edit_note)) }
                }
                if (!editing) {
                    NoteActionsMenu(document.note, onDelete?.let { { showDeleteConfirmation = true } },
                        enabled = !flushing && !state.localWritePending && state.deletingNoteId == null)
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background))
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (conflict) {
                MessageCard(if (document.remoteMissing)
                    AppLanguage.get(context, R.string.conflict_remote_missing)
                    else AppLanguage.get(context, R.string.conflict_remote_changed),
                    AppLanguage.get(context, R.string.save_copy), onKeepConflictAsCopy, Modifier.padding(horizontal = 20.dp))
            }
            state.error?.takeUnless { editing && localSaveFailed }?.let {
                MessageCard(it, action = AppLanguage.get(context, R.string.retry_save),
                    onRetry = if (state.localWritePending) ::retrySaving else null,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
            if (document.note.isProtected || (readOnly && !htmlNote && !attachment)) {
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
                    readOnly = readOnly,
                    isEditing = editing,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onTitleChanged = { if (!readOnly) onTitleChanged(it) },
                    onHtmlChanged = { if (!readOnly) onHtmlChanged(it) },
                    onOpenNote = onOpenNote,
                    onOpenLink = onOpenLink,
                    resourceLoader = resourceLoader,
                    serverUrl = serverUrl,
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
                    readOnly = readOnly,
                    isEditing = editing,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onTitleChanged = { if (!readOnly) onTitleChanged(it) },
                    onContentChanged = { if (!readOnly) onHtmlChanged(it) },
                    onOpenNote = onOpenNote,
                    onOpenLink = onOpenLink,
                    resourceLoader = resourceLoader,
                    serverUrl = serverUrl,
                )
            }
        }
    }
    if (showDeleteConfirmation) {
        DeleteNoteDialog(document.note, onDismiss = { showDeleteConfirmation = false }, onConfirm = {
            showDeleteConfirmation = false
            onDelete?.invoke()
        })
    }
}

@Composable
private fun NoteActionsMenu(note: NoteMeta, onDelete: (() -> Unit)?, enabled: Boolean = true) {
    val context = LocalContext.current
    if (onDelete == null || !note.canDelete) return
    var expanded by remember(note.id) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.testTag("note-actions-${note.id}")) {
            Icon(Icons.Outlined.MoreVert, AppLanguage.get(context, R.string.note_actions, note.title.ifBlank { AppLanguage.get(context, R.string.untitled) }))
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { expanded = false; onDelete() })
        }
    }
}

@Composable
private fun DeleteNoteDialog(note: NoteMeta, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("delete-note-dialog"),
        title = { Text(AppLanguage.get(context, R.string.delete_title, note.title.ifBlank { AppLanguage.get(context, R.string.untitled) })) },
        text = {
            Text(buildString {
                append(AppLanguage.get(context, R.string.delete_all_folders))
                if (note.childBranchIds.isNotEmpty()) {
                    append(AppLanguage.get(context, R.string.delete_children))
                }
                append(AppLanguage.get(context, R.string.delete_sync_message))
            })
        },
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
