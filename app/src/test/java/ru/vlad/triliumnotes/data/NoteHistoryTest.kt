package ru.vlad.triliumnotes.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class NoteHistoryTest {
    private val server = MockWebServer()
    private lateinit var api: TriliumApi
    private lateinit var history: NoteHistory

    @Before fun start() {
        server.start()
        api = TriliumApi(server.url("/notes/").toString().trimEnd('/'), "history-test-token")
        history = NoteHistory(api)
    }

    @After fun stop() { server.shutdown() }

    @Test fun listUsesOfficialRouteAndKeepsDatesTitlesAndNamedVersions() = runBlocking {
        server.enqueue(json(JSONArray()
            .put(revision("revOlder", "2026-10-20 09:30:00.000Z").put("title", "Previous title"))
            .put(revision("revNewer", "2026-10-24 16:00:00.000Z")
                .put("description", "Before changing the structure").put("source", "etapi"))
            .put(revision("revHidden", "2026-10-25 00:00:00.000Z").put("isProtected", true))
            .toString()))

        val result = history.list("noteAAAA")

        assertEquals(listOf("revNewer", "revOlder"), result.map { it.id })
        assertEquals("2026-10-24 16:00:00.000Z", result[0].createdAt)
        assertEquals("Before changing the structure", result[0].description)
        assertEquals("etapi", result[0].source)
        assertEquals("Previous title", result[1].title)
        assertEquals(27L, result[0].contentLength)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/notes/etapi/notes/noteAAAA/revisions", request.path)
        assertEquals("history-test-token", request.getHeader("Authorization"))
        assertEquals(1, server.requestCount)
    }

    @Test fun previewReadsRevisionMetadataAndHtmlWithoutWritingTheNote() = runBlocking {
        server.enqueue(json(revision("revisionAAAA").toString()))
        val content = "<html><body><p>Saved content</p></body></html>"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(content))

        val result = history.preview("noteAAAA", "revisionAAAA")

        assertEquals("revisionAAAA", result.revision.id)
        assertEquals(content, result.content)
        val metadata = server.takeRequest()
        val body = server.takeRequest()
        assertEquals("GET", metadata.method)
        assertEquals("/notes/etapi/revisions/revisionAAAA", metadata.path)
        assertEquals("GET", body.method)
        assertEquals("/notes/etapi/revisions/revisionAAAA/content", body.path)
        assertEquals(2, server.requestCount)
    }

    @Test fun previewRejectsRevisionOfAnotherNoteBeforeFetchingContent() = runBlocking {
        server.enqueue(json(revision("revisionAAAA").put("noteId", "otherNote").toString()))
        expectFailure<IOException> { history.preview("noteAAAA", "revisionAAAA") }
        assertEquals(1, server.requestCount)
    }

    @Test fun previewRejectsUnexpectedRevisionIdBeforeFetchingContent() = runBlocking {
        server.enqueue(json(revision("anotherRevision").toString()))
        expectFailure<IOException> { history.preview("noteAAAA", "revisionAAAA") }
        assertEquals(1, server.requestCount)
    }

    @Test fun protectedPreviewCannotFetchContent() = runBlocking {
        server.enqueue(json(revision("revisionAAAA").put("isProtected", true).toString()))
        expectFailure<ProtectedRevisionException> { history.preview("noteAAAA", "revisionAAAA") }
        assertEquals(1, server.requestCount)
    }

    @Test fun binaryPreviewDoesNotDecodeFileAsText() = runBlocking {
        server.enqueue(json(revision("revisionAAAA").put("type", "file")
            .put("mime", "application/pdf").toString()))
        expectFailure<UnsupportedRevisionContentException> { history.preview("noteAAAA", "revisionAAAA") }
        assertEquals(1, server.requestCount)
    }

    @Test fun listRejectsResponseForAnotherNote() = runBlocking {
        server.enqueue(json(JSONArray().put(revision("revisionAAAA").put("noteId", "otherNote")).toString()))
        expectFailure<IOException> { history.list("noteAAAA") }
        assertEquals(1, server.requestCount)
    }

    @Test fun saveCurrentVersionUsesSingularRevisionRouteAndDescription() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        history.saveCurrentRevision("noteAAAA", "Before restoring")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/notes/etapi/notes/noteAAAA/revision", request.path)
        assertEquals("Before restoring", JSONObject(request.body.readUtf8()).getString("description"))
        assertEquals("history-test-token", request.getHeader("Authorization"))
        assertEquals(1, server.requestCount)
    }

    @Test fun unsupportedServerRemainsAnApiFailureWithoutInventingLocalHistory() = runBlocking {
        server.enqueue(json("""{"code":"NOT_FOUND"}""").setResponseCode(404))
        val error = expectFailure<ApiException> { history.list("noteAAAA") }
        assertEquals(404, error.status)
        assertEquals(1, server.requestCount)
    }

    @Test fun interceptedPreviewCannotBeUsedForRestoration() = runBlocking {
        server.enqueue(json(revision("revisionAAAA").toString()))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(
            """<!doctype html><html><head><title>CrowdSec Captcha</title></head>
                <body><form data-challenge-url="/challenge"></form></body></html>"""))
        expectFailure<UnexpectedHtmlResponseException> { history.preview("noteAAAA", "revisionAAAA") }
        assertEquals(2, server.requestCount)
    }

    @Test fun restoreRequiresSameNoteAndCompatibleEditableType() {
        val revision = NoteRevision("revisionAAAA", "noteAAAA", "Past", "text", "text/html",
            "2026-10-24 16:00:00.000Z", null)
        val note = NoteMeta("noteAAAA", "Current", "text", "text/html", false, "blobAAAA", null, emptyList())
        assertTrue(revision.canRestoreTo(note))
        assertFalse(revision.canRestoreTo(note.copy(id = "otherNote")))
        assertFalse(revision.canRestoreTo(note.copy(isProtected = true)))
        assertFalse(revision.canRestoreTo(note.copy(type = "code", mime = "text/markdown")))
        assertFalse(revision.copy(isProtected = true).canRestoreTo(note))
    }

    private fun revision(id: String, createdAt: String = "2026-10-24 16:00:00.000Z"): JSONObject = JSONObject()
        .put("revisionId", id).put("noteId", "noteAAAA").put("title", "Saved title")
        .put("type", "text").put("mime", "text/html").put("isProtected", false)
        .put("utcDateCreated", createdAt).put("utcDateLastEdited", "2026-10-24 15:59:00.000Z")
        .put("contentLength", 27)

    private fun json(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)

    private suspend inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try { block() }
        catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.simpleName}, got ${error.javaClass.simpleName}", error)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
