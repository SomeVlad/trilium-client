package ru.vlad.triliumnotes.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TriliumApiCoverageTest {
    private val server = MockWebServer()
    private lateinit var api: TriliumApi

    @Before fun start() {
        server.start()
        api = TriliumApi(server.url("/").toString(), "test-token")
    }

    @After fun stop() { server.shutdown() }

    @Test fun searchIncludesBodyAndArchiveAndKeepsTriliumQuerySyntax() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"results\":[]}"))
        val query = "\"exact phrase\" #tag OR note.type = code"
        assertTrue(api.search(query).isEmpty())
        val url = server.takeRequest().requestUrl!!
        assertEquals(query, url.queryParameter("search"))
        assertEquals("false", url.queryParameter("fastSearch"))
        assertEquals("true", url.queryParameter("includeArchivedNotes"))
        assertEquals(null, url.queryParameter("limit"))
    }

    @Test fun oneInaccessibleBranchDoesNotHideOtherChildren() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"noteId":"root","childBranchIds":["branchAAAA","branchBBBB"]}"""))
        server.enqueue(MockResponse().setResponseCode(403))
        server.enqueue(MockResponse().setBody("""{"branchId":"branchBBBB","noteId":"noteBBBB","notePosition":10}"""))
        server.enqueue(MockResponse().setBody("""{"noteId":"noteBBBB","type":"code","mime":"text/plain"}"""))
        val result = api.getChildrenResult("root")
        assertEquals(listOf("branchAAAA"), result.failedBranchIds)
        assertEquals("noteBBBB", result.items.single().note.id)
    }

    @Test fun binaryBodiesKeepAllBytesAndMime() = runBlocking {
        val binary = byteArrayOf(0, -1, 42, -128, 10)
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png")
            .setBody(okio.Buffer().write(binary)))
        val result = api.getBinary("noteAAAA")
        assertArrayEquals(binary, result.bytes)
        assertEquals("image/png", result.mimeType)
        assertEquals("/etapi/notes/noteAAAA/content", server.takeRequest().path)
    }

    @Test fun attachmentContentUsesEtapiAttachmentRoute() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/pdf").setBody("synthetic-pdf"))
        assertEquals("application/pdf", api.getAttachmentContent("attachAA").mimeType)
        assertEquals("/etapi/attachments/attachAA/content", server.takeRequest().path)
    }

    @Test fun docNotesLoadVersionedPublicHtmlAndResolveRelativeImages() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"appVersion\":\"0.106.0\"}"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html")
            .setBody("<h1>Help</h1><img src=\"images/example.png\"><a href=\"#section\">Jump</a>"))
        val html = api.getDocContent("User Guide/Example")
        assertTrue(html.contains("/assets/v0.106.0/doc_notes/en/User%20Guide/images/example.png"))
        assertTrue(html.contains("href=\"#section\""))
        assertEquals("/etapi/app-info", server.takeRequest().path)
        assertEquals("/assets/v0.106.0/doc_notes/en/User%20Guide/Example.html", server.takeRequest().path)
    }

    @Test fun binaryRedirectDoesNotSendTokenToAnotherServer() = runBlocking {
        val other = MockWebServer()
        other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302)
                .setHeader("Location", other.url("/download")))
            try {
                api.getBinary("noteAAAA")
                throw AssertionError("Redirect must be rejected")
            } catch (error: ApiException) { assertEquals(302, error.status) }
            assertEquals(0, other.requestCount)
        } finally { other.shutdown() }
    }
}
