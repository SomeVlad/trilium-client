package ru.vlad.triliumnotes.editor

import android.annotation.SuppressLint
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import ru.vlad.triliumnotes.AppLanguage

/** Loaded on WebView's request thread, using native authenticated transport and cache. */
data class EditorResource(val mimeType: String, val bytes: ByteArray)

class RichNoteEditorController {
    internal var action: ((() -> Unit) -> Unit)? = null
    fun flush(onComplete: () -> Unit) { action?.invoke(onComplete) ?: onComplete() }
}

@Composable
fun rememberRichNoteEditorController(): RichNoteEditorController = remember { RichNoteEditorController() }

private class DeliveryState(val initialHtml: String, val initialTitle: String) {
    var html = initialHtml
    var title = initialTitle
    var htmlRevision = -1L
    var titleRevision = -1L
    var editingApplied = false
}

private class EditorPresentation {
    var disposed = false
    var visualReady = false
}

/** Only bundled editor code is executed; documents receive no credentials or network access. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RichNoteEditor(
    noteKey: String,
    initialHtml: String,
    title: String = "",
    initialFocusTitle: Boolean = false,
    initialMarkdown: String? = null,
    readOnly: Boolean = false,
    isEditing: Boolean = true,
    modifier: Modifier = Modifier,
    onTitleChanged: (String) -> Unit = {},
    onHtmlChanged: (String) -> Unit,
    onOpenNote: (String) -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    resourceLoader: (String) -> EditorResource? = { null },
    serverUrl: String = "",
    controller: RichNoteEditorController? = null,
) {
    val context = LocalContext.current
    // Observe configuration changes as well as the context used to create this WebView.
    val configuration = LocalConfiguration.current
    val language = remember(context, configuration) {
        AppLanguage.language(context)
    }
    key(noteKey, language) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val colors = MaterialTheme.colorScheme
        val latestTitle = rememberUpdatedState(onTitleChanged)
        val latestHtml = rememberUpdatedState(onHtmlChanged)
        val latestOpenNote = rememberUpdatedState(onOpenNote)
        val latestOpenLink = rememberUpdatedState(onOpenLink)
        val latestResourceLoader = rememberUpdatedState(resourceLoader)
        val latestServerUrl = rememberUpdatedState(serverUrl)
        val activeController = controller ?: rememberRichNoteEditorController()
        val delivery = remember { DeliveryState(initialHtml, title) }
        fun deliverHtml(value: String, revision: Long) {
            if (revision < delivery.htmlRevision) return
            delivery.htmlRevision = revision
            if (value != delivery.html) { delivery.html = value; latestHtml.value(value) }
        }
        fun deliverTitle(value: String, revision: Long) {
            if (revision < delivery.titleRevision) return
            delivery.titleRevision = revision
            if (value != delivery.title) { delivery.title = value; latestTitle.value(value) }
        }
        val theme = JSONObject()
            .put("language", language)
            .put("background", cssColor(colors.background.toArgb()))
            .put("foreground", cssColor(colors.onBackground.toArgb()))
            .put("muted", cssColor(colors.onSurfaceVariant.toArgb()))
            .put("surface", cssColor(colors.surfaceContainer.toArgb()))
            .put("border", cssColor(colors.outlineVariant.toArgb()))
            .put("accent", cssColor(colors.primary.toArgb()))
            .put("editing", isEditing && !readOnly)
            .put("keyboardVisible", WindowInsets.ime.getBottom(LocalDensity.current) > 0).toString()
        val initialTheme = remember { theme }
        val initialDocument = remember { JSONObject().put("html", initialHtml).put("title", title)
            .put("focusTitle", initialFocusTitle).put("markdown", initialMarkdown)
            .put("options", JSONObject(initialTheme)).toString() }
        val initialThemeVariables = remember {
            val options = JSONObject(initialTheme)
            listOf("background", "foreground", "muted", "surface", "border", "accent").joinToString(";") { name ->
                val color = options.getString(name)
                require(color.matches(Regex("#[0-9a-fA-F]{6}")))
                "--$name:$color"
            }
        }
        val presentation = remember { EditorPresentation() }
        val latestTheme = rememberUpdatedState(theme)
        val webView = remember {
            WebView(context).apply {
                setBackgroundColor(colors.background.toArgb())
                alpha = 0f
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                isVerticalScrollBarEnabled = false
                addJavascriptInterface(object {
                    @JavascriptInterface fun initialDocument(): String = initialDocument
                    @JavascriptInterface fun editorReady() {
                        post {
                            if (presentation.disposed) return@post
                            // The cached preview and the lazily started editor each announce
                            // readiness. Keep the preview visible while the editor starts.
                            evaluateJavascript("window.pocketOptions=${latestTheme.value};window.pocket?.configure(window.pocketOptions)") {
                                if (!presentation.disposed) postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                                    override fun onComplete(requestId: Long) {
                                        if (presentation.disposed) return
                                        invalidate()
                                        postOnAnimation {
                                            if (!presentation.disposed) {
                                                presentation.visualReady = true
                                                alpha = 1f
                                                if (JSONObject(latestTheme.value).optBoolean("editing"))
                                                    focusEditorAndShowKeyboard(latestTheme.value) { !presentation.disposed && presentation.visualReady && JSONObject(latestTheme.value).optBoolean("editing") }
                                            }
                                        }
                                    }
                                })
                            }
                        }
                    }
                    @JavascriptInterface fun htmlChanged(value: String, revision: Long) { post { deliverHtml(value, revision) } }
                    @JavascriptInterface fun titleChanged(value: String, revision: Long) { post { deliverTitle(value, revision) } }
                    @JavascriptInterface fun openNote(value: String) {
                        EditorResourcePolicy.noteId(value)?.let { id -> post { if (!presentation.disposed) latestOpenNote.value(id) } }
                    }
                    @JavascriptInterface fun openLink(value: String) {
                        val scheme = try { java.net.URI(value).scheme } catch (_: Exception) { null }
                        if (scheme == null || scheme in setOf("https", "http", "mailto", "tel")) post { if (!presentation.disposed) latestOpenLink.value(value) }
                    }
                }, "PocketNative")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val uri = request.url
                        if (uri.scheme == "https" && uri.host == EditorResourcePolicy.HOST &&
                            uri.path?.startsWith("/editor/") == true && uri.query == null) {
                            val path = uri.path!!.removePrefix("/")
                            if (path.split('/').any { it == ".." || it == "." }) return blocked()
                            return try {
                                val mime = when (path.substringAfterLast('.')) {
                                    "js" -> "text/javascript"
                                    "css" -> "text/css"
                                    "html" -> "text/html"
                                    else -> return blocked()
                                }
                                val stream = if (path == "editor/index.html") {
                                    val html = context.assets.open(path).bufferedReader().use { it.readText() }
                                    ByteArrayInputStream(html.replace("<html lang=\"ru\">",
                                        "<html lang=\"$language\" style=\"$initialThemeVariables\">").toByteArray(Charsets.UTF_8))
                                } else context.assets.open(path)
                                WebResourceResponse(mime, "UTF-8", stream)
                            } catch (_: Exception) { blocked() }
                        }
                        val resourceUrl = if (uri.scheme == "https" && uri.host == EditorResourcePolicy.HOST &&
                            uri.path == "/_note_resource") uri.getQueryParameter("url") else null
                        if (request.method == "GET" && resourceUrl != null && EditorResourcePolicy.isResourceUrl(resourceUrl, latestServerUrl.value)) {
                            val resource = try { latestResourceLoader.value(resourceUrl) } catch (_: Exception) { null }
                            if (resource != null) return WebResourceResponse(resource.mimeType, null, ByteArrayInputStream(resource.bytes))
                        }
                        return blocked()
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        if (!presentation.disposed && url == "https://${EditorResourcePolicy.HOST}/editor/index.html") {
                            if (presentation.visualReady && JSONObject(latestTheme.value).optBoolean("editing"))
                                view.focusEditorAndShowKeyboard(latestTheme.value) { !presentation.disposed && presentation.visualReady && JSONObject(latestTheme.value).optBoolean("editing") }
                            else view.evaluateJavascript("window.pocketOptions=${latestTheme.value};window.pocket?.configure(window.pocketOptions)", null)
                        }
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        EditorResourcePolicy.noteId(request.url.toString())?.let { latestOpenNote.value(it) }
                        return true
                    }
                }
                loadUrl("https://${EditorResourcePolicy.HOST}/editor/index.html")
            }
        }
        AndroidView(
            factory = { webView },
            modifier = modifier.testTag(if (isEditing && !readOnly) "note-editor" else "note-reader"),
        )
        LaunchedEffect(webView, theme) {
            // Keep the window's hardware renderer: a forced software layer distorts
            // CKEditor's placeholder pseudo-element even when its CSS metrics are correct.
            val entering = isEditing && !readOnly && !delivery.editingApplied
            delivery.editingApplied = isEditing && !readOnly
            webView.setBackgroundColor(colors.background.toArgb())
            if (entering && presentation.visualReady)
                webView.focusEditorAndShowKeyboard(theme) { !presentation.disposed && presentation.visualReady && JSONObject(latestTheme.value).optBoolean("editing") }
            else webView.evaluateJavascript("window.pocketOptions=$theme;window.pocket?.configure(window.pocketOptions)", null)
        }
        DisposableEffect(webView, lifecycle, activeController) {
            val flush: (() -> Unit) -> Unit = { complete ->
                webView.evaluateJavascript("window.pocket?.snapshot()") { result ->
                    try {
                        val raw = JSONTokener(result).nextValue()
                        if (raw is String) {
                            val snapshot = JSONObject(raw)
                            deliverHtml(snapshot.getString("html"), snapshot.getLong("htmlRevision"))
                            deliverTitle(snapshot.getString("title"), snapshot.getLong("titleRevision"))
                        }
                    } finally { complete() }
                }
            }
            activeController.action = flush
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                    flush {}
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                presentation.disposed = true
                lifecycle.removeObserver(observer)
                if (activeController.action === flush) activeController.action = null
                webView.removeJavascriptInterface("PocketNative")
                webView.destroy()
            }
        }
    }
}

private fun WebView.focusEditorAndShowKeyboard(options: String, canFocus: () -> Boolean) {
    if (!canFocus()) return
    requestFocus()
    // The DOM input must own focus before Android creates its input connection.
    // Early mode configuration can run before CKEditor is ready; editorReady retries it.
    evaluateJavascript("window.pocketOptions=$options;window.pocket?.configure(window.pocketOptions);window.pocket?.focusForEditing()") { focused ->
        if (focused == "true" && canFocus()) postOnAnimation {
            if (!canFocus()) return@postOnAnimation
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}

private fun cssColor(argb: Int): String = "#%06x".format(argb and 0xffffff)
private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

/** Shared route boundary. The resource loader additionally validates the actual server origin. */
object EditorResourcePolicy {
    const val HOST = "appassets.androidplatform.net"
    fun isResourceUrl(value: String, serverUrl: String = ""): Boolean {
        val normalized = if (value.startsWith("/")) "https://$HOST$value" else if (!value.contains("://")) "https://$HOST/$value" else value
        val uri = try { java.net.URI(normalized) } catch (_: Exception) { return false }
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.rawFragment != null) return false
        var path = uri.path ?: return false
        if (path.contains("..") || path.contains('\\')) return false
        if (uri.host != HOST && !uri.host.isNullOrBlank()) return true
        val basePath = runCatching { java.net.URI(serverUrl).path }.getOrNull().orEmpty().trimEnd('/')
        if (basePath.isNotEmpty() && path.startsWith("$basePath/")) path = path.removePrefix(basePath)
        return Regex("/(?:assets/v[0-9.]+/doc_notes/[^?#]+|api/images/[A-Za-z0-9_-]+/[^/]+|api/attachments/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/[^/]+|api/attachments/[A-Za-z0-9_-]+/download|api/notes/[A-Za-z0-9_-]+/download|attachments/[A-Za-z0-9_-]+/[^/]+)").matches(path)
    }
    fun noteId(value: String): String? {
        val fragment = value.substringAfter('#', "")
        val path = when {
            fragment.startsWith("root/") -> fragment.substringAfterLast('/')
            value.startsWith("/") && !value.startsWith("/api/") -> value.substringBefore('?').substringAfterLast('/')
            else -> return null
        }
        return path.takeIf { it != "root" && it.matches(Regex("[A-Za-z0-9_-]+")) }
    }
}
