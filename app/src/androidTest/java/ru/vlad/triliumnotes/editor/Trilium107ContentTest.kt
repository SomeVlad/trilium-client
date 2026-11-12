package ru.vlad.triliumnotes.editor

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** HTML emitted by Trilium v0.107.0 content_embed and block_reference serializers. */
class Trilium107ContentTest {
    @get:Rule val compose = createComposeRule()

    private fun js(expression: String): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        compose.runOnIdle {
            fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            requireNotNull(find(activity.window.decorView)).evaluateJavascript(expression) {
                result.set(it); latch.countDown()
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    @Test fun embedsAndBlockIdsSurviveAnEditToTheSurroundingText() {
        val html = """
            <p data-trilium-block-id="AbCdEfGhIjKl">Before</p>
            <figure class="include-note" data-note-id="testNote1234" data-box-size="resizable" data-editable="true" data-hide-title="true" data-display-mode="split" data-position="left" style="--include-note-height:20em;--include-note-width:30em"><figcaption>Caption</figcaption></figure>
            <figure class="include-note" data-attachment-id="testAttach12" data-box-size="full"></figure>
            <figure class="include-note" data-note-id="excerptNote1" data-block="AbCdEfGhIjKl:MnOpQrStUvWx" data-box-size="full" data-editable="true"></figure>
            <p>After</p>
            <section class="include-note" data-note-id="legacyNote12"></section>
            <figure class="captioned-content-embed"><section class="link-embed" data-url="https://example.com/a.mp3" data-embed-type="audio" data-box-size="compact">&nbsp;</section><figcaption>Audio caption</figcaption></figure>
            <div class="trilium-tabs"><section class="trilium-tab"><p class="trilium-tab-title">One</p><div class="trilium-tab-panel"><p>Panel</p></div></section></div>
        """.trimIndent()
        val saved = AtomicReference<String>()
        compose.setContent { MaterialTheme { RichNoteEditor("trilium107", html,
            modifier = Modifier.fillMaxSize(), onHtmlChanged = { saved.set(it) }) } }
        compose.waitUntil(30_000) { js("Boolean(window.pocket?.editor)") == "true" }
        assertNull("Opening the editor must not rewrite the note", saved.get())
        js("window.pocket.editor.model.change(w => w.insertText('Changed ', window.pocket.editor.model.document.getRoot().getChild(0), 0))")
        compose.waitUntil(5_000) { saved.get() != null }
        println("TRILIUM107_EMBEDS=" + saved.get())
        val body = Jsoup.parseBodyFragment(saved.get()).body()
        assertEquals("Changed Before", body.selectFirst("p")!!.text())
        assertEquals("AbCdEfGhIjKl", body.selectFirst("p")!!.attr("data-trilium-block-id"))
        val original = Jsoup.parseBodyFragment(html).select("figure.include-note")
        val roundTrip = body.select("figure.include-note")
        assertEquals("An empty embed must not disappear", original.size, roundTrip.size)
        original.zip(roundTrip).forEach { (before, after) ->
            before.attributes().forEach { attribute ->
                if (attribute.key == "style") {
                    assertEquals(attribute.value.replace(" ", "").trimEnd(';'),
                        after.attr(attribute.key).replace(" ", "").trimEnd(';'))
                } else assertEquals(attribute.key, attribute.value, after.attr(attribute.key))
            }
            assertEquals(before.text(), after.text())
        }
        assertFalse(saved.get().contains("data-pocket-"))
        assertEquals("legacyNote12", body.selectFirst("section.include-note")?.attr("data-note-id"))
        assertEquals("https://example.com/a.mp3", body.selectFirst(".captioned-content-embed .link-embed")?.attr("data-url"))
        assertEquals("Audio caption", body.selectFirst(".captioned-content-embed figcaption")?.text())
        assertEquals("One", body.selectFirst(".trilium-tab-title")?.text())
        assertEquals("Panel", body.selectFirst(".trilium-tab-panel")?.text())
        assertFalse("Block embeds cannot be serialized inside a paragraph", Regex("<p\\b[^>]*>[^<]*<figure").containsMatchIn(saved.get()))
        // A second editor data roundtrip catches accidental serialization of display-only placeholders.
        js("window.pocket.editor.setData(${org.json.JSONObject.quote(saved.get())})")
        js("window.pocket.editor.model.change(w => w.insertText('Again ', window.pocket.editor.model.document.getRoot().getChild(0), 0))")
        compose.waitUntil(5_000) { saved.get()?.contains("Again ") == true }
        assertEquals(3, Jsoup.parseBodyFragment(saved.get()).select("figure.include-note").size)
        assertFalse(saved.get().contains("data-pocket-"))
    }

    @Test fun readingNewReferencesOpensTheNoteOrAttachmentWithoutRewritingContent() {
        val opened = AtomicReference<String>()
        val attachment = AtomicReference<String>()
        val saved = AtomicReference<String>()
        compose.setContent { MaterialTheme { RichNoteEditor("refs107", """
            <p><a href="#root/testNote1234?block=AbCdEfGhIjKl">Block link</a></p>
            <figure class="include-note" data-note-id="testNote1234"></figure>
            <figure class="include-note" data-attachment-id="testAttach12"></figure>
            <p><a href="#root/testNote1234?viewMode=attachments&amp;attachmentId=testAttach12">Attachment link</a></p>
        """.trimIndent(), isEditing = false, modifier = Modifier.fillMaxSize(),
            onOpenNote = { opened.set(it) }, onOpenLink = { attachment.set(it) }, onHtmlChanged = { saved.set(it) }) } }
        compose.waitUntil(10_000) { js("Boolean(window.pocket?.ready)") == "true" }
        capture("includes-light")
        js("document.querySelector('#preview a').click()")
        compose.waitUntil(5_000) { opened.get() == "testNote1234" }
        js("document.querySelectorAll('#preview .include-note')[1].click()")
        compose.waitUntil(5_000) { attachment.get() == "api/attachments/testAttach12/download" }
        attachment.set(null)
        js("document.querySelector('#preview a[href*=viewMode]').click()")
        compose.waitUntil(5_000) { attachment.get() == "api/attachments/testAttach12/download" }
        assertNull(saved.get())
    }

    @Test fun inlineSourceKeepsItsLanguageAndPresentationWhenOtherTextChanges() {
        val saved = AtomicReference<String>()
        val html = """<p>Before</p><pre data-box-size="resizable" data-display-mode="source" data-hide-title="true" data-editable="true" data-position="wrap-left" style="--include-note-height:12em;"><code class="language-mermaid">graph TD;
A--&gt;B</code></pre><pre class="extended-code-block" data-box-size="full" data-code-tab-width="2"><code class="language-text-x-python">print(1)</code></pre>"""
        compose.setContent { MaterialTheme { RichNoteEditor("source107", html,
            modifier = Modifier.fillMaxSize(), onHtmlChanged = { saved.set(it) }) } }
        compose.waitUntil(30_000) { js("Boolean(window.pocket?.editor)") == "true" }
        js("window.pocket.editor.model.change(w => w.insertText('Edited ', window.pocket.editor.model.document.getRoot().getChild(0), 0))")
        compose.waitUntil(5_000) { saved.get() != null }
        val after = Jsoup.parseBodyFragment(saved.get()).select("pre")
        println("TRILIUM107_SOURCE=" + saved.get())
        val before = Jsoup.parseBodyFragment(html).select("pre")
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (a, b) ->
            assertEquals(a.selectFirst("code")!!.className(), b.selectFirst("code")!!.className())
            assertEquals(a.wholeText(), b.wholeText())
            a.attributes().filter { it.key != "style" }.forEach { assertEquals(it.value, b.attr(it.key)) }
        }
    }

    @Test fun splittingABlockDoesNotDuplicateItsReferenceId() {
        val saved = AtomicReference<String>()
        compose.setContent { MaterialTheme { RichNoteEditor("block107",
            "<p data-trilium-block-id='AbCdEfGhIjKl'>Hello world</p>",
            modifier = Modifier.fillMaxSize(), onHtmlChanged = { saved.set(it) }) } }
        compose.waitUntil(30_000) { js("Boolean(window.pocket?.editor)") == "true" }
        js("window.pocket.editor.model.change(w => w.setSelection(window.pocket.editor.model.document.getRoot().getChild(0), 0));window.pocket.editor.execute('enter')")
        compose.waitUntil(5_000) { saved.get() != null }
        println("TRILIUM107_BLOCK=" + saved.get())
        val referenced = Jsoup.parseBodyFragment(saved.get()).select("[data-trilium-block-id=AbCdEfGhIjKl]")
        assertEquals("A reference id must identify one block after Enter", 1, referenced.size)
        assertEquals("Hello world", referenced.single().text())
        js("window.pocket.editor.model.change(w => w.insertText('Intro', window.pocket.editor.model.document.getRoot().getChild(0), 0))")
        compose.waitUntil(5_000) { saved.get()?.contains("Intro") == true }
        val afterTyping = Jsoup.parseBodyFragment(saved.get()).select("[data-trilium-block-id=AbCdEfGhIjKl]")
        assertEquals(1, afterTyping.size)
        assertEquals("Typing in the new paragraph cannot steal the reference", "Hello world", afterTyping.single().text())
        js("window.pocket.editor.execute('undo')")
        compose.waitUntil(5_000) { saved.get()?.contains("Intro") == false }
        assertEquals("Hello world", Jsoup.parseBodyFragment(saved.get()).selectFirst("[data-trilium-block-id]")!!.text())
    }

    @Test fun darkIncludedNotesHaveReadableLinks() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { RichNoteEditor("dark-includes107",
            "<p>Заметка с материалами</p><figure class='include-note' data-note-id='testNote1234'><figcaption>Подробности проекта</figcaption></figure>" +
                "<figure class='include-note' data-note-id='excerptNote1' data-block='example'></figure>" +
                "<figure class='include-note' data-attachment-id='testAttach12'></figure><p>Продолжение текста</p>",
            isEditing = false, modifier = Modifier.fillMaxSize(), onHtmlChanged = { fail("Reading changed content") }) } }
        compose.waitUntil(10_000) { js("Boolean(window.pocket?.ready)") == "true" }
        assertEquals("3", js("document.querySelectorAll('#preview .include-note[role=link]').length"))
        capture("includes-dark")
    }

    private fun capture(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        fun currentWebView(): WebView {
            fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            return requireNotNull(find(activity.window.decorView))
        }
        var nativeState = "not sampled"
        try {
            compose.waitUntil(10_000) {
                var visible = false
                compose.runOnIdle {
                    val web = currentWebView()
                    nativeState = "alpha:${web.alpha},shown:${web.isShown},size:${web.width}x${web.height}"
                    visible = web.alpha >= 0.99f && web.isShown && web.width > 0 && web.height > 0
                }
                visible
            }
        } catch (error: Throwable) {
            println("TRILIUM107_CAPTURE_NATIVE=$name:$nativeState")
            throw error
        }
        // DOM readiness precedes the production alpha/visual-state transition.
        val visual = CountDownLatch(1)
        compose.runOnIdle {
            currentWebView().postVisualStateCallback(107, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { visual.countDown() }
            })
        }
        assertTrue("WebView did not draw its visible content", visual.await(10, TimeUnit.SECONDS))
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val committed = CountDownLatch(1)
        compose.runOnIdle {
            val decor = currentWebView().rootView
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                decor.invalidate()
            } else {
                decor.postOnAnimation { decor.postOnAnimation { committed.countDown() } }
            }
        }
        assertTrue("The visible window frame was not committed", committed.await(10, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        val directory = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "compat107-content").apply { mkdirs() }
        requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
            java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
