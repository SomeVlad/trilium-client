package ru.vlad.triliumnotes.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class TriliumAuthTest {
    private val server = MockWebServer()
    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun validValidation() {
        server.enqueue(MockResponse().setBody("""{"appVersion":"0.100.0"}"""))
        server.enqueue(MockResponse().setBody("""{"noteId":"root","title":"Root","type":"text","mime":"text/html","childBranchIds":[]}"""))
    }

    @Test fun passwordLoginValidatesTokenBeforeReturningAndPreservesSubpath() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"authToken":"generated-secret"}"""))
        validValidation()
        val session = TriliumAuth().authenticate(server.url("/trilium/").toString(), "password-value", allowInsecureHttp = true)
        val login = server.takeRequest()
        assertEquals("/trilium/etapi/auth/login", login.path)
        assertEquals("POST", login.method)
        assertNull(login.getHeader("Authorization"))
        assertEquals("password-value", JSONObject(login.body.readUtf8()).getString("password"))
        assertEquals("generated-secret", server.takeRequest().getHeader("Authorization"))
        assertEquals("/trilium/etapi/notes/root", server.takeRequest().path)
        assertTrue(session.passwordLogin)
        assertFalse(session.toString().contains("generated-secret"))
    }

    @Test fun existingTokenDoesNotCreateNewToken() = runBlocking {
        validValidation()
        val session = TriliumAuth().authenticate(server.url("/").toString(), "Bearer existing-token", useToken = true, allowInsecureHttp = true)
        assertFalse(session.passwordLogin)
        assertEquals("/etapi/app-info", server.takeRequest().path)
        assertEquals("existing-token", server.takeRequest().getHeader("Authorization"))
        assertEquals(2, server.requestCount)
    }

    @Test fun badPasswordReturnsTypedFailureWithoutExtraRequests() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        try {
            TriliumAuth().authenticate(server.url("/").toString(), "bad", allowInsecureHttp = true)
            fail("Expected invalid credentials")
        } catch (error: ApiException) { assertEquals(401, error.status) }
        assertEquals(1, server.requestCount)
    }

    @Test fun htmlChallengeIsReportedAsChallenge() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>Proxy login</body></html>"))
        try {
            TriliumAuth().authenticate(server.url("/").toString(), "password", allowInsecureHttp = true)
            fail("Expected proxy HTML failure")
        } catch (error: UnexpectedHtmlResponseException) { assertEquals(200, error.status) }
    }

    @Test fun loginRedirectNeverReceivesPassword() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/external-login")))
        try {
            TriliumAuth().authenticate(server.url("/").toString(), "password", allowInsecureHttp = true)
            fail("Expected redirect failure")
        } catch (error: ApiException) { assertEquals(307, error.status) }
        server.takeRequest()
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun validationRedirectNeverReceivesToken() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/external-login")))
        try {
            TriliumAuth().authenticate(server.url("/").toString(), "token", useToken = true, allowInsecureHttp = true)
            fail("Expected redirect failure")
        } catch (error: ApiException) { assertEquals(302, error.status) }
        server.takeRequest()
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun malformedTokenResponseNeverProducesSession() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        try {
            TriliumAuth().authenticate(server.url("/").toString(), "password", allowInsecureHttp = true)
            fail("Expected malformed response failure")
        } catch (_: InvalidAuthResponseException) { }
        assertEquals(1, server.requestCount)
    }

    @Test fun rootAccessMustValidateToo() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"appVersion":"0.100.0"}"""))
        server.enqueue(MockResponse().setResponseCode(403))
        try {
            TriliumAuth().authenticate(server.url("/").toString(), "token", useToken = true, allowInsecureHttp = true)
            fail("Expected denied root failure")
        } catch (error: ApiException) { assertEquals(403, error.status) }
    }

    @Test fun normalizationRejectsAmbiguousAndCredentialContainingUrls() {
        listOf("notes.example", "ftp://notes.example", "https://user:secret@notes.example", "https://notes.example?a=b", "https://notes.example#fragment", "http://notes.example").forEach { input ->
            try { normalizeServerUrl(input); fail("Accepted invalid URL") }
            catch (_: InvalidServerUrlException) { }
        }
        assertEquals("https://notes.example/sub/path", normalizeServerUrl(" https://NOTES.example:443/sub/path/ "))
    }
    @Test fun invalidHeaderTokenNeverLeaksIntoUserFacingFailure() = runBlocking {
        val secret = "private-token\nexfiltrate"
        try {
            TriliumAuth().authenticate(server.url("/").toString(), secret, useToken = true, allowInsecureHttp = true)
            fail("Expected invalid token")
        } catch (error: IllegalArgumentException) {
            assertFalse(authFailureMessage(org.robolectric.RuntimeEnvironment.getApplication(), error).contains("private-token"))
            assertFalse(authFailureMessage(org.robolectric.RuntimeEnvironment.getApplication(), error).contains("exfiltrate"))
        }
        assertEquals(0, server.requestCount)
        assertFalse(authFailureMessage(org.robolectric.RuntimeEnvironment.getApplication(), IllegalArgumentException("secret-value")).contains("secret-value"))
    }

}
