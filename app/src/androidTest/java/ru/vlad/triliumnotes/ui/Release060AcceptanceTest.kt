package ru.vlad.triliumnotes.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.NotesScreenState
import ru.vlad.triliumnotes.R
import ru.vlad.triliumnotes.capture.CaptureDestination
import ru.vlad.triliumnotes.capture.CaptureSheet
import ru.vlad.triliumnotes.capture.PendingCapture
import ru.vlad.triliumnotes.data.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Isolated release fixtures. No service connection, credentials, or owner documents. */
class Release060AcceptanceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val alpha = note("alpha060", "Alpha · План прогулки")
    private val beta = note("beta060", "Beta · Список книг")
    private val gamma = note("gamma060", "Gamma · Идеи")
    private val notes get() = listOf(gamma, beta, alpha)
    private fun note(id: String, title: String) = NoteMeta(id, title, "text", "text/html", false, null, null, emptyList())
    private fun label(id: Int) = AppLanguage.get(context, id)
    private fun libraryState() = NotesScreenState(loading = false,
        items = notes.mapIndexed { index, note -> TreeItem("branch-${note.id}", note, "", index, false) })

    @Before fun russianByExplicitPreference() { AppLanguage.select(context, "ru") }

    @Test fun newMenuKeepsNoteCreationSeparateFromFolderCreation() {
        var created = 0
        val folders = mutableListOf<String>()
        compose.setContent { NotesTheme {
            LibraryScreen(libraryState(), SyncState(), {}, {}, {}, {}, { created++ }, {}, {}, {},
                onCreateFolder = { folders += it })
        } }
        compose.onNodeWithTag("create-note").performClick()
        compose.onNodeWithTag("new-folder-menu").assertIsDisplayed()
        compose.onNodeWithTag("new-note-menu").performClick()
        compose.runOnIdle { assertEquals(1, created); assertTrue(folders.isEmpty()) }
        compose.onNodeWithTag("create-note").performClick()
        compose.onNodeWithTag("new-folder-menu").performClick()
        compose.onNodeWithTag("new-folder-title").performTextInput("Weekend folder")
        compose.onNodeWithTag("confirm-create-folder").performClick()
        compose.runOnIdle { assertEquals(1, created); assertEquals(listOf("Weekend folder"), folders) }
    }

    @Test fun favoriteAndRecentCollectionsFollowNavigationAndKeepTheirIdentity() {
        var state by mutableStateOf(libraryState())
        var preferences by mutableStateOf(LibraryPreferencesState())
        var opened: String? = null
        compose.setContent { NotesTheme {
            val collection = when (state.librarySection) {
                LibrarySection.FAVORITES -> notes.filter { it.id in preferences.favorites }
                LibrarySection.RECENT -> preferences.recent.mapNotNull { id -> notes.find { it.id == id } }
                else -> emptyList()
            }
            LibraryScreen(state, SyncState(), {}, {}, {}, {}, {},
                onOpen = { opened = it.id; preferences = preferences.copy(recent = listOf(it.id)) },
                onFolder = {}, onOpenFolderContent = {}, preferences = preferences, collectionNotes = collection,
                onSection = { state = state.copy(librarySection = it) },
                onFavorite = { preferences = preferences.copy(favorites = preferences.favorites + it) })
        } }
        compose.onNodeWithTag("note-actions-${beta.id}").performClick()
        compose.onNodeWithTag("note-favorite").performClick()
        compose.onNodeWithTag("section-FAVORITES").performClick()
        compose.onNodeWithTag("note-row-${beta.id}").assertIsDisplayed().performClick()
        compose.onNodeWithTag("note-row-${alpha.id}").assertDoesNotExist()
        compose.onNodeWithTag("section-RECENT").performClick()
        compose.onNodeWithTag("note-row-${beta.id}").assertIsDisplayed()
        compose.runOnIdle { assertEquals(beta.id, opened); assertEquals(listOf(beta.id), preferences.recent) }
    }

    @Test fun multiSelectionMoveUsesSelectedNotesAndSourceFolderThenClearsSelection() {
        var result: Triple<LibraryAction, List<NoteMeta>, String?>? = null
        var opens = 0
        var state by mutableStateOf(libraryState())
        compose.setContent { NotesTheme {
            LibraryScreen(state, SyncState(), {}, {}, {}, {}, {}, onOpen = { opens++ },
                onFolder = {}, onOpenFolderContent = {}, onTrashMany = {},
                onOrganize = { action, selected, parent -> result = Triple(action, selected, parent) })
        } }
        compose.onNodeWithTag("library-actions").performClick()
        compose.onNodeWithText(label(R.string.library_select)).performClick()
        compose.onNodeWithTag("note-row-${alpha.id}").performClick()
        compose.onNodeWithTag("note-row-${beta.id}").performClick()
        compose.onNodeWithTag("library-actions").performClick()
        compose.onNodeWithText(label(R.string.library_move)).performClick()
        // Choosing an action opens destination selection; cancellation must retain the user's selection.
        compose.onNodeWithText(AppLanguage.get(context, R.string.library_selected, 2)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, opens)
            assertEquals(LibraryAction.MOVE, result?.first)
            assertEquals(setOf(alpha.id, beta.id), result?.second?.map { it.id }?.toSet())
            assertEquals("root", result?.third)
            // Completion refreshes the source folder with the successfully moved notes removed.
            state = state.copy(items = state.items.filterNot { it.note.id in setOf(alpha.id, beta.id) })
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(AppLanguage.get(context, R.string.library_selected, 2)).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun rowCopyAndCloneKeepTheExactNoteAndSourceIdentity() {
        val requests = mutableListOf<Triple<LibraryAction, List<NoteMeta>, String?>>()
        compose.setContent { NotesTheme {
            LibraryScreen(libraryState(), SyncState(), {}, {}, {}, {}, {}, {}, {}, {}, onFavorite = {},
                onOrganize = { action, selected, parent -> requests += Triple(action, selected, parent) })
        } }
        for ((action, resource) in listOf(LibraryAction.COPY to R.string.library_copy, LibraryAction.CLONE to R.string.library_clone)) {
            compose.onNodeWithTag("note-actions-${alpha.id}").performClick()
            compose.onNodeWithText(label(resource)).performClick()
            compose.runOnIdle { assertEquals(Triple(action, listOf(alpha), "root"), requests.last()) }
        }
    }

    @Test fun titleSortChangesVisibleOrderAndManualReorderPersistsBranchIds() {
        var preferences by mutableStateOf(LibraryPreferencesState())
        var saved: List<String>? = null
        compose.setContent { NotesTheme {
            LibraryScreen(libraryState(), SyncState(), {}, {}, {}, {}, {}, {}, {}, {}, preferences = preferences,
                onSort = { preferences = preferences.copy(sorts = mapOf("root" to it)) }, onReorder = { saved = it })
        } }
        compose.onNodeWithTag("library-actions").performClick()
        compose.onNodeWithText(label(R.string.library_sort)).performClick()
        compose.onNodeWithText(label(R.string.library_sort_title)).performClick()
        val firstY = compose.onNodeWithTag("note-row-${alpha.id}").fetchSemanticsNode().boundsInRoot.top
        val secondY = compose.onNodeWithTag("note-row-${beta.id}").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Title order must be observable in the list", firstY < secondY)
        compose.onNodeWithTag("library-actions").performClick()
        compose.onNodeWithText(label(R.string.library_sort)).performClick()
        compose.onNodeWithText(label(R.string.library_reorder)).performClick()
        compose.onAllNodesWithContentDescription(label(R.string.library_move_down))[0].performClick()
        compose.onNodeWithText(label(R.string.library_save_order)).performClick()
        compose.runOnIdle { assertEquals(listOf("branch-${beta.id}", "branch-${gamma.id}", "branch-${alpha.id}"), saved) }
    }

    @Test fun trashRestorationRemovesOnlySelectedItemAndRespectsMissingParent() {
        var items by mutableStateOf(listOf(TrashItem(alpha.id, alpha.title), TrashItem(beta.id, beta.title, canRestore = false)))
        val restored = mutableListOf<String>()
        compose.setContent { NotesTheme {
            TrashSheet(items, false, null, false, onRestore = { id ->
                restored += id; items = items.filterNot { it.noteId == id }
            }, onRetry = {}, onDismiss = {})
        } }
        compose.onAllNodesWithText(label(R.string.library_restore))[1].assertIsNotEnabled()
        compose.onAllNodesWithText(label(R.string.library_restore))[0].performClick()
        compose.onNodeWithText(alpha.title).assertDoesNotExist()
        compose.onNodeWithText(beta.title).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(alpha.id), restored) }
    }

    @Test fun historyComparisonShowsBothVersionsWithoutRestoringOrEditing() {
        val revision = NoteRevision("revision060", alpha.id, alpha.title, "code", "text/plain", "2026-10-28T10:00:00Z", null)
        val preview = RevisionDocument(revision, "Keep this line\nOld paragraph")
        val document = NoteDocument(alpha.copy(type = "code", mime = "text/plain"), "Keep this line\nNew paragraph")
        var restores = 0
        compose.setContent { NotesTheme {
            NoteHistorySheet(document.note, NoteHistoryState(loading = false, selected = revision, preview = preview),
                {}, { restores++ }, {}, {}, {}, {}, currentDocument = document)
        } }
        compose.onNodeWithTag("history-compare").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("note-version-diff").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Old paragraph").assertIsDisplayed()
        compose.onNodeWithText("New paragraph").assertIsDisplayed()
        compose.onNodeWithTag("history-restore-confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, restores) }
    }

    @Test fun attachmentRenamePreservesIdentityAndDeletionRequiresConfirmation() {
        val attachment = AttachmentMeta("attach060", alpha.id, "file", "application/pdf", "Reading.pdf", 0)
        var attachments by mutableStateOf(listOf(attachment))
        val removed = mutableListOf<AttachmentMeta>()
        compose.setContent { NotesTheme {
            AttachmentManagerDialog(attachments, onRename = { item, title ->
                attachments = attachments.map { if (it.id == item.id) it.copy(title = title) else it }
            }, onDelete = { removed += it; attachments = emptyList() }, onDismiss = {})
        } }
        compose.onNodeWithTag("attachment-rename-${attachment.id}").performClick()
        compose.onNodeWithTag("attachment-title").performTextReplacement("Weekend.pdf")
        compose.onNodeWithText(label(R.string.editor_apply)).performClick()
        compose.onNodeWithText("Weekend.pdf").assertIsDisplayed()
        compose.runOnIdle { assertEquals(attachment.copy(title = "Weekend.pdf"), attachments.single()) }
        compose.onNodeWithTag("attachment-delete-${attachment.id}").performClick()
        compose.runOnIdle { assertTrue(removed.isEmpty()) }
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        compose.runOnIdle { assertTrue(removed.isEmpty()) }
        compose.onNodeWithTag("attachment-delete-${attachment.id}").performClick()
        compose.onAllNodesWithText(label(R.string.editor_delete_attachment)).filter(hasClickAction()).onFirst().performClick()
        compose.runOnIdle { assertEquals(listOf(attachment.copy(title = "Weekend.pdf")), removed) }
    }

    @Test fun editorInsertsInternalLinkAtCurrentCursorAndPreservesSurroundingText() {
        val html = AtomicReference<String>()
        val document = NoteDocument(alpha, "<p>Before After</p>")
        compose.setContent { NotesTheme {
            EditorScreen(NotesScreenState(document = document, loading = false), document, SyncState(),
                {}, {}, { html.set(it) }, {}, {}, linkCandidates = listOf(beta))
        } }
        compose.waitUntil(30_000) { js("Boolean(window.pocket?.ready)") == "true" }
        compose.onNodeWithContentDescription(label(R.string.edit_note)).performClick()
        compose.waitUntil(30_000) { js("Boolean(window.pocket?.editor && !window.pocket.editor.isReadOnly)") == "true" }
        js("(()=>{const e=window.pocket.editor;e.model.change(w=>w.setSelection(e.model.document.getRoot().getChild(0),7));e.editing.view.focus();return true})()")
        compose.onNodeWithTag("note-actions-${alpha.id}").performClick()
        compose.onNodeWithTag("note-insert-link").performClick()
        compose.onNodeWithTag("note-link-${beta.id}").performClick()
        compose.waitUntil(10_000) { html.get()?.contains("#root/${beta.id}") == true }
        val actual = html.get()
        assertNotNull(actual)
        assertTrue(actual!!.indexOf("Before") < actual.indexOf("#root/${beta.id}"))
        assertTrue(actual.indexOf("#root/${beta.id}") < actual.indexOf("After"))
    }

    @Test fun captureReleaseStatesInRussianEnglishLightAndDark() {
        var language by mutableStateOf("ru")
        var dark by mutableStateOf(false)
        var screen by mutableStateOf("library")
        var section by mutableStateOf(LibrarySection.NOTES)
        val revision = NoteRevision("revision060", alpha.id, "План / Plan", "code", "text/plain", "2026-10-28T10:00:00Z", null)
        val preview = RevisionDocument(revision, "Park\nBookshop\nCoffee")
        val current = NoteDocument(alpha.copy(title = "Выходные / Weekend", type = "code", mime = "text/plain"), "Park\nMuseum\nCoffee")
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            key(language, dark, screen) {
                CompositionLocalProvider(LocalContext provides AppLanguage.context(context), LocalConfiguration provides config) {
                    NotesTheme { when (screen) {
                        "library" -> LibraryScreen(libraryState().copy(librarySection = section), SyncState(), {}, {}, {}, {}, {}, {}, {}, {},
                            preferences = LibraryPreferencesState(setOf(alpha.id, beta.id), listOf(beta.id, alpha.id)),
                            collectionNotes = listOf(alpha, beta), onSection = { section = it }, onFavorite = {},
                            onOrganize = { _, _, _ -> }, onTrashMany = {}, onTrash = {}, onCreateFolder = {})
                        "trash" -> TrashSheet(listOf(TrashItem(alpha.id, alpha.title), TrashItem(beta.id, beta.title, canRestore = false)), false, null, false, {}, {}, {})
                        "history" -> NoteHistorySheet(current.note, NoteHistoryState(loading = false, selected = revision, preview = preview),
                            {}, {}, {}, {}, {}, {}, currentDocument = current)
                        "attachments" -> AttachmentManagerDialog(listOf(
                            AttachmentMeta("attach060", alpha.id, "file", "application/pdf", "Weekend-reading.pdf", 0),
                            AttachmentMeta("image060", alpha.id, "image", "image/jpeg", "Park-walk.jpg", 1)), { _, _ -> }, {}, {})
                        "capture" -> CaptureSheet(PendingCapture("share060", "Идея / Shared idea", "https://example.org/weekend", emptyList()),
                            "https://synthetic.invalid", listOf(CaptureDestination("folder060", "Выходные / Weekend", true)), false, null, {}, { _, _, _ -> })
                        "destination" -> DestinationDialog(label(R.string.library_move), listOf(
                            NoteDestination(note("folder060", "Выходные / Weekend"), "Заметки / Notes"),
                            NoteDestination(note("books060", "Книги / Reading"), "Заметки / Notes")), {}, {})
                    } }
                }
            }
        }
        try {
            for (locale in listOf("ru", "en")) for (night in listOf(false, true)) {
                val suffix = "$locale-${if (night) "dark" else "light"}"
                compose.runOnIdle { AppLanguage.select(context, locale); language = locale; dark = night; screen = "library"; section = LibrarySection.NOTES }
                compose.onNodeWithTag("section-FAVORITES").performClick()
                save("favorites-$suffix.png")
                compose.onNodeWithTag("section-RECENT").performClick()
                save("recent-$suffix.png")
                compose.onNodeWithTag("section-NOTES").performClick()
                compose.onNodeWithTag("library-actions").performClick()
                compose.onNodeWithText(label(R.string.library_select)).performClick()
                compose.onNodeWithTag("note-row-${alpha.id}").performClick()
                compose.onNodeWithTag("note-row-${beta.id}").performClick()
                compose.onNodeWithTag("library-actions").performClick()
                save("selection-$suffix.png")
                compose.runOnIdle { screen = "trash" }
                compose.onNodeWithTag("trash-list").assertIsDisplayed()
                save("trash-$suffix.png")
                compose.runOnIdle { screen = "history" }
                compose.onNodeWithTag("history-compare").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("note-version-diff").fetchSemanticsNodes().isNotEmpty() }
                save("diff-$suffix.png")
                for (fixture in listOf("attachments", "capture", "destination")) {
                    compose.runOnIdle { screen = fixture }
                    compose.waitForIdle()
                    save("$fixture-$suffix.png")
                }
            }
        } finally { AppLanguage.select(context, "ru") }
    }

    private fun save(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(350)
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "release060-review").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(output, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun js(expression: String): String {
        var result = ""
        val latch = CountDownLatch(1)
        compose.runOnIdle {
            fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            requireNotNull(WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find))
                .evaluateJavascript(expression) { result = it; latch.countDown() }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return result
    }
}
