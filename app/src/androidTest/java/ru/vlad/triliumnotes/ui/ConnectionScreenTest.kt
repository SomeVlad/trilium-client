package ru.vlad.triliumnotes.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.Folder
import ru.vlad.triliumnotes.NotesScreenState
import ru.vlad.triliumnotes.data.NoteMeta
import ru.vlad.triliumnotes.data.SyncState
import ru.vlad.triliumnotes.data.TreeItem
import java.io.File

/** Real production forms with synthetic credentials; never opens a network connection. */
class ConnectionScreenTest {
    @get:Rule val compose = createComposeRule()
    private data class Login(val server: String, val secret: String, val token: Boolean, val http: Boolean)

    @Test fun passwordSubmissionUsesPasswordBranchAndModeChangeClearsSecret() {
        val attempts = mutableListOf<Login>()
        compose.setContent {
            NotesTheme { ConnectionScreen(onConnect = { server, secret, token, http ->
                attempts += Login(server, secret, token, http)
            }) }
        }
        compose.onNodeWithTag("connection-server").performTextInput("https://notes.example.test")
        compose.onNodeWithTag("connection-secret").performTextInput("synthetic-password")
        compose.onNodeWithTag("connection-secret").performImeAction()
        compose.runOnIdle {
            assertEquals(listOf(Login("https://notes.example.test", "synthetic-password", false, false)), attempts)
        }
        compose.onNodeWithTag("connection-mode").performScrollTo().performClick()
        compose.onNodeWithText("ETAPI-токен").assertIsDisplayed()
        compose.onNodeWithTag("connection-secret").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("connection-submit").assertIsNotEnabled()
        compose.onNodeWithTag("connection-secret").performTextInput("synthetic-token")
        compose.onNodeWithTag("connection-secret").performImeAction()
        compose.runOnIdle {
            assertEquals(Login("https://notes.example.test", "synthetic-token", true, false), attempts.last())
            assertEquals(2, attempts.size)
        }
        compose.onNodeWithTag("connection-mode").performScrollTo().performClick()
        compose.onNodeWithTag("connection-secret").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("Пароль Trilium").assertIsDisplayed()
    }

    @Test fun plainHttpRequiresExplicitCheckboxBeforeSubmission() {
        val attempts = mutableListOf<Login>()
        compose.setContent {
            NotesTheme { ConnectionScreen(onConnect = { server, secret, token, http ->
                attempts += Login(server, secret, token, http)
            }) }
        }
        compose.onNodeWithTag("connection-server").performTextInput("http://192.0.2.10:8080")
        compose.onNodeWithTag("connection-secret").performTextInput("synthetic-password")
        compose.onNodeWithTag("connection-submit").assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithTag("connection-submit").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(Login("http://192.0.2.10:8080", "synthetic-password", false, true)), attempts)
        }
    }

    @Test fun busyFormCannotResubmitOrChangeCredentials() {
        var busy by mutableStateOf(false)
        var attempts = 0
        compose.setContent { NotesTheme { ConnectionScreen(busy = busy, onConnect = { _, _, _, _ -> attempts++ }) } }
        compose.onNodeWithTag("connection-server").performTextInput("https://notes.example.test")
        compose.onNodeWithTag("connection-secret").performTextInput("synthetic-password")
        compose.runOnIdle { busy = true }
        compose.onNodeWithTag("connection-submit").performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag("connection-server").assertIsNotEnabled()
        compose.onNodeWithTag("connection-secret").assertIsNotEnabled()
        compose.onNodeWithTag("connection-mode").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, attempts) }
    }

    @Test fun disconnectExplainsPendingChangesAndRequiresConfirmation() {
        var disconnects = 0
        compose.setContent {
            NotesTheme {
                ConnectionSettingsScreen("https://notes.example.test", 3, false, null,
                    onBack = {}, onDisconnect = { disconnects++ })
            }
        }
        compose.onNodeWithText("Не отправлено изменений: 3. Они сохранятся до следующего входа на этот сервер.")
            .assertIsDisplayed()
        compose.onNodeWithTag("connection-disconnect").performClick()
        compose.onNodeWithText("Выйти из Trilium?").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertEquals(0, disconnects) }
        compose.onNodeWithTag("connection-disconnect").performClick()
        compose.onNodeWithText("Выйти", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(1, disconnects) }
    }

    @Test fun captureLightConnectionAndFolderStates() = captureStates(false)
    @Test fun captureDarkConnectionAndFolderStates() = captureStates(true)

    private fun captureStates(dark: Boolean) {
        var screen by mutableStateOf("login")
        var busy by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        val emptyFolder = NoteMeta("qa_empty_folder", "Новый проект", "book", "text/html", false,
            null, null, emptyList(), listOf("root"))
        val prefix = if (dark) "dark" else "light"
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                NotesTheme {
                    when (screen) {
                        "login" -> ConnectionScreen(busy = busy, error = error, onConnect = { _, _, _, _ -> })
                        "settings" -> ConnectionSettingsScreen("https://notes.example.test", 3, false, null,
                            onBack = {}, onDisconnect = {})
                        else -> LibraryScreen(
                            if (screen == "empty-folder") NotesScreenState(loading = false,
                                folders = listOf(Folder("root", "Заметки"), Folder(emptyFolder.id, emptyFolder.title)),
                                currentFolderNote = emptyFolder, currentFolderHasContent = false)
                            else NotesScreenState(loading = false, items = listOf(TreeItem("qa_branch",
                                emptyFolder, "", 10, false))),
                            SyncState(), onBack = {}, onSearch = {}, onRefresh = {}, onSync = {}, onCreate = {},
                            onOpen = {}, onFolder = {}, onOpenFolderContent = {}, onCreateFolder = {},
                            onConnectionSettings = {})
                    }
                }
            }
        }
        compose.onNodeWithTag("connection-server").performTextInput("https://notes.example.test")
        compose.onNodeWithTag("connection-secret").performTextInput("synthetic-password")
        compose.onNodeWithTag("connection-secret").performImeAction()
        screenshot("$prefix-01-password")
        compose.onNodeWithTag("connection-mode").performClick()
        compose.onNodeWithTag("connection-secret").performTextInput("synthetic-token")
        compose.onNodeWithTag("connection-secret").performImeAction()
        screenshot("$prefix-02-token")
        compose.onNodeWithTag("connection-mode").performClick()
        compose.onNodeWithTag("connection-secret").performTextInput("synthetic-password")
        compose.onNodeWithTag("connection-secret").performImeAction()
        compose.runOnIdle { error = "Неверный пароль Trilium. Проверьте пароль и попробуйте ещё раз." }
        screenshot("$prefix-03-error")
        compose.runOnIdle { error = null; busy = true }
        screenshot("$prefix-04-busy")
        compose.runOnIdle { screen = "settings" }
        screenshot("$prefix-05-settings-pending")
        compose.onNodeWithTag("connection-disconnect").performClick()
        screenshot("$prefix-06-settings-confirm")
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { screen = "library" }
        compose.onNodeWithTag("create-note").performClick()
        screenshot("$prefix-07-new-folder-menu")
        compose.onNodeWithTag("new-folder-menu").performClick()
        screenshot("$prefix-08-new-folder-dialog")
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { screen = "empty-folder" }
        compose.onNodeWithText("Нет вложенных заметок").assertIsDisplayed()
        screenshot("$prefix-09-empty-folder")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // UiAutomation includes real dialog/dropdown windows as well as the Compose activity.
        Thread.sleep(250)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "auth-review")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
