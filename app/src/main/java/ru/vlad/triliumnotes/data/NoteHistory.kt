package ru.vlad.triliumnotes.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A revision stored by Trilium itself, rather than a device-local copy. */
data class NoteRevision(
    val id: String,
    val noteId: String,
    val title: String,
    val type: String,
    val mime: String,
    val createdAt: String?,
    val lastEditedAt: String?,
    val description: String = "",
    val source: String = "",
    val contentLength: Long? = null,
    val isProtected: Boolean = false,
) {
    val hasTextContent: Boolean
        get() = !isProtected && (type !in setOf("image", "file") || mime.startsWith("text/") ||
            mime in setOf("application/json", "application/javascript", "image/svg+xml"))

    /** Restoring another type would require changing the note's metadata and viewer. */
    fun canRestoreTo(note: NoteMeta): Boolean = hasTextContent && note.isEditable &&
        note.id == noteId && note.type == type &&
        note.mime.substringBefore(';').trim() == mime.substringBefore(';').trim()
}

data class RevisionDocument(val revision: NoteRevision, val content: String)

class ProtectedRevisionException : IOException("Protected revisions are unavailable through ETAPI")
class UnsupportedRevisionContentException : IOException("This revision has binary content")

/**
 * Official ETAPI routes, available in Trilium 0.105 and 0.106.
 *
 * ETAPI has no restore-revision route. The caller must preserve unsent edits and save
 * the current server revision before passing the selected title/content to the
 * existing repository save/sync pipeline. Reading a preview never changes a note.
 */
class NoteHistory(private val api: TriliumApi) {
    suspend fun list(noteId: String): List<NoteRevision> {
        requireEntityId(noteId)
        val array = JSONArray(api.historyRequest("GET", listOf("notes", noteId, "revisions")))
        val revisions = (0 until array.length()).map { index ->
            parseRevision(array.getJSONObject(index)).also { revision ->
                if (revision.noteId != noteId) throw IOException("Revision belongs to another note")
            }
        }
        // 0.105 may include protected revisions which cannot be read individually.
        return revisions.filterNot { it.isProtected }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<NoteRevision> { it.createdAt.orEmpty() }.thenBy { it.id })
    }

    suspend fun preview(noteId: String, revisionId: String): RevisionDocument {
        requireEntityId(noteId)
        requireEntityId(revisionId)
        val revision = parseRevision(JSONObject(api.historyRequest("GET", listOf("revisions", revisionId))))
        if (revision.id != revisionId || revision.noteId != noteId)
            throw IOException("Revision belongs to another note")
        if (revision.isProtected) throw ProtectedRevisionException()
        if (!revision.hasTextContent) throw UnsupportedRevisionContentException()
        val content = api.historyRequest("GET", listOf("revisions", revisionId, "content"), allowsHtmlBody = true)
        return RevisionDocument(revision, content)
    }

    /** Saves the current SERVER state; it does not upload a pending local draft. */
    suspend fun saveCurrentRevision(noteId: String, description: String = "") {
        requireEntityId(noteId)
        api.historyRequest("POST", listOf("notes", noteId, "revision"),
            JSONObject().put("description", description).toString())
    }
}

private fun requireEntityId(id: String) {
    require(id.matches(Regex("[a-zA-Z0-9_]{4,32}"))) { "Invalid Trilium entity ID" }
}

private fun parseRevision(json: JSONObject): NoteRevision {
    val id = json.getString("revisionId")
    val noteId = json.getString("noteId")
    requireEntityId(id)
    requireEntityId(noteId)
    fun value(name: String): String? = json.optString(name).takeIf { it.isNotBlank() && it != "null" }
    return NoteRevision(
        id = id,
        noteId = noteId,
        title = json.getString("title"),
        type = json.getString("type"),
        mime = json.optString("mime"),
        createdAt = value("utcDateCreated") ?: value("dateCreated"),
        lastEditedAt = value("utcDateLastEdited") ?: value("dateLastEdited"),
        description = value("description").orEmpty(),
        source = value("source").orEmpty(),
        contentLength = if (json.has("contentLength") && !json.isNull("contentLength"))
            json.optLong("contentLength").takeIf { it >= 0 } else null,
        isProtected = json.optBoolean("isProtected"),
    )
}
