package ru.vlad.triliumnotes.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.Folder
import ru.vlad.triliumnotes.NotesScreenState
import ru.vlad.triliumnotes.R
import ru.vlad.triliumnotes.capture.*
import ru.vlad.triliumnotes.data.*
import java.io.File

/** Product state review using production composables and disposable synthetic data only. */
class Release061ProductAcceptanceTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val alpha = note("qa061alpha", "План прогулки и полезные материалы на выходные")
    private val beta = note("qa061beta", "Список книг")
    private fun note(id: String, title: String) = NoteMeta(id, title, "text", "text/html", false, null, null, emptyList())
    private fun label(id: Int) = AppLanguage.get(context, id)
    private fun items() = listOf(alpha, beta).mapIndexed { i, n -> TreeItem("qa061branch$i", n, "", i, false) }
    @Before fun language() {
        AppLanguage.select(context, InstrumentationRegistry.getArguments().getString("reviewLanguage") ?: "ru")
    }
    @After fun resetLanguage() { AppLanguage.select(context, "ru") }
    private fun save(name: String) {
        compose.waitForIdle(); SystemClock.sleep(600)
        val folder = File(requireNotNull(context.getExternalFilesDir(null)), (InstrumentationRegistry.getArguments().getString("reviewOutput") ?: "release061-review")).apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    private fun waitImeSettled(tag: String, above: String? = null) {
        var previous = ""
        var stableSince = SystemClock.elapsedRealtime()
        var diagnostic = "Not sampled"
        try {
            compose.waitUntil(10_000) {
                var imeHeight = 0
                var windows = ""
                compose.runOnIdle {
                    val roots = WindowInspector.getGlobalWindowViews()
                    imeHeight = roots.maxOfOrNull { it.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0 } ?: 0
                    windows = roots.joinToString { "${it.javaClass.simpleName}:focus=${it.hasWindowFocus()}:height=${it.height}:ime=${it.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom}" }
                }
                val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
                val bounds = node.boundsInRoot
                val aboveBounds = above?.let { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
                val whole = bounds.top >= 0f && bounds.height >= node.size.height - 1f && bounds.width >= node.size.width - 1f
                val clear = aboveBounds == null || bounds.bottom <= aboveBounds.top
                diagnostic = "tag=$tag size=${node.size} root=$bounds window=${node.boundsInWindow} above=$aboveBounds ime=$imeHeight whole=$whole clear=$clear windows=$windows"
                val geometry = "$imeHeight:$bounds"
                val now = SystemClock.elapsedRealtime()
                if (imeHeight <= 0 || !whole || !clear || geometry != previous) {
                    previous = geometry; stableSince = now; false
                } else now - stableSince >= 750
            }
        } catch (failure: Throwable) {
            val name = "diagnostic-$tag-${SystemClock.uptimeMillis()}"
            runCatching {
                save(name)
                val folder = File(requireNotNull(context.getExternalFilesDir(null)), InstrumentationRegistry.getArguments().getString("reviewOutput") ?: "release061-review")
                File(folder, "$name.txt").writeText(diagnostic)
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw AssertionError(diagnostic, failure)
        }
    }
    private fun hideIme() { instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); SystemClock.sleep(300) }

    @Test fun captureLibraryActionStates() {
        var generation by mutableStateOf(0)
        var nested by mutableStateOf(false)
        compose.setContent { NotesTheme { key(generation) {
            val state = NotesScreenState(loading = false, items = items(), folders = if (nested)
                listOf(Folder("root", "Заметки"), Folder("qa061parent", "Книги и цитаты"), Folder("qa061folder", "Планы на выходные"))
                else listOf(Folder("root", "Заметки")))
            LibraryScreen(state, SyncState(), {}, {}, {}, {}, {}, {}, {}, {}, onCreateFolder = {},
                onConnectionSettings = {}, onFavorite = {}, onOrganize = { _, _, _ -> }, onTrashMany = {}, onTrash = {}, onReorder = {})
        } } }
        save("01-library-root")
        compose.onNodeWithTag("library-actions").performClick(); save("02-library-menu")
        compose.onNodeWithText(label(R.string.library_select)).performClick(); save("03-selection-zero")
        compose.onNodeWithTag("library-actions").performClick(); save("04-selection-zero-menu")
        hideIme()
        compose.onNodeWithTag("note-row-${alpha.id}").performClick(); save("05-selection-one")
        compose.onNodeWithTag("library-actions").performClick(); save("06-selection-one-menu")
        compose.onNodeWithText(label(R.string.delete)).performClick(); save("07-selection-delete-confirm")
        compose.runOnIdle { generation++; nested = true }
        save("08-nested-folder")
        compose.onNodeWithTag("library-actions").performClick(); save("09-nested-folder-menu")
        hideIme()
        compose.onNodeWithTag("note-actions-${alpha.id}").performClick(); save("10-item-menu")
        hideIme()
        compose.onNodeWithTag("create-note").performClick(); save("11-create-menu")
        compose.onNodeWithTag("new-folder-menu").performClick(); save("12-create-folder-ime")
        compose.onNodeWithTag("new-folder-title").performTextInput("Планы на зиму"); save("13-create-folder-filled-ime")
    }

    @Test fun captureConnectionStates() {
        var screen by mutableStateOf("login")
        var generation by mutableStateOf(0)
        compose.setContent { NotesTheme { key(generation) {
            if (screen.startsWith("settings")) ConnectionSettingsScreen("https://notes.example.org", 3, false,
                if (screen == "settings-error") "Не удалось выйти. Сохранённые изменения доступны на телефоне." else null, {}, {})
            else ConnectionScreen(busy = screen == "busy", error = if (screen == "error") "Не удалось подключиться. Проверьте адрес сервера и пароль." else null,
                storageFailed = screen == "storage", onConnect = { _, _, _, _ -> })
        } } }
        save("20-login-empty")
        compose.onNodeWithTag("connection-server").performTextInput("https://notes.example.org")
        compose.onNodeWithTag("connection-secret").performTextInput("synthetic-password"); save("21-login-valid-ime")
        hideIme(); compose.onNodeWithTag("connection-mode").performClick(); save("22-login-token")
        for (state in listOf("error", "busy", "storage", "settings", "settings-error")) {
            compose.runOnIdle { screen = state; generation++ }; save("23-$state")
        }
        compose.onNodeWithTag("connection-disconnect").performClick(); save("24-disconnect-confirm-pending")
    }

    @Test fun captureModalStates() {
        var screen by mutableStateOf("destination")
        var generation by mutableStateOf(0)
        val revision = NoteRevision("qa061revision", alpha.id, alpha.title, "code", "text/plain", "2026-10-29T09:30:00Z", null, "Перед изменением плана")
        val codeNote = alpha.copy(type = "code", mime = "text/plain")
        compose.setContent { NotesTheme { key(generation) {
            when {
                screen.startsWith("destination") -> DestinationDialog(label(R.string.library_move), if (screen == "destination-empty") emptyList() else listOf(
                    NoteDestination(note("qa061d1", "Планы"), "Заметки / Личное / Планы на следующие выходные"),
                    NoteDestination(note("qa061d2", "Планы"), "Заметки / Работа / Команда / Проекты и материалы")), {}, {})
                screen == "reorder" -> ReorderDialog(items(), {}, {})
                screen.startsWith("trash") -> TrashSheet(if (screen == "trash-list") listOf(TrashItem(alpha.id, alpha.title), TrashItem(beta.id, beta.title, canRestore = false)) else emptyList(),
                    screen == "trash-loading", if (screen == "trash-error") "Не удалось загрузить корзину. Проверьте подключение." else null, false, {}, {}, {})
                screen.startsWith("history") -> NoteHistorySheet(codeNote, NoteHistoryState(
                    revisions = if (screen == "history-list") listOf(revision) else emptyList(), loading = screen == "history-loading",
                    error = if (screen == "history-error") "Не удалось загрузить версии заметки." else null,
                    selected = revision.takeIf { screen == "history-preview" },
                    preview = RevisionDocument(revision, "Прогулка\nКнижный магазин\nКофе").takeIf { screen == "history-preview" }),
                    {}, {}, {}, {}, {}, {}, currentDocument = NoteDocument(codeNote, "Прогулка\nМузей\nКофе"))
                screen.startsWith("links") -> NoteLinkPicker(if (screen == "links-empty") emptyList() else listOf(alpha, beta), {}, {})
                screen.startsWith("backlinks") -> BacklinksDialog(if (screen == "backlinks-empty") emptyList() else listOf(alpha, beta), {}, {})
                screen.startsWith("attachments") -> AttachmentManagerDialog(if (screen == "attachments-empty") emptyList() else listOf(
                    AttachmentMeta("qa061attach", alpha.id, "file", "application/pdf", "Weekend-reading-and-walking-guide.pdf", 0),
                    AttachmentMeta("qa061photo", alpha.id, "image", "image/jpeg", "Прогулка в парке.jpg", 1)), { _, _ -> }, {}, {},
                    busy = screen == "attachments-busy", error = if (screen == "attachments-error") "Не удалось обновить вложения." else null, onRetry = {})
                screen.startsWith("capture") -> CaptureSheet(PendingCapture("qa061capture", "Идея прогулки", "https://example.org/weekend", emptyList()),
                    "https://notes.example.org", listOf(CaptureDestination("qa061folder", "Планы на выходные", true), CaptureDestination(alpha.id, alpha.title, false)) +
                        (1..30).map { CaptureDestination("qa061choice$it", if (it % 4 == 0) "Личное / Поездки / Материалы" else "Работа / Команда / Идеи и материалы для проекта $it", false) },
                    screen == "capture-busy", if (screen == "capture-error") "Не удалось отправить. Материал сохранён на телефоне." else null, {}, { _, _, _ -> }, onDiscard = {})
            }
        } } }
        for (state in listOf("destination", "destination-empty", "reorder", "trash-loading", "trash-error", "trash-empty", "trash-list",
            "history-loading", "history-error", "history-empty", "history-list", "history-preview", "links", "links-empty", "backlinks", "backlinks-empty",
            "attachments", "attachments-empty", "attachments-busy", "attachments-error", "capture", "capture-busy", "capture-error")) {
            compose.runOnIdle { screen = state; generation++ }; save("30-$state")
            if (state == "destination") {
                compose.onNode(hasSetTextAction()).performClick(); save("31-destination-search-ime"); hideIme()
            }
            if (state == "links") {
                compose.onNodeWithTag("note-link-query").performTextInput("несуществующее название"); save("31-links-search-empty-ime"); hideIme()
            }
            if (state == "history-preview") {
                compose.onNodeWithTag("history-restore").performClick(); save("32-history-restore-confirm")
                compose.onNodeWithText(label(R.string.cancel)).performClick()
                compose.onNodeWithTag("history-compare").performClick(); save("33-history-compare")
            }
            if (state == "attachments") {
                compose.onNodeWithTag("attachment-actions-qa061attach").performClick(); save("34-attachment-menu")
                compose.onNodeWithTag("attachment-rename-qa061attach").performClick(); save("35-attachment-rename")
                compose.onNodeWithText(label(R.string.cancel)).performClick()
                compose.onNodeWithTag("attachment-actions-qa061attach").performClick()
                compose.onNodeWithTag("attachment-delete-qa061attach").performClick(); save("36-attachment-delete-confirm")
            }
            if (state == "capture") {
                compose.onNodeWithTag("capture-destination").performClick(); save("37-capture-folder-picker")
                compose.onNodeWithText(label(R.string.cancel)).performClick()
                compose.onNodeWithTag("capture_note_title").performClick(); waitImeSettled("capture_note_title", "capture_save"); save("37-capture-title-ime"); hideIme()
                compose.onNodeWithText(label(R.string.capture_existing_note)).performClick(); save("38-capture-existing")
                compose.onNodeWithTag("capture-destination").performClick(); save("39-capture-existing-long-picker")
                compose.onNodeWithTag("capture-target-query").performTextInput("проекта 2"); waitImeSettled("capture-target-query"); save("40-capture-target-search-ime")
                compose.onNodeWithTag("capture-target-qa061choice2").performClick(); save("41-capture-target-picked")
                compose.onNodeWithTag("capture-destination").performClick()
                compose.onNodeWithTag("capture-target-query").performTextInput("несуществующее название"); waitImeSettled("capture-target-query"); save("42-capture-target-no-match-ime")
            }
        }
    }

    @Test fun captureFeedbackStatesAtLargeFont() {
        var state by mutableStateOf("error")
        var generation by mutableStateOf(0)
        compose.setContent { NotesTheme { key(generation) {
            CaptureSheet(PendingCapture("qa062feedback-$state", "Идея прогулки", "https://example.org/weekend", emptyList(),
                accountId = if (state == "account-mismatch") "synthetic-other-account" else null),
                "https://notes.example.org", listOf(CaptureDestination("qa062folder", "Планы на выходные", true)),
                busy = false, error = if (state == "error") label(R.string.msg_library_failed) else null,
                onDismiss = {}, onSave = { _, _, _ -> }, onDiscard = {}, accountMatches = state != "account-mismatch")
        } } }
        fun assertFeedback() {
            val node = compose.onNodeWithTag("capture-feedback").fetchSemanticsNode()
            val bounds = node.boundsInRoot
            assertTrue("Feedback must not be clipped", bounds.height >= node.size.height - 1f)
            assertTrue("Feedback must remain above Save", bounds.bottom <= compose.onNodeWithTag("capture_save").fetchSemanticsNode().boundsInRoot.top)
        }
        for (mode in listOf("error", "account-mismatch")) {
            compose.runOnIdle { state = mode; generation++ }
            compose.waitForIdle(); assertFeedback(); save("50-$mode-no-ime")
            compose.onNodeWithTag("capture_note_title").performClick()
            waitImeSettled("capture_note_title", "capture-feedback")
            assertFeedback(); save("51-$mode-ime")
            hideIme()
        }
    }

}
