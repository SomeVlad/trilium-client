package ru.vlad.triliumnotes

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ConnectionTotpTest {
    private val server = MockWebServer()
    private val models = ViewModelStore()
    private lateinit var app: Application
    private var previousLanguage: String? = null

    @Before fun prepare() {
        server.start()
        app = RuntimeEnvironment.getApplication()
        check(!File(app.filesDir, "session.encrypted").exists())
        previousLanguage = AppLanguage.preference(app)
        AppLanguage.select(app, "ru")
    }

    @After fun clean() {
        models.clear()
        server.shutdown()
        val editor = app.getSharedPreferences("app-language", 0).edit()
        if (previousLanguage == null) editor.remove("language") else editor.putString("language", previousLanguage)
        editor.commit()
    }

    private fun model() = ConnectionViewModel(app).also {
        models.put("connection", it)
        await { !it.state.value.restoring }
    }

    private fun reject(code: String) {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":401,"code":"$code"}"""))
    }

    @Test fun challengeSupportsRetryAndLanguageChangeWithoutRetainingCredentials() {
        val model = model()
        reject("TOTP_REQUIRED")
        model.connect(server.url("/").toString(), "synthetic-password", false, true)
        await { !model.state.value.busy }
        assertTrue(model.state.value.totpRequired)
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("totpToken"))
        assertFalse(model.state.value.toString().contains("synthetic-password"))
        AppLanguage.select(app, "en")
        model.refreshLanguage()
        assertTrue(model.state.value.error!!.startsWith("The server requires"))
        assertTrue(model.state.value.totpRequired)

        reject("WRONG_TOTP")
        model.connect(server.url("/").toString(), "synthetic-password", false, true, "000000")
        await { !model.state.value.busy }
        assertTrue(model.state.value.totpRequired)
        assertTrue(model.state.value.error!!.startsWith("Incorrect or already used code"))
        assertFalse(model.state.value.toString().contains("000000"))
        assertEquals("000000", JSONObject(server.takeRequest().body.readUtf8()).getString("totpToken"))
        assertNull(model.state.value.services)
        assertFalse(File(app.filesDir, "session.encrypted").exists())

        model.credentialsChanged()
        assertFalse(model.state.value.totpRequired)
        assertNull(model.state.value.error)
        // A new server/password starts with the original password-only handshake.
        reject("WRONG_PASSWORD")
        model.connect(server.url("/other").toString(), "new-password", false, true)
        await { !model.state.value.busy }
        assertFalse(model.state.value.totpRequired)
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("totpToken"))
        assertEquals(3, server.requestCount)
    }

    @Test fun tokenValidationNeverRequestsASecondFactor() {
        val model = model()
        reject("TOTP_REQUIRED")
        model.connect(server.url("/").toString(), "synthetic-token", true, true)
        await { !model.state.value.busy }
        assertFalse(model.state.value.totpRequired)
        assertEquals("/etapi/app-info", server.takeRequest().path)
        assertEquals(1, server.requestCount)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Connection state did not settle", condition())
    }
}
