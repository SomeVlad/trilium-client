package ru.vlad.triliumnotes.data

import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

data class ResourceContent(val mimeType: String, val bytes: ByteArray)
data class CachedAttachment(val file: File, val mimeType: String, val name: String)

internal data class ResourceTarget(val id: String, val attachment: Boolean) {
    val cacheKey: String get() = (if (attachment) "attachment_" else "note_") + id
}

/** Only a Trilium note/attachment endpoint may receive the private server credential. */
internal fun resolveResourceTarget(value: String, serverUrl: String): ResourceTarget? {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
    if (uri.userInfo != null || uri.fragment != null) return null
    if (uri.isAbsolute || uri.rawAuthority != null) {
        val server = runCatching { URI(serverUrl) }.getOrNull() ?: return null
        fun port(u: URI) = if (u.port >= 0) u.port else if (u.scheme.equals("https", true)) 443 else 80
        if (!uri.scheme.equals(server.scheme, true) || !uri.host.equals(server.host, true) || port(uri) != port(server)) return null
    }
    var path = uri.path.orEmpty().trimStart('/')
    val basePath = runCatching { URI(serverUrl).path }.getOrNull().orEmpty().trim('/')
    if (basePath.isNotEmpty() && path.startsWith("$basePath/")) path = path.removePrefix("$basePath/")
    val parts = path.split('/')
    if (parts.any { it == ".." || it == "." || '\\' in it }) return null
    val id = when {
        parts.size >= 3 && parts.take(2) == listOf("api", "images") -> parts[2]
        parts.size >= 4 && parts.take(2) == listOf("api", "attachments") &&
            parts[3] in setOf("image", "download", "content") -> parts[2]
        parts.size >= 5 && parts.take(2) == listOf("api", "attachments") -> {
            if (!parts[2].matches(Regex("[A-Za-z0-9_]{4,32}"))) return null
            parts[3]
        }
        parts.size in 3..4 && parts.take(2) == listOf("api", "attachments") -> parts[2]
        parts.size == 4 && parts.take(2) == listOf("etapi", "attachments") && parts[3] == "content" -> parts[2]
        parts.size == 4 && parts.take(2) == listOf("etapi", "notes") && parts[3] == "content" -> parts[2]
        parts.size == 4 && parts.take(2) == listOf("api", "notes") && parts[3] in setOf("download", "content") -> parts[2]
        else -> return null
    }
    if (!id.matches(Regex("[A-Za-z0-9_]{4,32}"))) return null
    return ResourceTarget(id, parts[1] == "attachments")
}

/** Private persistent cache, also used by Android's native attachment viewers while offline. */
class NoteResources(
    private val context: Context,
    private val api: TriliumApi,
    private val serverUrl: String = api.serverUrl,
    private val anonymousClient: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).followSslRedirects(false).build(),
) {
    private val directory = File(context.filesDir, "note-resources").apply { mkdirs() }
    private val locks = ConcurrentHashMap<String, Any>()
    private val resourceLocks = ConcurrentHashMap<String, Mutex>()

    // WebView invokes this on its IO thread, never on the UI thread or through JavaScript.
    fun load(path: String): ResourceContent? {
        val target = resolveResourceTarget(path, serverUrl)
        return runCatching {
            if (target != null) runBlocking { read(target) { it.inlineContent() } } else readPublicImage(path)
        }.getOrNull()
    }

    suspend fun loadNote(note: NoteMeta): CachedAttachment = withContext(Dispatchers.IO) {
        val target = ResourceTarget(note.id, false)
        read(target, resourceVersion(note)) { export(target.cacheKey, it, note.title, note.mime) }
    }

    suspend fun loadLinkedAttachment(path: String): CachedAttachment = withContext(Dispatchers.IO) {
        val target = requireNotNull(resolveResourceTarget(path, serverUrl)) { "Unsupported attachment link" }
        val name = runCatching { URI(path).path.substringAfterLast('/') }.getOrDefault("")
            .takeUnless { it in setOf("", "content", "download", target.id) } ?: AppLanguage.get(context, R.string.msg_attachment_name)
        read(target) { export(target.cacheKey, it, name, "application/octet-stream") }
    }

    suspend fun prefetchNote(note: NoteMeta) {
        read(ResourceTarget(note.id, false), resourceVersion(note), allowStaleOnError = false) { Unit }
    }

    suspend fun prefetchAttachment(attachment: AttachmentMeta, owner: NoteMeta) {
        val version = attachment.blobId ?: listOf(owner.blobId.orEmpty(), owner.modifiedAt.orEmpty()).joinToString(":")
        read(ResourceTarget(attachment.id, true), version, allowStaleOnError = false) { Unit }
    }

    /** Preserve the confirmed upload locally without downloading our own bytes again. */
    suspend fun cacheUploadedAttachment(attachment: AttachmentMeta, sourceFile: File) = withContext(Dispatchers.IO) {
        require(attachment.id.matches(Regex("[A-Za-z0-9_]{4,32}")))
        val version = attachment.blobId?.takeIf { it.isNotBlank() }
            ?: throw IOException("Uploaded attachment version is missing")
        val target = ResourceTarget(attachment.id, true)
        resourceLocks.getOrPut(target.cacheKey) { Mutex() }.withLock {
            if (!sourceFile.isFile) throw IOException("Uploaded attachment content is missing")
            val data = File(directory, "${target.cacheKey}.data")
            val mimeFile = File(directory, "${target.cacheKey}.mime")
            val versionFile = File(directory, "${target.cacheKey}.version")
            if (data.isFile && data.length() == sourceFile.length() && mimeFile.isFile && versionFile.isFile &&
                mimeFile.readText() == attachment.mime && versionFile.readText() == version) return@withLock
            require(sourceFile.canonicalFile != data.canonicalFile)
            val atomic = AtomicFile(data)
            val output = atomic.startWrite()
            try {
                sourceFile.inputStream().use { input ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(chunk)
                        if (count < 0) break
                        output.write(chunk, 0, count)
                    }
                }
                currentCoroutineContext().ensureActive()
                atomic.finishWrite(output)
            } catch (error: Throwable) {
                atomic.failWrite(output)
                throw error
            }
            write(mimeFile, attachment.mime.toByteArray(Charsets.UTF_8))
            write(versionFile, version.toByteArray(Charsets.UTF_8))
        }
    }

    /** Use the same persistent files as the WebView and native viewers. */
    suspend fun prefetchInline(body: String, validatedAttachmentIds: Set<String> = emptySet(), markdown: Boolean = false): Int = withContext(Dispatchers.IO) {
        val html = if (markdown) org.commonmark.renderer.html.HtmlRenderer.builder().build()
            .render(org.commonmark.parser.Parser.builder().build().parse(body)) else body
        val document = org.jsoup.Jsoup.parseBodyFragment(html)
        val paths = linkedSetOf<String>()
        document.select("[src], [poster], a[href]").forEach { element ->
            for (attribute in listOf("src", "poster", "href")) {
                val path = element.attr(attribute)
                if (path.isBlank()) continue
                val target = resolveResourceTarget(path, serverUrl)
                val imageElement = element.normalName() == "img" || element.normalName() == "image" ||
                    (element.normalName() == "source" && element.parent()?.normalName() == "picture") || attribute == "poster"
                val publicImage = imageElement &&
                    (path.startsWith("https://") || runCatching { isDocResource(URI(path)) }.getOrDefault(false))
                if (target != null || publicImage) paths += path
            }
        }
        paths.forEach { path ->
            val target = resolveResourceTarget(path, serverUrl)
            if (target != null && target.attachment && target.id in validatedAttachmentIds) return@forEach
            if (target != null) read(target, allowStaleOnError = false) { Unit }
            else if (readPublicImage(path) == null) throw IOException("Could not save image for offline access")
        }
        paths.size
    }

    private fun resourceVersion(note: NoteMeta) = note.blobId ?: note.modifiedAt.orEmpty()

    private fun BinaryFile.inlineContent(): ResourceContent {
        if (file.length() > MAX_INLINE_RESOURCE_BYTES) throw IOException("Resource exceeds the 20 MiB preview limit")
        return ResourceContent(mimeType, file.inputStream().use { it.readResourceBytes() })
    }

    private suspend fun export(key: String, content: BinaryFile, title: String, fallbackMime: String): CachedAttachment {
        val mime = content.mimeType.takeUnless { it == "application/octet-stream" }
            ?: fallbackMime.ifBlank { "application/octet-stream" }
        // FileProvider supplies a normal extension to compatible Android apps.
        val suffix = when (mime.substringBefore(';').lowercase(Locale.ROOT)) {
            "application/pdf" -> ".pdf"
            "image/png" -> ".png"
            "image/jpeg" -> ".jpg"
            "image/webp" -> ".webp"
            "image/gif" -> ".gif"
            "image/svg+xml" -> ".svg"
            "audio/mpeg" -> ".mp3"
            "audio/ogg" -> ".ogg"
            "video/mp4" -> ".mp4"
            else -> title.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }?.let { ".$it" }.orEmpty()
        }
        // The exported extension must never alias the .data/.mime/.version cache files.
        val export = File(directory, "view_$key$suffix")
        val coroutine = currentCoroutineContext()
        synchronized(locks.getOrPut(key) { Any() }) {
            val atomic = AtomicFile(export)
            val output = atomic.startWrite()
            try {
                content.file.inputStream().use { input ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        coroutine.ensureActive()
                        val count = input.read(chunk)
                        if (count < 0) break
                        output.write(chunk, 0, count)
                    }
                }
                coroutine.ensureActive()
                atomic.finishWrite(output)
            } catch (error: Throwable) {
                atomic.failWrite(output)
                throw error
            }
        }
        return CachedAttachment(export, mime, title.ifBlank { AppLanguage.get(context, R.string.msg_attachment_name) + suffix })
    }

    private suspend fun <T> read(target: ResourceTarget, version: String? = null, allowStaleOnError: Boolean = true,
        consume: suspend (BinaryFile) -> T): T = withContext(Dispatchers.IO) {
        resourceLocks.getOrPut(target.cacheKey) { Mutex() }.withLock {
            val file = File(directory, "${target.cacheKey}.data")
            val mimeFile = File(directory, "${target.cacheKey}.mime")
            val versionFile = File(directory, "${target.cacheKey}.version")
            val cached = file.isFile && mimeFile.isFile
            val sameVersion = version == null || (versionFile.isFile && versionFile.readText() == version)
            if (cached && sameVersion && ((allowStaleOnError && version == null) ||
                    version != null || System.currentTimeMillis() - file.lastModified() < 300_000)) {
                return@withLock consume(BinaryFile(file, mimeFile.readText()))
            }
            val result = try {
                if (target.attachment) api.downloadAttachment(target.id, file) else api.downloadBinary(target.id, file)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (cached && allowStaleOnError) return@withLock consume(BinaryFile(file, mimeFile.readText()))
                throw error
            }
            write(mimeFile, result.mimeType.toByteArray(Charsets.UTF_8))
            if (version != null) write(versionFile, version.toByteArray(Charsets.UTF_8))
            consume(result)
        }
    }

    private fun isDocResource(input: URI): Boolean {
        if (input.isAbsolute || input.rawAuthority != null) return false
        var resourcePath = input.path.orEmpty()
        val basePath = runCatching { URI(serverUrl).path }.getOrNull().orEmpty().trimEnd('/')
        if (basePath.isNotEmpty() && resourcePath.startsWith("$basePath/")) resourcePath = resourcePath.removePrefix(basePath)
        return resourcePath.matches(Regex("/?assets/v[0-9A-Za-z._-]+/doc_notes/.+"))
    }

    // Public images are fetched by a completely separate client with no bearer token or cookies.
    private fun readPublicImage(path: String): ResourceContent? {
        val input = runCatching { URI(path) }.getOrNull() ?: return null
        val url = if (input.isAbsolute) input else {
            if (!isDocResource(input)) return null
            URI(serverUrl.trimEnd('/') + "/").resolve(input)
        }
        if (url.scheme != "https" || url.host.isNullOrBlank() || url.userInfo != null || url.path.split('/').any { it == ".." }) return null
        val key = "public_" + MessageDigest.getInstance("SHA-256").digest(url.toASCIIString().toByteArray()).joinToString("") { "%02x".format(it) }
        return synchronized(locks.getOrPut(key) { Any() }) {
            val file = File(directory, "$key.data")
            val mimeFile = File(directory, "$key.mime")
            if (file.isFile && mimeFile.isFile) return@synchronized BinaryFile(file, mimeFile.readText()).inlineContent()
            anonymousClient.newCall(Request.Builder().url(url.toASCIIString()).build()).execute().use { response ->
                val mime = response.header("Content-Type").orEmpty().substringBefore(';').trim()
                if (!response.isSuccessful || !mime.startsWith("image/")) return@synchronized null
                val body = response.body ?: return@synchronized null
                if (body.contentLength() > MAX_INLINE_RESOURCE_BYTES) return@synchronized null
                val bytes = body.byteStream().use { it.readResourceBytes() }
                write(file, bytes)
                write(mimeFile, mime.toByteArray())
                ResourceContent(mime, bytes)
            }
        }
    }

    private fun write(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}
