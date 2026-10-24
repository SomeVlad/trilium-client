package ru.vlad.triliumnotes.editor

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Exercises the actual locally bundled CKEditor in Android WebView, without a server. */
class RichNoteEditorTest {
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
        val latch = CountDownLatch(1)
        val value = AtomicReference<String>()
        val view = webView()
        compose.runOnIdle { view.evaluateJavascript(expression) { value.set(it); latch.countDown() } }
        assertTrue("WebView JavaScript callback timed out", latch.await(10, TimeUnit.SECONDS))
        return value.get()
    }

    private fun ready(expectEditor: Boolean = true) {
        compose.waitUntil(30_000) { js("Boolean(window.pocket?.ready)") == "true" }
        if (expectEditor) {
            compose.waitUntil(30_000) { js("Boolean(window.pocket.editor)") == "true" }
            assertEquals("CKEditor initialization: ${js("window.pocket.failure || 'ready'")}", "true", js("Boolean(window.pocket.editor)"))
        } else assertEquals("Reading must not initialize CKEditor", "false", js("Boolean(window.pocket.editor)"))
    }

    @Test fun mountingAndChangingModesNeverWritesTheOriginalHtml() {
        val calls = AtomicInteger()
        var editing by mutableStateOf(false)
        compose.setContent { MaterialTheme { RichNoteEditor("mount", "<p class='keep' style='color:#885522'>Exact &amp; HTML</p>",
            title = "Синтетическая заметка", isEditing = editing, modifier = Modifier.fillMaxSize(), onHtmlChanged = { calls.incrementAndGet() }) } }
        ready(expectEditor = false)
        compose.runOnIdle { editing = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        try { compose.waitUntil(30_000) { js("Boolean(window.pocket.editor && !window.pocket.editor.isReadOnly)") == "true" } }
        catch (error: Throwable) { throw AssertionError("Mode state: " + js("JSON.stringify({options:window.pocketOptions,readonly:window.pocket.editor.isReadOnly,locks:[...window.pocket.editor._readOnlyLocks].map(String)})"), error) }
        js("window.pocket.flush()")
        compose.runOnIdle { editing = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.waitUntil(5_000) { js("window.pocket.editor.isReadOnly") == "true" }
        assertEquals(0, calls.get())
    }

    @Test fun realRichStructuresSurviveTypingAndResourcesKeepTheirOriginalUrls() {
        val initial = "<p>Start</p><figure class='table'><table style='border-color:rgb(20,30,40)'><tbody>" +
            "<tr><td colspan='2' data-keep='yes'><p>Cell</p></td></tr></tbody></table></figure>" +
            "<blockquote><p>Quote</p></blockquote><pre><code class='language-python'>a = 1\nprint(a)</code></pre>" +
            "<ul class='todo-list'><li><label class='todo-list__label'><input type='checkbox' checked='checked'>" +
            "<span class='todo-list__label__description'>Task</span></label></li></ul>" +
            "<p><img src='api/attachments/owner/attachment/picture.png' alt='Image'></p>" +
            "<p><span style='color:rgb(120,50,10);font-size:19px' class='custom'>Color</span> " +
            "<a class='reference-link' href='#root/internal'>Internal</a></p>" +
            "<div data-embed-type='video' data-url='https://example.org/video'></div>"
        val saved = AtomicReference<String>()
        compose.setContent { MaterialTheme { RichNoteEditor("rich", initial, modifier = Modifier.fillMaxSize(), onHtmlChanged = { saved.set(it) }) } }
        ready()
        assertNull("Opening a note wrote normalized HTML", saved.get())
        js("window.pocket.editor.model.change(w => w.insertText('X', window.pocket.editor.model.document.getRoot().getChild(0), 0))")
        compose.waitUntil(5_000) { saved.get() != null }
        val body = Jsoup.parseBodyFragment(saved.get()).body()
        assertEquals(1, body.select("table").size)
        assertEquals("2", body.selectFirst("td")?.attr("colspan"))
        assertEquals("yes", body.selectFirst("td")?.attr("data-keep"))
        assertEquals(1, body.select("blockquote").size)
        assertEquals(1, body.select("pre code").size)
        assertTrue(body.selectFirst("pre")!!.wholeText().contains("\n"))
        assertEquals(1, body.select("input[type=checkbox][checked]").size)
        assertEquals("api/attachments/owner/attachment/picture.png", body.selectFirst("img")?.attr("src"))
        assertEquals("Image", body.selectFirst("img")?.attr("alt"))
        val custom = body.selectFirst(".custom")!!
        assertTrue("Synthetic color styling lost", (custom.parents() + custom.getAllElements()).any { it.attr("style").contains("color:") })
        assertEquals(1, body.select("[data-embed-type=video][data-url]").size)
        assertFalse(saved.get().contains("/_note_resource"))
        js("window.pocket.editor.model.change(w=>{for(const item of window.pocket.editor.model.createRangeIn(window.pocket.editor.model.document.getRoot()).getItems())if(item.is('element','imageBlock')||item.is('element','imageInline'))w.setAttribute('src','https://example.org/changed.png',item)})")
        compose.waitUntil(5_000) { Jsoup.parseBodyFragment(saved.get()).selectFirst("img")?.attr("src") == "https://example.org/changed.png" }
    }

    @Test fun scriptsStayInertAndAreRetainedWhenOtherTextChanges() {
        val saved = AtomicReference<String>()
        compose.setContent { MaterialTheme { RichNoteEditor("inert",
            "<p onclick='window.documentExecuted=true'>Safe</p><script>window.documentExecuted=true</script>" +
                "<iframe srcdoc='<script>window.documentExecuted=true</script>'></iframe><custom-widget data-flavor='keep'>Original</custom-widget>",
            modifier = Modifier.fillMaxSize(), onHtmlChanged = { saved.set(it) }) } }
        ready()
        assertEquals("undefined", js("typeof window.documentExecuted").trim('"'))
        js("window.pocket.editor.model.change(w => w.insertText('X', window.pocket.editor.model.document.getRoot().getChild(0), 0))")
        compose.waitUntil(5_000) { saved.get() != null }
        val body = Jsoup.parseBodyFragment(saved.get()).body()
        assertEquals(1, body.select("script").size)
        assertEquals(1, body.select("iframe[srcdoc]").size)
        assertEquals(1, body.select("custom-widget[data-flavor=keep]").size)
        assertEquals("window.documentExecuted=true", body.selectFirst("p")?.attr("onclick"))
        assertEquals("undefined", js("typeof window.documentExecuted").trim('"'))
    }

    @Test fun titleInternalLinksAndSourceEditingUseNativeCallbacks() {
        val title = AtomicReference<String>()
        val opened = AtomicReference<String>()
        val saved = AtomicReference<String>()
        var editing by mutableStateOf(false)
        compose.setContent { MaterialTheme { RichNoteEditor("callbacks", "<p><a href='#root/target'>Link</a></p>",
            title = "Before", isEditing = editing, modifier = Modifier.fillMaxSize(),
            onTitleChanged = { title.set(it) }, onHtmlChanged = { saved.set(it) }, onOpenNote = { opened.set(it) }) } }
        ready(expectEditor = false)
        js("document.querySelector('.ck-content a').click()")
        compose.waitUntil(5_000) { opened.get() != null }
        assertEquals("target", opened.get())
        compose.runOnIdle { editing = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        try { compose.waitUntil(30_000) { js("Boolean(window.pocket.editor && !window.pocket.editor.isReadOnly)") == "true" } }
        catch (error: Throwable) { throw AssertionError("Mode state: " + js("JSON.stringify({options:window.pocketOptions,readonly:window.pocket.editor.isReadOnly,locks:[...window.pocket.editor._readOnlyLocks].map(String)})"), error) }
        js("document.getElementById('title').value='After';document.getElementById('title').dispatchEvent(new Event('input',{bubbles:true}))")
        compose.waitUntil(5_000) { title.get() != null }
        assertEquals("After", title.get())
        assertNull("Editing the title changed the body", saved.get())
        js("window.pocket.editor.plugins.get('SourceEditing').isSourceEditingMode=true")
        assertNull("Entering source mode changed the body", saved.get())
        compose.waitUntil(5_000) { js("Boolean(document.querySelector('.ck-source-editing-area textarea'))") == "true" }
        js("document.querySelector('.ck-source-editing-area textarea').value='<p>Raw source</p>';document.querySelector('.ck-source-editing-area textarea').dispatchEvent(new Event('input',{bubbles:true}))")
        compose.waitUntil(5_000) { saved.get() != null }
        assertEquals("<p>Raw source</p>", saved.get())
    }
    @Test fun markdownRendersTablesTasksAndCodeAndKeepsSourceFormatAfterEditing() {
        val markdown = "# Heading\n\n| A | B |\n| --- | --- |\n| one | two |\n\n- [x] Task\n\n```python\nprint(1)\n```\n\n<script>window.documentExecuted=true</script>"
        val saved = AtomicReference<String>()
        val controller = RichNoteEditorController()
        compose.setContent { MaterialTheme { RichNoteEditor("markdown", markdown, initialMarkdown = markdown,
            controller = controller, modifier = Modifier.fillMaxSize(), onHtmlChanged = { saved.set(it) }) } }
        ready()
        assertNull(saved.get())
        assertEquals("1", js("document.querySelectorAll('#editor-host .ck-content table').length"))
        assertEquals("1", js("document.querySelectorAll('#editor-host .ck-content input[type=checkbox]').length"))
        assertEquals("1", js("document.querySelectorAll('#editor-host .ck-content pre').length"))
        assertEquals("undefined", js("typeof window.documentExecuted").trim('"'))
        js("window.pocket.editor.model.change(w=>{const p=w.createElement('paragraph');w.insert(p,window.pocket.editor.model.document.getRoot(),'end');w.insertText('Added',p,0)})")
        compose.waitUntil(5_000) { saved.get() != null }
        val complete = CountDownLatch(1)
        compose.runOnIdle { controller.flush { complete.countDown() } }
        assertTrue(complete.await(5, TimeUnit.SECONDS))
        val result = saved.get()
        assertTrue(result.startsWith("# Heading"))
        assertTrue(result.contains("| one"))
        assertTrue(result.contains("[x] Task"))
        assertTrue(result.contains("```python"))
        assertTrue("Synthetic Markdown: $result", result.contains("<script>window.documentExecuted=true</script>"))
        assertFalse(result.contains("data-pocket-"))
    }

    @Test fun androidKeyboardCreatesAndContinuesAListAndFlushKeepsTheLastInput() {
        val saved = AtomicReference<String>()
        val controller = RichNoteEditorController()
        compose.setContent { MaterialTheme { RichNoteEditor("keyboard", "<p></p>", controller = controller,
            modifier = Modifier.fillMaxSize(), onHtmlChanged = { saved.set(it) }) } }
        ready()
        compose.waitUntil(15_000) { js("Boolean(window.pocketOptions.keyboardVisible)") == "true" }
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.sendStringSync("- ")
        instrumentation.sendStringSync("First")
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
        instrumentation.sendStringSync("Second")
        compose.waitUntil(5_000) { saved.get()?.contains("Second") == true }
        val complete = CountDownLatch(1)
        compose.runOnIdle { controller.flush { complete.countDown() } }
        assertTrue(complete.await(5, TimeUnit.SECONDS))
        val body = Jsoup.parseBodyFragment(saved.get()).body()
        assertEquals(2, body.select("ul > li").size)
        assertEquals(listOf("First", "Second"), body.select("ul > li").map { it.text() })
    }



}
