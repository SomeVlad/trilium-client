package ru.vlad.triliumnotes.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R

class AttachmentPickerControllerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explicitControllerReceivesDelayedFileResultAfterOwnerUiAndSavedStateRestoration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val restoration = StateRestorationTester(compose)
        var showEditor by mutableStateOf(true)
        var requestCode: Int? = null
        var delivered: Uri? = null
        var deliveredOwner: String? = null
        var cancelled = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) {
                assertTrue(contract is ActivityResultContracts.OpenDocument)
                assertEquals("*/*", (input as Array<*>).single())
                requestCode = code
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MaterialTheme {
                    var pickerOwner by rememberSaveable { mutableStateOf<String?>(null) }
                    val controller = rememberAttachmentPickerController(onPicked = { uri, inline ->
                        assertFalse(inline)
                        delivered = uri; deliveredOwner = pickerOwner
                    }, onCancelled = { cancelled++ })
                    if (showEditor) AttachmentPicker(enabled = true, onPickStarted = { pickerOwner = "ownerAAAA" },
                        onPicked = { _, _ -> fail("Explicit controller must own result delivery") }, controller = controller)
                }
            }
        }
        compose.onNodeWithTag("attach").performClick()
        compose.onNodeWithText(AppLanguage.get(context, R.string.action_attach_file)).performClick()
        compose.waitUntil(5_000) { requestCode != null }
        compose.runOnIdle { showEditor = false }
        restoration.emulateSavedInstanceStateRestore()
        val expected = Uri.parse("content://synthetic-picker/delayed.pdf")
        compose.runOnIdle {
            registry.dispatchResult(requireNotNull(requestCode), Activity.RESULT_OK, Intent().setData(expected))
        }
        compose.waitUntil(5_000) { delivered != null }
        assertEquals(expected, delivered)
        assertEquals("ownerAAAA", deliveredOwner)
        assertEquals(0, cancelled)
    }

    @Test fun rootControllerReceivesCancellationWithoutAnyEditorUi() {
        var controller: AttachmentPickerController? = null
        var requestCode: Int? = null
        var cancelled = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) {
                assertTrue(contract is ActivityResultContracts.OpenDocument)
                assertEquals("image/*", (input as Array<*>).single())
                requestCode = code
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                controller = rememberAttachmentPickerController(onPicked = { _, _ -> fail("Cancelled result") },
                    onCancelled = { cancelled++ })
            }
        }
        compose.runOnIdle { requireNotNull(controller).launchImage() }
        compose.runOnIdle { registry.dispatchResult(requireNotNull(requestCode), Activity.RESULT_CANCELED, null) }
        compose.waitUntil(5_000) { cancelled == 1 }
    }

    @Test fun attachmentManagerErrorOffersExplicitRetry() {
        var retried = 0
        compose.setContent {
            MaterialTheme {
                AttachmentManagerDialog(emptyList(), onRename = { _, _ -> }, onDelete = {}, onDismiss = {},
                    error = "Synthetic upload error", onRetry = { retried++ })
            }
        }
        compose.onNodeWithTag("attachment-manager-retry").performClick()
        compose.runOnIdle { assertEquals(1, retried) }
    }

    @Test fun cameraPathAndLauncherSurviveSavedStateWithoutEditorUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val restoration = StateRestorationTester(compose)
        var controller: AttachmentPickerController? = null
        var requestCode: Int? = null
        var captureUri: Uri? = null
        var delivered: Uri? = null
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) {
                assertTrue(contract is ActivityResultContracts.TakePicture)
                requestCode = code
                captureUri = input as Uri
                context.contentResolver.openOutputStream(requireNotNull(captureUri))!!.use {
                    it.write(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()))
                }
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                controller = rememberAttachmentPickerController(onPicked = { uri, inline ->
                    assertTrue(inline); delivered = uri
                }, onCancelled = { fail("Restored camera result must retain its capture path") })
            }
        }
        compose.runOnIdle { requireNotNull(controller).launchCamera() }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { registry.dispatchResult(requireNotNull(requestCode), Activity.RESULT_OK, null) }
        compose.waitUntil(5_000) { delivered != null }
        assertEquals(captureUri, delivered)
        context.contentResolver.delete(requireNotNull(captureUri), null, null)
    }

}
