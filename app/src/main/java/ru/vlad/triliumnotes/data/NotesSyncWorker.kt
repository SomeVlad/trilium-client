package ru.vlad.triliumnotes.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NotesSyncWorker internal constructor(
    context: Context,
    parameters: WorkerParameters,
    private val services: NotesServices,
) : CoroutineWorker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters) :
        this(context, parameters, NotesServices.get(context))

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Work queued before upload-only jobs were introduced has no input and
        // must retain its original full-copy behavior, including periodic work.
        val offline = if (inputData.getBoolean(NotesSyncScheduler.UPLOAD_ONLY, false)) {
            services.repository.syncPending()
            null
        } else services.offline.refreshAll()
        val pending = services.repository.syncState.value
        // A foreground crawl may already be running, or a new edit may have arrived
        // after this crawl took its outbox snapshot. Keep durable work scheduled.
        if (offline?.isRunning == true || offline?.error != null || pending.error != null ||
            pending.pendingCount > pending.conflicts.size) Result.retry() else Result.success()
    }
}
