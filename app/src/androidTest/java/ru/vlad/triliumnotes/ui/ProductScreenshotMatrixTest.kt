package ru.vlad.triliumnotes.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.Window
import android.view.PixelCopy
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.ImageView
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.awaitCancellation
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.BuildConfig
import ru.vlad.triliumnotes.Folder
import ru.vlad.triliumnotes.NotesScreenState
import ru.vlad.triliumnotes.data.CachedAttachment
import ru.vlad.triliumnotes.data.NoteDocument
import ru.vlad.triliumnotes.data.NoteMeta
import ru.vlad.triliumnotes.data.OfflineState
import ru.vlad.triliumnotes.data.SyncState
import ru.vlad.triliumnotes.data.TreeItem
import ru.vlad.triliumnotes.editor.EditorResource
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Presentation audit: real production screens + real WebView/editor, only synthetic state/files.
 * Run core/attachments in system light mode, darkKeys in system dark mode (for the actual IME).
 * No NotesServices, HTTP client, production URL, or owner corpus is used by this harness.
 */
class ProductScreenshotMatrixTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val output get() = File(requireNotNull(context.getExternalFilesDir(null)), "product-review").apply { mkdirs() }
    private val records = JSONArray()
    private enum class Action { NONE, BODY_EDIT, OVERFLOW, NEW_TITLE, CODE_EDIT, MEDIA_PLAY, MEDIA_PAUSE, STATUS_DETAIL, PENDING_DETAIL, SPECIAL_SOURCE }
    private data class Shot(
        val id: String, val title: String, val group: Int,
        val state: NotesScreenState = NotesScreenState(loading = false, networkAvailable = true),
        val sync: SyncState = SyncState(), val offline: OfflineState = OfflineState(),
        val document: NoteDocument? = null, val action: Action = Action.NONE,
        val attachment: String? = null, val waitText: String? = null,
    )

    @Test fun captureLightCore() = capture(core(), false, "light-core")
    @Test fun captureDarkKeys() {
        val keys = setOf("01-library-ready", "07-library-offline-pending", "09-library-unavailable",
            "12-folder-own-content", "18-html-reader", "19-new-title-ime", "20-body-ime", "26-conflict")
        capture(core().filter { it.id in keys }, true, "dark-keys")
    }
    @Test fun captureAttachmentVariants() = capture(attachments(), false, "light-attachments")
    @Test fun captureStatusDetails() = capture(statusDetails(), false, "light-status-details")

    private fun note(id: String, title: String, type: String = "text", mime: String = "text/html",
        children: List<String> = emptyList(), protected: Boolean = false) =
        NoteMeta(id, title, type, mime, protected, "synthetic-v1", null, children)
    private fun items(vararg notes: NoteMeta) = notes.mapIndexed { index, n -> TreeItem("qa_branch_$index", n, "", index, false) }
    private fun library() = NotesScreenState(loading = false, networkAvailable = true, items = items(
        note("qa_folder", "Книги и цитаты", children = listOf("qa_a", "qa_b")),
        note("qa_today", "Идеи на выходные"),
        note("qa_long", "Наблюдения, полезные ссылки и планы на ближайшие недели"),
        note("qa_code", "Небольшой скрипт", "code", "application/javascript")))
    private val html = "<p>Короткий план на неделю. <span style='color:#7251a5;background-color:#ebe1f6'>Важная мысль</span>.</p>" +
        "<p><a href='#root/qa_reference'>Материалы и связанные заметки</a></p>" +
        "<ul class='todo-list'><li><label class='todo-list__label'><input type='checkbox' checked='checked'>" +
        "<span class='todo-list__label__description'>Выбрать книгу</span></label></li></ul>" +
        "<figure class='table'><table><tbody><tr><th>День</th><th>План</th></tr><tr><td>Суббота</td><td>Прогулка</td></tr></tbody></table></figure>" +
        "<h2>На этой неделе</h2><ul><li>Найти интересный маршрут</li><li>Собрать фотографии</li></ul>"
    private fun doc(id: String = "qa_reader", title: String = "Идеи на выходные", body: String = html,
        type: String = "text", mime: String = "text/html", pending: Boolean = false, conflict: Boolean = false,
        protected: Boolean = false) = NoteDocument(note(id, title, type, mime, protected = protected), body, pending, conflict)
    private fun editor(id: String, title: String, group: Int, d: NoteDocument = doc(),
        state: NotesScreenState = NotesScreenState(loading = false, networkAvailable = true),
        sync: SyncState = SyncState(), action: Action = Action.NONE) =
        Shot(id, title, group, state.copy(document = d), sync, document = d, action = action)

    private fun core(): List<Shot> {
        val ready = library()
        val folder = note("qa_folder", "Книги и цитаты", children = listOf("qa_child_a", "qa_child_b"))
        val inFolder = ready.copy(folders = listOf(Folder("root", "Заметки"), Folder(folder.id, folder.title)),
            items = items(note("qa_child_a", "Что почитать летом"), note("qa_child_b", "Цитаты и заметки")),
            currentFolderNote = folder, currentFolderHasContent = true)
        val pending = doc(pending = true)
        val search = ready.copy(query = "планы", results = listOf(note("qa_result_a", "Планы на выходные"),
            note("qa_result_b", "Рабочие планы", children = listOf("qa_nested"))), fromCache = true, networkAvailable = true)
        val new = doc("qa_new", "", "<p></p>", pending = true)
        val shots = listOf(
            Shot("01-library-ready", "Библиотека: копия готова", 1, ready, offline = OfflineState(complete = true, available = true)),
            Shot("02-library-empty", "Пустая библиотека", 2, ready.copy(items = emptyList())),
            Shot("03-library-loading", "Первичная загрузка списка", 3, ready.copy(items = emptyList(), loading = true)),
            Shot("04-offline-download", "Первичное скачивание заметок 7/24", 4, ready, offline = OfflineState(isRunning = true, completed = 7, total = 24)),
            Shot("05-attachment-download", "Скачивание вложений", 5, ready, offline = OfflineState(isRunning = true, completed = 24, total = 24, resources = 8)),
            Shot("06-background-refresh", "Обновление существующей копии", 6, ready, offline = OfflineState(isRunning = true, completed = 7, total = 24, available = true)),
            Shot("07-library-offline-pending", "Офлайн: три неотправленные правки", 7, ready.copy(fromCache = true, networkAvailable = false),
                SyncState(pendingCount = 3), OfflineState(available = true)),
            Shot("08-library-syncing", "Отправка локальных правок", 8, ready, SyncState(pendingCount = 3, isSyncing = true), OfflineState(available = true)),
            Shot("09-library-unavailable", "Нет связи и нет скачанных заметок", 9,
                ready.copy(items = emptyList(), networkAvailable = false, error = "Не удалось загрузить заметки. Проверьте подключение и повторите.")),
            Shot("10-library-partial", "Частичная ошибка списка при доступной сети", 10,
                ready.copy(fromCache = true, networkAvailable = true, error = "Не удалось обновить часть списка. Уже загруженные заметки доступны."), offline = OfflineState(available = true)),
            Shot("11-library-sync-error", "Ошибка отправки правок", 11, ready,
                SyncState(pendingCount = 2, error = "Не удалось отправить изменения. Ваш текст сохранён на телефоне."), OfflineState(available = true)),
            Shot("11b-library-global-conflict", "Глобальный конфликт в библиотеке", 11, ready,
                SyncState(pendingCount = 2, conflicts = setOf("qa_today")), OfflineState(available = true)),
            Shot("12-folder-own-content", "Собственный текст после вложенных заметок", 12, inFolder),
            Shot("13-folder-empty", "Пустая папка без собственного текста", 13, inFolder.copy(items = emptyList(), currentFolderHasContent = false)),
            Shot("14-search-results", "Результаты поиска из кэша, сеть доступна", 14, search),
            Shot("15-search-empty", "Поиск без результатов", 15, search.copy(results = emptyList())),
            Shot("16-search-loading", "Выполнение поиска", 16, search.copy(results = emptyList(), searching = true)),
            Shot("17-search-error", "Ошибка поиска и сброс запроса", 17, search.copy(results = emptyList(), error = "Не удалось выполнить поиск. Проверьте подключение и повторите.")),
            editor("18-html-reader", "Чтение HTML: список, таблица, ссылка", 18),
            editor("19-new-title-ime", "Новая заметка: название и настоящая IME", 19, new,
                NotesScreenState(loading = false, networkAvailable = true, newNoteId = new.note.id, newNoteTitleUntouched = true), action = Action.NEW_TITLE),
            editor("20-body-ime", "Редактирование тела с настоящей IME", 20, action = Action.BODY_EDIT),
            editor("21-editor-overflow", "Раскрытая панель инструментов", 21, action = Action.OVERFLOW),
            editor("22-editor-saving", "Запись правки на телефон", 22, pending,
                NotesScreenState(loading = false, networkAvailable = true, localWritePending = true)),
            editor("23-editor-pending", "Правка сохранена только на телефоне", 23, pending, sync = SyncState(pendingCount = 1)),
            editor("24-editor-sending", "Отправка текущей заметки", 24, pending, sync = SyncState(pendingCount = 1, isSyncing = true)),
            editor("25-editor-save-error", "Ошибка локального сохранения", 25, pending,
                NotesScreenState(loading = false, networkAvailable = true, localWritePending = true,
                    error = "Не удалось сохранить текст на телефоне. Не закрывайте заметку.")),
            editor("25b-editor-save-error-ime", "Ошибка локального сохранения с телом и настоящей IME", 25, pending,
                NotesScreenState(loading = false, networkAvailable = true, localWritePending = true,
                    error = "Не удалось сохранить текст на телефоне. Не закрывайте заметку."), action = Action.BODY_EDIT),
            editor("26-conflict", "Конфликт: локальный текст и сохранение копии", 26,
                pending.copy(conflict = true), sync = SyncState(pendingCount = 1, conflicts = setOf(pending.note.id))),
            editor("27-markdown-reader", "Markdown: таблица, задачи и код", 27,
                doc("qa_markdown", "План поездки", "## Перед выездом\n\n- [x] Забронировать билеты\n- [ ] Собрать рюкзак\n\n| День | Место |\n| --- | --- |\n| Суббота | Парк |\n\n```javascript\nconst route = 'weekend';\n```", mime = "text/markdown")),
            editor("28-code-source", "Код: настоящий редактор исходного текста", 28,
                doc("qa_source", "Проверка списка", "const plans = ['Книга', 'Прогулка'];\n\nfor (const plan of plans) {\n  console.log(plan);\n}", "code", "application/javascript"), action = Action.CODE_EDIT),
            editor("29-protected", "Защищённая заметка", 29, doc("qa_protected", "Личные записи", "", protected = true)),
            editor("30-documentation", "Справка Trilium: только чтение", 30,
                doc("qa_docs", "Справка: заметки и связи", "<h2>Заметки и связи</h2><p>Справочная информация Trilium доступна для чтения.</p><ul><li>Дерево заметок</li><li>Клоны и связи</li></ul>", "doc")),
            editor("31-service-empty", "Служебный раздел без текстового документа", 31, doc("qa_service", "Рабочая панель", "", "contentWidget", "application/json")),
            editor("32-mermaid-source", "Mermaid: фактический source fallback", 32,
                doc("qa_mermaid", "Путь заметки", "graph TD\n  Idea[Идея] --> Draft[Черновик]\n  Draft --> Saved[Сохранено]", "mermaid", "text/plain")),
            editor("33-canvas-source", "Непустой холст: фактический source fallback", 33,
                doc("qa_canvas", "Холст идей", "{\n  \"nodes\": [{\"id\": \"idea\", \"text\": \"Новая идея\"}],\n  \"edges\": []\n}", "canvas", "application/json")),
            editor("34-relation-map-source", "Непустая карта связей: source fallback", 34,
                doc("qa_relations", "Связи проекта", "{\n  \"notes\": [\"Планы\", \"Материалы\"],\n  \"relations\": [{\"from\": \"Планы\", \"to\": \"Материалы\"}]\n}", "relationMap", "application/json")),
        )
        return shots + listOf(
            shots.first { it.id == "33-canvas-source" }.copy(id = "33b-canvas-source-expanded", title = "Холст: раскрытый точный исходный JSON", action = Action.SPECIAL_SOURCE),
            shots.first { it.id == "34-relation-map-source" }.copy(id = "34b-relations-source-expanded", title = "Карта связей: раскрытый точный исходный JSON", action = Action.SPECIAL_SOURCE),
        )
    }

    private fun attachments(): List<Shot> {
        fun shot(id: String, title: String, group: Int, variant: String, mime: String, wait: String? = null): Shot {
            val visibleName = when {
                variant == "image" -> "Цветная схема.png"
                variant == "pdf" -> "План поездки.pdf"
                variant == "text" -> "Материалы.txt"
                variant == "svg" -> "Схема проекта.svg"
                variant.startsWith("audio") -> "Запись прогулки.wav"
                variant.startsWith("video") || variant == "media" -> "Прогулка в парке.mp4"
                variant == "loading" || variant == "error" -> "Документы поездки.pdf"
                variant == "broken_pdf" -> "Чек.pdf"
                variant == "broken_image" -> "Фото прогулки.png"
                else -> "Архив материалов.bin"
            }
            return Shot(id, title, group, document = doc("qa_$variant", visibleName, "", if (mime.startsWith("image/")) "image" else "file", mime),
                attachment = variant, waitText = wait, action = when (variant) {
                    "audio_playing", "video_playing" -> Action.MEDIA_PLAY
                    "audio_paused", "video_paused" -> Action.MEDIA_PAUSE
                    else -> Action.NONE
                })
        }
        return listOf(
            shot("35a-attachment-image", "Изображение", 35, "image", "image/png"),
            shot("35b-attachment-pdf", "Документ PDF", 35, "pdf", "application/pdf"),
            shot("35c-attachment-text", "Текстовый файл", 35, "text", "text/plain", "Материалы поездки"),
            shot("35d-attachment-svg", "Векторная схема SVG", 35, "svg", "image/svg+xml"),
            shot("35e-attachment-audio-ready", "Аудиозапись перед воспроизведением", 35, "audio_ready", "audio/wav"),
            shot("35f-attachment-audio-playing", "Аудиозапись воспроизводится", 35, "audio_playing", "audio/wav"),
            shot("35g-attachment-audio-paused", "Аудиозапись на паузе", 35, "audio_paused", "audio/wav"),
            shot("35h-attachment-video", "Видео до первого воспроизведения", 35, "video", "video/mp4"),
            shot("35i-attachment-video-playing", "Валидное видео воспроизводится", 35, "video_playing", "video/mp4"),
            shot("35j-attachment-video-paused", "Валидное видео на паузе с декодированным кадром", 35, "video_paused", "video/mp4"),
            shot("36a-attachment-loading", "Загрузка вложения", 36, "loading", "application/pdf"),
            shot("36b-attachment-network-error", "Ошибка загрузки вложения", 36, "error", "application/pdf", "Не удалось загрузить вложение."),
            shot("37a-attachment-unknown", "Неизвестный формат файла", 37, "unknown", "application/octet-stream", "Файл доступен."),
            shot("37b-attachment-broken-pdf", "Повреждённый PDF", 37, "broken_pdf", "application/pdf", "Не удалось открыть PDF."),
            shot("37c-attachment-broken-image", "Неподдерживаемое изображение", 37, "broken_image", "image/png", "Не удалось показать изображение."),
            shot("37d-attachment-media-error", "Ошибка встроенного плеера", 37, "media", "video/mp4", "Не удалось воспроизвести файл."),
        )
    }

    private fun statusDetails(): List<Shot> {
        val core = core()
        fun from(id: String) = core.first { it.id == id }
        return listOf(
            from("01-library-ready").copy(id = "38-status-ready-details", title = "Подробности: копия готова", group = 38, action = Action.STATUS_DETAIL),
            from("11-library-sync-error").copy(id = "39-status-pending-details", title = "Подробности: ожидают отправки", group = 39,
                sync = SyncState(pendingCount = 2), action = Action.STATUS_DETAIL),
            from("07-library-offline-pending").copy(id = "40-status-offline-details", title = "Подробности: нет интернета", group = 40,
                sync = SyncState(), action = Action.STATUS_DETAIL),
            from("11-library-sync-error").copy(id = "41-status-error-details", title = "Подробности: ошибка отправки", group = 41, action = Action.STATUS_DETAIL),
            from("11b-library-global-conflict").copy(id = "42-status-conflict-details", title = "Подробности: конфликт", group = 42, action = Action.STATUS_DETAIL),
            from("23-editor-pending").copy(id = "43-note-pending-details", title = "Подробности правки текущей заметки", group = 43, action = Action.PENDING_DETAIL),
        )
    }

    private fun capture(allShots: List<Shot>, dark: Boolean, batch: String) {
        val from = InstrumentationRegistry.getArguments().getString("reviewFromGroup")?.toIntOrNull() ?: 1
        val requested = InstrumentationRegistry.getArguments().getString("reviewIds")?.split(',')?.toSet()
        val shots = allShots.filter { it.group >= from && (requested == null || it.id in requested) }
        require(shots.isNotEmpty())
        var current by mutableStateOf(shots.first())
        var generation by mutableStateOf(0)
        val base = Configuration(context.resources.configuration)
        compose.runOnIdle {
            val activity = activity()
            activity.enableEdgeToEdge()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        compose.setContent {
            val configuration = Configuration(base).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                NotesTheme { key(generation) {
                    val s = current
                    Box(Modifier.fillMaxSize().testTag("review-shot-${s.id}")) {
                    if (s.document == null) LibraryScreen(s.state, s.sync, {}, {}, {}, {}, {}, {}, {}, {}, offline = s.offline, onOpenConflict = {})
                    else EditorScreen(s.state.copy(document = s.document), s.document, s.sync,
                        onBack = {}, onTitleChanged = {}, onHtmlChanged = {}, onSync = {}, onKeepConflictAsCopy = {},
                        resourceLoader = { EditorResource("image/png", imageBytes()) },
                        attachmentLoader = { if (s.attachment == "loading") awaitCancellation() else attachment(s.attachment ?: "error") })
                    }
                } }
            }
        }
        for (shot in shots) {
            hideKeyboard()
            compose.runOnIdle { current = shot; generation++ }
            compose.waitForIdle()
            try {
                prepare(shot)
                verifyCurrentSemantics(shot)
                nativeFrameBarrier()
                captureWindow(shot, dark)
            } catch (failure: Throwable) {
                saveDiagnostics(shot, dark, failure)
                throw failure
            }
            writeManifest(batch, dark)
        }
        hideKeyboard()
    }

    private fun prepare(shot: Shot) {
        if (shot.document != null && shot.attachment == null && !shot.document.note.isProtected &&
            (shot.document.note.type == "doc" || shot.document.note.type == "text")) {
            waitWeb { "Boolean(window.pocket?.ready)" }
        }
        when (shot.action) {
            Action.BODY_EDIT, Action.OVERFLOW -> {
                compose.onNodeWithContentDescription("Редактировать заметку").performClick()
                waitWeb { "Boolean(window.pocket?.editor && !window.pocket.editor.isReadOnly && window.pocketOptions.keyboardVisible && document.activeElement.classList.contains('ck-content'))" }
                if (shot.action == Action.OVERFLOW) {
                    settleViewport()
                    tapWeb(".ck-toolbar__grouped-dropdown > button")
                    waitWeb { "Boolean(document.querySelector('.ck-toolbar__grouped-dropdown .ck-dropdown__panel-visible'))" }
                }
            }
            Action.NEW_TITLE -> waitWeb { "Boolean(window.pocket?.editor && window.pocketOptions.keyboardVisible && document.activeElement.id === 'title')" }
            Action.CODE_EDIT -> compose.onNodeWithContentDescription("Редактировать заметку").performClick()
            Action.SPECIAL_SOURCE -> {
                compose.onNodeWithTag("toggle-special-source").performClick()
                waitTag("special-note-source")
            }
            Action.STATUS_DETAIL -> {
                compose.onNodeWithTag("sync-status").performClick()
                waitTag("sync-status-dialog")
                compose.onNodeWithTag("sync-status-dialog").assertIsDisplayed()
            }
            Action.PENDING_DETAIL -> {
                compose.onNodeWithTag("pending-note-status").performClick()
                waitTag("pending-note-status-dialog")
                compose.onNodeWithTag("pending-note-status-dialog").assertIsDisplayed()
            }
            Action.MEDIA_PLAY, Action.MEDIA_PAUSE -> {
                val audio = shot.attachment?.startsWith("audio") == true
                waitMediaReady(audio)
                compose.onNodeWithText("Воспроизвести").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Пауза").fetchSemanticsNodes().isNotEmpty() }
                if (!audio) waitMediaPlaying(true)
                if (shot.attachment?.startsWith("video") == true) waitDecodedVideo()
                if (shot.action == Action.MEDIA_PAUSE) {
                    compose.onNodeWithText("Пауза").performClick()
                    compose.waitUntil(10_000) { compose.onAllNodesWithText("Воспроизвести").fetchSemanticsNodes().isNotEmpty() }
                    if (!audio) waitMediaPlaying(false)
                }
            }
            Action.NONE -> Unit
        }
        when (shot.attachment) {
            "pdf" -> waitTag("pdf-page-0")
            "image" -> compose.waitUntil(15_000) { var ready = false; compose.runOnIdle { ready = findImage(activity().window.decorView)?.drawable != null }; ready }
            "audio_ready" -> waitMediaReady(true)
            "video" -> waitMediaReady(false)
            "svg" -> {
                compose.waitUntil(15_000) { var ready = false; compose.runOnIdle { ready = findWeb(activity().window.decorView)?.progress == 100 }; ready }
                paintBarrier()
                nativeFrameBarrier()
                waitSvgPaint()
            }
        }
        shot.waitText?.let { expected -> compose.waitUntil(15_000) {
            compose.onAllNodesWithText(expected, substring = true).fetchSemanticsNodes().isNotEmpty()
        } }
        if (shot.action in setOf(Action.NEW_TITLE, Action.BODY_EDIT, Action.OVERFLOW)) settleViewport()
        paintBarrier()
    }

    private fun activity(): ComponentActivity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first() as ComponentActivity
    private fun findWeb(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
        (0 until view.childCount).firstNotNullOfOrNull { findWeb(view.getChildAt(it)) } else null
    private fun findImage(view: View): ImageView? = if (view is ImageView) view else if (view is ViewGroup)
        (0 until view.childCount).firstNotNullOfOrNull { findImage(view.getChildAt(it)) } else null
    private fun webView(): WebView { var view: WebView? = null; compose.runOnIdle { view = findWeb(activity().window.decorView) }; return requireNotNull(view) }
    private fun js(expression: String): String {
        val done = CountDownLatch(1); val value = AtomicReference<String>()
        val view = webView()
        compose.runOnIdle { view.evaluateJavascript(expression) { value.set(it); done.countDown() } }
        assertTrue("Synthetic WebView callback timed out", done.await(10, TimeUnit.SECONDS))
        return value.get()
    }
    private fun waitWeb(expression: () -> String) {
        compose.waitUntil(30_000) { runCatching { js(expression()) == "true" }.getOrDefault(false) }
        val view = webView()
        compose.waitUntil(10_000) { var visible = false; compose.runOnIdle { visible = view.alpha == 1f }; visible }
    }
    private fun findVideo(view: View): VideoView? = if (view is VideoView) view else if (view is ViewGroup)
        (0 until view.childCount).firstNotNullOfOrNull { findVideo(view.getChildAt(it)) } else null
    private fun waitMediaReady(audio: Boolean) {
        if (audio) {
            waitTag("audio-player")
            compose.waitUntil(20_000) {
                compose.onAllNodes(hasTestTag("audio-play") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
        } else compose.waitUntil(20_000) {
            var ready = false
            compose.runOnIdle { ready = (findVideo(activity().window.decorView)?.duration ?: 0) > 0 }
            ready
        }
    }
    /** Real SVG paint, without enabling page JavaScript: sample the known purple fixture inside native bounds. */
    private fun waitSvgPaint() = compose.waitUntil(15_000) {
        val view = webView(); val location = IntArray(2); var width = 0; var height = 0
        compose.runOnIdle { view.getLocationOnScreen(location); width = view.width; height = view.height }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        var purple = 0
        for (row in 1..8) for (col in 1..8) {
            val x = (location[0] + width * col / 9).coerceIn(0, bitmap.width - 1)
            val y = (location[1] + height * row / 9).coerceIn(0, bitmap.height - 1)
            val color = bitmap.getPixel(x, y)
            if (Color.red(color) < 200 && Color.blue(color) > 100 && Color.blue(color) - Color.green(color) > 35) purple++
        }
        bitmap.recycle()
        purple >= 5
    }
    private fun waitMediaPlaying(expected: Boolean) = compose.waitUntil(10_000) {
        var playing: Boolean? = null
        compose.runOnIdle { playing = findVideo(activity().window.decorView)?.isPlaying }
        playing == expected
    }
    private fun waitDecodedVideo() = compose.waitUntil(10_000) {
        val done = CountDownLatch(1); var copied = false
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        compose.runOnIdle {
            val video = requireNotNull(findVideo(activity().window.decorView))
            PixelCopy.request(video, bitmap, { result -> copied = result == PixelCopy.SUCCESS; done.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue("Synthetic video Surface PixelCopy callback timed out", done.await(5, TimeUnit.SECONDS))
        val pixels = IntArray(256); if (copied) bitmap.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        val decoded = copied && pixels.count { Color.red(it) + Color.green(it) + Color.blue(it) > 100 } > 20
        bitmap.recycle()
        decoded
    }
    private fun waitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun hideKeyboard() {
        compose.runOnIdle {
            val activity = activity()
            activity.currentFocus?.clearFocus()
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        instrumentation.waitForIdleSync()
    }
    private fun tapWeb(selector: String) {
        val geometry = JSONObject(JSONTokener(js("JSON.stringify((()=>{const e=document.querySelector('$selector'),r=e.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,width:innerWidth}})())")).nextValue() as String)
        val view = webView(); val location = IntArray(2); var scale = 1f
        compose.runOnIdle { view.getLocationOnScreen(location); scale = view.width / geometry.getDouble("width").toFloat() }
        val x = location[0] + geometry.getDouble("x").toFloat() * scale
        val y = location[1] + geometry.getDouble("y").toFloat() * scale
        val time = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time + 60, MotionEvent.ACTION_UP, x, y, 0))
    }
    private fun settleViewport() {
        var previous = ""; var same = 0
        repeat(25) {
            var geometry = ""
            compose.runOnIdle {
                val root = activity().window.decorView
                val frame = android.graphics.Rect(); root.getWindowVisibleDisplayFrame(frame)
                geometry = "${frame.width()}:${frame.height()}:${findWeb(root)?.height}"
            }
            same = if (geometry == previous) same + 1 else 0
            if (same >= 3) return
            previous = geometry
            SystemClock.sleep(80)
        }
        error("IME viewport did not settle for screenshot")
    }
    private fun paintBarrier() {
        var view: WebView? = null; compose.runOnIdle { view = findWeb(activity().window.decorView) }
        view?.let { web ->
            val done = CountDownLatch(1)
            compose.runOnIdle { web.postVisualStateCallback(System.nanoTime(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { done.countDown() }
            }) }
            assertTrue("Actual compositor did not reach fixture", done.await(10, TimeUnit.SECONDS))
        }
        instrumentation.waitForIdleSync()
    }
    private fun verifyCurrentSemantics(shot: Shot) {
        compose.onNodeWithTag("review-shot-${shot.id}").assertIsDisplayed()
        shot.state.error?.let {
            if (shot.action == Action.BODY_EDIT && shot.state.localWritePending) {
                compose.onNodeWithText("Не сохранено").assertIsDisplayed()
                compose.onNodeWithContentDescription("Повторить сохранение").assertIsDisplayed()
            } else compose.onNodeWithText(it).assertIsDisplayed()
        }
        if (shot.document == null) {
            when {
                shot.state.loading -> compose.onNodeWithText("Загружаем…").assertIsDisplayed()
                shot.state.searching -> compose.onNodeWithText("Ищем заметки…").assertIsDisplayed()
                shot.state.query.isNotBlank() && shot.state.results.isNotEmpty() ->
                    compose.onNodeWithText(shot.state.results.first().title).assertIsDisplayed()
                shot.state.query.isBlank() && shot.state.items.isNotEmpty() ->
                    compose.onNodeWithText(shot.state.items.first().note.title).assertIsDisplayed()
            }
        }

    }
    /** Compose idle is a semantic barrier, not a SurfaceFlinger latch barrier.
     * Force two future native window frames and await their completed frame metrics.
     * A screenshot may then latch the preceding frame; both contain this shot.
     */
    private fun nativeFrameBarrier() {
        val done = CountDownLatch(1)
        var window: Window? = null
        val registeredAt = System.nanoTime()
        var frames = 0
        val listener = Window.OnFrameMetricsAvailableListener { current, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP) >= registeredAt) frames++
            if (frames >= 2) done.countDown() else current.decorView.postInvalidateOnAnimation()
        }
        compose.runOnIdle {
            window = activity().window
            window!!.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
            window!!.decorView.postInvalidateOnAnimation()
        }
        val rendered = done.await(10, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { window?.removeOnFrameMetricsAvailableListener(listener) }
        assertTrue("Two current native frames did not render before screenshot", rendered)
        instrumentation.waitForIdleSync()
    }
    private fun saveDiagnostics(shot: Shot, dark: Boolean, failure: Throwable) {
        val prefix = "diagnostic-${if (dark) "dark" else "light"}-${shot.id}"
        val details = JSONObject().put("shot", shot.id).put("failure", failure.toString())
        runCatching {
            details.put("web", JSONTokener(js("""JSON.stringify({activeElement:{tag:document.activeElement?.tagName,id:document.activeElement?.id,classes:document.activeElement?.className},options:window.pocketOptions,viewport:{width:innerWidth,height:innerHeight,visualHeight:visualViewport.height,scrollY},groups:[...document.querySelectorAll('.ck-toolbar__grouped-dropdown')].map(e=>({classes:e.className,rect:e.getBoundingClientRect().toJSON(),expanded:e.querySelector('button')?.getAttribute('aria-expanded')})),panels:[...document.querySelectorAll('.ck-dropdown__panel')].map(e=>({classes:e.className,display:getComputedStyle(e).display,rect:e.getBoundingClientRect().toJSON()}))})""")).nextValue())
        }
        runCatching {
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(output, "$prefix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        File(output, "$prefix.json").writeText(details.toString(2))
    }
    private fun captureWindow(shot: Shot, dark: Boolean) {
        val name = "${if (dark) "dark" else "light"}-${shot.id}.png"
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        records.put(JSONObject().put("id", shot.id).put("primaryGroup", shot.group).put("title", shot.title)
            .put("theme", if (dark) "dark" else "light").put("file", name).put("width", bitmap.width).put("height", bitmap.height)
            .put("networkAvailable", shot.state.networkAvailable).put("fromCache", shot.state.fromCache)
            .put("pendingCount", shot.sync.pendingCount).put("syncing", shot.sync.isSyncing)
            .put("captureBarrier", "current semantic marker + expected text; WebView visual callback; two completed future native window frames")
            .put("fixture", "production composable; synthetic state/files; actual WebView/editor/IME where applicable"))
        bitmap.recycle()
        val semanticTarget = when (shot.action) {
            Action.STATUS_DETAIL -> "sync-status-dialog"
            Action.PENDING_DETAIL -> "pending-note-status-dialog"
            else -> "review-shot-${shot.id}"
        }
        File(output, name.removeSuffix(".png") + ".semantics.txt")
            .writeText(compose.onNodeWithTag(semanticTarget).printToString())
    }
    private fun writeManifest(batch: String, dark: Boolean) {
        File(output, "manifest-$batch.json").writeText(JSONObject().put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("sourceHead", InstrumentationRegistry.getArguments().getString("reviewHead") ?: "working-tree")
            .put("provenance", InstrumentationRegistry.getArguments().getString("reviewProvenance") ?: "candidate working-tree; production composables")
            .put("batch", batch).put("systemThemeRequested", if (dark) "dark" else "light")
            .put("androidNetwork", "disabled; networkAvailable is synthetic presentation state; no HTTP client in harness")
            .put("stateCount", records.length()).put("screenshots", records).toString(2))
    }
    private fun attachment(kind: String): CachedAttachment {
        if (kind == "loading") error("Suspending loader is supplied separately")
        if (kind == "error") error("Synthetic attachment fetch failure")
        val directory = File(context.filesDir, "note-resources/product-review").apply { mkdirs() }
        val extension = if (kind.startsWith("audio")) "wav" else if (kind.startsWith("video") || kind == "media") "mp4" else "bin"
        val file = File(directory, "fixture-$kind.$extension")
        val mime = when (kind) {
            "image" -> "image/png"
            "audio_ready", "audio_playing", "audio_paused" -> "audio/wav"
            "video", "video_playing", "video_paused" -> "video/mp4"
            "pdf", "broken_pdf" -> "application/pdf"
            "text" -> "text/plain"
            "svg" -> "image/svg+xml"
            "broken_image" -> "image/png"
            "media" -> "video/mp4"
            else -> "application/octet-stream"
        }
        when (kind) {
            "audio_ready", "audio_playing", "audio_paused" -> {
                val rate = 16000; val samples = rate * 4
                val bytes = java.nio.ByteBuffer.allocate(44 + samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray()).putInt(16)
                    .putShort(1.toShort()).putShort(1.toShort()).putInt(rate).putInt(rate * 2).putShort(2.toShort()).putShort(16.toShort())
                    .put("data".toByteArray()).putInt(samples * 2)
                repeat(samples) { index -> bytes.putShort((kotlin.math.sin(2 * Math.PI * 440 * index / rate) * 32767 * 0.12).toInt().toShort()) }
                file.writeBytes(bytes.array())
            }
            "video", "video_playing", "video_paused" -> instrumentation.context.assets.open("product-review/sample-video.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
            "image" -> file.writeBytes(imageBytes())
            "pdf" -> {
                val document = PdfDocument()
                repeat(2) { index ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(360, 440, index + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 48, 98); textSize = 23f }
                    page.canvas.drawText("Weekend notes", 24f, 54f, paint)
                    paint.color = Color.DKGRAY; paint.textSize = 15f
                    page.canvas.drawText("Synthetic document for product review", 24f, 94f, paint)
                    page.canvas.drawText("Page ${index + 1}", 24f, 140f, paint)
                    document.finishPage(page)
                }
                file.outputStream().use { document.writeTo(it) }; document.close()
            }
            "text" -> file.writeText("Материалы поездки\n\nКнига, билеты, заметки и фотографии.\nВсё содержимое этого файла искусственное.")
            "svg" -> file.writeText("<svg xmlns='http://www.w3.org/2000/svg' width='360' height='240'><rect width='360' height='240' fill='#efe6fa'/><circle cx='100' cy='120' r='48' fill='#7655a5'/><rect x='210' y='74' width='90' height='90' rx='16' fill='#b59bd7'/><path d='M148 120H210' stroke='#7655a5' stroke-width='6'/></svg>")
            else -> file.writeText("Synthetic binary fixture, intentionally unsupported or invalid.")
        }
        return CachedAttachment(file, mime, when (kind) { "pdf" -> "План поездки.pdf"; "image" -> "Цветная схема.png"; else -> "Материалы.$kind" })
    }
    private fun imageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(236, 226, 247))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(110, 80, 156) }
        canvas.drawCircle(145f, 130f, 78f, paint); paint.color = Color.rgb(175, 148, 210)
        canvas.drawRoundRect(260f, 78f, 420f, 225f, 22f, 22f, paint)
        paint.color = Color.rgb(65, 44, 91); paint.textSize = 22f
        canvas.drawText("Synthetic attachment", 106f, 286f, paint)
        val bytes = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes); bitmap.recycle()
        return bytes.toByteArray()
    }
}
