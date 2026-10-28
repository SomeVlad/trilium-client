package ru.vlad.triliumnotes.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.VideoView
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.data.CachedAttachment
import ru.vlad.triliumnotes.data.NoteMeta
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Native decoder/render lifecycle tests use only generated, nonprivate files. */
class AttachmentViewerTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun note(id: String = "fixture") = NoteMeta(id, "Вложение", "file", "application/pdf", false, "version", null, emptyList())
    private fun file(name: String) = File(context.cacheDir, "attachment-qa-$name")
    private fun pdf(pages: Int = 2): File = file("sample.pdf").also { target ->
        val document = PdfDocument()
        try {
            repeat(pages) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(360, 480, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("Synthetic page ${index + 1}", 20f, 50f, Paint().apply { color = Color.BLACK; textSize = 18f })
                document.finishPage(page)
            }
            target.outputStream().use { document.writeTo(it) }
        } finally { document.close() }
    }
    private fun image(): File = file("sample.png").also { target ->
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.BLUE)
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun audio(): File = file("sample.wav").also { target ->
        val rate = 8000
        val samples = ByteArray(rate * 2 * 10)
        val wav = ByteBuffer.allocate(44 + samples.size).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + samples.size)
        wav.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        wav.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        wav.put("data".toByteArray(Charsets.US_ASCII)).putInt(samples.size).put(samples)
        target.writeBytes(wav.array())
    }

    private fun waitForTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun optionalOwnerBinaryFilesDecodeAndRenderWithoutNetwork() {
        val directory = File(context.filesDir, "private-binary-fixtures")
        val manifest = File(directory, "manifest.json")
        assumeTrue("Owner binary fixtures must be injected into app-private storage", manifest.isFile)
        val cases = JSONArray(manifest.readText())
        var current by mutableStateOf<CachedAttachment?>(null)
        var id by mutableStateOf("initial")
        compose.setContent { MaterialTheme { current?.let { value -> AttachmentViewer(note(id), loader = { value }) } } }
        var pages = 0; var images = 0
        repeat(cases.length()) { index ->
            val item = cases.getJSONObject(index)
            val mime = item.getString("mime")
            val source = directory.listFiles()!!.single { it.name.startsWith("fixture-$index.") }
            assertTrue("Private binary length mismatch", source.length() == item.getLong("bytes"))
            if (mime == "application/pdf") {
                PdfRenderer(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                    pages += renderer.pageCount
                    assertTrue("Private PDF has no pages", renderer.pageCount > 0)
                }
            } else {
                val decoded = ImageDecoder.decodeDrawable(ImageDecoder.createSource(source)) { decoder, _, _ -> decoder.setTargetSize(64, 64) }
                assertTrue("Private image has invalid dimensions", decoded.intrinsicWidth > 0 && decoded.intrinsicHeight > 0)
                images++
            }
            compose.runOnIdle { current = CachedAttachment(source, mime, "Private fixture"); id = "owner-$index" }
            waitForTag(if (mime == "application/pdf") "pdf-page-0" else "attachment-image")
        }
        compose.runOnIdle { current = null }
        Log.i("PrivateCorpusQA", "binaryDecoded=${cases.length()} images=$images pdfPages=$pages")
    }

    @Test fun pdfRendersNativePagesAndImageOpens() {
        var cached by mutableStateOf(CachedAttachment(pdf(), "application/pdf", "sample.pdf"))
        var id by mutableStateOf("pdf")
        compose.setContent { MaterialTheme { AttachmentViewer(note(id), loader = { cached }) } }
        waitForTag("pdf-page-0")
        compose.onNodeWithTag("pdf-page-0").assertIsDisplayed()
        compose.onNodeWithText("PDF ·", substring = true).assertIsDisplayed()
        compose.onNodeWithText("application/pdf", substring = true).assertDoesNotExist()
        compose.runOnIdle { cached = CachedAttachment(image(), "image/png", "sample.png"); id = "image" }
        waitForTag("attachment-image")
        compose.onNodeWithTag("attachment-image").assertIsDisplayed()
        compose.onNodeWithText("PNG ·", substring = true).assertIsDisplayed()
        compose.onNodeWithText("image/png", substring = true).assertDoesNotExist()
    }

    @Test fun invalidPdfAndImageGiveRecoverableErrors() {
        val broken = file("broken").apply { writeText("not a supported binary file") }
        var id by mutableStateOf("pdf")
        var mime by mutableStateOf("application/pdf")
        compose.setContent { MaterialTheme { AttachmentViewer(note(id), loader = { CachedAttachment(broken, mime, "broken") }) } }
        compose.onNodeWithText("Не удалось открыть PDF. Попробуйте «Открыть файл».").assertIsDisplayed()
        compose.runOnIdle { mime = "image/png"; id = "image" }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("attachment-image").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Не удалось показать изображение. Попробуйте открыть файл в другом приложении.").assertIsDisplayed(); true }.getOrDefault(false) }
    }

    @Test fun attachmentFormatsUseHumanLabelsAndSafeExtensions() {
        val source = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = android.content.res.Configuration(source.resources.configuration).apply {
            setLocales(android.os.LocaleList(java.util.Locale.forLanguageTag("ru")))
        }
        val context = source.createConfigurationContext(configuration)
        assertEquals("PDF", attachmentFormat(context, "application/pdf", "document"))
        assertEquals("PNG", attachmentFormat(context, " IMAGE/PNG ; charset=binary", "image"))
        assertEquals("Текст", attachmentFormat(context, "text/plain; charset=utf-8", "readme.txt"))
        assertEquals("Аудио", attachmentFormat(context, "audio/wav", "sample.wav"))
        assertEquals("Видео", attachmentFormat(context, "video/mp4", "clip.mp4"))
        assertEquals("DOCX", attachmentFormat(context, "application/octet-stream", "sample.docx"))
        assertEquals("Файл", attachmentFormat(context, "application/unknown", "no extension"))
    }

    @Test fun audioUsesCompactNativeControlsAndPausesWithItsLifecycle() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val cached = CachedAttachment(audio(), "audio/wav", "sample.wav")
        var visible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    if (visible) AttachmentViewer(note("audio"), loader = { cached })
                }
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        waitForTag("audio-player")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("audio-play").fetchSemanticsNodes()
            .any { isEnabled().matches(it) } }
        compose.onNodeWithTag("audio-play").assertIsEnabled()
        val audioBounds = compose.onNodeWithTag("audio-player").fetchSemanticsNode().boundsInRoot
        val viewerBounds = compose.onNodeWithTag("attachment-viewer").fetchSemanticsNode().boundsInRoot
        assertTrue("Audio must not reserve a video viewport", audioBounds.height < viewerBounds.height / 2)
        compose.onNodeWithTag("audio-play").performClick()
        compose.onNodeWithText("Пауза").assertIsDisplayed()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.onNodeWithText("Воспроизвести").assertIsDisplayed()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithTag("audio-play").performClick()
        compose.onNodeWithText("Пауза").assertIsDisplayed()
        compose.runOnIdle { visible = false }
        compose.onNodeWithTag("audio-player").assertDoesNotExist()
    }

    @Test fun invalidAudioOffersFileActionsWithoutDisabledPlaybackControls() {
        val broken = file("broken.wav").apply { writeText("not a supported audio file") }
        compose.setContent { MaterialTheme {
            AttachmentViewer(note("broken-audio"), loader = { CachedAttachment(broken, "audio/wav", "broken.wav") })
        } }
        compose.waitUntil(10_000) { runCatching {
            compose.onNodeWithText("Не удалось воспроизвести файл. Попробуйте открыть его в другом приложении.").assertIsDisplayed()
            true
        }.getOrDefault(false) }
        compose.onNodeWithTag("audio-play").assertDoesNotExist()
        compose.onNodeWithText("Открыть файл").assertIsDisplayed()
    }

    @Test fun nativeVideoPauseUpdatesExternalPlaybackButtonAndCanResume() {
        val target = File(context.filesDir, "note-resources/video-control-qa/sample.mp4")
        target.parentFile!!.mkdirs()
        val source = file("short-video.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("product-review/sample-video.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        // Give native-control interactions time to finish before the two-second source completes.
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(source.absolutePath)
            val tracks = (0 until extractor.trackCount).map { track ->
                extractor.selectTrack(track)
                muxer.addTrack(extractor.getTrackFormat(track))
            }
            muxer.start()
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (extractor.sampleTrackIndex >= 0) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime * 30, extractor.sampleFlags)
                muxer.writeSampleData(tracks[extractor.sampleTrackIndex], buffer, info)
                extractor.advance()
            }
            muxer.stop()
        } finally {
            extractor.release()
            muxer.release()
        }
        compose.setContent { MaterialTheme {
            AttachmentViewer(note("video-controls"), loader = { CachedAttachment(target, "video/mp4", "sample.mp4") })
        } }
        var video: VideoView? = null
        compose.waitUntil(15_000) {
            var ready = false
            compose.runOnIdle {
                video = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { root ->
                    findNativeView(root) { it is VideoView } as? VideoView
                }
                ready = (video?.duration ?: 0) > 0
            }
            ready
        }
        compose.onNodeWithText("Воспроизвести").performClick()
        compose.onNodeWithText("Пауза").assertIsDisplayed()
        compose.runOnIdle {
            val view = requireNotNull(video)
            assertTrue(view.isPlaying)
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                MotionEvent.obtain(time, time, action, view.width / 2f, view.height / 2f, 0).also {
                    view.dispatchTouchEvent(it)
                    it.recycle()
                }
            }
        }
        var nativePause: View? = null
        compose.waitUntil(5_000) {
            compose.runOnIdle {
                nativePause = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { root ->
                    findNativeView(root) { view ->
                        view.isShown && runCatching { view.resources.getResourceEntryName(view.id) == "pause" }.getOrDefault(false)
                    }
                }
            }
            nativePause != null
        }
        compose.runOnIdle {
            // Click the actual framework MediaController button, which calls VideoView.pause().
            assertTrue(requireNotNull(nativePause).performClick())
            assertTrue(!requireNotNull(video).isPlaying)
        }
        compose.onNodeWithText("Воспроизвести").assertIsDisplayed().performClick()
        compose.onNodeWithText("Пауза").assertIsDisplayed()
        compose.runOnIdle { assertTrue(requireNotNull(video).isPlaying) }
    }

    private fun findNativeView(view: View, matches: (View) -> Boolean): View? {
        if (matches(view)) return view
        return if (view is ViewGroup) (0 until view.childCount).firstNotNullOfOrNull {
            findNativeView(view.getChildAt(it), matches)
        } else null
    }

    @Test fun failedDownloadCanBeRetried() {
        val attempts = AtomicInteger()
        val attachment = CachedAttachment(image(), "image/png", "sample.png")
        compose.setContent { MaterialTheme { AttachmentViewer(note(), loader = {
            if (attempts.incrementAndGet() == 1) error("Synthetic network error")
            attachment
        }) } }
        compose.onNodeWithText("Повторить").performClick()
        waitForTag("attachment-image")
        assertTrue("Retry did not invoke loader again", attempts.get() == 2)
    }

    @Test fun failedSaveRetriesTheDocumentPickerWithoutDownloadingAgain() {
        val launches = AtomicInteger()
        val downloads = AtomicInteger()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) {
                if (launches.incrementAndGet() >= 3) dispatchResult(requestCode, Activity.RESULT_CANCELED, null)
                else dispatchResult(requestCode, Activity.RESULT_OK,
                    Intent().setData(Uri.parse("content://invalid.synthetic/save")))
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val cached = CachedAttachment(image(), "image/png", "sample.png")
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    AttachmentViewer(note(), loader = { downloads.incrementAndGet(); cached })
                }
            }
        }
        waitForTag("attachment-image")
        compose.onNodeWithContentDescription("Сохранить файл").performClick()
        compose.waitUntil(5_000) { runCatching {
            compose.onNodeWithText("Не удалось сохранить файл в выбранное место.").assertIsDisplayed()
            true
        }.getOrDefault(false) }
        compose.onNodeWithText("Повторить").assertDoesNotExist()
        compose.onNodeWithText("Выбрать место").performClick()
        compose.runOnIdle { assertEquals(2, launches.get()); assertEquals(1, downloads.get()) }
        compose.waitUntil(5_000) { runCatching {
            compose.onNodeWithText("Не удалось сохранить файл в выбранное место.").assertIsDisplayed()
            true
        }.getOrDefault(false) }
        compose.onNodeWithContentDescription("Сохранить файл").performClick()
        compose.onNodeWithText("Не удалось сохранить файл в выбранное место.").assertDoesNotExist()
        compose.runOnIdle { assertEquals(3, launches.get()); assertEquals(1, downloads.get()) }
    }

    @Test fun leavingWhileDownloadIsPendingCancelsLoader() {
        var visible by mutableStateOf(true)
        val entered = AtomicBoolean()
        val cancelled = AtomicBoolean()
        compose.setContent { MaterialTheme { if (visible) AttachmentViewer(note(), loader = {
            entered.set(true)
            try { awaitCancellation() } finally { cancelled.set(true) }
        }) } }
        compose.waitUntil(5_000) { entered.get() }
        compose.runOnIdle { visible = false }
        compose.waitUntil(5_000) { cancelled.get() }
        compose.onNodeWithTag("attachment-viewer").assertDoesNotExist()
    }

    @Test fun leavingAndSwitchingPdfDuringRenderingDoesNotCrash() {
        val attachment = CachedAttachment(pdf(30), "application/pdf", "sample.pdf")
        var visible by mutableStateOf(true)
        var id by mutableStateOf("start")
        compose.setContent { MaterialTheme { if (visible) AttachmentViewer(note(id), loader = { attachment }) } }
        repeat(8) { index ->
            compose.runOnIdle { id = "switch-$index" }
            compose.runOnIdle { visible = false }
            compose.runOnIdle { visible = true }
        }
        waitForTag("pdf-page-0")
        compose.runOnIdle { visible = false }
        compose.onNodeWithTag("attachment-viewer").assertDoesNotExist()
    }
}
