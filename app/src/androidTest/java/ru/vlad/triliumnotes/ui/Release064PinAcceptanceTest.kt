package ru.vlad.triliumnotes.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.Folder
import ru.vlad.triliumnotes.NotesScreenState
import ru.vlad.triliumnotes.R
import ru.vlad.triliumnotes.data.*
import java.io.File

/** Actual production screens with disposable notes; never opens an account or touches its cache. */
class Release064PinAcceptanceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var previousLanguage = "ru"
    private val alpha = note("qa064alpha", "Alpha · План на неделю")
    private val beta = note("qa064beta", "Beta · Полезные материалы")
    private val gamma = note("qa064gamma", "Gamma · Список книг")
    private val notes get() = listOf(alpha, beta, gamma)
    private fun note(id: String, title: String) = NoteMeta(id, title, "text", "text/html", false, null, null, emptyList())
    private fun items(notes: List<NoteMeta> = this.notes) = notes.mapIndexed { index, note ->
        TreeItem("branch-${note.id}", note, "", index, false)
    }
    private fun state(notes: List<NoteMeta> = this.notes) = NotesScreenState(loading = false, items = items(notes))
    private fun label(id: Int) = AppLanguage.get(context, id)
    private fun LibraryPreferencesState.togglePin(id: String) = copy(pinned = if (id in pinned) pinned - id else pinned + id)
    private fun LibraryPreferencesState.toggleFavorite(id: String) = copy(favorites = if (id in favorites) favorites - id else favorites + id)

    @Before fun selectRussian() {
        previousLanguage = AppLanguage.language(context)
        AppLanguage.select(context, "ru")
    }
    @After fun restoreLanguage() { AppLanguage.select(context, previousLanguage) }

    @Composable private fun LibraryFixture(
        state: NotesScreenState,
        preferences: LibraryPreferencesState,
        onPin: (String) -> Unit,
        onFavorite: (String) -> Unit = {},
        collectionNotes: List<NoteMeta> = emptyList(),
        onSection: (LibrarySection) -> Unit = {},
        onSort: (NoteSort) -> Unit = {},
        onReorder: ((List<String>) -> Unit)? = null,
        onFolder: (NoteMeta) -> Unit = {},
        onOpen: (NoteMeta) -> Unit = {},
    ) {
        LibraryScreen(state, SyncState(), onBack = {}, onSearch = {}, onRefresh = {}, onSync = {}, onCreate = {},
            onOpen = onOpen, onFolder = onFolder, onOpenFolderContent = {}, preferences = preferences,
            collectionNotes = collectionNotes, onSection = onSection, onFavorite = onFavorite, onPin = onPin,
            onSort = onSort, onReorder = onReorder)
    }

    private fun assertOrder(vararg notes: NoteMeta) {
        val tops = notes.map { compose.onNodeWithTag("note-row-${it.id}").fetchSemanticsNode().boundsInRoot.top }
        assertTrue("Expected visible order ${notes.map { it.id }}, tops=$tops", tops.zipWithNext().all { (a, b) -> a < b })
    }
    private fun badge(kind: String, note: NoteMeta) = compose.onNodeWithTag("note-$kind-${note.id}", useUnmergedTree = true)
    private fun openMenu(note: NoteMeta) { compose.onNodeWithTag("note-actions-${note.id}").performClick() }
    private fun openReorder() {
        compose.onNodeWithTag("library-actions").performClick()
        compose.onNodeWithText(label(R.string.library_sort)).performClick()
        compose.onNodeWithText(label(R.string.library_reorder)).performClick()
    }

    @Test fun pinAndUnpinRestoreManualPositionWhileFavoritesRemainIndependent() {
        var preferences by mutableStateOf(LibraryPreferencesState(favorites = setOf(beta.id)))
        val original = state()
        compose.setContent { NotesTheme {
            LibraryFixture(original, preferences, onPin = { preferences = preferences.togglePin(it) },
                onFavorite = { preferences = preferences.toggleFavorite(it) })
        } }
        assertOrder(alpha, beta, gamma)
        openMenu(beta)
        compose.onNodeWithText(label(R.string.library_pin)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.library_favorite_remove)).assertIsDisplayed()
        compose.onNodeWithTag("note-pin").performClick()
        assertOrder(beta, alpha, gamma)
        badge("pinned", beta).assertExists()
        badge("favorited", beta).assertExists()
        openMenu(beta)
        compose.onNodeWithTag("note-favorite").performClick()
        badge("pinned", beta).assertExists()
        badge("favorited", beta).assertDoesNotExist()
        assertOrder(beta, alpha, gamma)
        openMenu(beta)
        compose.onNodeWithText(label(R.string.library_unpin)).assertIsDisplayed()
        compose.onNodeWithTag("note-pin").performClick()
        assertOrder(alpha, beta, gamma)
        badge("pinned", beta).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(preferences.favorites.isEmpty())
            assertTrue(preferences.pinned.isEmpty())
            assertEquals(items(), original.items)
        }
    }

    @Test fun selectedSortStaysWithinPinGroupsAndPinsNeverAddOtherFolderNotes() {
        val dated = listOf(gamma.copy(modifiedAt = "2026-10-29T13:00:00Z"),
            beta.copy(modifiedAt = "2026-10-29T12:00:00Z"), alpha.copy(modifiedAt = "2026-10-29T11:00:00Z"))
        var preferences by mutableStateOf(LibraryPreferencesState(pinned = setOf(beta.id, "qa064anotherFolder")))
        compose.setContent { NotesTheme {
            LibraryFixture(state(dated), preferences, onPin = {},
                onSort = { preferences = preferences.copy(sorts = mapOf("root" to it)) })
        } }
        assertOrder(beta, gamma, alpha)
        compose.onNodeWithTag("note-row-qa064anotherFolder").assertDoesNotExist()
        for ((sort, resource) in listOf(NoteSort.TITLE to R.string.library_sort_title, NoteSort.MODIFIED to R.string.library_sort_modified)) {
            compose.onNodeWithTag("library-actions").performClick()
            compose.onNodeWithText(label(R.string.library_sort)).performClick()
            compose.onNodeWithText(label(resource)).performClick()
            if (sort == NoteSort.TITLE) assertOrder(beta, alpha, gamma) else assertOrder(beta, gamma, alpha)
            compose.runOnIdle { assertEquals(sort, preferences.sorts["root"]) }
        }
    }

    @Test fun searchFavoritesAndRecentKeepTheirOwnOrderAndDoNotOfferFolderPins() {
        val incoming = listOf(gamma, alpha, beta)
        var screen by mutableStateOf(state().copy(query = "план", results = incoming))
        var preferences by mutableStateOf(LibraryPreferencesState(pinned = setOf(beta.id), favorites = notes.map { it.id }.toSet()))
        var pins = 0
        compose.setContent { NotesTheme {
            LibraryFixture(screen, preferences, onPin = { pins++ }, onFavorite = { preferences = preferences.toggleFavorite(it) },
                collectionNotes = incoming)
        } }
        val scopes = listOf(
            screen,
            screen.copy(query = "", librarySection = LibrarySection.FAVORITES),
            screen.copy(query = "", librarySection = LibrarySection.RECENT),
        )
        for (scope in scopes) {
            compose.runOnIdle {
                screen = scope
                preferences = preferences.copy(favorites = notes.map { it.id }.toSet())
            }
            assertOrder(gamma, alpha, beta)
            badge("pinned", beta).assertDoesNotExist()
            badge("favorited", beta).assertExists()
            openMenu(beta)
            compose.onNodeWithTag("note-pin").assertDoesNotExist()
            compose.onNodeWithTag("note-favorite").assertIsDisplayed().performClick()
        }
        compose.runOnIdle { assertEquals(0, pins); assertEquals(setOf(beta.id), preferences.pinned) }
    }

    @Test fun manualSavePreservesUnpinnedPositionsAndArrowsCannotCrossPinGroups() {
        val delta = note("qa064delta", "Delta · Идеи")
        val original = items(notes + delta)
        var screen by mutableStateOf(state().copy(items = original))
        var preferences by mutableStateOf(LibraryPreferencesState(pinned = setOf(beta.id, delta.id)))
        val saved = mutableListOf<List<String>>()
        compose.setContent { NotesTheme {
            LibraryFixture(screen, preferences, onPin = { preferences = preferences.togglePin(it) }, onReorder = { ids ->
                saved += ids
                screen = screen.copy(items = ids.map { id -> original.single { it.branchId == id } })
            })
        } }
        assertOrder(beta, delta, alpha, gamma)
        openReorder()
        compose.onNodeWithTag("reorder-down-branch-${delta.id}").assertIsNotEnabled()
        compose.onNodeWithTag("reorder-up-branch-${alpha.id}").assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.library_save_order)).performClick()
        compose.runOnIdle { assertEquals(original.map { it.branchId }, saved.single()) }
        openMenu(beta); compose.onNodeWithTag("note-pin").performClick()
        openMenu(delta); compose.onNodeWithTag("note-pin").performClick()
        assertOrder(alpha, beta, gamma, delta)
        compose.runOnIdle { preferences = preferences.copy(pinned = setOf(beta.id, delta.id)) }
        openReorder()
        compose.onNodeWithTag("reorder-down-branch-${beta.id}").performClick()
        compose.onNodeWithTag("reorder-down-branch-${alpha.id}").performClick()
        compose.onNodeWithText(label(R.string.library_save_order)).performClick()
        assertOrder(delta, beta, gamma, alpha)
        compose.runOnIdle {
            assertEquals(listOf(gamma, delta, alpha, beta).map { "branch-${it.id}" }, saved.last())
            preferences = preferences.copy(pinned = emptySet())
        }
        assertOrder(gamma, delta, alpha, beta)
    }

    @Test fun aPinnedFolderStillOpensItsOwnChildrenAndThePinFollowsItsOtherBranch() {
        val folder = beta.copy(type = "book", childBranchIds = listOf("qa064child"))
        var screen by mutableStateOf(state(listOf(alpha, folder, gamma)))
        var openedFolder: String? = null
        var openedNote: String? = null
        val preferences = LibraryPreferencesState(pinned = setOf(folder.id))
        compose.setContent { NotesTheme {
            LibraryFixture(screen, preferences, onPin = {}, onFolder = { openedFolder = it.id }, onOpen = { openedNote = it.id })
        } }
        assertOrder(folder, alpha, gamma)
        compose.onNodeWithTag("note-row-${folder.id}").performClick()
        compose.runOnIdle {
            assertEquals(folder.id, openedFolder)
            assertEquals(null, openedNote)
            screen = state(listOf(gamma, folder)).copy(folders = listOf(Folder("root", "Заметки"), Folder("qa064other", "Другая папка")),
                items = listOf(TreeItem("qa064other-gamma", gamma, "", 0, false), TreeItem("qa064clone-branch", folder, "Клон", 1, false)))
        }
        assertOrder(folder, gamma)
        badge("pinned", folder).assertExists()
        compose.onNodeWithTag("note-row-${alpha.id}").assertDoesNotExist()
    }

    @Test fun editorOffersPinsForLeafNotesButNotRootOrFolderOwnContent() {
        var current by mutableStateOf(beta.copy(type = "code", mime = "text/plain"))
        var ownContent by mutableStateOf(false)
        var pinned by mutableStateOf(false)
        var favorites = 0
        compose.setContent { NotesTheme { key(current.id) {
            val document = NoteDocument(current, "A small disposable note")
            val folders = listOf(Folder("root", "Заметки")) +
                if (ownContent) listOf(Folder(current.id, current.title)) else emptyList()
            EditorScreen(NotesScreenState(loading = false, document = document, folders = folders), document, SyncState(),
                onBack = {}, onTitleChanged = {}, onHtmlChanged = {}, onSync = {}, onKeepConflictAsCopy = {},
                pinned = pinned, onPin = { pinned = !pinned }, onFavorite = { favorites++ })
        } } }
        openMenu(current)
        compose.onNodeWithText(label(R.string.library_pin)).assertIsDisplayed()
        compose.onNodeWithTag("note-pin").performClick()
        openMenu(current)
        compose.onNodeWithText(label(R.string.library_unpin)).assertIsDisplayed()
        compose.onNodeWithTag("note-favorite").performClick()
        compose.runOnIdle { assertTrue(pinned); assertEquals(1, favorites) }
        for ((special, isOwnContent) in listOf(note("root", "Заметки") to false, beta.copy(type = "book") to false,
            gamma.copy(childBranchIds = listOf("qa064child")) to false, alpha to true)) {
            compose.runOnIdle {
                current = special.copy(type = if (special.type == "book") "book" else "code", mime = "text/plain")
                ownContent = isOwnContent
            }
            openMenu(current)
            compose.onNodeWithTag("note-pin").assertDoesNotExist()
            compose.onNodeWithTag("note-favorite").performClick()
        }
    }

    @Test fun captureRussianLight() = captureMatrix("ru", false)
    @Test fun captureRussianDark() = captureMatrix("ru", true)
    @Test fun captureEnglishLight() = captureMatrix("en", false)
    @Test fun captureEnglishDark() = captureMatrix("en", true)

    private fun captureMatrix(language: String, dark: Boolean) {
        AppLanguage.select(context, language)
        val a = alpha.copy(title = if (language == "ru") "План на неделю и материалы для следующей поездки" else "Weekly plan and useful material for the next trip")
        val b = beta.copy(title = if (language == "ru") "Рабочие заметки" else "Work notes")
        val c = gamma.copy(title = if (language == "ru") "Книги и цитаты" else "Books and quotes", childBranchIds = listOf("qa064child"))
        val fixture = listOf(c, b, a)
        var screen by mutableStateOf(state(fixture))
        var preferences by mutableStateOf(LibraryPreferencesState(pinned = setOf(a.id), favorites = setOf(a.id, c.id)))
        compose.runOnIdle {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ComponentActivity>().single()
            activity.enableEdgeToEdge()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                fontScale = 1.3f
            }
            val scaledDensity = Density(LocalDensity.current.density, fontScale = 1.3f)
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides scaledDensity) { NotesTheme {
                val collection = if (screen.librarySection == LibrarySection.FAVORITES)
                    fixture.filter { it.id in preferences.favorites }.sortedBy { it.title.lowercase() } else listOf(c, a)
                LibraryFixture(screen, preferences, onPin = { preferences = preferences.togglePin(it) },
                    onFavorite = { preferences = preferences.toggleFavorite(it) }, collectionNotes = collection,
                    onSection = { screen = screen.copy(librarySection = it) }, onReorder = {})
            } }
        }
        val prefix = "$language-${if (dark) "dark" else "light"}-font130"
        assertOrder(a, c, b)
        badge("pinned", a).assertExists(); badge("favorited", a).assertExists()
        val titleBounds = compose.onNodeWithText(a.title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val pinBounds = badge("pinned", a).fetchSemanticsNode().boundsInRoot
        val favoriteBounds = badge("favorited", a).fetchSemanticsNode().boundsInRoot
        val rowBounds = compose.onNodeWithTag("note-row-${a.id}").fetchSemanticsNode().boundsInRoot
        assertTrue("Both markers must leave room for the title at 130% font", titleBounds.width > 0 && titleBounds.right <= pinBounds.left)
        assertTrue("Pin and favorite markers must not overlap or leave their row", pinBounds.right <= favoriteBounds.left && favoriteBounds.right <= rowBounds.right)
        save("$prefix-01-pinned-and-favorite")
        openMenu(b)
        compose.onNodeWithText(label(R.string.library_pin)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.library_favorite_add)).assertIsDisplayed()
        save("$prefix-02-pin-menu")
        compose.onNodeWithTag("note-pin").performClick()
        assertOrder(b, a, c)
        save("$prefix-03-two-pins")
        openMenu(a)
        compose.onNodeWithText(label(R.string.library_unpin)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.library_favorite_remove)).assertIsDisplayed()
        save("$prefix-04-unpin-favorite-menu")
        compose.onNodeWithTag("note-pin").performClick()
        assertOrder(b, c, a)
        badge("pinned", a).assertDoesNotExist(); badge("favorited", a).assertExists()
        save("$prefix-05-unpinned-still-favorite")
        compose.onNodeWithTag("section-FAVORITES").performClick()
        badge("pinned", a).assertDoesNotExist()
        compose.onNodeWithTag("note-row-${b.id}").assertDoesNotExist()
        save("$prefix-06-favorites")
        openMenu(a)
        compose.onNodeWithTag("note-pin").assertDoesNotExist()
        save("$prefix-07-favorites-menu")
        compose.onNodeWithTag("note-favorite").performClick()
        compose.onNodeWithTag("note-row-${a.id}").assertDoesNotExist()
        compose.runOnIdle { screen = screen.copy(query = if (language == "ru") "заметки" else "notes", results = listOf(c, b, a)) }
        assertOrder(c, b, a)
        badge("pinned", b).assertDoesNotExist()
        save("$prefix-08-search-order")
    }

    private fun save(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(300)
        val directory = File(requireNotNull(context.getExternalFilesDir(null)),
            InstrumentationRegistry.getArguments().getString("reviewOutput") ?: "release064-pin-review").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
