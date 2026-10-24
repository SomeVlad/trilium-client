package ru.vlad.triliumnotes

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
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
class ConnectionLanguageTest {
    private lateinit var app: Application
    private val models = ViewModelStore()
    private var previousLanguage: String? = null
    private lateinit var session: File
    private var createdSessionFixture = false

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication()
        previousLanguage = AppLanguage.preference(app)
        AppLanguage.select(app, "ru")
        session = File(app.filesDir, "session.encrypted")
        check(!session.exists()) { "This test requires an empty synthetic application" }
    }

    @After fun clean() {
        models.clear()
        if (createdSessionFixture) session.delete()
        val editor = app.getSharedPreferences("app-language", 0).edit()
        if (previousLanguage == null) editor.remove("language")
        else editor.putString("language", previousLanguage)
        editor.commit()
    }

    private fun model() = ConnectionViewModel(app).also { models.put("connection", it) }

    @Test fun changingLanguageRetranslatesAnExistingSignInFailure() {
        val model = model()
        await { !model.state.value.restoring }
        model.connect("invalid-address", "synthetic-password", false, false)
        await { model.state.value.error != null }
        assertTrue(model.state.value.error!!.startsWith("Укажите"))
        AppLanguage.select(app, "en")
        model.refreshLanguage()
        assertTrue(model.state.value.error!!.startsWith("Enter the full server address"))
        assertFalse(model.state.value.error!!.contains("synthetic-password"))
        assertFalse(model.state.value.storageFailed)
        assertNull(model.state.value.services)
    }

    @Test fun changingLanguageRetainsTheStorageFailureAndOriginalFile() {
        val original = byteArrayOf(1, 2, 3)
        session.writeBytes(original)
        createdSessionFixture = true
        val model = model()
        await { !model.state.value.restoring }
        assertTrue(model.state.value.storageFailed)
        assertTrue(model.state.value.error!!.startsWith("Не удалось открыть"))
        AppLanguage.select(app, "en")
        model.refreshLanguage()
        assertTrue(model.state.value.storageFailed)
        assertTrue(model.state.value.error!!.startsWith("Could not open secure sign-in storage"))
        assertArrayEquals(original, session.readBytes())
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
