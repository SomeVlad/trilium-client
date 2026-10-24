package ru.vlad.triliumnotes.editor

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import org.json.JSONObject
import org.json.JSONTokener
import android.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.NotesScreenState
import ru.vlad.triliumnotes.data.NoteDocument
import ru.vlad.triliumnotes.data.NoteMeta
import ru.vlad.triliumnotes.data.SyncState
import ru.vlad.triliumnotes.ui.EditorScreen
import ru.vlad.triliumnotes.ui.NotesTheme

class EditorScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun saveSyntheticEditorScreenInLightAndDarkThemes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val baseConfiguration = Configuration(context.resources.configuration)
        val document = NoteDocument(
            note = NoteMeta("screenshot", "Пример заметки", "text", "text/html",
                false, null, null, emptyList()),
            html = "<h2>Короткий список</h2><ul><li>Первый пункт</li><li>Второй пункт</li></ul>" +
                "<p>Искусственный текст заметки для проверки экрана.</p>",
        )
        var dark by mutableStateOf(false)
        compose.setContent {
            val configuration = Configuration(baseConfiguration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                NotesTheme {
                    EditorScreen(NotesScreenState(document = document), document, SyncState(),
                        onBack = {}, onTitleChanged = {}, onHtmlChanged = {},
                        onSync = {}, onKeepConflictAsCopy = {})
                }
            }
        }

        waitForEditorTheme("rgb(250, 249, 252)")
        val light = save("editor-screen-light.png")
        compose.runOnIdle { dark = true }
        waitForEditorTheme("rgb(22, 21, 27)")
        val darkFile = save("editor-screen-dark.png")
        assertFalse("Theme switch did not change the screen", light.readBytes().contentEquals(darkFile.readBytes()))

        compose.runOnIdle { dark = false }
        waitForEditorTheme("rgb(250, 249, 252)")
        compose.onNodeWithContentDescription("Редактировать заметку").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("note-editor").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(30_000) {
            js("Boolean(window.pocket?.editor && !window.pocket.editor.isReadOnly && document.body.classList.contains('keyboard-visible') && document.body.classList.contains('body-focus'))") == "true"
        }
        compose.waitUntil(5_000) {
            js("""(()=>{const bar=document.querySelector('.ck-toolbar');if(!bar)return false;const r=bar.getBoundingClientRect();const button=bar.querySelector('button:not(.ck-disabled)');if(!button)return false;const b=button.getBoundingClientRect();return r.height>=48&&r.top>=0&&r.bottom<=visualViewport.height+visualViewport.offsetTop&&bar.contains(document.elementFromPoint(b.x+b.width/2,b.y+b.height/2))})()""") == "true"
        }
        js("window.toolbarPainted=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.toolbarPainted=true))")
        compose.waitUntil(5_000) { js("window.toolbarPainted") == "true" }
        val geometry = js("""JSON.stringify({viewport:{height:innerHeight,visualHeight:visualViewport.height,visualTop:visualViewport.offsetTop,scroll:scrollY},classes:document.body.className,paint:document.elementsFromPoint(45,205).map(e=>({tag:e.tagName,classes:e.className.baseVal??e.className})),elements:['.ck-editor__top','.ck-sticky-panel','.ck-sticky-panel__content','.ck-toolbar','.ck-toolbar__items','.ck-toolbar .ck-button','.ck-toolbar .ck-icon','.ck-editor__main','.ck-editor'].map(selector=>{const e=document.querySelector(selector);if(!e)return {selector,missing:true};const r=e.getBoundingClientRect(),s=getComputedStyle(e);return {selector,rect:{x:r.x,y:r.y,width:r.width,height:r.height,bottom:r.bottom},display:s.display,visibility:s.visibility,position:s.position,transform:s.transform,opacity:s.opacity,overflow:s.overflow,zIndex:s.zIndex,color:s.color,background:s.backgroundColor,clip:s.clipPath}})})""")
        File(requireNotNull(context.getExternalFilesDir(null)), "toolbar-geometry.json").writeText(geometry)
        saveWithKeyboard("editor-screen-edit-keyboard.png")
        assertTrue("Editor must retain hardware composition while editing", currentLayerType() == View.LAYER_TYPE_NONE)
        compose.onNodeWithText("Готово").performClick()
        compose.waitUntil(10_000) {
            js("Boolean(window.pocket.editor.isReadOnly && !window.pocketOptions.keyboardVisible && getComputedStyle(document.querySelector('.ck-editor__top')).display==='none')") == "true" && currentLayerType() == View.LAYER_TYPE_NONE
        }
    }

    private fun currentLayerType(): Int {
        var layer = -1
        compose.runOnIdle {
            fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            layer = requireNotNull(find(activity.window.decorView)).layerType
        }
        return layer
    }

    private fun waitForEditorTheme(background: String) {
        compose.waitUntil(30_000) {
            js("Boolean(window.pocket?.ready) && getComputedStyle(document.body).backgroundColor === '$background'") == "true"
        }
    }

    private fun js(expression: String): String {
        val latch = CountDownLatch(1)
        val value = AtomicReference<String>()
        compose.runOnIdle {
            fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            requireNotNull(find(activity.window.decorView)).evaluateJavascript(expression) { value.set(it); latch.countDown() }
        }
        assertTrue("WebView callback timed out", latch.await(10, TimeUnit.SECONDS))
        return value.get()
    }

    private fun save(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(requireNotNull(context.getExternalFilesDir(null)), name)
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        output.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return output
    }

    private fun saveWithKeyboard(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), name)
        val toolbar = JSONObject(JSONTokener(js("""JSON.stringify((()=>{const bar=document.querySelector('.ck-toolbar');const r=bar.getBoundingClientRect();const rgb=getComputedStyle(bar).backgroundColor.match(/\d+/g).map(Number);return {x:r.right-4,y:r.y+r.height/2,viewport:innerWidth,red:rgb[0],green:rgb[1],blue:rgb[2]}})())""")).nextValue() as String)
        val location = IntArray(2)
        var scale = 1.0
        compose.runOnIdle {
            fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            requireNotNull(find(activity.window.decorView)).apply {
                getLocationOnScreen(location); scale = width / toolbar.getDouble("viewport")
            }
        }
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val pixel = image.getPixel((location[0] + toolbar.getDouble("x") * scale).toInt(),
            (location[1] + toolbar.getDouble("y") * scale).toInt())
        assertTrue("Toolbar DOM exists but its background is absent from the Android display",
            kotlin.math.abs(Color.red(pixel) - toolbar.getInt("red")) < 3 &&
                kotlin.math.abs(Color.green(pixel) - toolbar.getInt("green")) < 3 &&
                kotlin.math.abs(Color.blue(pixel) - toolbar.getInt("blue")) < 3)
        output.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
        return output
    }
}
