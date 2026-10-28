package ru.vlad.triliumnotes.diagrams

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.FrameMetrics
import android.view.Window
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.AppLanguage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Only synthetic in-memory scenes. No login, NotesServices or owner documents are opened. */
class DiagramViewerTest {
    @get:Rule val compose = createComposeRule()
    @Test fun officialMermaidAndExcalidrawRenderOfflineInBothThemesWithZoomAndScreenshots() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousLanguage = AppLanguage.preference(context)
        var dark by mutableStateOf(false)
        var kind by mutableStateOf("mermaid")
        var revision by mutableIntStateOf(0)
        val source = "flowchart LR\n A[Capture] --> B[Inbox]\n B --> C[Note]\n C --> D[Offline sync]"
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                key(revision) { DiagramViewer("synthetic-$revision", kind, if (kind == "mermaid") source else canvas(), Modifier.fillMaxSize()) }
            }
        }
        try {
            for (mode in listOf("mermaid", "canvas")) for (night in listOf(false, true)) {
                compose.runOnIdle { AppLanguage.select(context, if (night) "en" else "ru"); kind = mode; dark = night; revision++ }
                compose.waitUntil(60_000) { js("Boolean(window.diagram?.ready)") == "true" }
                assertEquals("false", js("Boolean(window.diagram.error)"))
                assertTrue(js("document.querySelectorAll('#surface svg').length").toInt() > 0)
                assertEquals("0", js("document.querySelectorAll('#surface script,#surface iframe,#surface foreignObject').length"))
                if (mode == "mermaid") {
                    val labels = js("JSON.stringify([...document.querySelectorAll('#surface svg text')].map(e=>e.textContent))")
                    for (expected in listOf("Capture", "Inbox", "Note", "Offline sync"))
                        assertTrue("SVG labels must remain visible after sanitizing: $labels", labels.contains(expected))
                }
                val initialScale = js("window.diagram.transform.scale").toDouble()
                js("document.getElementById('zoom-in').click();true")
                assertTrue(js("window.diagram.transform.scale").toDouble() > initialScale)
                js("document.getElementById('fit').click();true")
                assertEquals(initialScale, js("window.diagram.transform.scale").toDouble(), 0.001)
                screenshot("$mode-${if(night) "dark-en" else "light-ru"}.png")
            }
        } finally {
            val edit = context.getSharedPreferences("app-language", 0).edit()
            if (previousLanguage == null) edit.remove("language").commit() else edit.putString("language", previousLanguage).commit()
        }
    }
    @Test fun malformedSceneDisplaysErrorAndDoesNotExposeNativeDataInterface() {
        compose.setContent { MaterialTheme { DiagramViewer("malformed", "canvas", "{broken", Modifier.fillMaxSize()) } }
        compose.waitUntil(30_000) { js("Boolean(window.diagram?.error)") == "true" }
        assertEquals("undefined", js("typeof window.PocketNative").trim('"'))
        assertEquals("0", js("document.querySelectorAll('#surface iframe,#surface script').length"))
    }
    private fun canvas(): String {
        fun element(id: String, type: String, x: Int, y: Int, width: Int, height: Int) = JSONObject()
            .put("id", id).put("type", type).put("x", x).put("y", y).put("width", width).put("height", height)
            .put("angle", 0).put("strokeColor", "#1e1e1e").put("backgroundColor", "#b2f2bb")
            .put("fillStyle", "solid").put("strokeWidth", 2).put("strokeStyle", "solid").put("roughness", 1)
            .put("opacity", 100).put("groupIds", JSONArray()).put("frameId", JSONObject.NULL)
            .put("seed", 1).put("version", 1).put("versionNonce", 1).put("isDeleted", false)
            .put("boundElements", JSONArray()).put("updated", 1).put("link", JSONObject.NULL).put("locked", false)
        val rect = element("box1", "rectangle", 20, 20, 280, 140)
        val text = element("text1", "text", 48, 65, 220, 50).put("text", "Offline canvas\nTrilium Client")
            .put("originalText", "Offline canvas\nTrilium Client").put("fontSize", 20).put("fontFamily", 1)
            .put("textAlign", "left").put("verticalAlign", "top").put("containerId", JSONObject.NULL).put("lineHeight", 1.25)
        return JSONObject().put("type", "excalidraw").put("version", 2).put("elements", JSONArray().put(rect).put(text))
            .put("appState", JSONObject()).put("files", JSONObject()).toString()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "diagram-review").apply { mkdirs() }
        val geometry = JSONObject(JSONTokener(js("""JSON.stringify((()=>{const svg=document.querySelector('#surface svg');return {viewport:{width:innerWidth,height:innerHeight},layoutViewport:document.getElementById('viewport').getBoundingClientRect().toJSON(),html:document.documentElement.getBoundingClientRect().toJSON(),body:document.body.getBoundingClientRect().toJSON(),svg:svg?.getBoundingClientRect().toJSON(),controls:document.getElementById('controls').getBoundingClientRect().toJSON(),ready:window.diagram?.ready,error:window.diagram?.error}})())""")).nextValue() as String)
        val rect = IntArray(4)
        val visual = CountDownLatch(1)
        compose.runOnIdle {
            val view = web()
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            rect[0] = location[0]; rect[1] = location[1]; rect[2] = view.width; rect[3] = view.height
            geometry.put("native", JSONObject().put("width", view.width).put("height", view.height)
                .put("shown", view.isShown).put("visibility", view.visibility).put("alpha", view.alpha)
                .put("layoutHeight", view.layoutParams?.height).put("hardwareAccelerated", view.isHardwareAccelerated))
            view.postVisualStateCallback(System.nanoTime(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { visual.countDown() }
            })
            view.invalidate()
        }
        File(folder, "$name.json").writeText(geometry.toString(2))
        assertTrue("WebView must have visible native bounds: $geometry", rect[2] > 0 && rect[3] > 0 && geometry.getJSONObject("native").getBoolean("shown"))
        assertTrue("DOM viewport must have visible bounds: $geometry", geometry.getJSONObject("viewport").getInt("width") > 0 && geometry.getJSONObject("viewport").getInt("height") > 0)
        assertTrue("Rendered SVG must have visible bounds: $geometry", geometry.getJSONObject("svg").getDouble("width") > 0 && geometry.getJSONObject("svg").getDouble("height") > 0)
        assertTrue("WebView compositor did not reach current diagram: $geometry", visual.await(10, TimeUnit.SECONDS))
        nativeFrames()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(folder, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val colors = hashSetOf<Int>()
            // Exclude both system bars and the bottom zoom controls: the diagram itself must paint.
            val inset = minOf(128, rect[3] / 5)
            for (y in (rect[1] + inset).coerceAtLeast(0) until (rect[1] + rect[3] - inset).coerceAtMost(bitmap.height) step 5)
                for (x in (rect[0] + 12).coerceAtLeast(0) until (rect[0] + rect[2] - 12).coerceAtMost(bitmap.width) step 5)
                    colors += bitmap.getPixel(x, y)
            assertTrue("Actual window image is blank despite ready DOM: colors=${colors.size}; $geometry", colors.size > 8)
        } finally { bitmap.recycle() }
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
        assertTrue("Two current native frames must complete before screenshot", complete)
    }

    private fun web(): WebView {
        fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
            (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
        return requireNotNull(WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find))
    }
    private fun js(expression: String): String {
        val latch = CountDownLatch(1); var result = ""
        compose.runOnIdle {
            fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            val roots = if (Build.VERSION.SDK_INT >= 29) WindowInspector.getGlobalWindowViews()
                else ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).map { it.window.decorView }
            requireNotNull(roots.firstNotNullOfOrNull(::find)).evaluateJavascript(expression) { result = it; latch.countDown() }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS)); return result
    }
}
