package ru.vlad.triliumnotes.editor

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Reads show sanitized original content; CKEditor is created only when editing is requested. */
class LazyReaderLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun complexHtmlReadsWithoutAnEditorOrWritesAndKeepsScriptsInert() {
        val original = "<h2>Reader heading</h2><p onclick='window.readerScriptRan=true'>Exact &amp; HTML " +
            "<a href='#root/target'>Internal link</a></p>" +
            "<figure class='table'><table><tbody><tr><td colspan='2'>Cell text</td></tr></tbody></table></figure>" +
            "<ul class='todo-list'><li><input type='checkbox' checked>Task text</li></ul>" +
            "<p><img src='api/attachments/owner/attachment/picture.png' alt='Cached image' onerror='window.readerScriptRan=true'></p>" +
            "<script>window.readerScriptRan=true</script><iframe srcdoc='<script>window.readerScriptRan=true</script>'></iframe>"
        val writes = AtomicInteger()
        val opened = AtomicReference<String>()
        val resources = ConcurrentLinkedQueue<String>()
        val image = image()
        compose.setContent { MaterialTheme {
            RichNoteEditor("reader-html", original, title = "Existing title", isEditing = false,
                modifier = Modifier.fillMaxSize(), onTitleChanged = { writes.incrementAndGet() },
                onHtmlChanged = { writes.incrementAndGet() }, onOpenNote = { opened.set(it) },
                resourceLoader = { resources += it; EditorResource("image/png", image) })
        } }
        readerReady()
        assertEquals("HTML reading must not load the editing engine", "undefined", js("typeof window.CKEDITOR").trim('"'))
        assertEquals("false", js("Boolean(window.pocket.editor)"))
        assertEquals("\"Reader heading\"", js("document.querySelector('#preview h2').textContent"))
        assertEquals("\"Cell text\"", js("document.querySelector('#preview td').textContent"))
        assertEquals("\"2\"", js("document.querySelector('#preview td').getAttribute('colspan')"))
        assertEquals("true", js("document.querySelector('#preview input[type=checkbox]').checked"))
        assertEquals("true", js("document.querySelector('#preview input[type=checkbox]').disabled"))
        compose.waitUntil(5_000) { js("document.querySelector('#preview img').naturalWidth") == "1" }
        assertTrue(resources.contains("api/attachments/owner/attachment/picture.png"))
        js("document.querySelector('#preview p').click();document.querySelector('#preview input').click()")
        assertEquals("undefined", js("typeof window.readerScriptRan").trim('"'))
        assertEquals("0", js("document.querySelectorAll('#preview script,#preview iframe,#preview [onclick],#preview [onerror]').length"))
        js("document.querySelector('#preview a').click()")
        compose.waitUntil(5_000) { opened.get() == "target" }
        assertEquals(original, snapshot())
        js("window.pocket.flush()")
        assertEquals(0, writes.get())
    }

    @Test fun markdownReaderShowsTablesTasksLinksImagesAndCodeWithoutChangingItsSource() {
        val original = "# Markdown reader\n\n| A | B |\n| --- | --- |\n| one | two |\n\n- [x] Completed task\n\n" +
            "[Internal](#root/target)\n\n![Cached image](api/attachments/owner/attachment/picture.png)\n\n" +
            "```javascript\nconst answer = 42;\n```\n\n<script>window.readerScriptRan=true</script>"
        val writes = AtomicInteger()
        val image = image()
        compose.setContent { MaterialTheme {
            RichNoteEditor("reader-markdown", original, initialMarkdown = original, isEditing = false,
                modifier = Modifier.fillMaxSize(), onHtmlChanged = { writes.incrementAndGet() },
                resourceLoader = { EditorResource("image/png", image) })
        } }
        readerReady()
        assertEquals("false", js("Boolean(window.pocket.editor)"))
        assertEquals("\"Markdown reader\"", js("document.querySelector('#preview h1').textContent"))
        assertEquals("1", js("document.querySelectorAll('#preview table').length"))
        assertEquals("\"one\"", js("document.querySelector('#preview td').textContent.trim()"))
        assertEquals("true", js("document.querySelector('#preview input[type=checkbox]').checked"))
        assertEquals("\"none\"", js("getComputedStyle(document.querySelector('#preview input[type=checkbox]').closest('li')).listStyleType"))
        assertEquals("\"#root/target\"", js("document.querySelector('#preview a').getAttribute('href')"))
        assertEquals("true", js("document.querySelector('#preview pre').textContent.includes('const answer = 42;')"))
        compose.waitUntil(5_000) { js("document.querySelector('#preview img').naturalWidth") == "1" }
        assertEquals("undefined", js("typeof window.readerScriptRan").trim('"'))
        assertEquals(original, snapshot())
        js("window.pocket.flush()")
        assertEquals(0, writes.get())
    }

    @Test fun enteringEditLazilyCreatesAndFocusesTheEditorWithoutWritingTheOriginal() {
        val original = "<p class='keep' style='color:#885522'>Exact &amp; original HTML</p>"
        val writes = AtomicInteger()
        var editing by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            RichNoteEditor("reader-edit", original, title = "Existing title", isEditing = editing,
                modifier = Modifier.fillMaxSize(), onTitleChanged = { writes.incrementAndGet() },
                onHtmlChanged = { writes.incrementAndGet() })
        } }
        readerReady()
        assertEquals("false", js("Boolean(window.pocket.editor)"))
        assertEquals(original, snapshot())

        compose.runOnIdle { editing = true }
        compose.waitUntil(30_000) { js("Boolean(window.pocket.editor && !window.pocket.editor.isReadOnly)") == "true" }
        compose.waitUntil(15_000) {
            js("Boolean(window.pocketOptions.keyboardVisible && document.activeElement.classList.contains('ck-content'))") == "true"
        }
        assertEquals("1", js("document.querySelectorAll('.ck-content').length"))
        assertEquals("true", js("(()=>{const p=document.getElementById('preview');return !p || (p.hidden && !p.children.length && !p.classList.contains('ck-content'))})()"))
        assertEquals(original, snapshot())
        js("window.pocket.flush()")
        assertEquals(0, writes.get())
    }

    @Test fun leavingEditWhileLazyInitializationIsPendingNeverReopensTheKeyboard() {
        val original = "<p>Keep this exact source</p>"
        val writes = AtomicInteger()
        var editing by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            RichNoteEditor("reader-race", original, isEditing = editing, modifier = Modifier.fillMaxSize(),
                onHtmlChanged = { writes.incrementAndGet() })
        } }
        readerReady()
        // This race isolates construction from library loading; the production loader reuses these globals.
        js("""(async()=>{for(const src of ['vendor/ckeditor5.umd.js','vendor/ru.umd.js']){
            await new Promise((resolve,reject)=>{const script=document.createElement('script');script.src=src;
                script.onload=resolve;script.onerror=()=>reject(new Error('Bundled script failed: '+src));document.head.append(script)});
            }window.readerTestLibrariesReady=true;})().catch(error=>{window.readerTestLibraryFailure=String(error)})""")
        compose.waitUntil(30_000) {
            js("Boolean(window.readerTestLibrariesReady || window.readerTestLibraryFailure)") == "true"
        }
        assertEquals("Bundled editor libraries: ${js("window.readerTestLibraryFailure || 'ready'")}",
            "true", js("Boolean(window.CKEDITOR && window.CKEDITOR_TRANSLATIONS?.ru && window.readerTestLibrariesReady)"))
        // Hold the real ClassicEditor creation at its boundary, then let Done win the race.
        js("""(()=>{const create=CKEDITOR.ClassicEditor.create.bind(CKEDITOR.ClassicEditor);
            CKEDITOR.ClassicEditor.create=(...args)=>new Promise(resolve=>{window.releaseEditorInitialization=resolve}).then(()=>create(...args));})()""")
        compose.runOnIdle { editing = true }
        compose.waitUntil(5_000) { js("typeof window.releaseEditorInitialization") == "\"function\"" }
        assertEquals("false", js("Boolean(window.pocket.editor)"))
        compose.runOnIdle { editing = false }
        compose.waitUntil(5_000) { js("Boolean(window.pocketOptions.editing)") == "false" }
        js("""(()=>{window.keyboardAfterDone=false;const configure=window.pocket.configure;
            window.pocket.configure=next=>{if(next.keyboardVisible)window.keyboardAfterDone=true;return configure(next)};
            window.releaseEditorInitialization();})()""")
        compose.waitUntil(30_000) { js("Boolean(window.pocket.editor && window.pocket.editor.isReadOnly)") == "true" }
        commitVisualState()
        assertEquals("false", js("Boolean(window.pocketOptions.editing || window.pocketOptions.keyboardVisible || window.keyboardAfterDone)"))
        assertEquals("false", js("document.activeElement.isContentEditable"))
        assertEquals(original, snapshot())
        assertEquals(0, writes.get())
    }

    private fun readerReady() {
        compose.waitUntil(30_000) { js("Boolean(window.pocket?.ready && document.querySelector('#preview')?.textContent)") == "true" }
        compose.waitUntil(5_000) {
            var visible = false
            compose.runOnIdle { visible = webView().alpha == 1f }
            visible
        }
    }

    private fun snapshot(): String = JSONObject(JSONTokener(js("window.pocket.snapshot()")).nextValue() as String).getString("html")

    private fun commitVisualState() {
        val painted = CountDownLatch(1)
        compose.runOnIdle {
            val view = webView()
            view.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    view.invalidate()
                    view.postOnAnimation { view.postOnAnimation { painted.countDown() } }
                }
            })
        }
        assertTrue("WebView did not commit and paint the completed initialization", painted.await(10, TimeUnit.SECONDS))
    }

    private fun image(): ByteArray {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { bytes ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
            bitmap.recycle()
            bytes.toByteArray()
        }
    }

    private fun webView(): WebView {
        fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
            (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
        val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
        return requireNotNull(find(activity.window.decorView))
    }

    private fun js(expression: String): String {
        val completed = CountDownLatch(1)
        val result = AtomicReference<String>()
        compose.runOnIdle { webView().evaluateJavascript(expression) { result.set(it); completed.countDown() } }
        assertTrue("WebView JavaScript callback timed out", completed.await(10, TimeUnit.SECONDS))
        return result.get()
    }
}
