package ru.vlad.triliumnotes.diagrams

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Production download/error/retry UI at 360dp; all requests and documents are synthetic. */
class DiagramPackageUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private data class Scene(val id: String, val kind: String, val dark: Boolean, val fixture: DiagramTestPackages)

    @Test fun backgroundPackageInstallationRestoresAnOpenFailedViewer() {
        val attempts = AtomicInteger()
        DiagramTestPackages(mapOf("mermaid" to syntheticRenderer("mermaid"))) { request, bytes ->
            val code = if (attempts.incrementAndGet() == 1) 503 else 200
            DiagramTestPackages.response(request, code, if (code == 200) bytes else ByteArray(0))
        }.use { fixture ->
            compose.setContent {
                MaterialTheme {
                    DiagramViewer("synthetic-background-install", "mermaid", "Synthetic diagram", Modifier.fillMaxSize(),
                        packages = fixture.packages)
                }
            }
            waitForTag("diagram-package-error")
            compose.onNodeWithTag("diagram-package-retry").assertIsDisplayed()
            // A full-copy worker shares this store and can finish while the failed viewer stays open.
            runBlocking { fixture.packages.ensure("mermaid") }
            compose.waitUntil(20_000) { js("Boolean(window.diagram?.ready)") == "true" }
            compose.onNodeWithTag("diagram-package-error").assertDoesNotExist()
            compose.onNodeWithTag("diagram-package-retry").assertDoesNotExist()
            assertEquals(2, attempts.get())
        }
    }

    @Test fun loadingErrorAndRetryRemainVisibleInBothLanguagesAndThemes() {
        val previousLanguage = AppLanguage.preference(context)
        var scene by mutableStateOf<Scene?>(null)
        compose.setContent {
            scene?.let { current ->
                MaterialTheme(colorScheme = if (current.dark) darkColorScheme() else lightColorScheme()) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                        key(current.id) {
                            Box(Modifier.width(360.dp).fillMaxHeight().testTag("diagram-ui-test-root")) {
                                val content = if (current.kind == "mermaid") "Synthetic private diagram" else
                                    """{"elements":[{"id":"test-shape","type":"rectangle","x":0,"y":0,"width":240,"height":100}],"files":{}}"""
                                DiagramViewer(current.id, current.kind, content, Modifier.fillMaxSize(), packages = current.fixture.packages)
                            }
                        }
                    }
                }
            }
        }
        val fixtures = mutableListOf<DiagramTestPackages>()
        try {
            for (kind in listOf("mermaid", "canvas")) for (language in listOf("ru", "en")) for (dark in listOf(false, true)) {
                val firstRelease = CountDownLatch(1)
                val retryRelease = CountDownLatch(1)
                val attempts = AtomicInteger()
                val fixture = DiagramTestPackages(mapOf(kind to syntheticRenderer(kind))) { request, bytes ->
                    val attempt = attempts.incrementAndGet()
                    val release = if (attempt == 1) firstRelease else retryRelease
                    if (!release.await(25, TimeUnit.SECONDS)) throw IOException("Synthetic UI test download timed out")
                    DiagramTestPackages.response(request, if (attempt == 1) 503 else 200, if (attempt == 1) ByteArray(0) else bytes)
                }.also(fixtures::add)
                val id = "$kind-$language-${if (dark) "dark" else "light"}"
                try {
                    compose.runOnIdle { AppLanguage.select(context, language); scene = Scene(id, kind, dark, fixture) }
                    waitForTag("diagram-package-loading")
                    compose.waitUntil(10_000) { attempts.get() == 1 }
                    compose.onNodeWithTag("diagram-package-loading").assertIsDisplayed()
                    compose.onNodeWithText(AppLanguage.get(context, R.string.diagram_downloading)).assertIsDisplayed()
                    compose.onNodeWithTag("diagram-package-retry").assertDoesNotExist()
                    screenshot("$id-loading", "diagram-package-loading", "diagram-package-status")

                    firstRelease.countDown()
                    waitForTag("diagram-package-error")
                    compose.onNodeWithTag("diagram-package-error").assertIsDisplayed()
                    compose.onNodeWithText(AppLanguage.get(context, R.string.diagram_download_failed)).assertIsDisplayed()
                    compose.onNodeWithTag("diagram-package-retry").assertIsDisplayed().assertIsEnabled()
                    compose.onNodeWithText(if (language == "ru") "Повторить" else "Retry").assertIsDisplayed()
                    screenshot("$id-error", "diagram-package-error", "diagram-package-retry")

                    compose.onNodeWithTag("diagram-package-retry").performClick()
                    waitForTag("diagram-package-loading")
                    compose.waitUntil(10_000) { attempts.get() == 2 }
                    compose.onNodeWithTag("diagram-package-error").assertDoesNotExist()
                    screenshot("$id-retry-loading", "diagram-package-loading", "diagram-package-status")
                    retryRelease.countDown()
                    compose.waitUntil(20_000) { js("Boolean(window.diagram?.ready)") == "true" }
                    assertEquals("false", js("Boolean(window.diagram.error)"))
                    assertEquals("1", js("document.querySelectorAll('#surface svg').length"))
                    compose.onNodeWithTag("diagram-package-retry").assertDoesNotExist()
                    assertEquals(2, attempts.get())
                    assertTrue(fixture.packages.isReady(kind))
                    assertTrue(fixture.requests.all { request -> request.method == "GET" && request.body == null &&
                        request.header("Authorization") == null && request.header("Cookie") == null &&
                        request.url.toString() == "https://diagram-package-test.invalid/$kind.zip" })
                } finally {
                    firstRelease.countDown(); retryRelease.countDown()
                    compose.runOnIdle { scene = null }
                    compose.waitForIdle()
                }
            }
        } finally {
            fixtures.forEach(DiagramTestPackages::close)
            val edit = context.getSharedPreferences("app-language", 0).edit()
            if (previousLanguage == null) edit.remove("language").commit() else edit.putString("language", previousLanguage).commit()
        }
    }

    private fun syntheticRenderer(kind: String): Map<String, ByteArray> {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 240 100\"><rect width=\"240\" height=\"100\" fill=\"#b2f2bb\"/><text x=\"12\" y=\"55\">Synthetic diagram</text></svg>"
        val code = if (kind == "mermaid")
            "window.TriliumMermaid={initialize(){},async render(){return {svg:${JSONObject.quote(svg)}}}};"
        else "window.TriliumExcalidraw={restoreElements(elements){return elements},async exportToSvg(){return new DOMParser().parseFromString(${JSONObject.quote(svg)},'image/svg+xml').documentElement}};"
        return mapOf((if (kind == "mermaid") "mermaid.js" else "excalidraw.js") to code.toByteArray())
    }

    private fun waitForTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun screenshot(name: String, vararg tags: String) {
        compose.waitForIdle()
        val root = compose.onNodeWithTag("diagram-ui-test-root").fetchSemanticsNode()
        val width = with(compose.density) { 360.dp.toPx() }
        assertTrue("Narrow presentation must be no wider than 360dp: ${root.size}", root.size.width <= width + 1)
        val geometry = JSONObject().put("widthDp", root.size.width / compose.density.density)
        for (tag in tags) {
            val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
            val bounds = node.boundsInRoot
            assertTrue("$tag must fit entirely inside the viewer: $bounds, viewer=${root.boundsInRoot}",
                bounds.width >= node.size.width - 1 && bounds.height >= node.size.height - 1 &&
                    bounds.left >= root.boundsInRoot.left && bounds.right <= root.boundsInRoot.right &&
                    bounds.top >= root.boundsInRoot.top && bounds.bottom <= root.boundsInRoot.bottom)
            geometry.put(tag, bounds.toString())
        }
        nativeFrames()
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "diagram-package-review").apply { mkdirs() }
        File(output, "$name.json").writeText(geometry.toString(2))
        requireNotNull(instrumentation.uiAutomation.takeScreenshot()).also { bitmap ->
            try {
                File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                val background = bitmap.getPixel((root.boundsInWindow.left.toInt() + 4).coerceIn(0, bitmap.width - 1),
                    (root.boundsInWindow.top.toInt() + 100).coerceIn(0, bitmap.height - 1))
                for (tag in tags.filter { it == "diagram-package-status" || it == "diagram-package-error" }) {
                    val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
                    var visiblePixels = 0
                    for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(bitmap.height))
                        for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(bitmap.width))
                            if (ColorUtils.calculateContrast(bitmap.getPixel(x, y), background) >= 3.0) visiblePixels++
                    assertTrue("$tag must paint readable text against its actual background: $visiblePixels contrasting pixels", visiblePixels > 10)
                    geometry.put("$tag-contrastingPixels", visiblePixels)
                }
                File(output, "$name.json").writeText(geometry.toString(2))
            }
            finally { bitmap.recycle() }
        }
    }

    private fun nativeFrames() {
        val done = CountDownLatch(1)
        val started = System.nanoTime()
        var frames = 0
        lateinit var window: Window
        val listener = Window.OnFrameMetricsAvailableListener { current, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP) >= started) frames++
            if (frames >= 2) done.countDown() else current.decorView.postInvalidateOnAnimation()
        }
        compose.runOnIdle {
            window = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first().window
            window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
            window.decorView.postInvalidateOnAnimation()
        }
        val complete = done.await(10, TimeUnit.SECONDS)
        compose.runOnIdle { window.removeOnFrameMetricsAvailableListener(listener) }
        assertTrue("Two native frames must complete before screenshot", complete)
    }

    private fun js(expression: String): String {
        val result = arrayOf("")
        val done = CountDownLatch(1)
        compose.runOnIdle {
            fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            val web = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find)
            if (web == null) done.countDown()
            else web.evaluateJavascript(expression) { result[0] = it; done.countDown() }
        }
        assertTrue("WebView JS callback must return", done.await(10, TimeUnit.SECONDS))
        return result[0]
    }
}
