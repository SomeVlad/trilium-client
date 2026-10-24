package ru.vlad.triliumnotes.editor

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Uses the native language bridge and real bundled CKEditor, including its initialization failure path. */
@RunWith(Parameterized::class)
class EditorLocalizationTest(private val systemLocale: String, private val language: String) {
    @get:Rule val compose = createComposeRule()
    private val languagePreferences get() = InstrumentationRegistry.getInstrumentation().targetContext
        .applicationContext.getSharedPreferences("app-language", Context.MODE_PRIVATE)
    private var previousChoice: String? = null

    @Before fun useAutomaticLanguageForLocaleMatrix() {
        previousChoice = languagePreferences.getString("language", null)
        languagePreferences.edit().remove("language").commit()
    }

    @After fun restoreLanguageChoice() {
        val edit = languagePreferences.edit()
        previousChoice?.let { edit.putString("language", it) } ?: edit.remove("language")
        edit.commit()
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0} → {1}")
        fun languages(): List<Array<String>> = listOf(
            arrayOf("en-US", "en"), arrayOf("ru-RU", "ru"), arrayOf("fr-FR,en-US", "ru"),
        )
    }

    private fun translated(english: String, russian: String) = if (language == "en") english else russian

    private fun mount(html: String, title: String = "", editing: () -> Boolean, onChanged: (String) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(systemLocale))
        }
        val context = base.createConfigurationContext(configuration)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) {
                MaterialTheme {
                    RichNoteEditor("localized", html, title = title, isEditing = editing(),
                        modifier = Modifier.fillMaxSize().imePadding(), onTitleChanged = onChanged, onHtmlChanged = onChanged)
                }
            }
        }
    }

    @Test fun languageBridgeLocalizesEditorControlsAndPreservesBilingualSource() {
        val original = "<p class='keep'>Название / Title &amp; текст</p>" +
            "<iframe src='https://example.org/video'></iframe><custom-widget data-test='keep'>User text</custom-widget>" +
            "<div data-url='https://example.org/preview'></div>"
        val title = "Без названия / Untitled"
        val writes = AtomicInteger()
        var editing by mutableStateOf(false)
        mount(original, title, { editing }) { writes.incrementAndGet() }
        ready()
        assertJsEquals(language, "window.pocketOptions.language")
        assertJsEquals(language, "document.documentElement.lang")
        assertJsEquals(translated("Untitled", "Без названия"), "document.getElementById('title').placeholder")
        assertJsEquals(translated("Note title", "Название заметки"), "document.getElementById('title').getAttribute('aria-label')")
        assertJsEquals(title, "document.getElementById('title').value")
        assertJsEquals(translated("Embedded material", "Встроенный материал"), "document.querySelector('[data-pocket-embed-url] strong').textContent")
        assertJsEquals(translated("Embedded HTML", "Встроенный HTML") + " · custom-widget",
            "document.querySelector('#preview [data-pocket-preserved]:not([data-pocket-embed-url])').textContent")
        assertJsEquals("\"" + translated("Embedded material", "Встроенный материал") + "\"",
            "getComputedStyle(document.querySelector('#preview [data-url]'), '::before').content")
        assertEquals(original, snapshot())

        compose.runOnIdle { editing = true }
        ready(expectEditor = true)
        assertJsEquals(language, "window.pocket.editor.locale.uiLanguage")
        assertJsEquals(translated("Title", "Название"), "document.getElementById('title').placeholder")
        assertJsEquals(translated("Bold", "Жирный"), "window.pocket.editor.ui.componentFactory.create('bold').label")
        assertJsEquals(translated("Link URL", "Ссылка URL"), "window.pocket.editor.locale.t('Link URL')")
        assertJsEquals(translated("Save", "Сохранить"), "window.pocket.editor.locale.t('Save')")
        assertJsEquals(translated("Heading 6", "Заголовок 6"), "window.pocket.editor.config.get('heading.options')[6].title")
        waitForToolbar()
        screenshot("editor-content")
        js("window.pocket.editor.plugins.get('SourceEditing').isSourceEditingMode=true")
        compose.waitUntil(5_000) { js("Boolean(document.querySelector('.ck-source-editing-area textarea'))") == "true" }
        js("window.pocket.editor.plugins.get('SourceEditing').isSourceEditingMode=false;window.pocket.flush()")
        assertEquals(original, snapshot())
        assertJsEquals(title, "document.getElementById('title').value")
        assertEquals("UI localization and a source-mode round trip must never report an edit", 0, writes.get())
    }

    @Test fun emptyNoteShowsLocalizedBodyPlaceholderWithoutWritingContent() {
        val writes = AtomicInteger()
        mount("", editing = { true }) { writes.incrementAndGet() }
        ready(expectEditor = true)
        assertJsEquals(translated("Note text…", "Текст заметки…"), "document.querySelector('.ck-placeholder').dataset.placeholder")
        assertEquals("", snapshot())
        assertEquals(0, writes.get())
        waitForToolbar()
        screenshot("editor-empty")
        // Open a real UI dropdown, rather than relying only on locale dictionary assertions.
        js("""(() => {
            const heading = document.querySelector('.ck-heading-dropdown');
            if (!heading || !heading.getClientRects().length)
                document.querySelector('.ck-toolbar__grouped-dropdown > button').click();
        })()""")
        // CKEditor creates the overflow toolbar lazily. Query its heading only after it renders.
        compose.waitUntil(5_000) {
            js("Boolean(document.querySelector('.ck-heading-dropdown')?.getClientRects().length)") == "true"
        }
        js("document.querySelector('.ck-heading-dropdown > button').click()")
        compose.waitUntil(5_000) {
            js("Boolean(document.querySelector('.ck-heading-dropdown .ck-dropdown__panel-visible')?.getClientRects().length)") == "true"
        }
        screenshot("editor-headings")
    }

    @Test fun savedLanguageOverridesSystemLocaleWithoutChangingBilingualContent() {
        val chosen = if (language == "en") "ru" else "en"
        languagePreferences.edit().putString("language", chosen).commit()
        val original = "<p class='keep'>Название / Title &amp; текст</p>" +
            "<iframe src='https://example.org/video'></iframe><custom-widget data-test='keep'>User text</custom-widget>" +
            "<div data-url='https://example.org/preview'></div>"
        val title = "Без названия / Untitled"
        val writes = AtomicInteger()
        mount(original, title, editing = { true }) { writes.incrementAndGet() }
        ready(expectEditor = true)
        assertJsEquals(chosen, "window.pocketOptions.language")
        assertJsEquals(chosen, "document.documentElement.lang")
        assertJsEquals(chosen, "window.pocket.editor.locale.uiLanguage")
        assertJsEquals(title, "document.getElementById('title').value")
        js("window.pocket.flush()")
        assertEquals(original, snapshot())
        assertEquals("An explicit UI language must never translate or write note data", 0, writes.get())
    }

    @Test fun failedBundleShowsLocalizedFallbackAndKeepsExactSourceUntilAnActualEdit() {
        val original = "<p class='keep'>Exact &amp; исходник</p>\n<!-- keep spacing -->"
        val saved = AtomicReference<String>()
        var editing by mutableStateOf(false)
        mount(original, "Личное / Personal", { editing }) { saved.set(it) }
        ready()
        // HTML reading does not need the bundle. Reject its first load at the DOM boundary.
        assertJsEquals("undefined", "typeof window.CKEDITOR")
        js("""(() => {
            const append = document.head.append.bind(document.head);
            document.head.append = (...nodes) => nodes.forEach(node => {
                if (node.tagName === 'SCRIPT' && node.src.endsWith('/vendor/ckeditor5.umd.js'))
                    queueMicrotask(() => node.dispatchEvent(new Event('error')));
                else append(node);
            });
        })()""")
        compose.runOnIdle { editing = true }
        compose.waitUntil(10_000) { js("Boolean(window.pocket?.ready && !document.getElementById('fallback').hidden)") == "true" }
        assertJsEquals(translated("Could not open the visual editor. The source text is available below.",
            "Не удалось открыть визуальный редактор. Исходный текст доступен ниже."), "document.getElementById('error').textContent")
        assertJsEquals(translated("Source text", "Исходный текст"), "document.getElementById('fallback').getAttribute('aria-label')")
        assertJsEquals(original, "document.getElementById('fallback').value")
        assertEquals(original, snapshot())
        js("window.pocket.flush()")
        assertNull("Opening the source fallback must not report an edit", saved.get())
        screenshot("fallback")
        js("document.getElementById('fallback').value += 'Изменение / Edit';document.getElementById('fallback').dispatchEvent(new Event('input',{bubbles:true}))")
        compose.waitUntil(5_000) { saved.get() != null }
        assertEquals(original + "Изменение / Edit", saved.get())
    }

    private fun ready(expectEditor: Boolean = false) {
        compose.waitUntil(30_000) {
            js(if (expectEditor) "Boolean(window.pocket?.ready && window.pocket.editor)" else "Boolean(window.pocket?.ready)") == "true"
        }
    }

    private fun assertJsEquals(expected: String, expression: String) = assertEquals(expected, JSONTokener(js(expression)).nextValue())
    private fun snapshot(): String = JSONObject(JSONTokener(js("window.pocket.snapshot()")).nextValue() as String).getString("html")

    private fun waitForToolbar() {
        compose.waitUntil(15_000) {
            js("""(() => {
                const bar = document.querySelector('.ck-toolbar');
                if (!bar || !document.body.classList.contains('body-focus') || !window.pocketOptions.keyboardVisible) return false;
                const rect = bar.getBoundingClientRect();
                return rect.height >= 48 && rect.top >= 0 && rect.bottom <= visualViewport.height + visualViewport.offsetTop;
            })()""") == "true"
        }
    }

    private fun screenshot(screen: String) {
        compose.waitUntil(10_000) {
            var visible = false
            compose.runOnIdle { visible = webView().alpha == 1f && webView().hasWindowFocus() }
            visible
        }
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
        assertTrue("Localized WebView did not commit its visible frame", painted.await(10, TimeUnit.SECONDS))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "localization-review")
            assertTrue(directory.isDirectory || directory.mkdirs())
            File(directory, "$screen-${systemLocale.substringBefore(',')}.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { image.recycle() }
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
        compose.runOnIdle {
            webView().evaluateJavascript(expression) {
                result.set(it); completed.countDown()
            }
        }
        assertTrue("WebView JavaScript callback timed out", completed.await(10, TimeUnit.SECONDS))
        return result.get()
    }
}
