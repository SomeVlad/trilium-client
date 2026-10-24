package ru.vlad.triliumnotes.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.MainActivity
import ru.vlad.triliumnotes.data.NotesServices
import ru.vlad.triliumnotes.data.SessionStore
import java.io.File

class LanguageSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private var previousChoice: String? = null

    @Before fun setRussianForTest() {
        previousChoice = AppLanguage.preference(app)
        AppLanguage.select(app, "ru")
    }

    @After fun restoreChoice() {
        if (previousChoice == null) app.getSharedPreferences("app-language", Context.MODE_PRIVATE)
            .edit().remove("language").commit()
        else AppLanguage.select(app, requireNotNull(previousChoice))
    }

    private fun openTwoLanguageMenu() {
        compose.onNodeWithTag("language-selector").performScrollTo().performClick()
        compose.onAllNodes(hasTestTag("language-option-ru") or hasTestTag("language-option-en"))
            .assertCountEquals(2)
        compose.onNodeWithTag("language-option-ru").assertIsDisplayed()
        compose.onNodeWithTag("language-option-en").assertIsDisplayed()
        compose.onNodeWithText("Системный").assertDoesNotExist()
        compose.onNodeWithText("System").assertDoesNotExist()
    }

    private fun awaitIntro(text: String) {
        compose.waitUntil(10_000) {
            runCatching { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    @Test fun loginOffersExactlyRussianAndEnglishWithoutSigningIn() {
        var choice: String? = null
        compose.setContent {
            NotesTheme { ConnectionScreen(onConnect = { _, _, _, _ -> }, onLanguageSelected = { choice = it }) }
        }
        compose.onNodeWithTag("language-selector").performScrollTo()
        screenshot("login-row")
        openTwoLanguageMenu()
        screenshot("login-menu")
        compose.onNodeWithTag("language-option-en").performClick()
        compose.runOnIdle { assertEquals("en", choice) }
    }

    @Test fun connectionSettingsOfferTheSameTwoChoices() {
        var choice: String? = null
        compose.setContent {
            NotesTheme { ConnectionSettingsScreen("https://notes.example.test", 3, false, null,
                onBack = {}, onDisconnect = {}, onLanguageSelected = { choice = it }) }
        }
        compose.onNodeWithTag("language-selector").performScrollTo()
        screenshot("settings-row")
        openTwoLanguageMenu()
        screenshot("settings-menu")
        compose.onNodeWithTag("language-option-ru").performClick()
        compose.runOnIdle { assertEquals("ru", choice) }
    }

    @Test fun mainActivityAppliesAndRemembersBothLanguagesAcrossRecreationAndRelaunch() {
        // Preserve any owner session/cache: this acceptance test requires an empty test install.
        assertNull("Run language acceptance on an empty test installation", SessionStore(app).load())
        assertEquals(false, NotesServices.hasLegacyCache(app))
        var activity: ActivityScenario<MainActivity>? = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("language-selector")).fetchSemanticsNodes().isNotEmpty() }
            var russian: MainActivity? = null
            requireNotNull(activity).onActivity { russian = it }
            openTwoLanguageMenu()
            compose.onNodeWithTag("language-option-ru").performClick()
            requireNotNull(activity).onActivity { assertSame("The same language needs no recreation", russian, it) }
            assertEquals("ru", AppLanguage.preference(app))
            openTwoLanguageMenu()
            compose.onNodeWithTag("language-option-en").performClick()
            awaitIntro("Connect to your notes server")
            requireNotNull(activity).onActivity {
                assertNotSame("Changing the language recreates the activity", russian, it)
                assertEquals("en", it.resources.configuration.locales[0].language)
            }
            assertEquals("en", AppLanguage.preference(app))
            requireNotNull(activity).close()
            activity = ActivityScenario.launch(MainActivity::class.java)
            awaitIntro("Connect to your notes server")
            var english: MainActivity? = null
            requireNotNull(activity).onActivity { english = it }
            openTwoLanguageMenu()
            compose.onNodeWithTag("language-option-ru").performClick()
            awaitIntro("Подключитесь к своему серверу заметок")
            requireNotNull(activity).onActivity {
                assertNotSame(english, it)
                assertEquals("ru", it.resources.configuration.locales[0].language)
            }
            assertEquals("ru", AppLanguage.preference(app))
        } finally { activity?.close() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "manual-language-review")
            assertTrue(directory.isDirectory || directory.mkdirs())
            File(directory, "$name.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { image.recycle() }
    }
}
