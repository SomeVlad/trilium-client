package ru.vlad.triliumnotes.ui

import android.net.Uri
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R

/** The caller captures the owner note before opening this picker. */
@Composable
fun AttachmentPicker(
    enabled: Boolean,
    onPickStarted: () -> Unit,
    onPicked: (Uri, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val image = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri, true)
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri, false)
    }
    Box(modifier) {
        IconButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.testTag("attach")) {
            Icon(Icons.Outlined.AttachFile, AppLanguage.get(context, R.string.action_attach))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.action_attach_image)) },
                leadingIcon = { Icon(Icons.Outlined.Image, null) }, onClick = {
                    expanded = false
                    onPickStarted()
                    image.launch(arrayOf("image/*"))
                })
            DropdownMenuItem(text = { Text(AppLanguage.get(context, R.string.action_attach_file)) },
                leadingIcon = { Icon(Icons.Outlined.AttachFile, null) }, onClick = {
                    expanded = false
                    onPickStarted()
                    file.launch(arrayOf("*/*"))
                })
        }
    }
}
