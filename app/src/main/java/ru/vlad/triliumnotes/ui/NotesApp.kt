@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.vlad.triliumnotes.ui

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
import ru.vlad.triliumnotes.NotesScreenState
import ru.vlad.triliumnotes.NotesViewModel
import ru.vlad.triliumnotes.LOCAL_SAVE_ERROR
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
    val state by model.state.collectAsStateWithLifecycle()
    val sync by model.syncState.collectAsStateWithLifecycle()
    val offline by model.offlineState.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
        catch (_: ActivityNotFoundException) { linkError = "На телефоне нет приложения для открытия этой ссылки." }
        catch (_: SecurityException) { linkError = "Не удалось открыть ссылку." }
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
                .takeUnless { it.isBlank() || it == "download" || it == "content" } ?: "Вложение"
            val note = NoteMeta(target.id, name, "file", "application/octet-stream", false, null, null, emptyList())
            Dialog(onDismissRequest = { attachmentUrl = null },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                Scaffold(topBar = {
                    TopAppBar(title = { Text("Вложение", style = MaterialTheme.typography.titleMedium) },
                        navigationIcon = { IconButton(onClick = { attachmentUrl = null }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Вернуться к заметке")
                        } })
                }) { padding ->
                    AttachmentViewer(note, { model.loadLinkedAttachment(url) }, Modifier.fillMaxSize().padding(padding))
                }
            }
        }
    }
    linkError?.let { message ->
        AlertDialog(onDismissRequest = { linkError = null }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { linkError = null }) { Text("Закрыть") } })
    }
}

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
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад")
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
                        Text(folder.title, style = MaterialTheme.typography.titleLarge,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (!inFolder && onConnectionSettings != null) {
                        IconButton(onClick = { dismissKeyboard(); onConnectionSettings() },
                            enabled = !busy && !state.localWritePending && state.deletingNoteId == null,
                            modifier = Modifier.testTag("connection-settings")) {
                            Icon(Icons.Outlined.Settings, "Настройки подключения")
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
                    Icon(Icons.Outlined.Add, contentDescription = "Создать заметку или папку")
                }
                DropdownMenu(expanded = createMenuExpanded && !busy,
                    onDismissRequest = { createMenuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Новая заметка") },
                        leadingIcon = { Icon(Icons.Outlined.NoteAdd, null) },
                        modifier = Modifier.testTag("new-note-menu"),
                        onClick = { createMenuExpanded = false; onCreate() })
                    DropdownMenuItem(text = { Text("Новая папка") },
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
                    placeholder = { Text("Найти во всех заметках", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null, modifier = Modifier.size(21.dp)) },
                    trailingIcon = if (state.query.isNotEmpty()) ({
                        IconButton(onClick = ::clearSearch) { Icon(Icons.Outlined.Close, "Очистить поиск") }
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
                        Text(if (searching) "Результаты поиска" else "Вложенные заметки",
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
                            Text(if (state.searching) "Ищем заметки…" else "Загружаем…",
                                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                    }
                }
            } else if (searching && state.error != null && state.results.isEmpty()) {
                item(key = "reset-failed-search") {
                    TextButton(onClick = ::clearSearch) { Text("Сбросить поиск") }
                }
            } else if ((searching && state.results.isEmpty()) || (!searching && state.items.isEmpty())) {
                item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (state.error != null) Icons.Outlined.CloudOff else if (searching) Icons.Outlined.SearchOff else Icons.Outlined.Description, null,
                            Modifier.size(32.dp), tint = colors.onSurfaceVariant)
                        Spacer(Modifier.height(14.dp))
                        Text(if (state.error != null) "Нет заметок для показа" else if (searching) "Ничего не найдено" else if (inFolder) "Нет вложенных заметок" else "Здесь пока нет заметок",
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        if (searching) TextButton(onClick = ::clearSearch) { Text("Сбросить поиск") }
                        else if (state.error == null) TextButton(onClick = onCreate) { Text("Создать заметку") }
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
            title = { Text("Новая папка") },
            text = {
                OutlinedTextField(value = folderTitle, onValueChange = { folderTitle = it },
                    label = { Text("Название папки") }, singleLine = true,
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
                    }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { showFolderDialog = false }) { Text("Отмена") } })
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
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().testTag("folder-content").clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, onClickLabel = "Открыть содержимое заметки", onClick = onOpen)
        .heightIn(min = 68.dp).padding(vertical = 13.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(39.dp).clip(RoundedCornerShape(11.dp)).background(colors.surfaceVariant),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Article, null, Modifier.size(23.dp), tint = colors.onSurfaceVariant)
        }
        Spacer(Modifier.width(13.dp))
        Text(if (note?.type == "text") "Текст заметки" else "Содержимое заметки",
            modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
    }
}

internal fun noteKind(note: NoteMeta): String = when {
    note.isProtected -> "Защищённая заметка"
    note.type == "text" -> "Текстовая заметка"
    note.type == "doc" -> "Справка Trilium"
    note.type == "code" -> "Код · ${note.mime.substringAfterLast('/')}"
    note.type == "image" -> "Изображение"
    note.type == "file" -> attachmentFormat(note.mime, note.title)
    note.type == "book" -> "Папка"
    note.type == "canvas" -> "Холст"
    note.type == "mermaid" -> "Диаграмма Mermaid"
    note.type == "relationMap" -> "Карта связей"
    note.type == "noteMap" -> "Карта заметок"
    note.type == "search" -> "Сохранённый поиск"
    note.type == "launcher" -> "Команда Trilium"
    note.type == "contentWidget" -> "Служебный раздел Trilium"
    note.type == "webView" -> "Веб-страница"
    else -> "Специальная заметка"
}

@Composable
internal fun NoteRow(note: NoteMeta, prefix: String, onOpen: () -> Unit, onFolder: () -> Unit,
    onDelete: (() -> Unit)? = null, deleteEnabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val folder = note.childBranchIds.isNotEmpty() || note.type == "book"
    Row(Modifier.fillMaxWidth().testTag("note-row-${note.id}").clip(RoundedCornerShape(12.dp))
        .clickable(onClickLabel = if (folder) "Открыть вложенные заметки" else "Открыть заметку",
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
            val displayTitle = note.title.ifBlank { "Без названия" }
            Text(if (prefix.isEmpty()) displayTitle else "$prefix · $displayTitle",
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (folder || note.type != "text" || note.isProtected) {
                Spacer(Modifier.height(3.dp))
                Text(if (folder) "Вложенных: ${note.childBranchIds.size}${if (note.isProtected) " · Защищено" else ""}" else noteKind(note),
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
    attachmentLoader: suspend (NoteMeta) -> CachedAttachment = { error("Файл пока не загружен") },
    onRetryLocalSave: () -> Unit = {},
    onDelete: (() -> Unit)? = null,
    serverUrl: String = "",
) {
    val colors = MaterialTheme.colorScheme
    val initialHtml = remember { document.html }
    val htmlNote = document.note.type == "doc" ||
        (document.note.type == "text" && document.note.mime.substringBefore(';') == "text/html")
    val attachment = document.note.type == "image" || document.note.type == "file"
    val readOnly = !document.note.isEditable
    val conflict = document.conflict || document.note.id in sync.conflicts
    val localSaveFailed = state.localWritePending && state.error == LOCAL_SAVE_ERROR
    var editing by rememberSaveable(document.note.id) { mutableStateOf(state.newNoteId == document.note.id && !readOnly) }
    // Only this newly created, untouched title can hide the server's generated fallback.
    // A real existing title is always passed through, including "Без названия".
    val untouchedNewTitle = state.newNoteId == document.note.id && state.newNoteTitleUntouched
    val editorTitle = if (untouchedNewTitle && document.note.title == "Без названия") "" else document.note.title
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
                        localSaveFailed -> "Не сохранено"
                        flushing || state.localWritePending -> "Сохраняю…"
                        conflict -> "Есть другая версия"
                        document.note.isProtected -> "Защищённая заметка"
                        readOnly && htmlNote -> "Только чтение"
                        readOnly && !attachment -> noteKind(document.note)
                        else -> ""
                    }
                    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.labelSmall,
                        color = if (localSaveFailed) colors.error else colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }, navigationIcon = {
                IconButton(onClick = { if (editing) finishEditing() else onBack() }, enabled = !flushing && (editing || !state.localWritePending)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, if (editing) "Завершить редактирование" else "Назад к заметкам")
                }
            }, actions = {
                if (editing && localSaveFailed) {
                    TextButton(onClick = ::retrySaving, enabled = !flushing,
                        modifier = Modifier.semantics { contentDescription = "Повторить сохранение" }) {
                        Text("Повторить")
                    }
                }
                if (document.pending && !flushing && !state.localWritePending && !conflict) {
                    PendingNoteStatusControl(sync, state.networkAvailable, onSync)
                }
                if (!readOnly) {
                    if (editing) TextButton(onClick = ::finishEditing, enabled = !flushing) { Text("Готово") }
                    else IconButton(onClick = { editing = true }) { Icon(Icons.Outlined.Edit, "Редактировать заметку") }
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
                    "Сервер не нашёл заметку или её папку. Ваши данные сохранены на телефоне. Копия появится в корне заметок."
                    else "На сервере заметка изменилась. Ваш текст сохранён отдельно на телефоне.",
                    "Сохранить копию", onKeepConflictAsCopy, Modifier.padding(horizontal = 20.dp))
            }
            state.error?.takeUnless { editing && localSaveFailed }?.let {
                MessageCard(it, action = "Повторить сохранение",
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
                    Text("Исходный текст диаграммы", Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
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
    if (onDelete == null || !note.canDelete) return
    var expanded by remember(note.id) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.testTag("note-actions-${note.id}")) {
            Icon(Icons.Outlined.MoreVert, "Действия с заметкой «${note.title.ifBlank { "Без названия" }}»")
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { expanded = false; onDelete() })
        }
    }
}

@Composable
private fun DeleteNoteDialog(note: NoteMeta, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("delete-note-dialog"),
        title = { Text("Удалить «${note.title.ifBlank { "Без названия" }}»?") },
        text = {
            Text(buildString {
                append("Заметка будет удалена из всех папок.")
                if (note.childBranchIds.isNotEmpty()) {
                    append(" Вложенные заметки тоже будут удалены, кроме тех, что находятся и в других папках.")
                }
                append(" Удаление отправится на сервер при подключении.")
            })
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm-delete-note")) {
                Text("Удалить", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

/** Preserve special formats without presenting their internal data as a rendered document. */
@Composable
private fun SpecialNoteContent(note: NoteMeta, source: String, onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier, serverUrl: String = "") {
    var showSource by rememberSaveable(note.id) { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(22.dp).testTag("special-note")) {
        Text(note.title.ifBlank { "Без названия" }, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(22.dp))
        Text(when {
            note.isProtected -> "Чтобы прочитать эту заметку, откройте её в Trilium и разблокируйте."
            source.isBlank() -> "Этот раздел доступен в Trilium."
            else -> "Визуальный просмотр этого типа заметки пока недоступен в приложении. Заметку можно открыть в Trilium."
        }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            onOpenLink("${serverUrl.trimEnd('/')}/#root/${Uri.encode(note.id)}")
        }) {
            Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Открыть в Trilium")
        }
        if (!note.isProtected && source.isNotBlank()) {
            TextButton(onClick = { showSource = !showSource }, modifier = Modifier.testTag("toggle-special-source")) {
                Text(if (showSource) "Скрыть исходные данные" else "Показать исходные данные")
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
    message: String, action: String = "Повторить", onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth().padding(vertical = 9.dp), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(13.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onRetry != null) TextButton(onClick = onRetry, contentPadding = PaddingValues(top = 6.dp, end = 9.dp)) {
                Text(action, fontWeight = FontWeight.Medium)
            }
        }
    }
}
