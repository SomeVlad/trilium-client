package ru.vlad.triliumnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.vlad.triliumnotes.data.OfflineState
import ru.vlad.triliumnotes.data.SyncState

/** A quiet toolbar status; opening details never starts a refresh by itself. */
@Composable
internal fun SyncStatusControl(
    sync: SyncState,
    offline: OfflineState,
    networkAvailable: Boolean,
    fromCache: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    isViewBusy: Boolean = false,
    viewError: String? = null,
    onOpenConflict: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val conflicts = sync.conflicts.size
    val queued = (sync.pendingCount - conflicts).coerceAtLeast(0)
    val errors = listOfNotNull(viewError, sync.error, offline.error).distinct()
    // Transfer failures stay available in details; a usable offline copy is not an alarm.
    val hasAlert = viewError != null || (errors.isNotEmpty() && (networkAvailable || !offline.available))
    val active = sync.isSyncing || offline.isRunning
    val icon = when {
        conflicts > 0 -> Icons.Outlined.WarningAmber
        hasAlert -> Icons.Outlined.ErrorOutline
        queued > 0 -> Icons.Outlined.CloudUpload
        !networkAvailable -> Icons.Outlined.CloudOff
        offline.available -> Icons.Outlined.CloudDone
        else -> Icons.Outlined.CloudDownload
    }
    val description = when {
        conflicts > 0 -> "Конфликтов: $conflicts"
        hasAlert -> "Ошибка синхронизации"
        queued > 0 && sync.isSyncing -> "Отправка изменений: $queued"
        queued > 0 -> "Не отправлено: $queued"
        !networkAvailable -> "Нет интернета"
        offline.isRunning && !offline.available -> initialOfflineProgressText(offline)
        active -> "Обновление заметок"
        offline.available -> "Доступно офлайн"
        else -> "Офлайн-копия пока не готова"
    }
    val badgeCount = if (conflicts > 0) conflicts else queued
    val tint = when {
        conflicts > 0 || hasAlert -> colors.error
        queued > 0 -> colors.primary
        else -> colors.onSurfaceVariant
    }
    IconButton(onClick = { showDetails = true },
        modifier = modifier.testTag("sync-status").semantics {
            contentDescription = "Состояние синхронизации. $description"
        }) {
        BadgedBox(badge = {
            if (badgeCount > 0) Badge(Modifier.clearAndSetSemantics {},
                containerColor = if (conflicts > 0 || hasAlert) colors.error else colors.primary,
                contentColor = if (conflicts > 0 || hasAlert) colors.onError else colors.onPrimary) {
                Text(if (badgeCount > 99) "99+" else badgeCount.toString())
            }
        }) {
            val showProgress = networkAvailable && conflicts == 0 && !hasAlert &&
                (if (queued > 0) sync.isSyncing else active)
            if (showProgress) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = tint)
            } else Icon(icon, contentDescription = null, tint = tint)
        }
    }
    if (showDetails) {
        AlertDialog(onDismissRequest = { showDetails = false },
            modifier = Modifier.testTag("sync-status-dialog"),
            title = { Text("Синхронизация") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!networkAvailable) Text("Нет подключения к интернету.")
                    if (offline.isRunning && !offline.available) Text(initialOfflineProgressText(offline))
                    else if (offline.isRunning) Text("Обновление офлайн-копии…")
                    else if (offline.available) Text("Заметки доступны офлайн.")
                    else Text("Полная офлайн-копия ещё не скачана.")
                    if (fromCache && networkAvailable) Text("Показана сохранённая копия заметок.")
                    if (queued > 0) {
                        Text(if (sync.isSyncing) "Отправка изменений: $queued" else "Ожидают отправки: $queued")
                        Text("Изменения сохранены на телефоне.")
                    }
                    if (conflicts > 0) Text("Конфликтов: $conflicts. Эти изменения не отправляются автоматически.", color = colors.error)
                    errors.forEach { Text(it, color = colors.error) }
                }
            },
            confirmButton = {
                if (conflicts > 0 && onOpenConflict != null) {
                    TextButton(onClick = { showDetails = false; onOpenConflict() }) { Text("Открыть заметку") }
                } else if (conflicts == 0) {
                    TextButton(onClick = { showDetails = false; onRefresh() }, enabled = !active && !isViewBusy) { Text("Обновить") }
                }
            },
            dismissButton = { TextButton(onClick = { showDetails = false }) { Text("Закрыть") } },
        )
    }
}

/** Only for a durable, pending note; local-save and conflict warnings remain in the editor. */
@Composable
internal fun PendingNoteStatusControl(
    sync: SyncState,
    networkAvailable: Boolean,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val hasAlert = networkAvailable && sync.error != null
    val sending = networkAvailable && sync.isSyncing
    val description = when {
        hasAlert -> "Не удалось отправить изменения"
        sending -> "Отправка изменений"
        !networkAvailable -> "Не отправлено. Нет интернета"
        else -> "Изменения сохранены на телефоне"
    }
    IconButton(onClick = { showDetails = true },
        modifier = modifier.testTag("pending-note-status").semantics { contentDescription = description }) {
        if (sending && !hasAlert) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        } else Icon(if (hasAlert) Icons.Outlined.ErrorOutline else Icons.Outlined.CloudUpload,
            contentDescription = null, tint = if (hasAlert) colors.error else colors.onSurfaceVariant)
    }
    if (showDetails) {
        AlertDialog(onDismissRequest = { showDetails = false },
            modifier = Modifier.testTag("pending-note-status-dialog"),
            title = { Text("Изменения заметки") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Изменения сохранены на телефоне.")
                    when {
                        !networkAvailable -> Text("Ожидается подключение к интернету. Изменения отправятся автоматически.")
                        sending -> Text("Отправка изменений…")
                        sync.error == null -> Text("Изменения отправятся автоматически.")
                    }
                    sync.error?.let { Text(it, color = if (hasAlert) colors.error else colors.onSurfaceVariant) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false; onSync() },
                    enabled = networkAvailable && !sync.isSyncing) { Text("Отправить") }
            },
            dismissButton = { TextButton(onClick = { showDetails = false }) { Text("Закрыть") } },
        )
    }
}

/** Only the first offline copy takes a temporary line in the library. */
@Composable
internal fun InitialOfflineProgress(offline: OfflineState, modifier: Modifier = Modifier) {
    if (offline.isRunning && !offline.available) {
        Text(initialOfflineProgressText(offline), modifier.testTag("offline-progress"),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun initialOfflineProgressText(offline: OfflineState): String =
    if (offline.completed == offline.total) "Скачивание вложений…"
    else "Скачивание для офлайна: ${offline.completed}/${offline.total}"
