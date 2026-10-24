package ru.vlad.triliumnotes.ui

import android.content.Context
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.vlad.triliumnotes.data.CachedAttachment
import ru.vlad.triliumnotes.data.NoteMeta
import java.io.ByteArrayInputStream

private enum class AttachmentRecovery { DOWNLOAD, SAVE, NONE }

@Composable
fun AttachmentViewer(note: NoteMeta, loader: suspend (NoteMeta) -> CachedAttachment, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var attachment by remember(note.id) { mutableStateOf<CachedAttachment?>(null) }
    var error by remember(note.id) { mutableStateOf<String?>(null) }
    var recovery by remember(note.id) { mutableStateOf(AttachmentRecovery.NONE) }
    var attempt by remember(note.id) { mutableIntStateOf(0) }
    fun clearSaveError() {
        if (recovery == AttachmentRecovery.SAVE) {
            error = null
            recovery = AttachmentRecovery.NONE
        }
    }
    LaunchedEffect(note.id, note.blobId, attempt) {
        error = null
        recovery = AttachmentRecovery.NONE
        try { attachment = loader(note) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            error = AppLanguage.get(context, R.string.attachment_download_error)
            recovery = AttachmentRecovery.DOWNLOAD
        }
    }
    val current = attachment
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(current?.mimeType ?: "application/octet-stream")) { uri ->
        if (uri != null && current != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use { output -> current.file.inputStream().use { it.copyTo(output) } }
                }
                clearSaveError()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                error = AppLanguage.get(context, R.string.file_save_error)
                recovery = AttachmentRecovery.SAVE
            }
        }
    }
    Column(modifier.fillMaxSize().testTag("attachment-viewer")) {
        Text(note.title, Modifier.padding(horizontal = 22.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineMedium)
        error?.let { message ->
            Text(message, Modifier.padding(horizontal = 22.dp), color = MaterialTheme.colorScheme.error)
            when (recovery) {
                AttachmentRecovery.DOWNLOAD -> TextButton(onClick = { attempt++ }, Modifier.padding(start = 14.dp)) { Text(AppLanguage.get(context, R.string.retry)) }
                AttachmentRecovery.SAVE -> if (current != null) TextButton(onClick = {
                    clearSaveError()
                    saver.launch(current.name)
                }, Modifier.padding(start = 14.dp)) { Text(AppLanguage.get(context, R.string.choose_location)) }
                AttachmentRecovery.NONE -> Unit
            }
        }
        if (current == null) {
            if (error == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", current.file)
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, current.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(Intent.createChooser(intent, AppLanguage.get(context, R.string.open_file))) }
                        .onFailure {
                            error = AppLanguage.get(context, R.string.file_no_app)
                            recovery = AttachmentRecovery.NONE
                        }
                }) { Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(AppLanguage.get(context, R.string.open_file)) }
                IconButton(onClick = { clearSaveError(); saver.launch(current.name) }) { Icon(Icons.Outlined.Download, AppLanguage.get(context, R.string.save_file)) }
                IconButton(onClick = {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", current.file)
                    val intent = Intent(Intent.ACTION_SEND).setType(current.mimeType).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(Intent.createChooser(intent, AppLanguage.get(context, R.string.share_file))) }
                        .onFailure {
                            error = AppLanguage.get(context, R.string.file_share_error)
                            recovery = AttachmentRecovery.NONE
                        }
                }) { Icon(Icons.Outlined.Share, AppLanguage.get(context, R.string.share_file)) }
            }
            Text("${attachmentFormat(context, current.mimeType, current.name)} · ${fileSize(context, current.file.length())}", Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                when {
                    current.mimeType == "application/pdf" -> PdfAttachment(current)
                    current.mimeType == "image/svg+xml" -> SvgAttachment(current)
                    current.mimeType.startsWith("image/") -> ImageAttachment(current)
                    current.mimeType.startsWith("video/") -> MediaAttachment(current)
                    current.mimeType.startsWith("audio/") -> AudioAttachment(current)
                    current.mimeType.startsWith("text/") || current.mimeType in setOf("application/json", "application/xml") -> TextAttachment(current)
                    else -> Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                        Text(AppLanguage.get(context, R.string.file_available_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

internal fun attachmentFormat(context: Context, mime: String, name: String): String {
    val normalized = mime.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
    val extension = name.substringAfterLast('/').substringAfterLast('.', "")
        .takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) }?.uppercase(java.util.Locale.ROOT)
    return when (normalized) {
        "application/pdf" -> "PDF"
        "image/png" -> "PNG"
        "image/jpeg" -> "JPEG"
        "image/gif" -> "GIF"
        "image/webp" -> "WebP"
        "image/svg+xml" -> "SVG"
        "image/avif" -> "AVIF"
        "image/heif" -> "HEIF"
        "image/heic" -> "HEIC"
        "image/bmp" -> "BMP"
        "image/tiff" -> "TIFF"
        "application/json" -> "JSON"
        "application/xml" -> "XML"
        else -> when {
            normalized.startsWith("text/") -> AppLanguage.get(context, R.string.format_text)
            normalized.startsWith("audio/") -> AppLanguage.get(context, R.string.format_audio)
            normalized.startsWith("video/") -> AppLanguage.get(context, R.string.format_video)
            normalized.startsWith("image/") -> extension ?: AppLanguage.get(context, R.string.format_image)
            else -> extension ?: AppLanguage.get(context, R.string.format_file)
        }
    }
}

private fun fileSize(context: Context, size: Long): String = when {
    size < 1024 -> AppLanguage.get(context, R.string.file_size_bytes, size)
    size < 1024 * 1024 -> AppLanguage.get(context, R.string.file_size_kb, size / 1024)
    else -> AppLanguage.get(context, R.string.file_size_mb, size / 1048576.0)
}

@Composable
private fun ImageAttachment(attachment: CachedAttachment) {
    val context = LocalContext.current
    var scale by remember(attachment.file) { mutableFloatStateOf(1f) }
    var pan by remember(attachment.file) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var drawable by remember(attachment.file) { mutableStateOf<Drawable?>(null) }
    var failed by remember(attachment.file) { mutableStateOf(false) }
    LaunchedEffect(attachment.file) {
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(attachment.file)) { decoder, info, _ ->
                    val factor = minOf(1f, 2048f / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize((info.size.width * factor).toInt().coerceAtLeast(1), (info.size.height * factor).toInt().coerceAtLeast(1))
                }
            }
        }
        drawable = decoded.getOrNull()
        failed = decoded.isFailure
    }
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { size = it }.pointerInput(attachment.file) {
        detectTransformGestures { _, movement, zoom, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            val imageWidth = drawable?.intrinsicWidth?.coerceAtLeast(1) ?: size.width.coerceAtLeast(1)
            val imageHeight = drawable?.intrinsicHeight?.coerceAtLeast(1) ?: size.height.coerceAtLeast(1)
            val fit = minOf(size.width.toFloat() / imageWidth, size.height.toFloat() / imageHeight)
            val maxX = ((imageWidth * fit * scale - size.width) / 2f).coerceAtLeast(0f)
            val maxY = ((imageHeight * fit * scale - size.height) / 2f).coerceAtLeast(0f)
            pan = Offset((pan.x + movement.x).coerceIn(-maxX, maxX), (pan.y + movement.y).coerceIn(-maxY, maxY))
        }
    }) {
        AndroidView(factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = attachment.name } },
            modifier = Modifier.fillMaxSize().testTag("attachment-image").graphicsLayer(scaleX = scale, scaleY = scale, translationX = pan.x, translationY = pan.y),
            update = { view ->
                if (view.drawable !== drawable) {
                    (view.drawable as? AnimatedImageDrawable)?.stop()
                    view.setImageDrawable(drawable)
                    (drawable as? AnimatedImageDrawable)?.start()
                }
            }, onRelease = { view -> (view.drawable as? AnimatedImageDrawable)?.stop(); view.setImageDrawable(null) })
        if (drawable == null && !failed) CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (failed) Text(AppLanguage.get(context, R.string.image_display_error), Modifier.padding(22.dp))
        if (scale > 1f) TextButton(onClick = { scale = 1f; pan = Offset.Zero }, Modifier.align(Alignment.BottomEnd).padding(12.dp)) { Text(AppLanguage.get(context, R.string.reset_zoom)) }
    }
}

@Composable
private fun PdfAttachment(attachment: CachedAttachment) {
    val context = LocalContext.current
    val renderer = remember(attachment.file) {
        runCatching {
            val descriptor = ParcelFileDescriptor.open(attachment.file, ParcelFileDescriptor.MODE_READ_ONLY)
            try { PdfRenderer(descriptor) } catch (error: Exception) { descriptor.close(); throw error }
        }.getOrNull()
    }
    var closed by remember(renderer) { mutableStateOf(false) }
    if (renderer == null) {
        Text(AppLanguage.get(context, R.string.pdf_open_error), Modifier.padding(22.dp))
        return
    }
    DisposableEffect(renderer) { onDispose { synchronized(renderer) { closed = true; renderer.close() } } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = with(LocalDensity.current) { maxWidth.toPx().toInt().coerceIn(1, 2048) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items((0 until renderer.pageCount).toList(), key = { it }) { index ->
                var bitmap by remember(renderer, index, width) { mutableStateOf<Bitmap?>(null) }
                var pageFailed by remember(renderer, index, width) { mutableStateOf(false) }
                LaunchedEffect(renderer, index, width) {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { synchronized(renderer) {
                            check(!closed)
                            renderer.openPage(index).use { page ->
                                val height = (width.toLong() * page.height / page.width).coerceIn(1, 8192).toInt()
                                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                                    it.eraseColor(android.graphics.Color.WHITE)
                                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                            }
                        } }
                    }
                    bitmap = result.getOrNull()
                    pageFailed = result.isFailure
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    bitmap?.let { Image(it.asImageBitmap(), AppLanguage.get(context, R.string.pdf_page, index + 1), Modifier.fillMaxWidth().testTag("pdf-page-$index")) }
                        ?: if (pageFailed) Text(AppLanguage.get(context, R.string.pdf_page_error), Modifier.padding(16.dp))
                        else CircularProgressIndicator(Modifier.padding(24.dp).size(24.dp))
                    Text("${index + 1} / ${renderer.pageCount}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SvgAttachment(attachment: CachedAttachment) {
    AndroidView(factory = { context ->
        WebView(context).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                    return if (request?.url.toString() == "https://attachment.invalid/image") {
                        WebResourceResponse("image/svg+xml", "utf-8", attachment.file.inputStream())
                    } else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            }
            loadDataWithBaseURL("https://attachment.invalid/", """<!doctype html>
                <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                <style>html,body{margin:0;padding:0;width:100%;height:auto}img{display:block;width:100%;height:auto}</style>
                </head><body><img src="https://attachment.invalid/image"></body></html>""".trimIndent(), "text/html", "utf-8", null)
        }
    }, modifier = Modifier.fillMaxSize().testTag("attachment-svg"), onRelease = { it.destroy() })
}

@Composable
private fun AudioAttachment(attachment: CachedAttachment) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val manager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val player = remember(attachment.file, lifecycleOwner) { MediaPlayer() }
    var prepared by remember(player) { mutableStateOf(false) }
    var playing by remember(player) { mutableStateOf(false) }
    var failed by remember(player) { mutableStateOf(false) }
    var released by remember(player) { mutableStateOf(false) }
    val attributes = remember { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build() }
    val focus = remember(player) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (!released && change <= 0 && playing) {
                    runCatching { player.pause() }
                    playing = false
                }
            }.build()
    }
    DisposableEffect(player, lifecycleOwner) {
        player.setAudioAttributes(attributes)
        player.setOnPreparedListener { if (!released) prepared = true }
        player.setOnCompletionListener {
            if (!released) { playing = false; manager.abandonAudioFocusRequest(focus) }
        }
        player.setOnErrorListener { _, _, _ ->
            if (!released) {
                failed = true; prepared = false; playing = false
                manager.abandonAudioFocusRequest(focus)
            }
            true
        }
        try {
            player.setDataSource(attachment.file.absolutePath)
            player.prepareAsync()
        } catch (_: Exception) { failed = true }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                if (!released && playing) runCatching { player.pause() }
                playing = false
                manager.abandonAudioFocusRequest(focus)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            released = true
            manager.abandonAudioFocusRequest(focus)
            player.setOnPreparedListener(null)
            player.setOnCompletionListener(null)
            player.setOnErrorListener(null)
            player.release()
        }
    }
    Column(Modifier.fillMaxWidth().padding(22.dp).testTag("audio-player"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (failed) {
            Text(AppLanguage.get(context, R.string.media_play_error),
                color = MaterialTheme.colorScheme.error)
        } else {
            FilledTonalButton(enabled = prepared, modifier = Modifier.testTag("audio-play"), onClick = {
                runCatching {
                    if (playing) {
                        player.pause()
                        manager.abandonAudioFocusRequest(focus)
                        playing = false
                    } else {
                        check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                        player.start()
                        playing = true
                    }
                }.onFailure {
                    failed = true; prepared = false; playing = false
                    manager.abandonAudioFocusRequest(focus)
                }
            }) {
                Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(if (playing) AppLanguage.get(context, R.string.pause) else AppLanguage.get(context, R.string.play))
            }
            if (!prepared) Text(AppLanguage.get(context, R.string.audio_preparing), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MediaAttachment(attachment: CachedAttachment) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<VideoView?>(null) }
    var failed by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    if (failed) {
        Text(AppLanguage.get(context, R.string.media_play_error), Modifier.padding(22.dp))
    } else Column(Modifier.fillMaxSize()) {
        AndroidView(factory = { ctx ->
            VideoView(ctx).apply {
                player = this
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", attachment.file)
                setVideoURI(uri)
                setMediaController(android.widget.MediaController(ctx).also { it.setAnchorView(this) })
                setOnPreparedListener { seekTo(1) }
                setOnCompletionListener { playing = false }
                setOnErrorListener { _, _, _ -> failed = true; true }
            }
        }, modifier = Modifier.fillMaxWidth().weight(1f), onRelease = { it.stopPlayback(); player = null })
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
            FilledTonalButton(enabled = !failed, onClick = {
                if (playing) player?.pause() else player?.start()
                playing = !playing
            }) { Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null); Text(if (playing) AppLanguage.get(context, R.string.pause) else AppLanguage.get(context, R.string.play)) }
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) { player?.pause(); playing = false }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun TextAttachment(attachment: CachedAttachment) {
    val context = LocalContext.current
    var text by remember(attachment.file) { mutableStateOf("") }
    var truncated by remember(attachment.file) { mutableStateOf(false) }
    var failed by remember(attachment.file) { mutableStateOf(false) }
    LaunchedEffect(attachment.file) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                attachment.file.reader().use { reader ->
                    val buffer = CharArray(1_048_577)
                    var count = 0
                    while (count < buffer.size) {
                        val read = reader.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                    }
                    String(buffer, 0, count)
                }
            }
        }
        failed = result.isFailure
        val loaded = result.getOrDefault("")
        truncated = loaded.length > 1_048_576
        text = loaded.take(1_048_576)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        if (failed) Text(AppLanguage.get(context, R.string.file_read_error), color = MaterialTheme.colorScheme.error)
        if (truncated) Text(AppLanguage.get(context, R.string.file_truncated_message),
            Modifier.padding(bottom = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, fontFamily = FontFamily.Monospace)
    }
}
