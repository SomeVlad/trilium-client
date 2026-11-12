package ru.vlad.triliumnotes.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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

    @Test fun catalogDocNotesUseExactPageAndResolveResourcesOnNewerServers() = runBlocking {
        for (version in listOf("0.107.0", "0.107.1-beta.1", "0.108.0", "1.0.0")) {
            val subpathApi = TriliumApi(server.url("/notes/").toString(), "test-token")
            server.enqueue(MockResponse().setBody(JSONObject().put("appVersion", version).toString()))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject()
                .put("doc_notes", JSONObject().put("task_state", "<h1>Selected documentation</h1>" +
                    "<img src='../../doc_notes/en/images/example.png'><a href='#root/targetAA'>Note</a>")
                    .put("share", "Wrong documentation page")).toString()))
            val html = subpathApi.getDocContent("task_state")
            assertTrue(html.contains("Selected documentation"))
            assertFalse(html.contains("Wrong documentation page"))
            assertTrue(html.contains("/notes/assets/v$version/doc_notes/en/images/example.png"))
            assertTrue(html.contains("href=\"#root/targetAA\""))
            assertEquals("GET", server.takeRequest().method)
            val catalog = server.takeRequest()
            assertEquals("GET", catalog.method)
            assertEquals("/notes/assets/v$version/translations/en/translation.json", catalog.path)
        }
        assertEquals(8, server.requestCount)
    }

    @Test fun unknownCatalogDocNameDoesNotLoadAnotherPageOrTryLegacyHtml() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"appVersion":"0.107.0"}"""))
        try { api.getDocContent("unknown_page"); fail("Expected unsupported documentation page") }
        catch (_: IOException) { }
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test fun missingOrMalformedCatalogPagesFailWithoutLegacyFallbackOrWrites() = runBlocking {
        for (body in listOf("not-json", "null", "{}", """{"doc_notes":null}""",
            """{"doc_notes":{"share":"Unrelated page"}}""", """{"doc_notes":{"task_state":null}}""",
            """{"doc_notes":{"task_state":42}}""", """{"doc_notes":{"task_state":""}}""")) {
            server.enqueue(MockResponse().setBody("""{"appVersion":"0.107.0"}"""))
            server.enqueue(MockResponse().setBody(body))
            try { api.getDocContent("task_state"); fail("Expected missing or invalid documentation") }
            catch (_: IOException) { }
            assertEquals("GET", server.takeRequest().method)
            val catalog = server.takeRequest()
            assertEquals("GET", catalog.method)
            assertEquals("/assets/v0.107.0/translations/en/translation.json", catalog.path)
        }
        assertEquals(16, server.requestCount)
    }

    @Test fun catalogSamplePagesExplainTheWebClientExampleInsteadOfDisplayingTemplateTokens() = runBlocking {
        for (name in listOf("launchbar_script_launcher", "launchbar_widget_launcher")) {
            server.enqueue(MockResponse().setBody("""{"appVersion":"0.107.0"}"""))
            server.enqueue(MockResponse().setBody(JSONObject().put("doc_notes", JSONObject()
                .put(name, "<h4>Example</h4><pre>{{sample}}</pre>")).toString()))
            val html = api.getDocContent(name)
            assertFalse(html.contains("{{sample}}"))
            assertTrue(html.contains("Trilium web client"))
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun docNameParserTreatsJsonNullAndMalformedValuesAsAbsentBeforeLabelFallback() {
        fun note(direct: Any?, label: Any? = "task_state") = JSONObject().put("noteId", "noteAAAA")
            .put("docName", direct ?: JSONObject.NULL)
            .put("attributes", JSONArray().put(JSONObject().put("type", "label").put("name", "docName")
                .put("value", label ?: JSONObject.NULL)))
        for (value in listOf(JSONObject.NULL, "", "null", 42, JSONObject()))
            assertEquals("task_state", parseNote(note(value)).docName)
        assertEquals("hidden", parseNote(note("hidden")).docName)
        assertNull(parseNote(note(null, null)).docName)
        assertNull(parseNote(note(null, 42)).docName)
        assertNull(parseNote(JSONObject().put("noteId", "noteAAAA")).docName)
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
