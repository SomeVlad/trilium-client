package ru.vlad.triliumnotes.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.NotesScreenState
import ru.vlad.triliumnotes.R
import ru.vlad.triliumnotes.data.NoteDocument
import ru.vlad.triliumnotes.data.NoteMeta
import ru.vlad.triliumnotes.data.SyncState
import java.util.concurrent.atomic.AtomicInteger

/** A delayed system-picker result must survive an attachment/restore editor-body replacement. */
class EditorPickerLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pickerResultSurvivesEditorEpochAndKeepsItsOriginalOwner() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var document by mutableStateOf(NoteDocument(NoteMeta("ownerAAAA", "Original owner", "text", "text/html",
            false, null, null, emptyList()), "<p>Before append</p>"))
        var state by mutableStateOf(NotesScreenState(loading = false))
        val editingEvents = AtomicInteger()
        val writes = AtomicInteger()
        var pickerRequest: Int? = null
        var capturedOwner: String? = null
        var deliveredOwner: String? = null
        var deliveredUri: Uri? = null
        val expectedUri = Uri.parse("content://synthetic-picker/selected-file.pdf")
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) {
                assertTrue(contract is ActivityResultContracts.OpenDocument)
                pickerRequest = requestCode
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                NotesTheme {
                    NoteEditorHost(document.note.id) {
                        EditorScreen(state, document, SyncState(), onBack = {}, onTitleChanged = { writes.incrementAndGet() },
                            onHtmlChanged = { writes.incrementAndGet() }, onSync = {}, onKeepConflictAsCopy = {},
                            onAttachmentPickStarted = { capturedOwner = document.note.id },
                            onAttachment = { uri, inline ->
                                assertFalse(inline)
                                deliveredOwner = capturedOwner
                                deliveredUri = uri
                            }, onEditingChanged = { assertFalse(it); editingEvents.incrementAndGet() })
                    }
                }
            }
        }
        val initialEditingEvents = editingEvents.get()
        compose.onNodeWithTag("attach").performClick()
        compose.onNodeWithText(AppLanguage.get(context, R.string.action_attach_file)).performClick()
        compose.waitUntil(10_000) { pickerRequest != null && editingEvents.get() > initialEditingEvents }
        assertNull(deliveredUri)
        compose.runOnIdle {
            document = document.copy(note = document.note.copy(title = "After append"), html = "<p>After append</p>")
            state = state.copy(editorEpoch = state.editorEpoch + 1)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("attach").assertIsDisplayed()
        compose.onNodeWithContentDescription(AppLanguage.get(context, R.string.edit_note)).assertIsDisplayed()
        compose.runOnIdle {
            registry.dispatchResult(requireNotNull(pickerRequest), Activity.RESULT_OK, Intent().setData(expectedUri))
        }
        compose.waitUntil(5_000) { deliveredUri != null }
        assertEquals(expectedUri, deliveredUri)
        assertEquals("ownerAAAA", deliveredOwner)
        assertEquals(0, writes.get())
    }
}
