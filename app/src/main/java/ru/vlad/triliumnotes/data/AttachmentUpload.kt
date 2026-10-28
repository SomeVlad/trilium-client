package ru.vlad.triliumnotes.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

data class PendingAttachmentUpload(
    val uploadId: String,
    val noteId: String,
    val title: String,
    val mime: String,
    val inlineImage: Boolean,
    val attachmentId: String? = null,
    val contentUploaded: Boolean = false,
    val insertionAnchor: String? = null,
)

data class AttachmentUploadState(
    val pendingCount: Int = 0,
    val isSyncing: Boolean = false,
    val error: String? = null,
    val currentTitle: String? = null,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
)

/** The active editor still owns an unsaved buffer; retry once that buffer is flushed. */
class AttachmentInsertionDeferred : IOException("Attachment insertion deferred")

/**
 * An account-scoped outbox. The picker URI is never retained: bytes and metadata are
 * committed in one directory rename before stage returns. A confirmed binary PUT is
 * durable before appendReference runs. That callback must durably and idempotently
 * append to the existing note-edit outbox; it may be called again after a crash.
 */
class AttachmentUploadQueue(
    private val context: Context,
    private val api: TriliumApi,
    private val repository: NotesRepository,
    private val resources: NoteResources = NoteResources(context, api),
    private val appendReference: suspend (PendingAttachmentUpload, AttachmentMeta) -> Unit,
) {
    private val directory = File(context.filesDir, "attachment-uploads").apply { mkdirs() }
    private val syncMutex = Mutex()
    private val stageMutex = Mutex()
    private val mutable = MutableStateFlow(AttachmentUploadState(pendingCount = activeEntries().size))
    val state = mutable.asStateFlow()

    /** Trash suspends uploads without losing staged bytes; restore makes them runnable again. */
    suspend fun refreshPendingCount(): Int = withContext(Dispatchers.IO) {
        activeEntries().size.also { mutable.value = mutable.value.copy(pendingCount = it, error = if (it == 0) null else mutable.value.error) }
    }

    private fun activeEntries(): List<File> = entries().filter { folder ->
        runCatching { !repository.isRecoverablyDeleted(read(folder).noteId) }.getOrDefault(true)
    }

    /** Read durable entries rather than a transient foreground count. */
    suspend fun hasPending(noteId: String): Boolean = withContext(Dispatchers.IO) {
        entries().any { folder ->
            // A damaged entry must not allow a destructive restore to race an upload.
            runCatching { read(folder).noteId == noteId }.getOrDefault(true)
        }
    }

    suspend fun stage(uri: Uri, noteId: String, inlineImage: Boolean, insertionAnchor: String? = null): PendingAttachmentUpload =
        withContext(Dispatchers.IO) {
            require(noteId.matches(Regex("[A-Za-z0-9_]{4,32}")) && noteId != "new_note")
            require(uri.scheme == "content" || uri.scheme == "file")
            val document = repository.cachedNote(noteId) ?: throw IOException("Open the note first")
            require(document.note.isEditable && !document.conflict)
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
            }
            val title = uploadTitle(name ?: uri.lastPathSegment)
            val mime = resolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                ?.takeIf { it.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) }
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(title.substringAfterLast('.', "").lowercase(Locale.ROOT))
                ?: "application/octet-stream"
            require(!inlineImage || mime.startsWith("image/"))
            require(insertionAnchor == null || isAttachmentAnchor(insertionAnchor))
            val upload = PendingAttachmentUpload(UUID.randomUUID().toString(), noteId, title, mime, inlineImage,
                insertionAnchor = insertionAnchor)
            val stage = File(directory, ".stage-${upload.uploadId}")
            if (!stage.mkdirs()) throw IOException("Could not stage attachment")
            try {
                val target = AtomicFile(File(stage, "content"))
                val output = target.startWrite()
                try {
                    val input = resolver.openInputStream(uri) ?: throw IOException("Could not open attachment")
                    input.use {
                        val chunk = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = it.read(chunk)
                            if (count < 0) break
                            output.write(chunk, 0, count)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    target.finishWrite(output)
                } catch (error: Throwable) {
                    target.failWrite(output)
                    throw error
                }
                save(stage, upload)
                if (!stage.renameTo(File(directory, upload.uploadId))) throw IOException("Could not commit attachment")
                mutable.value = mutable.value.copy(pendingCount = activeEntries().size, error = null)
                upload
            } finally {
                stage.deleteRecursively()
            }
        }

    /** Stable IDs make external-share staging replayable after process death. Completion records contain no URLs. */
    suspend fun stageFile(file: File, noteId: String, title: String, mime: String, inlineImage: Boolean,
        uploadId: String, insertionAnchor: String? = null): PendingAttachmentUpload = withContext(Dispatchers.IO) { stageMutex.withLock stage@{
        require(isAttachmentAnchor(uploadId))
        require(noteId.matches(Regex("[A-Za-z0-9_]{4,32}")) && noteId != "new_note")
        require(insertionAnchor == null || isAttachmentAnchor(insertionAnchor))
        require(mime.matches(Regex("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")))
        require(!inlineImage || mime.startsWith("image/"))
        val completed = File(directory, ".completed/$uploadId")
        val destination = File(directory, uploadId)
        if (File(destination, "entry.json").isFile || File(destination, "entry.json.bak").isFile)
            return@stage read(destination).also { require(it.noteId == noteId) }
        val upload = PendingAttachmentUpload(uploadId, noteId, uploadTitle(title), mime, inlineImage,
            insertionAnchor = insertionAnchor)
        if (completed.isFile || File(completed.path + ".bak").isFile) {
            require(AtomicFile(completed).readFully().toString(Charsets.UTF_8) == noteId)
            return@stage upload
        }
        require(repository.cachedNote(noteId)?.let { it.note.isEditable && !it.conflict } == true)
        val stage = File(directory, ".stage-$uploadId")
        // A previous interrupted copy is private staging, never a committed upload.
        stage.deleteRecursively()
        if (!stage.mkdirs()) throw IOException("Could not stage attachment")
        try {
            val atomic = AtomicFile(File(stage, "content"))
            val output = atomic.startWrite()
            try {
                file.inputStream().use { input ->
                    val bytes = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(bytes)
                        if (count < 0) break
                        output.write(bytes, 0, count)
                    }
                }
                atomic.finishWrite(output)
            } catch (error: Throwable) { atomic.failWrite(output); throw error }
            save(stage, upload)
            if (!stage.renameTo(destination)) throw IOException("Could not commit attachment")
            mutable.value = mutable.value.copy(pendingCount = activeEntries().size, error = null)
            upload
        } finally { stage.deleteRecursively() }
    } }

    suspend fun pendingAnchors(): Set<String> = withContext(Dispatchers.IO) {
        entries().mapNotNull { runCatching { read(it).insertionAnchor }.getOrNull() }.toSet()
    }

    /** Call after repository.syncPending(): local note IDs stay stable when created. */
    suspend fun syncPending(): AttachmentUploadState = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val total = activeEntries().size
            var completed = 0
            mutable.value = AttachmentUploadState(total, isSyncing = true, totalCount = total)
            var failed = false
            try {
                for (folder in entries()) {
                    currentCoroutineContext().ensureActive()
                    try {
                        var upload = read(folder)
                        mutable.value = mutable.value.copy(currentTitle = upload.title)
                        // Native trash is reversible: retain the original outbox entry for restore.
                        if (repository.isRecoverablyDeleted(upload.noteId)) continue
                        // Legacy permanent deletions keep their bytes in the retained directory.
                        if (repository.isDeleted(upload.noteId)) {
                            retain(folder)
                            continue
                        }
                        if (repository.isHidden(upload.noteId)) continue
                        val owner = repository.cachedNote(upload.noteId) ?: continue
                        if (owner.remoteMissing) {
                            retain(folder)
                            continue
                        }
                        if (owner.conflict) continue
                        if (!owner.note.isEditable) throw IOException("Attachment owner is not editable")
                        // ETAPI does not accept a caller-supplied attachmentId. The unique
                        // temporary title recovers a POST whose response was lost.
                        val markerTitle = ".trilium-android-upload-${upload.uploadId}-${upload.title}"
                        var attachment = upload.attachmentId?.let { id ->
                            AttachmentMeta(id, upload.noteId, if (upload.inlineImage) "image" else "file", upload.mime, upload.title, 0)
                        } ?: api.getAttachments(upload.noteId).firstOrNull { it.title == markerTitle }
                            ?: api.createAttachment(upload.noteId, if (upload.inlineImage) "image" else "file", upload.mime, markerTitle, 0)
                        require(attachment.ownerId == upload.noteId)
                        if (upload.attachmentId == null) {
                            upload = upload.copy(attachmentId = attachment.id)
                            save(folder, upload)
                        }
                        if (!upload.contentUploaded) {
                            val content = File(folder, "content")
                            if (!content.isFile) throw IOException("Attachment content is missing")
                            api.putAttachmentContent(attachment.id, content)
                            upload = upload.copy(contentUploaded = true)
                            save(folder, upload)
                        }
                        api.patchAttachmentTitle(attachment.id, upload.title)
                        // POST metadata still refers to the initial empty blob. Validate
                        // the version after PUT so the next offline crawl reuses this copy.
                        val freshAttachments = api.getAttachments(upload.noteId)
                        repository.rememberAttachments(upload.noteId, freshAttachments)
                        attachment = freshAttachments.firstOrNull { it.id == attachment.id }
                            ?: throw IOException("Uploaded attachment metadata is missing")
                        resources.cacheUploadedAttachment(attachment, File(folder, "content"))
                        appendReference(upload, attachment)
                        val completedDirectory = File(directory, ".completed").apply { mkdirs() }
                        val completion = AtomicFile(File(completedDirectory, upload.uploadId))
                        val completionOutput = completion.startWrite()
                        try { completionOutput.write(upload.noteId.toByteArray(Charsets.UTF_8)); completion.finishWrite(completionOutput) }
                        catch (error: Throwable) { completion.failWrite(completionOutput); throw error }
                        // The note reference is now in the existing durable edit queue.
                        // Remove metadata first; an orphan content file is never re-enqueued.
                        AtomicFile(File(folder, "entry.json")).delete()
                        folder.deleteRecursively()
                        completed++
                        mutable.value = mutable.value.copy(completedCount = completed, pendingCount = activeEntries().size)
                    } catch (_: AttachmentInsertionDeferred) {
                        // The PUT is confirmed; this is an editor handoff, not a failure.
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: ApiException) {
                        val upload = runCatching { read(folder) }.getOrNull()
                        var ownerMissing = false
                        if (error.status == 404 && upload != null && !repository.isLocalCreate(upload.noteId)) {
                            try {
                                api.getNote(upload.noteId)
                                // Only the attachment disappeared. Keep its original bytes
                                // and create a replacement on the next attempt.
                                save(folder, upload.copy(attachmentId = null, contentUploaded = false))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (ownerError: ApiException) {
                                ownerMissing = ownerError.status == 404
                            } catch (_: Exception) { }
                        }
                        if (ownerMissing) {
                            try { retain(folder) } catch (_: IOException) { failed = true }
                        } else failed = true
                    } catch (_: Exception) {
                        failed = true
                    }
                }
            } finally {
                mutable.value = AttachmentUploadState(activeEntries().size, error = if (failed)
                    AppLanguage.get(context, R.string.msg_upload_retry) else null,
                    completedCount = completed, totalCount = total)
            }
            state.value
        }
    }

    private fun retain(folder: File) {
        val retained = File(context.filesDir, "attachment-uploads-retained").apply { mkdirs() }
        if (!folder.renameTo(File(retained, folder.name))) throw IOException("Could not retain attachment")
    }

    private fun entries(): List<File> = directory.listFiles().orEmpty().filter {
        it.isDirectory && it.name.matches(Regex("[a-f0-9-]{36}")) &&
            (File(it, "entry.json").isFile || File(it, "entry.json.bak").isFile)
    }.sortedBy { it.name }

    private fun save(folder: File, upload: PendingAttachmentUpload) {
        val json = JSONObject().put("uploadId", upload.uploadId).put("noteId", upload.noteId)
            .put("title", upload.title).put("mime", upload.mime).put("inlineImage", upload.inlineImage)
            .put("attachmentId", upload.attachmentId).put("contentUploaded", upload.contentUploaded)
            .put("insertionAnchor", upload.insertionAnchor)
        val atomic = AtomicFile(File(folder, "entry.json"))
        val output = atomic.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun read(folder: File): PendingAttachmentUpload {
        val json = JSONObject(AtomicFile(File(folder, "entry.json")).readFully().toString(Charsets.UTF_8))
        val upload = PendingAttachmentUpload(json.getString("uploadId"), json.getString("noteId"),
            json.getString("title"), json.getString("mime"), json.getBoolean("inlineImage"),
            json.optString("attachmentId").takeIf { it.isNotBlank() && it != "null" }, json.optBoolean("contentUploaded"),
            json.optString("insertionAnchor").takeIf { it.isNotBlank() && it != "null" })
        require(upload.insertionAnchor == null || isAttachmentAnchor(upload.insertionAnchor))
        require(upload.uploadId == folder.name)
        require(upload.noteId.matches(Regex("[A-Za-z0-9_]{4,32}")))
        require(upload.attachmentId == null || upload.attachmentId.matches(Regex("[A-Za-z0-9_]{4,32}")))
        require(!upload.contentUploaded || upload.attachmentId != null)
        return upload
    }
}

internal fun uploadTitle(value: String?): String = value.orEmpty().substringAfterLast('/').substringAfterLast('\\')
    .filterNot { it.isISOControl() }.trim().take(240).ifBlank { "attachment" }

/** Relative Trilium URL: credentials never enter note markup or JavaScript. */
fun attachmentReference(upload: PendingAttachmentUpload, attachment: AttachmentMeta, markdown: Boolean): String {
    require(attachment.id.matches(Regex("[A-Za-z0-9_]{4,32}")))
    val name = Uri.encode(upload.title)
    require(upload.noteId.matches(Regex("[A-Za-z0-9_]{4,32}")))
    val path = "api/attachments/${upload.noteId}/${attachment.id}/$name"
    if (markdown) {
        // CommonMark permits backslash-escaping every ASCII punctuation character.
        // This also prevents a filename from becoming raw inline HTML in the label.
        val label = buildString {
            upload.title.forEach { char ->
                if (char.code in 33..47 || char.code in 58..64 || char.code in 91..96 || char.code in 123..126)
                    append('\\')
                append(char)
            }
        }
        return "\n\n${if (upload.inlineImage) "!" else ""}[$label](<$path>)\n"
    }
    fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    return if (upload.inlineImage) "<p><img src=\"${escape(path)}\" alt=\"${escape(upload.title)}\"></p>"
    else "<p><a href=\"${escape(path)}\">${escape(upload.title)}</a></p>"
}


fun isAttachmentAnchor(value: String): Boolean = value.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))

/** Replace one durable cursor placeholder without reparsing or normalizing surrounding source. */
fun replaceAttachmentPlaceholder(content: String, upload: PendingAttachmentUpload, attachment: AttachmentMeta,
    markdown: Boolean): String? {
    val anchor = upload.insertionAnchor ?: return null
    require(isAttachmentAnchor(anchor))
    val target = "#pocket-upload-$anchor"
    val pattern = if (markdown) Regex("\\[(?:\\\\.|[^\\]\\n])*]\\(<?${Regex.escape(target)}>?\\)")
        else Regex("<a\\b(?=[^>]*\\bhref=[\"']${Regex.escape(target)}[\"'])[^>]*>[\\s\\S]*?</a>", RegexOption.IGNORE_CASE)
    val match = pattern.find(content) ?: return null
    val reference = attachmentReference(upload, attachment, markdown).trim().let {
        if (markdown) it else it.removePrefix("<p>").removeSuffix("</p>")
    }
    return content.replaceRange(match.range, reference)
}

/** Remove abandoned picker reservations, keeping anchors belonging to durable uploads or the active picker. */
fun clearAbandonedAttachmentPlaceholders(content: String, activeAnchors: Set<String>, markdown: Boolean): String {
    val anchorPattern = if (markdown)
        Regex("\\[(?:\\\\.|[^\\]\\n])*]\\(<?#pocket-upload-([a-f0-9-]{36})>?\\)")
    else Regex("<a\\b(?=[^>]*\\bhref=[\"']#pocket-upload-([a-f0-9-]{36})[\"'])[^>]*>[\\s\\S]*?</a>", RegexOption.IGNORE_CASE)
    return anchorPattern.replace(content) { match -> if (match.groupValues[1] in activeAnchors) match.value else "" }
}
