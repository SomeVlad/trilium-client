package ru.vlad.triliumnotes.editor

import android.util.Log
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
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.BuildConfig
import ru.vlad.triliumnotes.data.resolveResourceTarget
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Optional owner corpus, injected into app-private storage; never bundled, logged, or copied into reports. */
class PrivateFixtureRoundTripTest {
    @get:Rule val compose = createComposeRule()
    private fun webView(): WebView {
        var found: WebView? = null
        compose.runOnIdle {
            fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            found = find(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first().window.decorView)
        }
        return requireNotNull(found)
    }
    private fun js(expression: String): String {
        val done = CountDownLatch(1)
        val result = AtomicReference<String>()
        val web = webView()
        compose.runOnIdle { web.evaluateJavascript(expression) { result.set(it); done.countDown() } }
        assertTrue("Editor callback timed out", done.await(15, TimeUnit.SECONDS))
        return result.get()
    }
    private fun ready() = compose.waitUntil(30_000) { js("Boolean(window.pocket?.ready)") == "true" }
    private fun snapshot(): String {
        val outer = JSONTokener(js("window.pocket.snapshot()")).nextValue() as String
        return JSONObject(outer).getString("html")
    }
    private fun words(value: String) = Regex("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*").findAll(value.replace(MARKER, "")).map { it.value }.toList()
    private fun normalized(value: String) = value.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
    private fun body(value: String) = Jsoup.parseBodyFragment(value).body()
    private fun signature(element: Element): String = element.tagName() + element.attributes().asList()
        .sortedBy { it.key }.joinToString { attribute -> attribute.key + "=" +
            if (attribute.key == "class") attribute.value.split(Regex("\\s+")).sorted().joinToString(" ") else attribute.value }
    private fun links(root: Element): Map<String, List<String>> {
        // The editor drops <a href="..."></a> without text, children, or named targets.
        // Compare actual linked meaning, allowing adjacent equal-target spans to merge.
        return root.select("a[href]").filter { anchor ->
            anchor.text().isNotBlank() || anchor.children().isNotEmpty() ||
                anchor.attributes().asList().any { it.key != "href" }
        }.groupBy { it.attr("href") }.mapValues { (_, anchors) -> anchors.flatMap { anchor ->
            words(anchor.text()) + anchor.select("img").map { "image:" + it.attr("src") } +
                listOf("id", "name", "title", "aria-label").filter { anchor.hasAttr(it) }.map { it + ":" + anchor.attr(it) }
        } }
    }
    private fun preservation(original: String, changed: String): Set<String> {
        val a = body(original); val b = body(changed)
        val failures = mutableSetOf<String>()
        if (words(a.text()) != words(b.text())) failures += "words"
        if (a.select("td,th").map { normalized(it.text()) } != b.select("td,th").map { normalized(it.text().replace(MARKER, "")) }) failures += "table-cells"
        if (a.select("td,th").map { it.attr("colspan") + ":" + it.attr("rowspan") } != b.select("td,th").map { it.attr("colspan") + ":" + it.attr("rowspan") }) failures += "table-spans"
        if (a.select("input[type=checkbox]").map { it.hasAttr("checked") } != b.select("input[type=checkbox]").map { it.hasAttr("checked") }) failures += "task-checked"
        if (a.select("img").map { it.attr("src") } != b.select("img").map { it.attr("src") }) failures += "image-src"
        if (links(a) != links(b)) failures += "linked-meaning"
        val embeds = "iframe,object,embed,video,audio,source,[data-embed-type],oembed"
        if (a.select(embeds).map { signature(it) } != b.select(embeds).map { signature(it) }) failures += "embed-attrs"
        if (changed.contains("/_note_resource")) failures += "resource-rewrite"
        return failures
    }

    @Test fun ownerInlineImagePathsResolveThroughTheActualNativeResourcePolicy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.filesDir, "private-editor-fixtures.json")
        assumeTrue("Owner corpus must be injected into app-private storage", fixture.isFile)
        val cases = JSONArray(fixture.readText())
        val internal = mutableListOf<String>()
        repeat(cases.length()) { index ->
            val item = cases.getJSONObject(index)
            if (!item.getBoolean("markdown")) body(item.getString("body")).select("img[src]").forEach {
                val src = it.attr("src")
                if (src.startsWith("api/") || src.startsWith("/api/")) internal += src
            }
        }
        assertTrue("Owner inline image corpus changed", internal.size == 6)
        var attachments = 0; var notes = 0; var escaped = 0; var ownerPaths = 0
        internal.forEach { path ->
            val target = resolveResourceTarget(path, BuildConfig.TRILIUM_BASE_URL)
            assertTrue("Owner inline image URL was rejected by native policy", target != null)
            if (target!!.attachment) attachments++ else notes++
            if (path.contains("%")) escaped++
            if (path.trimStart('/').split('/').size >= 5 && path.contains("api/attachments/")) ownerPaths++
        }
        Log.i("PrivateCorpusQA", "inlinePathsResolved=${internal.size} attachments=$attachments notes=$notes escaped=$escaped ownerPaths=$ownerPaths")
    }

    @Test fun ownerCorpusMountEditSerializeAndReopenPreservesContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.filesDir, "private-editor-fixtures.json")
        assumeTrue("Owner corpus must be injected into app-private storage", fixture.isFile)
        val cases = JSONArray(fixture.readText())
        val saved = AtomicReference<String?>()
        val calls = AtomicInteger()
        var key by mutableStateOf("initial")
        var html by mutableStateOf("")
        var markdown by mutableStateOf<String?>(null)
        var editing by mutableStateOf(false)
        compose.setContent { MaterialTheme { RichNoteEditor(key, html, title = "Fixture", initialMarkdown = markdown,
            isEditing = editing, modifier = Modifier.fillMaxSize(), onHtmlChanged = { saved.set(it); calls.incrementAndGet() }) } }
        val failures = sortedMapOf<String, Int>()
        var htmlCount = 0; var markdownCount = 0
        fun fail(category: String) { failures[category] = (failures[category] ?: 0) + 1 }
        val selected = InstrumentationRegistry.getArguments().getString("privateCaseIndices")?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet()
        repeat(cases.length()) { index ->
            if (selected != null && index !in selected) return@repeat
            val item = cases.getJSONObject(index)
            val original = item.getString("body")
            val isMarkdown = item.getBoolean("markdown")
            if (isMarkdown) markdownCount++ else htmlCount++
            saved.set(null); calls.set(0)
            compose.runOnIdle { html = original; markdown = if (isMarkdown) original else null; editing = false; key = "case-$index" }
            ready()
            if (calls.get() != 0) fail("mount-write")
            if (snapshot() != original) fail("mount-original")
            compose.runOnIdle { editing = true }
            compose.waitUntil(30_000) { js("Boolean(window.pocket.editor && !window.pocket.editor.isReadOnly)") == "true" }
            if (snapshot() != original || calls.get() != 0) fail("mode-switch-write")
            js("window.pocket.editor.model.change(w=>{const p=w.createElement('paragraph');w.insert(p,window.pocket.editor.model.document.getRoot(),'end');w.insertText('$MARKER',p,0)})")
            compose.waitUntil(5_000) { saved.get() != null }
            val changed = snapshot()
            if (!changed.contains(MARKER)) fail("missing-edit")
            if (!isMarkdown) preservation(original, changed).also { categories ->
                if (categories.isNotEmpty()) {
                    Log.i("PrivateCorpusQA", "case=$index categories=${categories.joinToString()}")
                    File(context.filesDir, "private-corpus-failure-$index.json").writeText(JSONObject().put("original", original).put("changed", changed).toString())
                }
            }.forEach { fail(it) }
            else {
                val rendered = JSONTokener(js("window.pocket.editor.data.get({rootName:'main'})")).nextValue() as String
                if (!rendered.contains(MARKER)) fail("markdown-edit")
                if (words(original) != words(changed)) fail("markdown-words")
                if (Regex("\\[[ xX]\\]").findAll(original).map { it.value.lowercase() }.toList() !=
                    Regex("\\[[ xX]\\]").findAll(changed).map { it.value.lowercase() }.toList()) fail("markdown-task-checked")
            }
            saved.set(null); calls.set(0)
            compose.runOnIdle { html = changed; markdown = if (isMarkdown) changed else null; editing = false; key = "reopen-$index" }
            ready()
            if (snapshot() != changed || calls.get() != 0) fail("reopen-original")
            if (!isMarkdown) preservation(original, snapshot()).forEach { fail("reopen-$it") }
            Log.i("PrivateCorpusQA", "completed=${index + 1}/${cases.length()}")
        }
        Log.i("PrivateCorpusQA", "html=$htmlCount markdown=$markdownCount outcomes=${JSONObject(failures as Map<*, *>)}")
        assertTrue("Owner corpus failed categories: ${failures.keys.joinToString()}", failures.isEmpty())
    }
    companion object { private const val MARKER = "PocketQaSyntheticMarker7741" }
}
