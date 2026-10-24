package ru.vlad.triliumnotes.editor

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlin.math.ceil
import kotlin.math.floor
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Checks the bundled editor's actual paragraph and placeholder styles in WebView. */
class EditorTypographyTest {
    @get:Rule val compose = createComposeRule()

    private fun webView(): WebView {
        var result: WebView? = null
        compose.runOnIdle {
            fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            result = find(activity.window.decorView)
        }
        return requireNotNull(result)
    }

    private fun js(expression: String): String {
        val completed = CountDownLatch(1)
        val result = AtomicReference<String>()
        val view = webView()
        compose.runOnIdle {
            view.evaluateJavascript(expression) {
                result.set(it)
                completed.countDown()
            }
        }
        assertTrue("WebView JavaScript callback timed out", completed.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    private fun showEmptyEditor() {
        compose.setContent {
            MaterialTheme {
                RichNoteEditor("typography", "", title = "Синтетическая заметка",
                    modifier = Modifier.fillMaxSize(), onHtmlChanged = {})
            }
        }
        compose.waitUntil(30_000) { js("Boolean(window.pocket?.ready && window.pocket.editor)") == "true" }
        compose.waitForIdle()
    }

    /** Synthetic screenshots make WebView software/hardware rasterization directly comparable. */
    @Test fun savePlaceholderAndBodyWithSoftwareAndHardwareRendering() {
        showEmptyEditor()
        captureLayer(View.LAYER_TYPE_SOFTWARE, "typography-placeholder-software.png")
        captureLayer(View.LAYER_TYPE_NONE, "typography-placeholder-hardware.png")
        js("window.pocket.editor.model.change(w => w.insertText('Текст заметки…', window.pocket.editor.model.document.getRoot().getChild(0), 0))")
        captureLayer(View.LAYER_TYPE_SOFTWARE, "typography-body-software.png")
        captureLayer(View.LAYER_TYPE_NONE, "typography-body-hardware.png")
    }

    @Test fun productionPlaceholderPaintMatchesItsMeasuredTextWidth() {
        showEmptyEditor()
        val view = webView()
        val painted = CountDownLatch(1)
        compose.runOnIdle {
            // Native animation frames alone do not synchronize Chromium's compositor.
            view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    view.invalidate()
                    view.postOnAnimation { view.postOnAnimation { painted.countDown() } }
                }
            })
        }
        assertTrue("WebView did not commit and paint its DOM", painted.await(10, TimeUnit.SECONDS))
        val raw = js("""(() => {
            const p = document.querySelector('.ck-placeholder'), rect = p.getBoundingClientRect();
            const style = getComputedStyle(p, '::before'), context = document.createElement('canvas').getContext('2d');
            context.font = [style.fontStyle, style.fontWeight, style.fontSize, style.fontFamily].join(' ');
            return JSON.stringify({left:rect.left,top:rect.top,width:rect.width,height:rect.height,
                viewport:innerWidth,textWidth:context.measureText(p.dataset.placeholder).width,
                display:style.display,background:getComputedStyle(document.body).backgroundColor,
                scrollY,visualViewport:visualViewport ? {offsetTop:visualViewport.offsetTop,
                    offsetLeft:visualViewport.offsetLeft,height:visualViewport.height,scale:visualViewport.scale} : null,
                activeElement:document.activeElement?.outerHTML.substring(0,500),
                pseudo:{content:style.content,font:style.font,color:style.color,position:style.position,
                    visibility:style.visibility,opacity:style.opacity},p:p.outerHTML});
        })()""")
        val geometry = JSONObject(JSONTokener(raw).nextValue() as String)
        assertTrue("Placeholder must be visible: $geometry", geometry.getString("display") != "none")
        val location = IntArray(2)
        var scale = 0.0
        compose.runOnIdle {
            view.getLocationOnScreen(location)
            scale = view.width / geometry.getDouble("viewport")
            geometry.put("native", JSONObject().put("screenX", location[0]).put("screenY", location[1])
                .put("width", view.width).put("height", view.height).put("scrollY", view.scrollY)
                .put("layerType", view.layerType).put("hardwareAccelerated", view.isHardwareAccelerated)
                .put("scale", scale))
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val left = floor(location[0] + geometry.getDouble("left") * scale).toInt().coerceAtLeast(0)
            val top = floor(location[1] + geometry.getDouble("top") * scale).toInt().coerceAtLeast(0)
            val right = ceil(left + geometry.getDouble("width") * scale).toInt().coerceAtMost(bitmap.width)
            val bottom = ceil(top + geometry.getDouble("height") * scale).toInt().coerceAtMost(bitmap.height)
            geometry.put("bitmap", JSONObject().put("width", bitmap.width).put("height", bitmap.height))
            geometry.put("crop", JSONObject().put("left", left).put("top", top).put("right", right).put("bottom", bottom))
            val artifacts = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
            File(artifacts, "typography-production-placeholder.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            File(artifacts, "typography-production-placeholder.json").writeText(geometry.toString(2))
            val background = Regex("[0-9]+").findAll(geometry.getString("background"))
                .take(3).map { it.value.toInt() }.toList()
            assertEquals("Expected an opaque RGB background", 3, background.size)
            var minX = right
            var maxX = left - 1
            var foregroundPixels = 0
            for (y in top until bottom) for (x in left until right) {
                val pixel = bitmap.getPixel(x, y)
                if (maxOf(kotlin.math.abs(Color.red(pixel) - background[0]),
                        kotlin.math.abs(Color.green(pixel) - background[1]),
                        kotlin.math.abs(Color.blue(pixel) - background[2])) > 40) {
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    foregroundPixels++
                }
            }
            val width = if (foregroundPixels == 0) 0 else maxX - minX + 1
            val expectedWidth = geometry.getDouble("textWidth") * scale
            val ratio = width / expectedWidth
            assertTrue("Placeholder paint is compressed: painted=$width expected=$expectedWidth ratio=$ratio pixels=$foregroundPixels",
                foregroundPixels >= 20 && expectedWidth > 90 && ratio >= 0.75)
        } finally {
            bitmap.recycle()
        }
    }

    private fun captureLayer(layer: Int, name: String) {
        val view = webView()
        val painted = CountDownLatch(1)
        compose.runOnIdle {
            view.setLayerType(layer, null)
            view.invalidate()
            view.postOnAnimation { view.postOnAnimation { painted.countDown() } }
        }
        assertTrue("WebView did not paint after layer change", painted.await(10, TimeUnit.SECONDS))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), name)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun emptyPlaceholderAndTypedParagraphHaveReadableMatchingMetrics() {
        showEmptyEditor()
        val placeholder = metrics("document.querySelector('.ck-placeholder')", "::before")
        assertTrue("Placeholder must have readable glyphs: $placeholder", placeholder.getDouble("textWidth") > 90)
        assertTrue("Placeholder must fit its paragraph: $placeholder",
            placeholder.getDouble("width") >= placeholder.getDouble("textWidth"))
        assertEquals("Placeholder must use the app body size: $placeholder", 17.0, placeholder.getDouble("fontSize"), 0.1)
        assertTrue("Placeholder must use the app font: $placeholder", placeholder.getString("fontFamily").contains("Roboto"))
        assertEquals(28.05, placeholder.getString("lineHeight").removeSuffix("px").toDouble(), 0.1)
        assertEquals("none", placeholder.getString("transform"))
        js("window.pocket.editor.model.change(w => w.insertText('Текст заметки…', window.pocket.editor.model.document.getRoot().getChild(0), 0))")
        val paragraph = metrics("document.querySelector('.ck-content p')")
        assertEquals(placeholder.getDouble("fontSize"), paragraph.getDouble("fontSize"), 0.1)
        assertEquals(placeholder.getString("fontFamily"), paragraph.getString("fontFamily"))
        assertEquals(placeholder.getDouble("textWidth"), paragraph.getDouble("textWidth"), 0.5)
    }

    private fun metrics(element: String, pseudo: String = ""): JSONObject {
        val result = js("""(() => {
            const element = $element, style = getComputedStyle(element, '$pseudo' || null);
            const canvas = document.createElement('canvas'), context = canvas.getContext('2d');
            context.font = [style.fontStyle, style.fontWeight, style.fontSize, style.fontFamily].join(' ');
            return JSON.stringify({fontSize:parseFloat(style.fontSize),fontFamily:style.fontFamily,
                lineHeight:style.lineHeight,letterSpacing:style.letterSpacing,transform:style.transform,
                width:parseFloat(style.width),textWidth:context.measureText('Текст заметки…').width});
        })()""")
        return JSONObject(JSONTokener(result).nextValue() as String)
    }
}
