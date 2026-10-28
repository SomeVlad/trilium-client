package ru.vlad.triliumnotes.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Owned labels and relations are preserved by a subtree copy. */
data class NoteAttribute(
    val id: String,
    val noteId: String,
    val type: String,
    val name: String,
    val value: String,
    val position: Int = 0,
    val isInheritable: Boolean = false,
) {
    internal fun toJson() = JSONObject().put("attributeId", id).put("noteId", noteId)
        .put("type", type).put("name", name).put("value", value).put("position", position)
        .put("isInheritable", isInheritable)

    companion object {
        internal fun fromJson(json: JSONObject) = NoteAttribute(json.optString("attributeId"),
            json.optString("noteId"), json.optString("type"), json.optString("name"),
            json.optString("value"), json.optInt("position"), json.optBoolean("isInheritable"))
    }
}

/** Native Trilium trash, limited by the server's recent-history window (500 events). */
data class TrashItem(
    val noteId: String,
    val title: String,
    val deletedAt: String? = null,
    val canRestore: Boolean = true,
    val localOnly: Boolean = false,
    val pending: Boolean = false,
    val restoring: Boolean = false,
)

internal data class TreeBranch(val id: String, val noteId: String, val parentId: String,
    val prefix: String = "", val position: Int = 0, val expanded: Boolean = false) {
    fun toJson() = JSONObject().put("branchId", id).put("noteId", noteId).put("parentNoteId", parentId)
        .put("prefix", prefix).put("notePosition", position).put("isExpanded", expanded)
    companion object {
        fun parse(json: JSONObject) = TreeBranch(json.getString("branchId"), json.getString("noteId"),
            json.getString("parentNoteId"), json.optString("prefix").takeUnless { it == "null" }.orEmpty(),
            json.optInt("notePosition"), json.optBoolean("isExpanded"))
    }
}

internal suspend fun TriliumApi.treeBranch(branchId: String): TreeBranch =
    TreeBranch.parse(JSONObject(etapiRequest("GET", listOf("branches", branchId))))

internal suspend fun TriliumApi.placeBranch(branch: TreeBranch): TreeBranch =
    TreeBranch.parse(JSONObject(etapiRequest("POST", listOf("branches"), branch.toJson().apply {
        // POST rejects caller-supplied branchId; Trilium derives it from parent and child.
        remove("branchId")
    }.toString())))

internal suspend fun TriliumApi.removeBranch(branchId: String) {
    try { etapiRequest("DELETE", listOf("branches", branchId), expectedCode = 204) }
    catch (error: ApiException) { if (error.status != 404) throw error }
}

internal suspend fun TriliumApi.positionBranch(branchId: String, position: Int) {
    etapiRequest("PATCH", listOf("branches", branchId), JSONObject().put("notePosition", position).toString())
}

internal suspend fun TriliumApi.refreshOrdering(parentId: String) {
    etapiRequest("POST", listOf("refresh-note-ordering", parentId), expectedCode = 204)
}

internal suspend fun TriliumApi.nativeTrash(): List<TrashItem> {
    val entries = JSONArray(etapiRequest("GET", listOf("notes", "history")))
    return (0 until entries.length()).mapNotNull { index ->
        val item = entries.getJSONObject(index)
        val id = item.optString("noteId")
        if (!(item.optBoolean("current_isDeleted") || item.optInt("current_isDeleted") == 1) || (!id.matches(Regex("[A-Za-z0-9_]{4,32}")) || id.startsWith('_'))) null
        else TrashItem(id, item.optString("current_title").ifBlank { item.optString("title") },
            item.optString("utcDate").takeIf { it.isNotBlank() && it != "null" },
            canRestore = item.optBoolean("canBeUndeleted"))
    }.distinctBy { it.noteId }
}

/** One entry can span multiple idempotent requests; it stays durable until all succeed. */
internal data class TreeOperation(
    val id: String,
    val kind: String,
    val noteId: String,
    val fromParentId: String = "",
    val targetParentId: String = "",
    val branchId: String = "",
    val prefix: String = "",
    val position: Int = 0,
    val expanded: Boolean = false,
    val order: List<String> = emptyList(),
    val orderNotes: List<String> = emptyList(),
    val revision: Long = 0,
    val copyNodes: List<CopyNode> = emptyList(),
    val copyEdges: List<TreeBranch> = emptyList(),
    val copyResources: List<CopyResource> = emptyList(),
) {
    fun toJson() = JSONObject().put("id", id).put("kind", kind).put("noteId", noteId)
        .put("fromParentId", fromParentId).put("targetParentId", targetParentId).put("branchId", branchId)
        .put("prefix", prefix).put("position", position).put("expanded", expanded)
        .put("order", JSONArray(order)).put("orderNotes", JSONArray(orderNotes)).put("revision", revision)
        .put("copyNodes", JSONArray(copyNodes.map { it.toJson() }))
        .put("copyEdges", JSONArray(copyEdges.map { it.toJson() }))
        .put("copyResources", JSONArray(copyResources.map { it.toJson() }))
    companion object {
        fun parse(json: JSONObject) = TreeOperation(json.getString("id"), json.getString("kind"),
            json.getString("noteId"), json.optString("fromParentId"), json.optString("targetParentId"),
            json.optString("branchId"), json.optString("prefix"), json.optInt("position"),
            json.optBoolean("expanded"), json.optJSONArray("order").strings(), json.optJSONArray("orderNotes").strings(), json.optLong("revision"),
            json.optJSONArray("copyNodes").objects().map(CopyNode::parse),
            json.optJSONArray("copyEdges").objects().map(TreeBranch::parse),
            json.optJSONArray("copyResources").objects().map(CopyResource::parse))
    }
}

internal data class CopyNode(val originalId: String, val id: String, val parentId: String,
    val title: String, val type: String, val mime: String, val attributes: List<NoteAttribute> = emptyList(),
    val shellCreated: Boolean = false) {
    fun toJson() = JSONObject().put("originalId", originalId).put("id", id).put("parentId", parentId)
        .put("title", title).put("type", type).put("mime", mime).put("shellCreated", shellCreated)
        .put("attributes", JSONArray(attributes.map { it.toJson() }))
    companion object {
        fun parse(json: JSONObject) = CopyNode(json.getString("originalId"), json.getString("id"),
            json.getString("parentId"), json.getString("title"), json.getString("type"), json.getString("mime"),
            json.optJSONArray("attributes").objects().map(NoteAttribute::fromJson), json.optBoolean("shellCreated"))
    }
}

internal data class CopyResource(val originalId: String, val localId: String, val ownerId: String,
    val attachment: Boolean, val mime: String, val title: String, val role: String = "file",
    val position: Int = 0, val remoteId: String? = null, val uploaded: Boolean = false) {
    val fileName: String get() = (if (attachment) "attachment_" else "note_") + localId
    fun toJson() = JSONObject().put("originalId", originalId).put("localId", localId).put("ownerId", ownerId)
        .put("attachment", attachment).put("mime", mime).put("title", title).put("role", role)
        .put("position", position).put("remoteId", remoteId).put("uploaded", uploaded)
    companion object {
        fun parse(json: JSONObject) = CopyResource(json.getString("originalId"), json.getString("localId"),
            json.getString("ownerId"), json.getBoolean("attachment"), json.getString("mime"), json.getString("title"),
            json.optString("role", "file"), json.optInt("position"),
            json.optString("remoteId").takeIf { it.isNotBlank() && it != "null" }, json.optBoolean("uploaded"))
    }
}

/** Replace ID tokens only inside native note/resource URLs; unrelated prose stays byte-for-byte. */
internal fun remapTreeReferences(body: String, ids: Map<String, String>, serverUrl: String? = null): String {
    val links = Regex("(?:https?://[^\\s\\\"'<>)]*)?(?:(?:api|etapi)/(?:attachments|images|notes)/|#(?:root/)?)" +
        "[A-Za-z0-9_/#.%?=&+\\-]*")
    val remapped = links.replace(body) { match ->
        val absolute = runCatching { java.net.URI(match.value) }.getOrNull()?.takeIf { it.isAbsolute }
        val server = serverUrl?.let { runCatching { java.net.URI(it) }.getOrNull() }
        fun port(uri: java.net.URI) = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
        val sameServer = absolute == null || (server != null && absolute.scheme.equals(server.scheme, true) &&
            absolute.host.equals(server.host, true) && port(absolute) == port(server) &&
            (absolute.path.orEmpty().startsWith(server.path.orEmpty().trimEnd('/') + "/") || absolute.path == server.path))
        if (!sameServer) match.value else Regex("[A-Za-z0-9_]{4,65}").replace(match.value) { token -> ids[token.value] ?: token.value }
    }
    val attributes = Regex("((?:data-note-id|data-attachment-id)\\s*=\\s*[\"'])([A-Za-z0-9_]{4,32})([\"'])", RegexOption.IGNORE_CASE)
    return attributes.replace(remapped) { match -> match.groupValues[1] +
        (ids[match.groupValues[2]] ?: match.groupValues[2]) + match.groupValues[3] }
}

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).map { getJSONObject(it) }
internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else
    (0 until length()).map { getString(it) }

internal class TreeOperationException(val resource: Int) : IOException("Tree operation needs attention")

/** Native structured note references use named JSON fields rather than HTML links. */
internal fun remapCopyContent(body: String, ids: Map<String, String>, type: String, serverUrl: String): String {
    val urls = remapTreeReferences(body, ids, serverUrl)
    if (type !in setOf("relationMap", "noteMap", "canvas", "geoMap", "mindMap", "search")) return urls
    val fields = Regex("(\"(?:noteId|sourceNoteId|targetNoteId|parentNoteId|attachmentId|notePath)\"\\s*:\\s*)\"((?:\\\\.|[^\"\\\\])*)\"")
    return fields.replace(urls) { match ->
        val value = runCatching { JSONObject("{\"value\":\"${match.groupValues[2]}\"}").getString("value") }.getOrNull()
            ?: return@replace match.value
        val remapped = ids[value] ?: value.split('/').joinToString("/") { ids[it] ?: it }
        if (remapped == value) match.value else match.groupValues[1] + JSONObject.quote(remapped)
    }
}
