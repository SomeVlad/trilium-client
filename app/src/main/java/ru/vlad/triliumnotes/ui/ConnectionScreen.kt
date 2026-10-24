package ru.vlad.triliumnotes.ui

import androidx.compose.ui.platform.LocalContext
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ConnectionScreen(
    busy: Boolean = false,
    error: String? = null,
    storageFailed: Boolean = false,
    onResetStorage: () -> Unit = {},
    onLanguageSelected: (String) -> Unit = {},
    onConnect: (String, String, Boolean, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var server by rememberSaveable { mutableStateOf("") }
    // Secrets deliberately do not enter Android's saved instance state.
    var secret by remember { mutableStateOf("") }
    var useToken by rememberSaveable { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var allowHttp by rememberSaveable(server) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val http = server.trim().startsWith("http://", ignoreCase = true)
    val canConnect = !busy && !storageFailed && server.isNotBlank() && secret.isNotBlank() && (!http || allowHttp)
    fun submit() {
        if (canConnect) {
            keyboard?.hide(); focus.clearFocus()
            onConnect(server, secret, useToken, allowHttp)
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
                Spacer(Modifier.height(24.dp))
                Text(AppLanguage.get(context, R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(10.dp))
                Text(AppLanguage.get(context, R.string.connect_intro), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(32.dp))
                OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth().testTag("connection-server"),
                    label = { Text(AppLanguage.get(context, R.string.server_address)) }, placeholder = { Text(AppLanguage.get(context, R.string.server_example)) },
                    singleLine = true, enabled = !busy && !storageFailed,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth().testTag("connection-secret"),
                    label = { Text(if (useToken) AppLanguage.get(context, R.string.etapi_token) else AppLanguage.get(context, R.string.trilium_password)) }, singleLine = true,
                    enabled = !busy && !storageFailed,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    trailingIcon = { IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (visible) AppLanguage.get(context, R.string.hide) else AppLanguage.get(context, R.string.show))
                    } })
                if (useToken) {
                    Spacer(Modifier.height(8.dp))
                    Text(AppLanguage.get(context, R.string.token_help),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { useToken = !useToken; secret = ""; visible = false }, enabled = !busy && !storageFailed,
                    modifier = Modifier.testTag("connection-mode")) {
                    Text(if (useToken) AppLanguage.get(context, R.string.use_password) else AppLanguage.get(context, R.string.use_token))
                }
                if (http) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(allowHttp, { allowHttp = it }, enabled = !busy)
                        Text(AppLanguage.get(context, R.string.allow_http), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("connection-error"))
                }
                Spacer(Modifier.height(20.dp))
                Button(onClick = ::submit, enabled = canConnect,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = if (busy) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        disabledContentColor = if (busy) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("connection-submit")) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (busy) AppLanguage.get(context, R.string.connecting) else AppLanguage.get(context, R.string.connect))
                }
                if (storageFailed) {
                    TextButton(onClick = onResetStorage, enabled = !busy) { Text(AppLanguage.get(context, R.string.reset_connection)) }
                }
                LanguageSetting(onLanguageSelected, enabled = !busy)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(
    serverUrl: String,
    pendingCount: Int,
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onLanguageSelected: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    BackHandler { if (!busy) onBack() }
    Scaffold(topBar = { TopAppBar(title = { Text(AppLanguage.get(context, R.string.connection_settings)) }, navigationIcon = {
        IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, AppLanguage.get(context, R.string.back)) }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text(AppLanguage.get(context, R.string.trilium_server), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(serverUrl, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            Text(AppLanguage.get(context, R.string.disconnect_help),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (pendingCount > 0) {
                Spacer(Modifier.height(12.dp))
                Text(AppLanguage.get(context, R.string.pending_retained, pendingCount),
                    style = MaterialTheme.typography.bodyMedium)
            }
            error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { confirm = true }, enabled = !busy,
                modifier = Modifier.testTag("connection-disconnect")) { Text(AppLanguage.get(context, R.string.change_server)) }
            LanguageSetting(onLanguageSelected, enabled = !busy)
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(AppLanguage.get(context, R.string.disconnect_title)) },
        text = { Text(AppLanguage.get(context, R.string.disconnect_message)) },
        confirmButton = { TextButton(onClick = { confirm = false; onDisconnect() }) { Text(AppLanguage.get(context, R.string.sign_out)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(AppLanguage.get(context, R.string.cancel)) } })
}

/** The menu deliberately offers only the two supported languages. */
@Composable
private fun LanguageSetting(onSelected: (String) -> Unit, enabled: Boolean = true) {
    val context = LocalContext.current
    val selected = AppLanguage.language(context)
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(AppLanguage.get(context, R.string.language), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            TextButton(onClick = { expanded = true }, enabled = enabled,
                modifier = Modifier.testTag("language-selector")) {
                Text(AppLanguage.get(context, if (selected == "en") R.string.language_english else R.string.language_russian))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                modifier = Modifier.testTag("language-menu")) {
                listOf("ru" to R.string.language_russian, "en" to R.string.language_english).forEach { (language, label) ->
                    DropdownMenuItem(text = { Text(AppLanguage.get(context, label)) },
                        modifier = Modifier.testTag("language-option-$language"),
                        trailingIcon = { if (selected == language) Icon(Icons.Outlined.Check, contentDescription = null) },
                        onClick = { expanded = false; onSelected(language) })
                }
            }
        }
    }
}
