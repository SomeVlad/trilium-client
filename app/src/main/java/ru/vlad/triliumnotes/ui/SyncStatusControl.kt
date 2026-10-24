package ru.vlad.triliumnotes.ui

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R

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
    val context = LocalContext.current
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
        conflicts > 0 -> AppLanguage.get(context, R.string.conflict_count, conflicts)
        hasAlert -> AppLanguage.get(context, R.string.sync_error)
        queued > 0 && sync.isSyncing -> AppLanguage.get(context, R.string.sending_count, queued)
        queued > 0 -> AppLanguage.get(context, R.string.unsent_count, queued)
        !networkAvailable -> AppLanguage.get(context, R.string.no_internet)
        offline.isRunning && !offline.available -> initialOfflineProgressText(context, offline)
        active -> AppLanguage.get(context, R.string.updating_notes)
        offline.available -> AppLanguage.get(context, R.string.available_offline)
        else -> AppLanguage.get(context, R.string.offline_not_ready)
    }
    val badgeCount = if (conflicts > 0) conflicts else queued
    val tint = when {
        conflicts > 0 || hasAlert -> colors.error
        queued > 0 -> colors.primary
        else -> colors.onSurfaceVariant
    }
    IconButton(onClick = { showDetails = true },
        modifier = modifier.testTag("sync-status").semantics {
            contentDescription = AppLanguage.get(context, R.string.sync_status_description, description)
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
            title = { Text(AppLanguage.get(context, R.string.sync)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!networkAvailable) Text(AppLanguage.get(context, R.string.no_internet_message))
                    if (offline.isRunning && !offline.available) Text(initialOfflineProgressText(context, offline))
                    else if (offline.isRunning) Text(AppLanguage.get(context, R.string.updating_offline))
                    else if (offline.available) Text(AppLanguage.get(context, R.string.notes_available_offline))
                    else Text(AppLanguage.get(context, R.string.offline_not_downloaded))
                    if (fromCache && networkAvailable) Text(AppLanguage.get(context, R.string.showing_cached_notes))
                    if (queued > 0) {
                        Text(if (sync.isSyncing) AppLanguage.get(context, R.string.sending_count, queued) else AppLanguage.get(context, R.string.queued_count, queued))
                        Text(AppLanguage.get(context, R.string.changes_saved_message))
                    }
                    if (conflicts > 0) Text(AppLanguage.get(context, R.string.conflict_count_message, conflicts), color = colors.error)
                    errors.forEach { Text(it, color = colors.error) }
                }
            },
            confirmButton = {
                if (conflicts > 0 && onOpenConflict != null) {
                    TextButton(onClick = { showDetails = false; onOpenConflict() }) { Text(AppLanguage.get(context, R.string.open_note)) }
                } else if (conflicts == 0) {
                    TextButton(onClick = { showDetails = false; onRefresh() }, enabled = !active && !isViewBusy) { Text(AppLanguage.get(context, R.string.refresh)) }
                }
            },
            dismissButton = { TextButton(onClick = { showDetails = false }) { Text(AppLanguage.get(context, R.string.close)) } },
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
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val hasAlert = networkAvailable && sync.error != null
    val sending = networkAvailable && sync.isSyncing
    val description = when {
        hasAlert -> AppLanguage.get(context, R.string.send_failed)
        sending -> AppLanguage.get(context, R.string.sending_changes)
        !networkAvailable -> AppLanguage.get(context, R.string.unsent_no_internet)
        else -> AppLanguage.get(context, R.string.changes_saved)
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
            title = { Text(AppLanguage.get(context, R.string.note_changes)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(AppLanguage.get(context, R.string.changes_saved_message))
                    when {
                        !networkAvailable -> Text(AppLanguage.get(context, R.string.waiting_internet))
                        sending -> Text(AppLanguage.get(context, R.string.sending_changes_progress))
                        sync.error == null -> Text(AppLanguage.get(context, R.string.changes_send_automatically))
                    }
                    sync.error?.let { Text(it, color = if (hasAlert) colors.error else colors.onSurfaceVariant) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false; onSync() },
                    enabled = networkAvailable && !sync.isSyncing) { Text(AppLanguage.get(context, R.string.send)) }
            },
            dismissButton = { TextButton(onClick = { showDetails = false }) { Text(AppLanguage.get(context, R.string.close)) } },
        )
    }
}

/** Only the first offline copy takes a temporary line in the library. */
@Composable
internal fun InitialOfflineProgress(offline: OfflineState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (offline.isRunning && !offline.available) {
        Text(initialOfflineProgressText(context, offline), modifier.testTag("offline-progress"),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun initialOfflineProgressText(context: Context, offline: OfflineState): String =
    if (offline.completed == offline.total) AppLanguage.get(context, R.string.downloading_attachments)
    else AppLanguage.get(context, R.string.offline_progress, offline.completed, offline.total)
