package ru.vlad.triliumnotes.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TriliumApiChallengeTest {
    private val server = MockWebServer()
    private lateinit var api: TriliumApi

    @Before fun start() {
        server.start()
        api = TriliumApi(server.url("/").toString().trimEnd('/'), "test-token")
    }

    @After fun stop() { server.shutdown() }

    @Test fun captchaHtmlWithHttp200IsRecognizedBeforeJsonParsing() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/html; charset=utf-8")
            .setBody("<!doctype html><html><body>CAPTCHA private fixture</body></html>"))
        val error = expectHtmlChallenge { api.getNote("noteAAAA") }
        assertEquals(200, error.status)
    }

    @Test fun captchaHtmlWithHttp403IsRecognized() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403)
            .setHeader("Content-Type", "text/html")
            .setBody("<html><body>CrowdSec CAPTCHA</body></html>"))
        assertEquals(403, expectHtmlChallenge { api.search("test") }.status)
    }

    @Test fun htmlNoteContentRemainsAllowed() = runBlocking {
        val html = "<html><body>legitimate note content</body></html>"
        server.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/html; charset=utf-8")
            .setBody(html))
        assertEquals(html, api.getContent("noteAAAA"))
    }

    @Test fun exactCrowdSecCaptchaPageCannotBecomeNoteContent() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/html")
            .setBody("""<!doctype html><html><head><title>CrowdSec Captcha</title></head>
                <body><form data-challenge-url="/challenge"></form></body></html>"""))
        assertEquals(200, expectHtmlChallenge { api.getContent("noteAAAA") }.status)
    }

    @Test fun ordinaryNoteMentioningCaptchaOrChallengeAttributeIsAllowed() = runBlocking {
        val html = "<p>CrowdSec Captcha and data-challenge-url are documentation terms.</p>"
        server.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/html")
            .setBody(html))
        assertEquals(html, api.getContent("noteAAAA"))
    }

    @Test fun jsonContainingCaptchaWordIsNotRejected() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"noteId":"noteAAAA","title":"CAPTCHA","type":"text","mime":"text/html"}"""))
        assertEquals("CAPTCHA", api.getNote("noteAAAA").title)
    }

    @Test fun interceptedContentWriteIsNotAcknowledged() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/html")
            .setBody("<html><body>CAPTCHA</body></html>"))
        assertEquals(200, expectHtmlChallenge {
            api.putContent("noteAAAA", "<p>local draft</p>")
        }.status)
    }

    private suspend fun expectHtmlChallenge(block: suspend () -> Unit): UnexpectedHtmlResponseException {
        try {
            block()
        } catch (error: UnexpectedHtmlResponseException) {
            assertTrue(error.message.orEmpty().contains("HTML"))
            return error
        }
        throw AssertionError("Expected HTML challenge response")
    }
}
