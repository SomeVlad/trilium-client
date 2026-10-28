package ru.vlad.triliumnotes.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.runBlocking
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.Folder
import ru.vlad.triliumnotes.NotesScreenState
import ru.vlad.triliumnotes.data.OfflineState
import ru.vlad.triliumnotes.data.NoteMeta
import ru.vlad.triliumnotes.data.SyncState
import ru.vlad.triliumnotes.data.TreeItem

class LibraryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun folderDeletionExplainsDescendantsAndCancellationDoesNothing() {
        val folder = note("folder", "Книги", listOf("branch"))
        val deleted = mutableListOf<NoteMeta>()
        compose.setContent {
            NotesTheme {
                LibraryScreen(NotesScreenState(loading = false,
                    folders = listOf(Folder("root", "Заметки"), Folder(folder.id, folder.title)),
                    currentFolderNote = folder), SyncState(), onBack = {}, onSearch = {}, onRefresh = {},
                    onSync = {}, onCreate = {}, onOpen = {}, onFolder = {}, onOpenFolderContent = {},
                    onDelete = { deleted += it })
            }
        }
        compose.onNodeWithTag("note-actions-folder").performClick()
        compose.onNodeWithText("Удалить").performClick()
        compose.onNodeWithText("Переместить в корзину?").assertIsDisplayed()
        compose.onNodeWithText("Книги\n\nЗаметку можно восстановить из корзины. Удаление затронет её клоны и вложенные заметки без других родителей.").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithTag("delete-note-dialog").assertDoesNotExist()
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }
        compose.onNodeWithTag("note-actions-folder").performClick()
        compose.onNodeWithText("Удалить").performClick()
        compose.onNodeWithTag("confirm-delete-note").performClick()
        compose.runOnIdle { assertEquals(listOf(folder), deleted) }
    }

    @Test fun searchAttachmentDeletionDoesNotOpenTheFileAndProtectsSystemNotes() {
        val file = note("file", "Документ.pdf").copy(type = "file", mime = "application/pdf")
        val root = note("root", "Заметки")
        val system = note("_hidden", "Служебная заметка")
        var opens = 0
        val deleted = mutableListOf<NoteMeta>()
        var state by mutableStateOf(NotesScreenState(query = "документ", results = listOf(file, root, system),
            loading = false, deletingNoteId = file.id))
        compose.setContent {
            NotesTheme {
                LibraryScreen(state, SyncState(), onBack = {}, onSearch = {}, onRefresh = {}, onSync = {}, onCreate = {},
                    onOpen = { opens++ }, onFolder = { opens++ }, onOpenFolderContent = {},
                    onDelete = { deleted += it })
            }
        }
        compose.onNodeWithTag("note-actions-root").assertDoesNotExist()
        compose.onNodeWithTag("note-actions-_hidden").assertDoesNotExist()
        compose.onNodeWithTag("note-actions-file").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(deletingNoteId = null) }
        compose.onNodeWithTag("note-actions-file").performClick()
        compose.onNodeWithText("Удалить").performClick()
        compose.onNodeWithText("Переместить в корзину?").assertIsDisplayed()
        compose.onNodeWithText("Документ.pdf\n\nЗаметку можно восстановить из корзины. Удаление затронет её клоны и вложенные заметки без других родителей.").assertIsDisplayed()
        compose.onNodeWithTag("confirm-delete-note").performClick()
        compose.runOnIdle { assertEquals(0, opens); assertEquals(listOf(file), deleted) }
    }

    @Test fun changingSearchQueryKeepsFieldFocusedWhileSelectingItsOwnSavedListState() {
        var state by mutableStateOf(NotesScreenState(loading = false))
        compose.setContent {
            val saved = rememberSaveableStateHolder()
            val list = rememberLibraryListState(saved, state)
            NotesTheme {
                LibraryScreen(state, SyncState(), onBack = {}, onSearch = { state = state.copy(query = it) },
                    onRefresh = {}, onSync = {}, onCreate = {}, onOpen = {}, onFolder = {},
                    onOpenFolderContent = {}, listState = list)
            }
        }
        compose.onNodeWithTag("note-search").performClick().performTextInput("зам")
        compose.onNodeWithTag("note-search").assertIsFocused().performTextInput("етки")
        compose.onNodeWithTag("note-search").assertIsFocused().assertTextEquals("заметки")
    }

    @Test fun scrollIndexAndOffsetSurviveEditorUnmountAndNestedFolderNavigation() {
        val parent = Folder("parent", "Родитель")
        val child = Folder("child", "Вложенная папка")
        val parentState = NotesScreenState(loading = false,
            folders = listOf(Folder("root", "Заметки"), parent),
            items = (0..49).map { TreeItem("branch-$it", note("note-$it", "Заметка $it"), "", it, false) })
        var screen by mutableStateOf<NotesScreenState?>(parentState)
        lateinit var visibleList: LazyListState
        compose.setContent {
            val saved = rememberSaveableStateHolder()
            NotesTheme {
                screen?.let { state ->
                    val list = rememberLibraryListState(saved, state)
                    SideEffect { visibleList = list }
                    LibraryScreen(state, SyncState(), onBack = {}, onSearch = {}, onRefresh = {}, onSync = {},
                        onCreate = {}, onOpen = {}, onFolder = {}, onOpenFolderContent = {}, listState = list)
                }
            }
        }
        compose.runOnIdle { runBlocking { visibleList.scrollToItem(22, 37) } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(22, visibleList.firstVisibleItemIndex)
            assertEquals(37, visibleList.firstVisibleItemScrollOffset)
            screen = null // The editor replaces and fully unmounts the library.
        }
        compose.waitForIdle()
        compose.runOnIdle { screen = parentState.copy(loading = true) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(22, visibleList.firstVisibleItemIndex)
            assertEquals(37, visibleList.firstVisibleItemScrollOffset)
            screen = parentState.copy(folders = parentState.folders + child)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, visibleList.firstVisibleItemIndex)
            assertEquals(0, visibleList.firstVisibleItemScrollOffset)
            runBlocking { visibleList.scrollToItem(8, 19) }
        }
        compose.waitForIdle()
        compose.runOnIdle { screen = parentState }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(22, visibleList.firstVisibleItemIndex)
            assertEquals(37, visibleList.firstVisibleItemScrollOffset)
            screen = parentState.copy(query = "Заметка", results = parentState.items.map { it.note })
        }
        compose.waitForIdle()
        compose.runOnIdle { runBlocking { visibleList.scrollToItem(14, 29) }; screen = null }
        compose.waitForIdle()
        compose.runOnIdle {
            screen = parentState.copy(query = "Заметка", results = parentState.items.map { it.note }, searching = true)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(14, visibleList.firstVisibleItemIndex)
            assertEquals(29, visibleList.firstVisibleItemScrollOffset)
        }
    }

    @Test fun folderContentFollowsChildrenAndOpensItsOwnNote() {
        var contentOpens = 0
        var childOpens = 0
        val folder = note("folder", "Книги", listOf("branch"))
        val child = note("child", "Заметка внутри")
        val state = NotesScreenState(
            folders = listOf(Folder("root", "Заметки"), Folder(folder.id, folder.title)),
            currentFolderNote = folder, currentFolderHasContent = true,
            items = listOf(TreeItem("branch", child, "", 0, false)), loading = false,
        )
        compose.setContent {
            NotesTheme {
                LibraryScreen(state, SyncState(), onBack = {}, onSearch = {}, onRefresh = {},
                    onSync = {}, onCreate = {}, onOpen = { childOpens++ }, onFolder = {},
                    onOpenFolderContent = { contentOpens++ })
            }
        }
        compose.onNodeWithText("Текст заметки").assertIsDisplayed()
        compose.onNodeWithText("Заметки").assertDoesNotExist()
        val childBounds = compose.onNodeWithTag("note-row-child").fetchSemanticsNode().boundsInRoot
        val contentBounds = compose.onNodeWithTag("folder-content").fetchSemanticsNode().boundsInRoot
        assertTrue("Содержимое заметки должно идти после вложенных заметок", contentBounds.top >= childBounds.bottom)
        compose.onNodeWithTag("folder-content").performClick()
        compose.onNodeWithTag("note-row-child").performClick()
        compose.runOnIdle {
            assertEquals(1, contentOpens)
            assertEquals(1, childOpens)
        }
    }

    @Test fun unknownAndEmptyFolderBodiesNeverShowAnAction() {
        val folder = note("folder", "Книги", listOf("branch"))
        val child = note("child", "Заметка внутри")
        var state by mutableStateOf(NotesScreenState(
            folders = listOf(Folder("root", "Заметки"), Folder(folder.id, folder.title)),
            currentFolderNote = folder, currentFolderHasContent = null,
            items = listOf(TreeItem("branch", child, "", 0, false)), loading = false,
        ))
        compose.setContent {
            NotesTheme {
                LibraryScreen(state, SyncState(), onBack = {}, onSearch = {}, onRefresh = {},
                    onSync = {}, onCreate = {}, onOpen = {}, onFolder = {}, onOpenFolderContent = {})
            }
        }
        compose.onNodeWithTag("folder-content").assertDoesNotExist()
        compose.onNodeWithTag("note-row-child").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(currentFolderHasContent = false) }
        compose.onNodeWithTag("folder-content").assertDoesNotExist()
        compose.onNodeWithTag("note-row-child").assertIsDisplayed()
    }

    @Test fun folderContentAppearsOnlyAfterConfirmationAndStaysOutOfSearch() {
        val folder = note("folder", "Книги", listOf("branch"))
        val child = note("child", "Заметка внутри")
        var state by mutableStateOf(NotesScreenState(
            folders = listOf(Folder("root", "Заметки"), Folder(folder.id, folder.title)),
            currentFolderNote = folder, currentFolderHasContent = null,
            items = listOf(TreeItem("branch", child, "", 0, false)), loading = false,
        ))
        compose.setContent {
            NotesTheme {
                LibraryScreen(state, SyncState(), onBack = {}, onSearch = {}, onRefresh = {},
                    onSync = {}, onCreate = {}, onOpen = {}, onFolder = {}, onOpenFolderContent = {})
            }
        }
        compose.onNodeWithTag("folder-content").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(currentFolderHasContent = true) }
        compose.onNodeWithTag("folder-content").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(query = "Заметка", results = listOf(child)) }
        compose.onNodeWithTag("folder-content").assertDoesNotExist()
        compose.onNodeWithTag("note-row-child").assertIsDisplayed()
    }

    @Test fun offlineProgressCountsOnlyTheInitialCopy() {
        var offline by mutableStateOf(OfflineState(isRunning = true, completed = 0, total = 12))
        compose.setContent {
            NotesTheme {
                LibraryScreen(NotesScreenState(loading = false), SyncState(), onBack = {}, onSearch = {},
                    onRefresh = {}, onSync = {}, onCreate = {}, onOpen = {}, onFolder = {},
                    onOpenFolderContent = {}, offline = offline)
            }
        }
        compose.onNodeWithText("Скачивание для офлайна: 0/12").assertIsDisplayed()
        compose.runOnIdle { offline = offline.copy(completed = 12) }
        compose.onNodeWithText("Скачивание вложений…").assertIsDisplayed()
        compose.runOnIdle { offline = offline.copy(available = true, completed = 0) }
        compose.onNodeWithTag("offline-progress").assertDoesNotExist()
        compose.onNodeWithTag("sync-status").assertContentDescriptionEquals("Состояние синхронизации. Обновление заметок")
        compose.runOnIdle { offline = offline.copy(completed = 7) }
        compose.onNodeWithText("Скачивание для офлайна: 7/12").assertDoesNotExist()
        compose.onNodeWithText("Обновление заметок…").assertDoesNotExist()
        compose.runOnIdle { offline = offline.copy(isRunning = false) }
        compose.onNodeWithTag("sync-status").assertContentDescriptionEquals("Состояние синхронизации. Доступно офлайн")
        compose.onNodeWithText("Офлайн-копия готова").assertDoesNotExist()
    }

    @Test fun failedSearchOffersCurrentViewRetryAndReset() {
        var retries = 0
        val queries = mutableListOf<String>()
        compose.setContent {
            NotesTheme {
                LibraryScreen(NotesScreenState(query = "нет результата", error = "Поиск недоступен", loading = false),
                    SyncState(), onBack = {}, onSearch = { queries += it }, onRefresh = { retries++ },
                    onSync = {}, onCreate = {}, onOpen = {}, onFolder = {}, onOpenFolderContent = {})
            }
        }
        compose.onNodeWithText("Поиск недоступен").assertIsDisplayed()
        compose.onNodeWithText("Нет заметок для показа").assertDoesNotExist()
        compose.onNodeWithText("Ничего не найдено").assertDoesNotExist()
        compose.onNodeWithText("Повторить").performClick()
        compose.onNodeWithText("Сбросить поиск").performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(listOf(""), queries)
        }
    }

    @Test fun loadingDisablesCreationAndDuplicateRefresh() {
        var refreshes = 0
        compose.setContent {
            NotesTheme {
                LibraryScreen(NotesScreenState(loading = true), SyncState(), onBack = {}, onSearch = {},
                    onRefresh = { refreshes++ }, onSync = {}, onCreate = {}, onOpen = {}, onFolder = {}, onOpenFolderContent = {})
            }
        }
        compose.onNodeWithTag("create-note").assertIsNotEnabled()
        compose.onNodeWithText("Загружаем…").assertIsDisplayed()
        compose.onNodeWithTag("sync-status").assertContentDescriptionEquals("Состояние синхронизации. Офлайн-копия пока не готова")
        compose.onNodeWithTag("sync-status").performClick()
        compose.onNodeWithText("Обновить").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, refreshes) }
    }

    @Test fun readyStatusIsAnIconAndOpeningDetailsDoesNotRefresh() {
        var refreshes = 0
        compose.setContent {
            NotesTheme {
                LibraryScreen(NotesScreenState(loading = false), SyncState(), onBack = {}, onSearch = {},
                    onRefresh = { refreshes++ }, onSync = {}, onCreate = {}, onOpen = {}, onFolder = {},
                    onOpenFolderContent = {}, offline = OfflineState(available = true))
            }
        }
        compose.onNodeWithTag("sync-status").assertContentDescriptionEquals("Состояние синхронизации. Доступно офлайн")
        compose.onNodeWithTag("offline-status").assertDoesNotExist()
        compose.onNodeWithText("Офлайн-копия готова").assertDoesNotExist()
        compose.onNodeWithText("Заметки доступны офлайн.").assertDoesNotExist()
        compose.onNodeWithText("TRILIUM").assertDoesNotExist()
        compose.onNodeWithTag("library-section-heading").assertDoesNotExist()
        compose.onNodeWithTag("sync-status").performClick()
        compose.onNodeWithText("Заметки доступны офлайн.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, refreshes) }
        compose.onNodeWithText("Обновить").performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
        compose.onNodeWithTag("sync-status-dialog").assertDoesNotExist()
    }

    @Test fun cachedOnlineResultsDoNotClaimThereIsNoInternet() {
        compose.setContent {
            NotesTheme {
                LibraryScreen(NotesScreenState(loading = false, fromCache = true, networkAvailable = true),
                    SyncState(), onBack = {}, onSearch = {}, onRefresh = {}, onSync = {}, onCreate = {},
                    onOpen = {}, onFolder = {}, onOpenFolderContent = {}, offline = OfflineState(available = true))
            }
        }
        compose.onNodeWithTag("sync-status").assertContentDescriptionEquals("Состояние синхронизации. Доступно офлайн")
        compose.onNodeWithTag("sync-status").performClick()
        compose.onNodeWithText("Показана сохранённая копия заметок.").assertIsDisplayed()
        compose.onNodeWithText("Нет подключения к интернету.").assertDoesNotExist()
    }

    @Test fun conflictDetailsOpenTheNoteInsteadOfOfferingRefreshAsResolution() {
        var opens = 0
        var refreshes = 0
        compose.setContent {
            NotesTheme {
                LibraryScreen(NotesScreenState(loading = false), SyncState(pendingCount = 1, conflicts = setOf("conflicted")),
                    onBack = {}, onSearch = {}, onRefresh = { refreshes++ }, onSync = {}, onCreate = {},
                    onOpen = {}, onFolder = {}, onOpenFolderContent = {}, offline = OfflineState(available = true),
                    onOpenConflict = { opens++ })
            }
        }
        compose.onNodeWithTag("sync-status").assertContentDescriptionEquals("Состояние синхронизации. Конфликтов: 1")
        compose.onNodeWithTag("sync-status").performClick()
        compose.onNodeWithText("Обновить").assertDoesNotExist()
        compose.onNodeWithText("Открыть заметку").performClick()
        compose.runOnIdle { assertEquals(1, opens); assertEquals(0, refreshes) }
        compose.onNodeWithTag("sync-status-dialog").assertDoesNotExist()
    }

    @Test fun usableOfflineCopyKeepsTransferFailuresInDetailsAndPendingVisibleInTheIcon() {
        var sync by mutableStateOf(SyncState(pendingCount = 2, error = "Синтетическая ошибка отправки"))
        compose.setContent {
            NotesTheme {
                LibraryScreen(NotesScreenState(loading = false, networkAvailable = false, fromCache = true), sync,
                    onBack = {}, onSearch = {}, onRefresh = {}, onSync = {}, onCreate = {}, onOpen = {},
                    onFolder = {}, onOpenFolderContent = {},
                    offline = OfflineState(available = true, error = "Синтетическая ошибка скачивания"))
            }
        }
        compose.onNodeWithTag("sync-status").assertContentDescriptionEquals("Состояние синхронизации. Не отправлено: 2")
        compose.onNodeWithText("Синтетическая ошибка отправки").assertDoesNotExist()
        compose.onNodeWithTag("sync-status").performClick()
        compose.onNodeWithText("Синтетическая ошибка отправки").assertIsDisplayed()
        compose.onNodeWithText("Синтетическая ошибка скачивания").assertIsDisplayed()
        compose.onNodeWithText("Ожидают отправки: 2").assertIsDisplayed()
        compose.onNodeWithText("Закрыть").performClick()
        compose.runOnIdle { sync = sync.copy(pendingCount = 0) }
        compose.onNodeWithTag("sync-status").assertContentDescriptionEquals("Состояние синхронизации. Нет интернета")
    }

    @Test fun viewErrorsRemainVisibleWithAUsableOfflineCopy() {
        compose.setContent {
            NotesTheme {
                LibraryScreen(NotesScreenState(loading = false, networkAvailable = false, error = "Не удалось обновить список"),
                    SyncState(), onBack = {}, onSearch = {}, onRefresh = {}, onSync = {}, onCreate = {},
                    onOpen = {}, onFolder = {}, onOpenFolderContent = {}, offline = OfflineState(available = true))
            }
        }
        compose.onNodeWithText("Не удалось обновить список").assertIsDisplayed()
        compose.onNodeWithTag("sync-status").assertContentDescriptionEquals("Состояние синхронизации. Ошибка синхронизации")
    }

    @Test fun searchProgressDoesNotImpersonateSyncActivity() {
        compose.setContent {
            NotesTheme {
                LibraryScreen(NotesScreenState(loading = false, query = "пример", searching = true), SyncState(),
                    onBack = {}, onSearch = {}, onRefresh = {}, onSync = {}, onCreate = {}, onOpen = {},
                    onFolder = {}, onOpenFolderContent = {}, offline = OfflineState(available = true))
            }
        }
        compose.onNodeWithTag("sync-status").assertContentDescriptionEquals("Состояние синхронизации. Доступно офлайн")
        compose.onNodeWithText("Ищем заметки…").assertIsDisplayed()
        compose.onNodeWithText("Результаты поиска").assertIsDisplayed()
        compose.onNodeWithTag("sync-status").performClick()
        compose.onNodeWithText("Обновить").assertIsNotEnabled()
    }

    @Test fun breadcrumbsShowOnlyRealAncestors() {
        val folder = note("folder", "Раздел")
        compose.setContent {
            NotesTheme {
                LibraryScreen(NotesScreenState(loading = false,
                    folders = listOf(Folder("root", "Заметки"), Folder("parent", "Справочник"), Folder(folder.id, folder.title))),
                    SyncState(), onBack = {}, onSearch = {}, onRefresh = {}, onSync = {}, onCreate = {},
                    onOpen = {}, onFolder = {}, onOpenFolderContent = {})
            }
        }
        compose.onNodeWithText("Справочник").assertIsDisplayed()
        compose.onNodeWithText("Раздел").assertIsDisplayed()
        compose.onNodeWithText("Заметки").assertDoesNotExist()
        compose.onNodeWithText("Вложенные заметки").assertIsDisplayed()
    }

    @Test fun saveSyntheticLibraryInBothSystemThemes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val baseConfiguration = Configuration(context.resources.configuration)
        var dark by mutableStateOf(false)
        val folder = note("folder", "Личная библиотека", listOf("branch-1", "branch-2", "branch-3"))
        val children = listOf(
            note("reading", "Книги и цитаты", listOf("nested")),
            note("today", "Сегодня"),
            note("long-title", "Идеи, наблюдения и планы на ближайшие недели"),
            note("code", "Небольшой скрипт").copy(type = "code", mime = "application/javascript"),
        )
        val state = NotesScreenState(
            folders = listOf(Folder("root", "Заметки"), Folder(folder.id, folder.title)), currentFolderNote = folder,
            currentFolderHasContent = true,
            items = children.mapIndexed { index, child -> TreeItem("branch-$index", child, "", index, false) }, loading = false,
        )
        compose.setContent {
            val configuration = Configuration(baseConfiguration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                NotesTheme {
                    LibraryScreen(state, SyncState(), onBack = {}, onSearch = {}, onRefresh = {}, onSync = {},
                        onCreate = {}, onOpen = {}, onFolder = {}, onOpenFolderContent = {})
                }
            }
        }
        compose.waitForIdle()
        val light = saveScreenshot("list-screen-light.png")
        compose.runOnIdle { dark = true }
        compose.waitForIdle()
        val night = saveScreenshot("list-screen-dark.png")
        assertFalse(light.readBytes().contentEquals(night.readBytes()))
    }

    private fun saveScreenshot(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(requireNotNull(context.getExternalFilesDir(null)), name)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return file
    }

    private fun note(id: String, title: String, children: List<String> = emptyList()) = NoteMeta(
        id, title, "text", "text/html", false, null, null, children,
    )
}
