package ru.vlad.triliumnotes.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object NotesSyncScheduler {
    internal const val ONE_TIME_WORK_NAME = "notes-sync-now"
    internal const val PENDING_WORK_NAME = "notes-sync-pending"
    internal const val PERIODIC_WORK_NAME = "notes-sync-periodic"
    internal const val UPLOAD_ONLY = "upload-only"
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Explicit full refresh. Keep the original name for work queued by older versions. */
    fun scheduleOneTime(context: Context): Operation = WorkManager.getInstance(context.applicationContext)
        .enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(NotesSyncWorker::class.java).setConstraints(connected).build())

    /** A completed copy survives activity stop/start; only a missing or failed copy needs a crawl. */
    fun ensureOfflineCopy(context: Context, state: OfflineState): Operation? =
        if (!state.isRunning && (!state.available || state.error != null)) scheduleOneTime(context) else null

    /** Durable edits have their own job and never reset offline download progress. */
    fun schedulePending(context: Context): Operation = WorkManager.getInstance(context.applicationContext)
        .enqueueUniqueWork(PENDING_WORK_NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(NotesSyncWorker::class.java).setConstraints(connected)
                .setInputData(workDataOf(UPLOAD_ONLY to true)).build())

    /** Android can defer this work; it also covers an edit saved as a running KEEP job finishes. */
    fun ensurePeriodic(context: Context): Operation = WorkManager.getInstance(context.applicationContext)
        .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequest.Builder(NotesSyncWorker::class.java, 15, TimeUnit.MINUTES)
                .setInitialDelay(15, TimeUnit.MINUTES).setConstraints(connected).build())
}
