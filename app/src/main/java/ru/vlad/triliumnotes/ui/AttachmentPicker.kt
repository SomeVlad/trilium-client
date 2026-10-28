package ru.vlad.triliumnotes.ui

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.compose.runtime.saveable.rememberSaveable
import java.io.File
import java.util.UUID
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R

/** Keep this controller above the note/loading branches so results register during process restoration. */
class AttachmentPickerController internal constructor(
    private val image: () -> Unit,
    private val file: () -> Unit,
    private val camera: () -> Unit,
) {
    fun launchImage() = image()
    fun launchFile() = file()
    fun launchCamera() = camera()
}

@Composable
fun rememberAttachmentPickerController(
    onPicked: (Uri, Boolean) -> Unit,
    onCancelled: () -> Unit = {},
): AttachmentPickerController {
    val context = LocalContext.current
    val latestPicked = rememberUpdatedState(onPicked)
    val latestCancelled = rememberUpdatedState(onCancelled)
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path = cameraPath
        cameraPath = null
        if (success && path != null && File(path).isFile && File(path).length() > 0) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
            // The owner removes this app-private temporary JPEG only after durable staging succeeds.
            latestPicked.value(uri, true)
        } else {
            path?.let { File(it).delete() }
            latestCancelled.value()
        }
    }
    val image = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) latestPicked.value(uri, true) else latestCancelled.value()
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) latestPicked.value(uri, false) else latestCancelled.value()
    }
    return remember(image, file, camera, context) {
        AttachmentPickerController(
            image = { try { image.launch(arrayOf("image/*")) } catch (_: Exception) { latestCancelled.value() } },
            file = { try { file.launch(arrayOf("*/*")) } catch (_: Exception) { latestCancelled.value() } },
            camera = {
                var capture: File? = null
                try {
                    val directory = File(context.filesDir, "capture-inbox").apply { mkdirs() }
                    val target = File(directory, "camera-${UUID.randomUUID()}.jpg")
                    capture = target
                    cameraPath = target.absolutePath
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
                    camera.launch(uri)
                } catch (_: Exception) {
                    cameraPath = null
                    capture?.delete()
                    latestCancelled.value()
                }
            },
        )
    }
}

/** The caller captures the owner note before opening this picker. */
@Composable
fun AttachmentPicker(
    enabled: Boolean,
    onPickStarted: () -> Unit,
    onPicked: (Uri, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onCancelled: () -> Unit = {},
    preparePick: ((() -> Unit) -> Unit)? = null,
    controller: AttachmentPickerController? = null,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val picker = controller ?: rememberAttachmentPickerController(onPicked, onCancelled)
    fun prepare(launch: () -> Unit) {
        onPickStarted()
        if (preparePick != null) preparePick(launch) else launch()
    }
    Box(modifier) {
        IconButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.testTag("attach")) {
            Icon(Icons.Outlined.AttachFile, AppLanguage.get(context, R.string.action_attach))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.editor_attach_camera)) },
                leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null) }, onClick = {
                    expanded = false
                    prepare { picker.launchCamera() }
                })
            DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.action_attach_image)) },
                leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = {
                    expanded = false
                    prepare { picker.launchImage() }
                })
            DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.action_attach_file)) },
                leadingIcon = { Icon(Icons.Outlined.AttachFile, null) }, onClick = {
                    expanded = false
                    prepare { picker.launchFile() }
                })
        }
    }
}
