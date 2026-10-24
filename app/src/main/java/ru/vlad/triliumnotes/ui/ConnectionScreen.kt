package ru.vlad.triliumnotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
    onAbout: () -> Unit = {},
    onConnect: (String, String, Boolean, Boolean) -> Unit,
) {
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
                Text("Trilium Client", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(10.dp))
                Text("Подключитесь к своему серверу заметок", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(32.dp))
                OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth().testTag("connection-server"),
                    label = { Text("Адрес сервера") }, placeholder = { Text("https://notes.example.com") },
                    singleLine = true, enabled = !busy && !storageFailed,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth().testTag("connection-secret"),
                    label = { Text(if (useToken) "ETAPI-токен" else "Пароль Trilium") }, singleLine = true,
                    enabled = !busy && !storageFailed,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    trailingIcon = { IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (visible) "Скрыть" else "Показать")
                    } })
                if (useToken) {
                    Spacer(Modifier.height(8.dp))
                    Text("Создайте токен в Trilium: Настройки → ETAPI.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { useToken = !useToken; secret = ""; visible = false }, enabled = !busy && !storageFailed,
                    modifier = Modifier.testTag("connection-mode")) {
                    Text(if (useToken) "Войти по паролю" else "Войти с ETAPI-токеном")
                }
                if (http) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(allowHttp, { allowHttp = it }, enabled = !busy)
                        Text("Разрешить HTTP: данные передаются без шифрования", style = MaterialTheme.typography.bodyMedium)
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
                    Text(if (busy) "Подключение…" else "Подключиться")
                }
                if (storageFailed) {
                    TextButton(onClick = onResetStorage, enabled = !busy) { Text("Сбросить подключение") }
                }
                TextButton(onClick = onAbout) { Text("О приложении") }
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
    onAbout: () -> Unit = {},
) {
    var confirm by remember { mutableStateOf(false) }
    BackHandler { if (!busy) onBack() }
    Scaffold(topBar = { TopAppBar(title = { Text("Подключение") }, navigationIcon = {
        IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text("Сервер Trilium", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(serverUrl, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            Text("При выходе локальные заметки останутся на телефоне. Войдите на тот же сервер, чтобы продолжить работу.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (pendingCount > 0) {
                Spacer(Modifier.height(12.dp))
                Text("Не отправлено изменений: $pendingCount. Они сохранятся до следующего входа на этот сервер.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { confirm = true }, enabled = !busy,
                modifier = Modifier.testTag("connection-disconnect")) { Text("Выйти / сменить сервер") }
            TextButton(onClick = onAbout) { Text("О приложении") }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Выйти из Trilium?") },
        text = { Text("Синхронизация остановится. Локальные заметки и неотправленные правки сохранятся на телефоне.") },
        confirmButton = { TextButton(onClick = { confirm = false; onDisconnect() }) { Text("Выйти") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } })
}
