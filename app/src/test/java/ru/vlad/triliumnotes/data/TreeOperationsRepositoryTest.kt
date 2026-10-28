package ru.vlad.triliumnotes.data

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class TreeOperationsRepositoryTest {
    private val server = MockWebServer()
    private val fake = TreeServer()
    private lateinit var directory: File
    private lateinit var context: Context
    private fun api(offline: Boolean = false) = TriliumApi(if (offline) "http://127.0.0.1:1" else server.url("/").toString(),
        "synthetic-tree-token", OkHttpClient.Builder().retryOnConnectionFailure(false)
            .connectTimeout(100, TimeUnit.MILLISECONDS).readTimeout(500, TimeUnit.MILLISECONDS).build())
    @Before fun start() {
        directory = Files.createTempDirectory("tree-operations").toFile()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = directory }
        server.dispatcher = fake
        server.start()
    }
    @After fun stop() { server.shutdown(); directory.deleteRecursively() }

    private suspend fun hydrate(repository: NotesRepository, binary: Boolean = false) {
        val client = api()
        val resources = NoteResources(context, client)
        for (id in fake.notes.keys.toList()) {
            repository.listChildren(id)
            val document = repository.load(id)
            val catalog = client.getAttachments(id)
            repository.rememberAttachments(id, catalog)
            if (binary) {
                if (document.note.type in setOf("image", "file")) resources.prefetchNote(document.note)
                catalog.forEach { resources.prefetchAttachment(it, document.note) }
            }
        }
    }

    @Test fun offlineMovePreservesIdsDraftAndBothPhasesRetryWithoutDuplicateOrOrphan() = runBlocking {
        var repository = NotesRepository(context, api())
        hydrate(repository)
        repository.saveDraft("childAAA", "Draft", "preserved local draft")
        repository.moveBranch("folderAA_childAAA", "folderAA", "targetAA")
        assertTrue(repository.cachedChildren("folderAA").orEmpty().none { it.note.id == "childAAA" })
        assertTrue(repository.cachedChildren("targetAA").orEmpty().any { it.note.id == "childAAA" })
        assertEquals("preserved local draft", repository.cachedNote("childAAA")?.html)
        repository = NotesRepository(context, api(offline = true))
        assertEquals(2, repository.syncState.value.pendingCount)
        assertEquals(2, repository.syncPending().pendingCount)
        fake.dropBranchPost = true
        repository = NotesRepository(context, api())
        assertEquals(1, repository.syncPending().pendingCount)
        assertTrue(fake.branches.containsKey("folderAA_childAAA"))
        assertTrue(fake.branches.containsKey("targetAA_childAAA"))
        fake.dropBranchDelete = true
        assertEquals(1, NotesRepository(context, api()).syncPending().pendingCount)
        assertFalse(fake.deleted.contains("childAAA"))
        repository = NotesRepository(context, api())
        assertEquals(0, repository.syncPending().pendingCount)
        assertEquals(listOf("targetAA"), repository.load("childAAA").note.parentNoteIds)
        assertEquals("preserved local draft", fake.notes["childAAA"]?.body)
        assertEquals(1, fake.branches.values.count { it.noteId == "childAAA" })
    }

    @Test fun cloneKeepsOriginalAndRejectsCyclesThroughAnyCloneParent() = runBlocking {
        val repository = NotesRepository(context, api())
        hydrate(repository)
        repository.cloneBranch("folderAA_childAAA", "folderAA", "targetAA")
        assertEquals(0, repository.syncPending().pendingCount)
        assertEquals(setOf("folderAA", "targetAA"), fake.parents("childAAA").toSet())
        try {
            repository.moveBranch("root_folderAA", "root", "childAAA")
            fail("Moving an ancestor into its descendant must fail")
        } catch (error: TreeOperationException) { assertEquals(ru.vlad.triliumnotes.R.string.tree_cycle, error.resource) }
        try {
            repository.cloneBranch("root_targetAA", "root", "childAAA")
            fail("Clone parent also makes a cycle")
        } catch (_: TreeOperationException) { }
        assertEquals(0, repository.syncState.value.pendingCount)
    }

    @Test fun pendingPlacementSurvivesStaleTreeRefreshAndReorderPersistsAcrossRestart() = runBlocking {
        var repository = NotesRepository(context, api())
        hydrate(repository)
        repository.moveBranch("folderAA_childAAA", "folderAA", "targetAA")
        repository.listChildren("folderAA")
        repository.listChildren("targetAA")
        assertTrue(repository.cachedChildren("folderAA").orEmpty().none { it.note.id == "childAAA" })
        assertTrue(repository.cachedChildren("targetAA").orEmpty().any { it.note.id == "childAAA" })
        val reversed = repository.cachedChildren("root").orEmpty().map { it.branchId }.reversed()
        repository.reorderChildren("root", reversed)
        repository = NotesRepository(context, api())
        assertEquals(reversed, repository.cachedChildren("root").orEmpty().map { it.branchId })
        assertEquals(0, repository.syncPending().pendingCount)
        assertEquals(reversed, repository.listChildren("root").map { it.branchId })
        assertEquals(1, fake.orderRefreshes)
    }

    @Test fun unsentTrashRestoresEntireDraftOfflineWithoutServerMutation() = runBlocking {
        var repository = NotesRepository(context, api())
        hydrate(repository)
        repository.saveDraft("childAAA", "Draft title", "unsent unique content")
        repository.trash("folderAA")
        assertTrue(repository.isDeleted("childAAA"))
        assertTrue(repository.isRecoverablyDeleted("folderAA"))
        assertTrue(repository.isRecoverablyDeleted("childAAA"))
        assertFalse(repository.isRecoverablyDeleted("targetAA"))
        assertNull(repository.cachedNote("childAAA"))
        repository = NotesRepository(context, api(offline = true))
        assertTrue(repository.cachedTrash().single().canRestore)
        repository.restore("folderAA")
        assertEquals("unsent unique content", repository.cachedNote("childAAA")?.html)
        assertFalse(repository.isDeleted("folderAA"))
        assertTrue(repository.cachedTrash().isEmpty())
        assertEquals(1, repository.syncState.value.pendingCount)
        assertTrue(fake.deleted.isEmpty())
    }

    @Test fun nativeTrashLostResponseThenRestoreRetainsSourceIdAndLocalDraft() = runBlocking {
        var repository = NotesRepository(context, api())
        hydrate(repository)
        repository.saveDraft("folderAA", "Draft folder", "folder offline body")
        repository.trash("folderAA")
        fake.dropNoteDelete = true
        assertEquals(1, repository.syncPending().pendingCount)
        assertTrue("folderAA" in fake.deleted)
        repository = NotesRepository(context, api(offline = true))
        repository.restore("folderAA")
        assertTrue(repository.isDeleted("folderAA"))
        assertEquals(1, repository.syncPending().pendingCount)
        fake.dropRestore = true
        assertEquals(1, NotesRepository(context, api()).syncPending().pendingCount)
        repository = NotesRepository(context, api())
        assertEquals(1, repository.syncPending().pendingCount) // restored draft waits for normal edit sync
        assertEquals("folder offline body", repository.cachedNote("folderAA")?.html)
        assertEquals("Draft folder", repository.cachedNote("folderAA")?.note?.title)
        assertFalse(repository.isDeleted("childAAA"))
        assertEquals(0, repository.syncPending().pendingCount)
        assertEquals("folder offline body", fake.notes["folderAA"]?.body)
        assertEquals(1, fake.restoreCalls)
    }

    @Test fun legacyDeleteTombstoneIsNotAdvertisedAsRecoverableDraft() = runBlocking {
        var repository = NotesRepository(context, api())
        hydrate(repository)
        repository.saveDraft("folderAA", "Old draft", "legacy erased draft")
        repository.delete("folderAA")
        assertTrue(repository.cachedTrash().isEmpty())
        assertFalse(repository.isRecoverablyDeleted("folderAA"))
        assertFalse(repository.isRecoverablyDeleted("childAAA"))
        assertEquals(0, repository.syncPending().pendingCount)
        assertFalse(File(directory, "trilium-cache.json").readText().contains("legacy erased draft"))
        repository = NotesRepository(context, api())
        assertTrue(repository.listTrash().any { it.noteId == "folderAA" && it.canRestore })
        repository.restore("folderAA")
        assertEquals(0, repository.syncPending().pendingCount)
        assertNull(repository.cachedNote("folderAA")) // discarded legacy local body is never fabricated
        assertEquals("Folder body", repository.load("folderAA").html)
    }

    @Test fun incompleteCopyRejectsBeforeStagingAnyNotesOrOperations() = runBlocking {
        val repository = NotesRepository(context, api())
        repository.listChildren("root")
        repository.load("folderAA")
        try { repository.copySubtree("folderAA", "targetAA"); fail("Incomplete subtree must fail") }
        catch (_: TreeOperationException) { }
        assertEquals(0, repository.syncState.value.pendingCount)
        assertEquals(0, fake.creates)
        assertTrue(File(directory, "tree-copies").listFiles().isNullOrEmpty())
    }

    @Test fun completeSubtreeCopyPreservesClonesBinaryAttachmentsAttributesAndRecoversLostAttachmentPost() = runBlocking {
        fake.addBinaryAndAttachment()
        var repository = NotesRepository(context, api())
        hydrate(repository, binary = true)
        val copy = repository.copySubtree("folderAA", "targetAA")
        val copyChildren = repository.cachedChildren(copy.note.id).orEmpty()
        val copiedChild = copyChildren.first { it.note.type == "text" }.note.id
        val copiedImage = copyChildren.first { it.note.type == "image" }.note.id
        assertNotEquals("folderAA", copy.note.id)
        assertFalse(repository.cachedNote(copiedChild)!!.html.contains("attachAA"))
        assertTrue(repository.cachedNote(copiedChild)!!.html.contains(copiedImage))
        fake.dropAttachmentPost = true
        assertTrue(repository.syncPending().pendingCount > 0)
        assertEquals(1, fake.attachmentCreates)
        repository = NotesRepository(context, api())
        assertEquals(0, repository.syncPending().pendingCount)
        assertEquals(1, fake.attachmentCreates)
        assertEquals(3, fake.creates)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, -1), fake.notes[copiedImage]!!.binary)
        val newAttachment = fake.attachments.values.single { it.ownerId == copiedChild }
        assertArrayEquals("synthetic image bytes".toByteArray(), newAttachment.bytes)
        assertEquals("picture.png", newAttachment.title)
        assertTrue(fake.notes[copiedChild]!!.body.contains(newAttachment.id))
        assertTrue(fake.notes[copiedChild]!!.body.contains(copy.note.id))
        assertFalse(fake.notes[copiedChild]!!.body.contains("attachAA"))
        assertEquals("source-label", fake.attributes.values.single { it.optString("noteId") == copy.note.id }.optString("name"))
        assertTrue(fake.notes["childAAA"]!!.body.contains("attachAA"))
        assertTrue(fake.notes["childAAA"]!!.body.contains("imageAAA"))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, -1), fake.notes["imageAAA"]!!.binary)
        assertTrue(File(directory, "tree-copies").listFiles().isNullOrEmpty())
    }

    @Test fun bookSubtreeWithClonedDescendantCopiesAsIndependentDag() = runBlocking {
        fake.notes["bookAAAA"] = TreeServer.Note("Book", "book")
        fake.notes["canvasAA"] = TreeServer.Note("Canvas", "relationMap", "application/json", "{\"notes\":[{\"noteId\":\"childAAA\",\"x\":1,\"y\":2}],\"label\":\"childAAA\"}")
        fake.branches["folderAA_bookAAAA"] = TreeBranch("folderAA_bookAAAA", "bookAAAA", "folderAA", position = 20)
        fake.branches["bookAAAA_childAAA"] = TreeBranch("bookAAAA_childAAA", "childAAA", "bookAAAA", position = 10)
        fake.branches["bookAAAA_canvasAA"] = TreeBranch("bookAAAA_canvasAA", "canvasAA", "bookAAAA", position = 20)
        var repository = NotesRepository(context, api())
        hydrate(repository)
        val copy = repository.copySubtree("folderAA", "targetAA")
        val children = repository.cachedChildren(copy.note.id)!!
        val copiedBook = children.single { it.note.type == "book" }.note.id
        val copiedChild = children.single { it.note.type == "text" }.note.id
        val copiedCanvas = repository.cachedChildren(copiedBook)!!.single { it.note.type == "relationMap" }.note.id
        repository = NotesRepository(context, api())
        assertEquals(0, repository.syncPending().pendingCount)
        assertEquals(setOf(copy.note.id, copiedBook), fake.parents(copiedChild).toSet())
        assertEquals("book", fake.notes[copiedBook]!!.type)
        val mappedCanvas = JSONObject(fake.notes[copiedCanvas]!!.body)
        assertEquals(copiedChild, mappedCanvas.getJSONArray("notes").getJSONObject(0).getString("noteId"))
        assertEquals("childAAA", mappedCanvas.getString("label"))
        assertEquals("Child body", fake.notes[copiedChild]!!.body)
        assertEquals(4, fake.creates)
    }

    @Test fun trashAndRestoreBinaryCopyNeverReplacesBinaryBytesWithEmptyText() = runBlocking {
        fake.addBinaryAndAttachment()
        var repository = NotesRepository(context, api())
        hydrate(repository, binary = true)
        val copy = repository.copySubtree("imageAAA", "targetAA")
        repository.trash(copy.note.id)
        assertEquals(0, repository.syncPending().pendingCount)
        assertTrue(copy.note.id in fake.deleted)
        repository = NotesRepository(context, api())
        repository.restore(copy.note.id)
        assertEquals(0, repository.syncPending().pendingCount)
        assertFalse(repository.hasPendingEdits(copy.note.id))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, -1), fake.notes[copy.note.id]!!.binary)
    }

    @Test fun movingLocalChildOutOfUnsentTrashedFolderDoesNotBlockOutbox() = runBlocking {
        var repository = NotesRepository(context, api())
        val folder = repository.createFolder("root", "Unsent folder")
        val child = repository.create(folder.note.id, "Child", "kept body")
        val childBranch = repository.cachedChildren(folder.note.id)!!.single().branchId
        repository.moveBranch(childBranch, folder.note.id, "root")
        repository.trash(folder.note.id)
        repository = NotesRepository(context, api())
        assertEquals(0, repository.syncPending().pendingCount)
        assertEquals(listOf("root"), fake.parents(child.note.id))
        assertEquals("kept body", fake.notes[child.note.id]!!.body)
        assertFalse(folder.note.id in fake.notes)
    }

    @Test fun staleAttachmentCatalogRejectsCopyBeforeItCanRetainSourceOwnedLink() = runBlocking {
        val repository = NotesRepository(context, api())
        hydrate(repository)
        // Simulate a newly uploaded attachment after the previous full offline crawl.
        repository.saveDraft("childAAA", "Child", "<img src=\"api/attachments/childAAA/newAttAA/p.png\">")
        val before = repository.syncState.value.pendingCount
        try { repository.copySubtree("childAAA", "targetAA"); fail("Unknown newly uploaded attachment must not be shared silently") }
        catch (_: TreeOperationException) { }
        assertEquals(before, repository.syncState.value.pendingCount)
        assertEquals(0, fake.creates)
    }

    @Test fun nativeReferenceRemappingLeavesExternalSitesAndProseUntouched() {
        val content = "<a href=\"#root/folderAA/childAAA\">childAAA</a>" +
            "<section data-note-id=\"childAAA\"></section>" +
            "<img src=\"https://elsewhere.example/api/images/childAAA/p.png\">"
        val copied = remapTreeReferences(content, mapOf("folderAA" to "newFolAA", "childAAA" to "newChild"), server.url("/").toString())
        assertTrue(copied.contains("#root/newFolAA/newChild"))
        assertTrue(copied.contains(">childAAA</a>"))
        assertTrue(copied.contains("data-note-id=\"newChild\""))
        assertTrue(copied.contains("https://elsewhere.example/api/images/childAAA/p.png"))
    }

    @Test fun captureIdempotencySurvivesRestartAndDoesNotReplaceExistingDraft() = runBlocking {
        var repository = NotesRepository(context, api())
        val created = repository.createWithId("root", "Captured", "first", "captureA", "root_captureA")
        repository.saveDraft(created.note.id, "Edited title", "edited first")
        repository = NotesRepository(context, api())
        repository.createWithId("root", "Captured", "first", "captureA", "root_captureA")
        repository.appendCapturedText("captureA", "capture-request-001", " appended")
        repository = NotesRepository(context, api())
        repository.appendCapturedText("captureA", "capture-request-001", " appended")
        assertEquals("edited first appended", repository.cachedNote("captureA")!!.html)
        assertEquals("Edited title", repository.cachedNote("captureA")!!.note.title)
        assertEquals(0, repository.syncPending().pendingCount)
        assertEquals(1, fake.creates)
        repository.appendCapturedText("captureA", "capture-request-001", " appended")
        assertEquals(0, repository.syncState.value.pendingCount)
    }

    private class TreeServer : Dispatcher() {
        data class Note(var title: String, val type: String = "text", val mime: String = "text/html",
            var body: String = "", var binary: ByteArray = byteArrayOf())
        data class Attachment(val id: String, val ownerId: String, var title: String,
            val mime: String, val role: String, val position: Int, var bytes: ByteArray = byteArrayOf())
        val notes = linkedMapOf("root" to Note("Root", "book"), "folderAA" to Note("Folder", body = "Folder body"),
            "targetAA" to Note("Target", "book"), "childAAA" to Note("Child", body = "Child body"))
        val branches = linkedMapOf("root_folderAA" to TreeBranch("root_folderAA", "folderAA", "root", position = 10),
            "root_targetAA" to TreeBranch("root_targetAA", "targetAA", "root", position = 20),
            "folderAA_childAAA" to TreeBranch("folderAA_childAAA", "childAAA", "folderAA", "prefix", 10))
        val attachments = linkedMapOf<String, Attachment>()
        val attributes = linkedMapOf<String, JSONObject>()
        val deleted = mutableSetOf<String>()
        val deleteGroups = mutableMapOf<String, Set<String>>()
        var dropBranchPost = false
        var dropBranchDelete = false
        var dropNoteDelete = false
        var dropRestore = false
        var dropAttachmentPost = false
        var creates = 0
        var attachmentCreates = 0
        var restoreCalls = 0
        var orderRefreshes = 0
        fun parents(id: String) = branches.values.filter { it.noteId == id }.map { it.parentId }
        fun meta(id: String): JSONObject {
            val note = requireNotNull(notes[id])
            return JSONObject().put("noteId", id).put("title", note.title).put("type", note.type).put("mime", note.mime)
                .put("blobId", "blob-$id").put("utcDateModified", "2026-10-28T00:00:00Z")
                .put("isProtected", false).put("parentNoteIds", JSONArray(parents(id)))
                .put("childBranchIds", JSONArray(branches.values.filter { it.parentId == id && it.noteId !in deleted }.map { it.id }))
                .put("attributes", JSONArray(attributes.values.filter { it.optString("noteId") == id }))
        }
        fun addBinaryAndAttachment() {
            notes["imageAAA"] = Note("Image", "image", "image/png", binary = byteArrayOf(0, 1, 2, 3, -1))
            branches["folderAA_imageAAA"] = TreeBranch("folderAA_imageAAA", "imageAAA", "folderAA", position = 20)
            notes["childAAA"]!!.body = "<p><a href=\"#root/folderAA\">folder</a><img src=\"api/images/imageAAA/image.png\"><img src=\"api/attachments/childAAA/attachAA/picture.png\"></p>"
            attachments["attachAA"] = Attachment("attachAA", "childAAA", "picture.png", "image/png", "image", 10,
                "synthetic image bytes".toByteArray())
            attributes["attrAAAA"] = JSONObject().put("attributeId", "attrAAAA").put("noteId", "folderAA")
                .put("type", "label").put("name", "source-label").put("value", "keep me").put("position", 10)
        }
        private fun json(item: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(item.toString())
        private fun lost() = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl!!.pathSegments.drop(1)
            val method = request.method
            if (path == listOf("create-note") && method == "POST") {
                val data = JSONObject(request.body.readUtf8())
                val id = data.getString("noteId")
                if (id in notes) return MockResponse().setResponseCode(409)
                notes[id] = Note(data.getString("title"), data.getString("type"), data.getString("mime"), data.optString("content"))
                val parent = data.getString("parentNoteId")
                branches["${parent}_$id"] = TreeBranch("${parent}_$id", id, parent, position = 100)
                creates++
                return json(JSONObject().put("note", meta(id))).setResponseCode(201)
            }
            if (path == listOf("notes", "history")) {
                return json(JSONArray(deleted.map { id -> JSONObject().put("noteId", id).put("current_title", notes[id]!!.title)
                    .put("current_isDeleted", 1).put("canBeUndeleted", parents(id).any { it !in deleted }) }))
            }
            if (path.firstOrNull() == "notes" && path.size >= 2) {
                val id = path[1]
                val note = notes[id] ?: return MockResponse().setResponseCode(404)
                if (path.last() == "undelete" && method == "POST") {
                    if (id !in deleted) return MockResponse().setResponseCode(400)
                    restoreCalls++
                    deleted.removeAll(deleteGroups[id].orEmpty() + id)
                    if (dropRestore) { dropRestore = false; return lost() }
                    return json(JSONObject().put("success", true))
                }
                if (path.size == 2 && method == "DELETE") {
                    val group = mutableSetOf(id)
                    var changed: Boolean
                    do {
                        changed = false
                        notes.keys.forEach { child ->
                            if (child != "root" && child !in group && parents(child).isNotEmpty() && parents(child).all { it in group }) {
                                group += child; changed = true
                            }
                        }
                    } while (changed)
                    deleted += group
                    deleteGroups[id] = group
                    if (dropNoteDelete) { dropNoteDelete = false; return lost() }
                    return MockResponse().setResponseCode(204)
                }
                if (id in deleted) return MockResponse().setResponseCode(404)
                if (path.size == 2) return when (method) {
                    "PATCH" -> { note.title = JSONObject(request.body.readUtf8()).getString("title"); json(meta(id)) }
                    else -> json(meta(id))
                }
                if (path.last() == "attachments") return json(JSONArray(attachments.values.filter { it.ownerId == id }.map { attachmentJson(it) }))
                if (path.last() == "content") {
                    if (method == "PUT") {
                        val bytes = request.body.readByteArray()
                        if (note.type in setOf("image", "file")) note.binary = bytes else note.body = bytes.toString(Charsets.UTF_8)
                        return MockResponse().setResponseCode(204)
                    }
                    return MockResponse().setHeader("Content-Type", note.mime).setBody(okio.Buffer().write(
                        if (note.type in setOf("image", "file")) note.binary else note.body.toByteArray()))
                }
            }
            if (path.firstOrNull() == "branches") {
                if (path.size == 1 && method == "POST") {
                    val body = JSONObject(request.body.readUtf8())
                    if (body.keys().asSequence().any { it !in setOf("noteId", "parentNoteId", "notePosition", "prefix", "isExpanded") })
                        return MockResponse().setResponseCode(400)
                    val branch = TreeBranch.parse(body.put("branchId", "${body.getString("parentNoteId")}_${body.getString("noteId")}"))
                    branches[branch.id] = branch
                    if (dropBranchPost) { dropBranchPost = false; return lost() }
                    return json(branch.toJson()).setResponseCode(201)
                }
                val branch = branches[path[1]] ?: return MockResponse().setResponseCode(404)
                if (method == "DELETE") {
                    branches.remove(branch.id)
                    if (dropBranchDelete) { dropBranchDelete = false; return lost() }
                    return MockResponse().setResponseCode(204)
                }
                if (method == "PATCH") branches[branch.id] = branch.copy(position = JSONObject(request.body.readUtf8()).getInt("notePosition"))
                return json(branches[branch.id]!!.toJson())
            }
            if (path.firstOrNull() == "attachments") {
                if (path.size == 1 && method == "POST") {
                    val data = JSONObject(request.body.readUtf8())
                    val id = "newAtt${++attachmentCreates}"
                    val item = Attachment(id, data.getString("ownerId"), data.getString("title"), data.getString("mime"),
                        data.getString("role"), data.getInt("position"))
                    attachments[id] = item
                    if (dropAttachmentPost) { dropAttachmentPost = false; return lost() }
                    return json(attachmentJson(item)).setResponseCode(201)
                }
                val item = attachments[path[1]] ?: return MockResponse().setResponseCode(404)
                if (path.last() == "content") {
                    if (method == "PUT") { item.bytes = request.body.readByteArray(); return MockResponse().setResponseCode(204) }
                    return MockResponse().setHeader("Content-Type", item.mime).setBody(okio.Buffer().write(item.bytes))
                }
                if (method == "PATCH") item.title = JSONObject(request.body.readUtf8()).getString("title")
                return json(attachmentJson(item))
            }
            if (path.firstOrNull() == "attributes") {
                if (method == "POST") {
                    val item = JSONObject(request.body.readUtf8()); attributes[item.getString("attributeId")] = item
                    return json(item).setResponseCode(201)
                }
                return attributes[path[1]]?.let(::json) ?: MockResponse().setResponseCode(404)
            }
            if (path.firstOrNull() == "refresh-note-ordering") { orderRefreshes++; return MockResponse().setResponseCode(204) }
            return MockResponse().setResponseCode(404)
        }
        private fun attachmentJson(item: Attachment) = JSONObject().put("attachmentId", item.id).put("ownerId", item.ownerId)
            .put("title", item.title).put("mime", item.mime).put("role", item.role).put("position", item.position).put("blobId", "attachment-${item.id}")
    }
}
