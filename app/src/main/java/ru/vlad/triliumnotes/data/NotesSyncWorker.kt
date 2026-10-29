package ru.vlad.triliumnotes.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NotesSyncWorker internal constructor(
    context: Context,
    parameters: WorkerParameters,
    private val suppliedServices: NotesServices?,
) : CoroutineWorker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters) :
        this(context, parameters, null)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val services = suppliedServices ?: NotesServices.current(applicationContext) ?: return@withContext Result.success()
        // A queued request belongs to one login generation. Old jobs cannot borrow a new token.
        if (services.sessionId != inputData.getString(NotesSyncScheduler.SESSION_ID))
            return@withContext Result.success()
        services.repository.syncPending()
        val uploads = services.uploads.syncPending()
        val attachmentEdits = services.attachments.syncPending()
        services.repository.syncPending()
        val packagesOnly = inputData.getBoolean(NotesSyncScheduler.PACKAGES_ONLY, false) ||
            (runAttemptCount > 0 && services.offline.state.value.packagesOnly)
        val offline = if (inputData.getBoolean(NotesSyncScheduler.UPLOAD_ONLY, false)) null
            else services.offline.refreshAll(packagesOnly = packagesOnly)
        val pending = services.repository.syncState.value
        // A foreground crawl may already be running, or a new edit may have arrived
        // after this crawl took its outbox snapshot. Keep durable work scheduled.
        if (offline?.isRunning == true || offline?.error != null || pending.error != null ||
            uploads.pendingCount > 0 || uploads.error != null || attachmentEdits > 0 ||
            pending.pendingCount > pending.conflicts.size) Result.retry() else Result.success()
    }
}
