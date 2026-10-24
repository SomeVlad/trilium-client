package ru.vlad.triliumnotes.editor

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic HTML only: Trilium can store literal line feeds inside a rich-text paragraph. */
class HtmlLineBreakTest {
    @get:Rule val compose = createComposeRule()

    @Test fun literalHtmlLinesSurviveReaderEditingAndReopenWithoutAnOpeningWrite() {
        val original = "<p><code>CmdAlpha</code> description\n<code>CmdBeta</code> second\n\n" +
            "<a href='https://example.org/a'>LinkGamma</a>\n" +
            "<a href='#root/target'>LinkDelta</a></p><p>Other block</p>"
        val calls = AtomicInteger()
        val saved = AtomicReference<String?>()
        var key by mutableStateOf("literal-lines")
        var html by mutableStateOf(original)
        var editing by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            RichNoteEditor(key, html, isEditing = editing, modifier = Modifier.fillMaxSize(),
                onHtmlChanged = { saved.set(it); calls.incrementAndGet() })
        } }
        ready()
        assertVisualLines("#preview", listOf("CmdAlpha", "CmdBeta", "LinkGamma", "LinkDelta"), listOf(1, 2, 1))
        assertEquals(original, snapshot())
        assertEquals(0, calls.get())

        compose.runOnIdle { editing = true }
        editorReady()
        assertVisualLines("#editor-host .ck-content", listOf("CmdAlpha", "CmdBeta", "LinkGamma", "LinkDelta"), listOf(1, 2, 1))
        js("window.pocket.flush()")
        assertEquals("Opening the HTML editor must keep the original bytes", original, snapshot())
        assertEquals(0, calls.get())

        // A real change elsewhere must not destroy the line boundaries in the first paragraph.
        js("window.pocket.editor.model.change(w=>w.insertText('Added ',window.pocket.editor.model.document.getRoot().getChild(1),0))")
        compose.waitUntil(5_000) { saved.get() != null }
        val changed = snapshot()
        val body = Jsoup.parseBodyFragment(changed).body()
        assertEquals(listOf("CmdAlpha", "CmdBeta"), body.select("code").map { it.text() })
        assertEquals(listOf("https://example.org/a", "#root/target"), body.select("a").map { it.attr("href") })
        assertTrue(body.select("p").last()!!.text().contains("Added Other block"))
        saved.set(null); calls.set(0)
        compose.runOnIdle { html = changed; editing = false; key = "literal-lines-reopen" }
        ready()
        assertVisualLines("#preview", listOf("CmdAlpha", "CmdBeta", "LinkGamma", "LinkDelta"), listOf(1, 2, 1))
        assertEquals(changed, snapshot())
        assertEquals(0, calls.get())
        compose.runOnIdle { editing = true }
        editorReady()
        assertVisualLines("#editor-host .ck-content", listOf("CmdAlpha", "CmdBeta", "LinkGamma", "LinkDelta"), listOf(1, 2, 1))
        assertEquals(changed, snapshot())
        assertEquals(0, calls.get())
    }

    @Test fun bareHtmlTextKeepsLiteralLines() {
        val original = "BareAlpha\nBareBeta\n\nBareGamma"
        compose.setContent { MaterialTheme {
            RichNoteEditor("bare-lines", original, isEditing = false, modifier = Modifier.fillMaxSize(), onHtmlChanged = {})
        } }
        ready()
        assertVisualLines("#preview", listOf("BareAlpha", "BareBeta", "BareGamma"), listOf(1, 2))
        assertEquals(original, snapshot())
    }

    @Test fun preformattedCodeAndAnExplicitBreakWithFormattingNewlineAreNotDoubled() {
        val original = "<pre><code>PreAlpha\nPreBeta\n\nPreGamma</code></pre>" +
            "<p>ExplicitAlpha<br>\nExplicitBeta</p><p style='white-space:normal'>NormalAlpha\nNormalBeta</p>"
        compose.setContent { MaterialTheme {
            RichNoteEditor("existing-breaks", original, isEditing = false, modifier = Modifier.fillMaxSize(), onHtmlChanged = {})
        } }
        ready()
        assertVisualLines("#preview pre", listOf("PreAlpha", "PreBeta", "PreGamma"), listOf(1, 2))
        assertVisualLines("#preview p", listOf("ExplicitAlpha", "ExplicitBeta"), listOf(1))
        assertEquals("0", js("document.querySelectorAll('#preview pre br').length"))
        val top = positions("#preview p[style]", listOf("NormalAlpha", "NormalBeta"))
        assertEquals(top[0], top[1], 1.0)
        assertEquals(original, snapshot())
    }

    @Test fun markdownSoftBreakUsesItsOwnRendererWithoutAnAdditionalHtmlBreak() {
        val original = "MarkdownAlpha\nMarkdownBeta"
        compose.setContent { MaterialTheme {
            RichNoteEditor("markdown-lines", original, initialMarkdown = original, isEditing = false,
                modifier = Modifier.fillMaxSize(), onHtmlChanged = {})
        } }
        ready()
        assertVisualLines("#preview", listOf("MarkdownAlpha", "MarkdownBeta"), listOf(1))
        assertEquals(original, snapshot())
    }

    @Test fun blockFormattingWhitespaceDoesNotCreateExtraLines() {
        val original = "\n<div>\n  <p><span>FormatAlpha</span> <strong>FormatBeta</strong></p>\n  " +
            "<p>FormatGamma</p>\n</div>\n<ul>\n  <li>ItemAlpha</li>\n  <li>ItemBeta</li>\n</ul>\n" +
            "<table>\n<tbody>\n<tr>\n<td>CellAlpha</td>\n<td>CellBeta</td>\n</tr>\n</tbody>\n</table>\n" +
            "<dl>\n<dt>TermAlpha</dt>\n<dd>DefinitionBeta</dd>\n</dl>\n"
        compose.setContent { MaterialTheme {
            RichNoteEditor("formatting-lines", original, isEditing = false, modifier = Modifier.fillMaxSize(), onHtmlChanged = {})
        } }
        ready()
        assertEquals("0", js("document.querySelectorAll('#preview br').length"))
        val positions = positions("#preview p", listOf("FormatAlpha", "FormatBeta"))
        assertEquals("Inline formatting must not become separate lines", positions[0], positions[1], 1.0)
        assertEquals(original, snapshot())
    }

    @Test fun definitionListFormattingWhitespaceStaysStructural() {
        val original = "<dl>\n<dt>TermAlpha</dt>\n<dd>DefinitionBeta</dd>\n</dl>"
        compose.setContent { MaterialTheme {
            RichNoteEditor("definition-lines", original, isEditing = false,
                modifier = Modifier.fillMaxSize(), onHtmlChanged = {})
        } }
        ready()
        assertEquals("0", js("document.querySelectorAll('#preview br').length"))
        assertEquals(original, snapshot())
    }

    /** Optional owner notes stay in app-private storage and assertion messages never include their content. */
    @Test fun optionalPrivateHtmlLinesSurviveAnUnrelatedEditAndReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.filesDir, "private-editor-fixtures.json")
        assumeTrue("Private corpus must be injected into app-private storage", fixture.isFile)
        val cases = JSONArray(fixture.readText())
        val saved = AtomicReference<String?>()
        val calls = AtomicInteger()
        var key by mutableStateOf("private-initial")
        var html by mutableStateOf("")
        var editing by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            RichNoteEditor(key, html, isEditing = editing, modifier = Modifier.fillMaxSize(),
                onHtmlChanged = { saved.set(it); calls.incrementAndGet() })
        } }
        repeat(cases.length()) { index ->
            val item = cases.getJSONObject(index)
            assumeTrue("Line fixture must be HTML", !item.getBoolean("markdown"))
            val original = item.getString("body")
            saved.set(null); calls.set(0)
            compose.runOnIdle { html = original; editing = false; key = "private-$index" }
            ready()
            assertPrivateLines(original, "#preview", index)
            capturePrivateScreenshot(index, "reader")
            assertTrue("Private no-op source mismatch at case $index", snapshot() == original)
            assertEquals("Private reader wrote case $index", 0, calls.get())
            compose.runOnIdle { editing = true }
            editorReady()
            assertPrivateLines(original, "#editor-host .ck-content", index)
            assertTrue("Private mode switch source mismatch at case $index", snapshot() == original)
            assertEquals("Private mode switch wrote case $index", 0, calls.get())
            js("window.pocket.editor.model.change(w=>{const p=w.createElement('paragraph');w.insert(p,window.pocket.editor.model.document.getRoot(),'end');w.insertText('PrivateQaMarker',p,0)})")
            compose.waitUntil(5_000) { saved.get() != null }
            val changed = snapshot()
            assertTrue("Private edit missing at case $index", changed.contains("PrivateQaMarker"))
            calls.set(0)
            compose.runOnIdle { html = changed; editing = false; key = "private-reopen-$index" }
            ready()
            assertPrivateLines(original, "#preview", index)
            capturePrivateScreenshot(index, "reopen")
            assertTrue("Private reopen source mismatch at case $index", snapshot() == changed)
            assertEquals("Private reopen wrote case $index", 0, calls.get())
        }
    }

    private fun capturePrivateScreenshot(index: Int, stage: String) {
        if (InstrumentationRegistry.getArguments().getString("capturePrivateLineScreenshots") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        var found: WebView? = null
        compose.runOnIdle {
            fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            found = find(activity.window.decorView)
        }
        val web = requireNotNull(found)
        compose.waitUntil(15_000) {
            var visibleAndFocused = false
            compose.runOnIdle { visibleAndFocused = web.alpha >= .99f && web.isShown && web.hasWindowFocus() }
            visibleAndFocused
        }
        val painted = CountDownLatch(1)
        compose.runOnIdle {
            web.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    web.invalidate()
                    web.postOnAnimation { web.postOnAnimation { painted.countDown() } }
                }
            })
        }
        assertTrue("Private screenshot paint timed out at case $index", painted.await(10, TimeUnit.SECONDS))
        compose.runOnIdle {
            assertTrue("Private screenshot lost visible window focus at case $index",
                web.alpha >= .99f && web.isShown && web.hasWindowFocus())
        }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.filesDir, "private-lines-$index-$stage.png").outputStream().use {
                assertTrue("Private screenshot encode failed at case $index", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun assertPrivateLines(original: String, selector: String, index: Int) {
        // Only numeric geometry leaves WebView. Full text is used solely to locate original line starts.
        val geometry = JSONObject(JSONTokener(js("""(()=>{
            const source=document.createElement('template');source.innerHTML=${JSONObject.quote(original)};
            const lines=source.content.querySelector('p').textContent.split('\n');
            const root=document.querySelector(${JSONObject.quote(selector)}+' p');
            const walk=document.createTreeWalker(root,NodeFilter.SHOW_TEXT);const nodes=[];let node,offset=0;
            while(node=walk.nextNode()){nodes.push({node,start:offset});offset+=node.data.length;}
            const text=nodes.map(item=>item.node.data).join('');const tops=[],ends=[],steps=[];let cursor=0,previous=-1;
            lines.forEach((line,lineIndex)=>{line=line.trim();if(!line)return;
                const at=text.indexOf(line,cursor);if(at<0)throw new Error('Private line missing');
                const item=nodes.find(item=>at>=item.start&&at<item.start+item.node.data.length);
                const range=document.createRange();range.setStart(item.node,at-item.start);range.setEnd(item.node,at-item.start+1);
                tops.push(range.getBoundingClientRect().top);
                const last=at+line.length-1;const end=nodes.find(item=>last>=item.start&&last<item.start+item.node.data.length);
                range.setStart(end.node,last-end.start);range.setEnd(end.node,last-end.start+1);ends.push(range.getBoundingClientRect().top);
                if(previous>=0)steps.push(lineIndex-previous);
                previous=lineIndex;cursor=at+line.length;});
            return JSON.stringify({tops,ends,steps,height:parseFloat(getComputedStyle(root).lineHeight)});})()""")).nextValue() as String)
        val top = geometry.getJSONArray("tops")
        val ends = geometry.getJSONArray("ends")
        val steps = geometry.getJSONArray("steps")
        assertTrue("Private line fixture has too few lines at case $index", top.length() >= 2)
        repeat(steps.length()) { line ->
            assertEquals("Private visible line distance at case $index line $line",
                steps.getInt(line) * geometry.getDouble("height"), top.getDouble(line + 1) - ends.getDouble(line), 4.0)
        }
    }

    private fun assertVisualLines(selector: String, words: List<String>, expectedSteps: List<Int>) {
        val top = positions(selector, words)
        val lineHeight = js("parseFloat(getComputedStyle(document.querySelector(${JSONObject.quote(selector)})).lineHeight)").toDouble()
        expectedSteps.forEachIndexed { index, steps ->
            assertEquals("Visible line distance for synthetic tokens ${words[index]} and ${words[index + 1]}",
                steps * lineHeight, top[index + 1] - top[index], 4.0)
        }
    }

    private fun positions(selector: String, words: List<String>): List<Double> = words.map { word ->
        js("""(()=>{const root=document.querySelector(${JSONObject.quote(selector)});
            const walk=document.createTreeWalker(root,NodeFilter.SHOW_TEXT);let node;
            while(node=walk.nextNode()){const start=node.data.indexOf(${JSONObject.quote(word)});if(start>=0){
                const range=document.createRange();range.setStart(node,start);range.setEnd(node,start+${word.length});
                return range.getBoundingClientRect().top;}}return null})()""").let {
            assertNotEquals("Synthetic token missing: $word", "null", it); it.toDouble()
        }
    }

    private fun ready() = compose.waitUntil(30_000) { js("Boolean(window.pocket?.ready)") == "true" }
    private fun editorReady() = compose.waitUntil(30_000) { js("Boolean(window.pocket.editor && !window.pocket.editor.isReadOnly)") == "true" }
    private fun snapshot() = JSONObject(JSONTokener(js("window.pocket.snapshot()")).nextValue() as String).getString("html")
    private fun js(expression: String): String {
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        compose.runOnIdle {
            fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            requireNotNull(find(activity.window.decorView)).evaluateJavascript(expression) { result.set(it); done.countDown() }
        }
        assertTrue("WebView callback timed out", done.await(10, TimeUnit.SECONDS))
        return result.get()
    }
}
