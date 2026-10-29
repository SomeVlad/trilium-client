package ru.vlad.triliumnotes.diagrams

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import ru.vlad.triliumnotes.R
import ru.vlad.triliumnotes.AppLanguage
import java.io.ByteArrayInputStream

/** An offline renderer with no service references, server resources, credentials, or editing callbacks. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DiagramViewer(noteKey: String, kind: String, content: String, modifier: Modifier = Modifier,
    packages: DiagramPackages? = null,
) {
    require(kind == "mermaid" || kind == "canvas")
    val context = LocalContext.current
    val store = packages ?: remember(context.applicationContext) { DiagramPackages.get(context) }
    val packageRevision by store.changes.collectAsState()
    var ready by remember(noteKey, kind, store) { mutableStateOf(store.isReady(kind)) }
    var failed by remember(noteKey, kind, store) { mutableStateOf(false) }
    var attempt by remember(noteKey, kind, store) { mutableIntStateOf(0) }
    LaunchedEffect(noteKey, kind, store, attempt, packageRevision) {
        if (!ready) {
            failed = false
            try { store.ensure(kind); ready = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
    }
    if (ready) {
        key(noteKey, kind) { ReadyDiagramViewer(noteKey, kind, content, modifier, store) }
    } else {
        Box(modifier.testTag("diagram_viewer"), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!failed) CircularProgressIndicator(Modifier.testTag("diagram-package-loading"))
                Text(AppLanguage.get(context, if (failed) R.string.diagram_download_failed else R.string.diagram_downloading),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.testTag(if (failed) "diagram-package-error" else "diagram-package-status"))
                if (failed) TextButton(onClick = { failed = false; attempt++ }, modifier = Modifier.testTag("diagram-package-retry")) {
                    Text(AppLanguage.get(context, R.string.retry))
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ReadyDiagramViewer(noteKey: String, kind: String, content: String, modifier: Modifier, packages: DiagramPackages) {
    require(kind == "mermaid" || kind == "canvas")
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    fun color(value: androidx.compose.ui.graphics.Color) = "#%06x".format(value.toArgb() and 0xffffff)
    val theme = JSONObject().put("background", color(colors.background)).put("foreground", color(colors.onBackground))
        .put("surface", color(colors.surfaceContainer)).put("border", color(colors.outlineVariant))
        .put("dark", colors.background.luminance() < 0.4)
    val payload = JSONObject().put("kind", kind).put("content", content).put("theme", theme)
        .put("language", AppLanguage.language(context)).toString()
    var loading by remember(noteKey) { mutableStateOf(true) }
    var pageReady by remember(noteKey) { mutableStateOf(false) }
    val background = colors.background.toArgb()
    val webView = remember(noteKey) {
        WebView(context).apply {
            // WebView uses LayoutParams.height to choose its HTML viewport sizing policy.
            // WRAP_CONTENT collapses percentage-height pages even when Compose measures the view.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(background)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.blockNetworkLoads = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.setSupportZoom(false)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            // The interface can only announce presentation state. It cannot read app data or write notes.
            addJavascriptInterface(object {
                @JavascriptInterface fun ready() { post { loading = false } }
                @JavascriptInterface fun failed() { post { loading = false } }
            }, "ViewerStatus")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val path = DiagramResourcePolicy.assetPath(request.url, request.method) ?: return blocked()
                    return try {
                        val mime = when (path.substringAfterLast('.')) {
                            "js" -> "text/javascript"
                            "css" -> "text/css"
                            "html" -> "text/html"
                            "woff2" -> "font/woff2"
                            else -> return blocked()
                        }
                        WebResourceResponse(mime, if (mime.startsWith("font/")) null else "UTF-8", 200, "OK",
                            mapOf("Cache-Control" to "no-store"), (packages.openAsset(path) ?: context.assets.open(path)))
                    } catch (_: Exception) { blocked() }
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (url == DiagramResourcePolicy.INDEX) {
                        pageReady = true
                        // A missing JS runtime gets a visible local error rather than an endless spinner.
                        view.evaluateJavascript("Boolean(window.diagram)") { available ->
                            if (available != "true") {
                                loading = false
                                val message = AppLanguage.get(context, R.string.diagram_render_error)
                                view.evaluateJavascript("document.getElementById('surface').textContent=${JSONObject.quote(message)}", null)
                            }
                        }
                    }
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.url.toString() != DiagramResourcePolicy.INDEX
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { loading = true }
            }
            loadUrl(DiagramResourcePolicy.INDEX)
        }
    }
    Box(modifier.testTag("diagram_viewer")) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
    LaunchedEffect(payload, pageReady) {
        webView.setBackgroundColor(background)
        if (pageReady) webView.evaluateJavascript("window.diagram?.render($payload);", null)
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.removeJavascriptInterface("ViewerStatus")
            webView.destroy()
        }
    }
}

internal object DiagramResourcePolicy {
    const val HOST = "diagrams.trilium.invalid"
    const val INDEX = "https://$HOST/diagrams/index.html"
    fun assetPath(uri: Uri, method: String): String? {
        if (method != "GET" || uri.scheme != "https" || uri.host != HOST || uri.port != -1 ||
            uri.userInfo != null || uri.query != null || uri.fragment != null) { return null }
        val path = uri.path?.removePrefix("/") ?: return null
        if (!path.startsWith("diagrams/") || path.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
            '\\' in path || path.any { it.isISOControl() }) { return null }
        if (path.substringAfterLast('.') !in setOf("html", "js", "css", "woff2")) { return null }
        return path
    }
}
private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
