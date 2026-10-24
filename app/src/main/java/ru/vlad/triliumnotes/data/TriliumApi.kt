package ru.vlad.triliumnotes.data

import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

data class NoteMeta(
    val id: String,
    val title: String,
    val type: String,
    val mime: String,
    val isProtected: Boolean,
    val blobId: String?,
    val modifiedAt: String?,
    val childBranchIds: List<String>,
    val parentNoteIds: List<String> = emptyList(),
    val docName: String? = null,
)

data class TreeItem(
    val branchId: String,
    val note: NoteMeta,
    val prefix: String,
    val position: Int,
    val isExpanded: Boolean,
)

data class NoteDocument(
    val note: NoteMeta,
    val html: String,
    val pending: Boolean = false,
    val conflict: Boolean = false,
    val remoteMissing: Boolean = false,
)

data class ChildrenResult(
    val parent: NoteMeta,
    val items: List<TreeItem>,
    val failedBranchIds: List<String> = emptyList(),
    val fromCache: Boolean = false,
)

data class AttachmentMeta(
    val id: String,
    val ownerId: String,
    val role: String,
    val mime: String,
    val title: String,
    val position: Int,
    val blobId: String? = null,
)

data class BinaryContent(val bytes: ByteArray, val mimeType: String)
data class BinaryFile(val file: File, val mimeType: String)

internal const val MAX_INLINE_RESOURCE_BYTES = 20 * 1024 * 1024

/** Used only by APIs whose caller explicitly needs an in-memory image. */
internal fun InputStream.readResourceBytes(): ByteArray {
    val result = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (true) {
        val count = read(chunk, 0, minOf(chunk.size, MAX_INLINE_RESOURCE_BYTES - result.size() + 1))
        if (count < 0) return result.toByteArray()
        if (result.size() + count > MAX_INLINE_RESOURCE_BYTES) throw IOException("Resource exceeds the 20 MiB preview limit")
        result.write(chunk, 0, count)
    }
}

/** ETAPI cannot establish a protected session. Binary content is fetched separately. */
val NoteMeta.hasTextContent: Boolean
    get() = !isProtected && (type !in setOf("image", "file") || mime.startsWith("text/") ||
        mime in setOf("application/json", "application/javascript", "image/svg+xml"))

val NoteMeta.isEditable: Boolean
    get() = !isProtected && type in setOf("text", "code", "mermaid")

val NoteMeta.canDelete: Boolean
    get() = id != "root" && !id.startsWith('_')

class ApiException(val status: Int) : IOException("Trilium API error: $status")
class UnexpectedHtmlResponseException(val status: Int) :
    IOException("Trilium returned HTML instead of JSON (HTTP $status)")

class TriliumApi(
    baseUrl: String,
    token: String,
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build(),
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    @Volatile private var active = true
    val serverUrl: String = baseUrl.trimEnd('/')
    private val base: HttpUrl = (baseUrl.trimEnd('/') + "/etapi/").toHttpUrl()
    private val serverRoot = (baseUrl.trimEnd('/') + "/").toHttpUrl()
    private val authorization = token.removePrefix("Bearer ").trim()
    private val binaryClient = client.newBuilder()
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    init {
        require(authorization.isNotBlank() && authorization.all { it in '!'..'~' }) { "Invalid ETAPI token" }
    }

    /** Deactivating a session also stops requests issued by an old foreground model. */
    fun close() {
        active = false
        client.dispatcher.cancelAll()
        binaryClient.dispatcher.cancelAll()
    }

    private fun checkActive() {
        if (!active) throw CancellationException("Session ended")
    }

    suspend fun validateSession() {
        val info = JSONObject(send("GET", endpoint("app-info")))
        if (info.optString("appVersion").isBlank()) throw IOException("Invalid Trilium app-info response")
        if (getNote("root").id != "root") throw IOException("Invalid Trilium root response")
    }

    suspend fun getNote(noteId: String): NoteMeta =
        parseNote(JSONObject(send("GET", endpoint("notes", noteId))))

    suspend fun getContent(noteId: String): String =
        send("GET", endpoint("notes", noteId, "content"), allowsHtmlBody = true)

    suspend fun getDocContent(docName: String): String {
        require(docName.matches(Regex("[A-Za-z0-9_/\\- ()&.,`]+")) &&
            docName.split('/').none { it.isEmpty() || it.all { char -> char == '.' } })
        val version = JSONObject(send("GET", endpoint("app-info"))).getString("appVersion")
        require(version.matches(Regex("[A-Za-z0-9.\\-]+")))
        val url = serverRoot.newBuilder().addPathSegment("assets").addPathSegment("v$version")
            .addPathSegment("doc_notes").addPathSegment("en").apply {
                val parts = docName.split('/')
                parts.forEachIndexed { index, part -> addPathSegment(if (index == parts.lastIndex) "$part.html" else part) }
            }.build()
        val html = send("GET", url, allowsHtmlBody = true)
        val document = Jsoup.parse(html)
        document.select("[src], [href]").forEach { element ->
            for (attribute in listOf("src", "href")) {
                val value = element.attr(attribute)
                if (value.isBlank() || value.startsWith('#')) continue
                url.resolve(value)?.let { target ->
                    if (target.host == serverRoot.host && target.port == serverRoot.port && target.scheme == serverRoot.scheme)
                        element.attr(attribute, target.encodedPath + (target.encodedQuery?.let { "?$it" } ?: ""))
                }
            }
        }
        return document.body().html()
    }

    suspend fun getBinary(noteId: String): BinaryContent =
        sendBytes(endpoint("notes", noteId, "content"))

    suspend fun getAttachmentContent(attachmentId: String): BinaryContent =
        sendBytes(endpoint("attachments", attachmentId, "content"))

    suspend fun downloadBinary(noteId: String, destination: File): BinaryFile =
        download(endpoint("notes", noteId, "content"), destination)

    suspend fun downloadAttachment(attachmentId: String, destination: File): BinaryFile =
        download(endpoint("attachments", attachmentId, "content"), destination)

    suspend fun getAttachments(noteId: String): List<AttachmentMeta> {
        val items = org.json.JSONArray(send("GET", endpoint("notes", noteId, "attachments")))
        return (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            AttachmentMeta(item.getString("attachmentId"), item.optString("ownerId"),
                item.optString("role"), item.optString("mime"), item.optString("title"), item.optInt("position"),
                item.optString("blobId").takeIf { it.isNotBlank() && it != "null" })
        }
    }

    internal suspend fun historyRequest(method: String, segments: List<String>, body: String? = null,
        allowsHtmlBody: Boolean = false): String =
        send(method, endpoint(*segments.toTypedArray()), body, allowsHtmlBody = allowsHtmlBody,
            expectedCode = if (method == "POST" && segments.lastOrNull() == "revision") 204 else null)

    suspend fun createAttachment(ownerId: String, role: String, mime: String, title: String,
        position: Int): AttachmentMeta {
        val item = JSONObject(send("POST", endpoint("attachments"), JSONObject()
            .put("ownerId", ownerId).put("role", role).put("mime", mime)
            .put("title", title).put("position", position).toString()))
        return AttachmentMeta(item.getString("attachmentId"), item.getString("ownerId"),
            item.optString("role"), item.optString("mime"), item.optString("title"), item.optInt("position"),
            item.optString("blobId").takeIf { it.isNotBlank() && it != "null" })
    }

    suspend fun patchAttachmentTitle(attachmentId: String, title: String) {
        send("PATCH", endpoint("attachments", attachmentId), JSONObject().put("title", title).toString())
    }

    /** Stream from the durable outbox copy; never hold a selected file in memory. */
    suspend fun putAttachmentContent(attachmentId: String, file: File) = withContext(Dispatchers.IO) {
        checkActive()
        val request = Request.Builder().url(endpoint("attachments", attachmentId, "content"))
            .header("Authorization", authorization)
            .put(file.asRequestBody("application/octet-stream".toMediaType())).build()
        binaryClient.newBuilder().callTimeout(5, TimeUnit.MINUTES).build().newCall(request).execute().use { response ->
            checkActive()
            if (isKnownCrowdSecCaptchaPage(response.peekBody(65_536).string()))
                throw UnexpectedHtmlResponseException(response.code)
            if (response.code != 204) throw ApiException(response.code)
        }
    }

    suspend fun getChildren(parentId: String): List<TreeItem> = getChildrenResult(parentId).items

    suspend fun getChildrenResult(parentId: String): ChildrenResult {
        val parent = getNote(parentId)
        val items = mutableListOf<TreeItem>()
        val failed = mutableListOf<String>()
        // Keep requests sequential: opening a large folder must not create a burst
        // of concurrent metadata requests at the reverse proxy.
        parent.childBranchIds.forEach { branchId ->
            try {
                val branch = JSONObject(send("GET", endpoint("branches", branchId)))
                if (branch.getString("noteId").startsWith('_')) return@forEach
                items += TreeItem(
                    branchId = branch.getString("branchId"),
                    note = getNote(branch.getString("noteId")),
                    prefix = if (branch.isNull("prefix")) "" else branch.getString("prefix"),
                    position = branch.optInt("notePosition"),
                    isExpanded = branch.optBoolean("isExpanded"),
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failed += branchId
            }
        }
        return ChildrenResult(parent, items.sortedWith(compareBy<TreeItem> { it.position }.thenBy { it.branchId }), failed)
    }

    suspend fun search(query: String): List<NoteMeta> {
        val url = endpoint("notes").newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("fastSearch", "false")
            .addQueryParameter("includeArchivedNotes", "true")
            .build()
        val results = JSONObject(send("GET", url)).getJSONArray("results")
        return (0 until results.length()).map { parseNote(results.getJSONObject(it)) }
    }

    suspend fun deleteNote(noteId: String) {
        send("DELETE", endpoint("notes", noteId))
    }

    suspend fun putContent(noteId: String, html: String) {
        send("PUT", endpoint("notes", noteId, "content"), html,
            "text/plain; charset=utf-8")
    }

    suspend fun patchTitle(noteId: String, title: String): NoteMeta {
        val body = JSONObject().put("title", title).toString()
        return parseNote(JSONObject(send("PATCH", endpoint("notes", noteId), body)))
    }

    suspend fun createNote(
        noteId: String,
        parentId: String,
        title: String,
        html: String,
        type: String = "text",
        mime: String = "text/html",
    ): NoteMeta {
        val body = JSONObject()
            .put("parentNoteId", parentId)
            .put("title", title)
            .put("type", type)
            .put("mime", mime)
            .put("content", html)
            .put("noteId", noteId)
            .toString()
        return parseNote(JSONObject(send("POST", endpoint("create-note"), body)).getJSONObject("note"))
    }

    private fun endpoint(vararg parts: String): HttpUrl = base.newBuilder().apply {
        parts.forEachIndexed { index, part ->
            // Branch IDs can join two 32-character note IDs with an underscore.
            val maxIdLength = if (parts.firstOrNull() == "branches" && index == 1) 65 else 32
            require(part == "root" || part.matches(Regex("[A-Za-z0-9_]{4,$maxIdLength}")) ||
                part in setOf("notes", "branches", "content", "create-note", "attachments", "app-info"))
            addPathSegment(part)
        }
    }.build()

    private suspend fun sendBytes(url: HttpUrl): BinaryContent = withContext(Dispatchers.IO) {
        checkActive()
        val request = Request.Builder().url(url).header("Authorization", authorization).build()
        binaryClient.newCall(request).execute().use { response ->
            checkActive()
            if (isKnownCrowdSecCaptchaPage(response.peekBody(65_536).string()))
                throw UnexpectedHtmlResponseException(response.code)
            if (!response.isSuccessful) throw ApiException(response.code)
            val body = response.body
            if (body != null && body.contentLength() > MAX_INLINE_RESOURCE_BYTES)
                throw IOException("Resource exceeds the 20 MiB preview limit")
            val bytes = body?.byteStream()?.use { it.readResourceBytes() } ?: byteArrayOf()
            BinaryContent(bytes, response.header("Content-Type")?.substringBefore(';')?.trim()
                ?: "application/octet-stream")
        }
    }

    /** Native viewing and offline storage never materialize an attachment in memory. */
    private suspend fun download(url: HttpUrl, destination: File): BinaryFile = withContext(Dispatchers.IO) {
        checkActive()
        val request = Request.Builder().url(url).header("Authorization", authorization).build()
        binaryClient.newCall(request).execute().use { response ->
            checkActive()
            if (isKnownCrowdSecCaptchaPage(response.peekBody(65_536).string()))
                throw UnexpectedHtmlResponseException(response.code)
            if (!response.isSuccessful) throw ApiException(response.code)
            val atomic = AtomicFile(destination)
            val output = atomic.startWrite()
            try {
                response.body?.byteStream()?.use { input ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        checkActive()
                        val count = input.read(chunk)
                        if (count < 0) break
                        output.write(chunk, 0, count)
                    }
                }
                currentCoroutineContext().ensureActive()
                checkActive()
                atomic.finishWrite(output)
            } catch (error: Throwable) {
                atomic.failWrite(output)
                throw error
            }
            BinaryFile(destination, response.header("Content-Type")?.substringBefore(';')?.trim()
                ?: "application/octet-stream")
        }
    }

    private suspend fun send(
        method: String,
        url: HttpUrl,
        content: String? = null,
        contentType: String = "application/json; charset=utf-8",
        allowsHtmlBody: Boolean = false,
        expectedCode: Int? = null,
    ): String = withContext(Dispatchers.IO) {
        checkActive()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .method(method, content?.toRequestBody(contentType.toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            checkActive()
            // ETAPI metadata/text is kept in memory; file/image downloads use download().
            val responseBody = response.body
            if (responseBody != null && (responseBody.contentLength() > MAX_INLINE_RESOURCE_BYTES ||
                    (responseBody.source().request(MAX_INLINE_RESOURCE_BYTES + 1L) &&
                        responseBody.source().buffer.size > MAX_INLINE_RESOURCE_BYTES))) {
                throw IOException("Server response exceeds the 20 MiB limit")
            }
            val body = responseBody?.string().orEmpty()
            if ((allowsHtmlBody && isKnownCrowdSecCaptchaPage(body)) ||
                (!allowsHtmlBody && isHtmlInsteadOfJson(response.header("Content-Type"), body))) {
                throw UnexpectedHtmlResponseException(response.code)
            }
            if (!response.isSuccessful || (expectedCode != null && response.code != expectedCode))
                throw ApiException(response.code)
            body
        }
    }
}

private val crowdSecCaptchaTitle = Regex(
    """<title(?:\s+[^>]*)?>\s*CrowdSec Captcha\s*</title>""",
    RegexOption.IGNORE_CASE,
)
private val crowdSecChallengeUrl = Regex("""\bdata-challenge-url\s*=""", RegexOption.IGNORE_CASE)

private fun isKnownCrowdSecCaptchaPage(body: String): Boolean =
    crowdSecCaptchaTitle.containsMatchIn(body) && crowdSecChallengeUrl.containsMatchIn(body)

internal fun isHtmlInsteadOfJson(contentType: String?, body: String): Boolean {
    val start = body.trimStart().take(512)
    val prefix = start.lowercase(Locale.ROOT)
    val htmlBody = prefix.startsWith("<!doctype html") || prefix.startsWith("<html") ||
        prefix.startsWith("<head") || prefix.startsWith("<body")
    val declaredHtml = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) in
        setOf("text/html", "application/xhtml+xml")
    val explicitCaptcha = !prefix.startsWith("{") && !prefix.startsWith("[") &&
        (prefix.contains("captcha") || prefix.contains("crowdsec"))
    return htmlBody || (declaredHtml && prefix.startsWith("<")) || explicitCaptcha
}

internal fun parseNote(json: JSONObject): NoteMeta {
    val childBranches = json.optJSONArray("childBranchIds")
    val parents = json.optJSONArray("parentNoteIds")
    val attributes = json.optJSONArray("attributes")
    val docName = json.optString("docName").takeIf { it.isNotBlank() } ?: attributes?.let { items ->
        (0 until items.length()).map { items.getJSONObject(it) }
            .firstOrNull { it.optString("type") == "label" && it.optString("name") == "docName" }
            ?.optString("value")?.takeIf { it.isNotBlank() }
    }
    return NoteMeta(
        id = json.getString("noteId"),
        title = json.optString("title"),
        type = json.optString("type"),
        mime = json.optString("mime"),
        isProtected = json.optBoolean("isProtected"),
        blobId = if (json.isNull("blobId")) null else json.getString("blobId"),
        modifiedAt = if (json.isNull("utcDateModified")) null else json.getString("utcDateModified"),
        childBranchIds = if (childBranches == null) emptyList() else
            (0 until childBranches.length()).map { childBranches.getString(it) },
        parentNoteIds = if (parents == null) emptyList() else
            (0 until parents.length()).map { parents.getString(it) },
        docName = docName,
    )
}
