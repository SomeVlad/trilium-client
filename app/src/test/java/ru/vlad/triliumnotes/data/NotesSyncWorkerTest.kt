package ru.vlad.triliumnotes.data

import android.content.Context
import android.content.ContextWrapper
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import ru.vlad.triliumnotes.ConnectionState
import ru.vlad.triliumnotes.diagrams.DiagramPackages
import ru.vlad.triliumnotes.diagrams.DiagramPackageSpec
import okio.Buffer

@RunWith(RobolectricTestRunner::class)
class NotesSyncWorkerTest {
    private val server = MockWebServer()
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var services: NotesServices
    private lateinit var factory: WorkerFactory
    private val singleton = NotesServices::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previousServices: Any? = null
    @Volatile private var unavailable = false
    @Volatile private var draftDuringAttachments = false
    @Volatile private var includeChild = false
    @Volatile private var remoteHtml = "<p>Cached root</p>"
    @Volatile private var rootType = "text"
    @Volatile private var rendererBytes: ByteArray? = null
    @Volatile private var failRenderer = false
    private val requests = ConcurrentLinkedQueue<String>()
    private val workerInputs = ConcurrentLinkedQueue<Data>()
    private var childAttachmentsEntered: CountDownLatch? = null
    private var releaseChildAttachments: CountDownLatch? = null

    @Before fun start() {
        previousServices = singleton.get(null)
        singleton.set(null, null)
        directory = Files.createTempDirectory("notes-worker-test").toFile()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = directory
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path}"
                if (unavailable) return MockResponse().setResponseCode(503)
                if (request.path == "/renderer.zip") return if (failRenderer) MockResponse().setResponseCode(503)
                    else MockResponse().setBody(Buffer().write(requireNotNull(rendererBytes)))
                return when (request.path) {
                    "/etapi/notes/root" -> MockResponse().setBody("""{"noteId":"root","title":"Root","type":"$rootType","mime":"text/html","blobId":"versionA","childBranchIds":${if (includeChild) "[\"branch\"]" else "[]"}}""")
                    "/etapi/notes/root/content" -> {
                        if (request.method == "PUT") remoteHtml = request.body.readUtf8()
                        MockResponse().setBody(remoteHtml)
                    }
                    "/etapi/notes/root/attachments" -> {
                        if (draftDuringAttachments) {
                            draftDuringAttachments = false
                            runBlocking { services.repository.saveDraft("root", "Local edit", "<p>New draft</p>") }
                        }
                        MockResponse().setBody("[]")
                    }
                    "/etapi/branches/branch" -> MockResponse().setBody("""{"branchId":"branch","parentNoteId":"root","noteId":"child","notePosition":10}""")
                    "/etapi/notes/child" -> MockResponse().setBody("""{"noteId":"child","title":"Child","type":"text","mime":"text/html","blobId":"childA","childBranchIds":[]}""")
                    "/etapi/notes/child/content" -> MockResponse().setBody("<p>Child</p>")
                    "/etapi/notes/child/attachments" -> {
                        childAttachmentsEntered?.countDown()
                        releaseChildAttachments?.await(5, TimeUnit.SECONDS)
                        MockResponse().setBody("[]")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val url = server.url("/").toString()
        services = NotesServices(context, TriliumApi(url, "test-token"), url)
        singleton.set(null, services)
        factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String,
                workerParameters: WorkerParameters): ListenableWorker? {
                if (workerClassName != NotesSyncWorker::class.java.name) return null
                workerInputs += workerParameters.inputData
                return NotesSyncWorker(appContext, workerParameters, services)
            }
        }
    }

    @After fun stop() {
        singleton.set(null, previousServices)
        releaseChildAttachments?.countDown()
        server.shutdown()
        directory.deleteRecursively()
    }

    private fun worker(runAttemptCount: Int = 0): NotesSyncWorker = TestListenableWorkerBuilder<NotesSyncWorker>(context)
        .setRunAttemptCount(runAttemptCount).setWorkerFactory(factory).build()

    private fun workManager(): WorkManager {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(factory).build())
        return WorkManager.getInstance(context)
    }

    private suspend fun runPending(manager: WorkManager, name: String): WorkInfo {
        val work = manager.getWorkInfosForUniqueWork(name).get().single { !it.state.isFinished }
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(work.id)
        return requireNotNull(withTimeout(5_000) {
            manager.getWorkInfoByIdFlow(work.id).first { it?.state?.isFinished == true }
        })
    }

    @Test fun failedRefreshRetriesAndNextAttemptPopulatesTheSharedStore() = runBlocking {
        unavailable = true
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertTrue(!services.offline.state.value.complete)
        unavailable = false

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        assertTrue(services.offline.state.value.complete)
        assertEquals("<p>Cached root</p>", services.repository.cachedNote("root")?.html)
        val reopened = NotesRepository(context, services.api)
        assertEquals("<p>Cached root</p>", reopened.cachedNote("root")?.html)
    }

    @Test fun editAfterTheOutboxSnapshotKeepsBackgroundWorkPending() = runBlocking {
        draftDuringAttachments = true

        assertEquals(ListenableWorker.Result.retry(), worker().doWork())

        assertEquals(1, services.repository.syncState.value.pendingCount)
        assertEquals("<p>New draft</p>", services.repository.cachedNote("root")?.html)
        assertTrue(services.repository.cachedNote("root")?.pending == true)
    }

    @Test fun repeatedLaunchesKeepOneOfflineJobAndItRunsWhenNetworkReturns() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(factory).build())
        val manager = WorkManager.getInstance(context)
        repeat(3) { NotesSyncScheduler.scheduleOneTime(context).result.get(5, TimeUnit.SECONDS) }
        val pending = manager.getWorkInfosForUniqueWork(NotesSyncScheduler.ONE_TIME_WORK_NAME).get()
        assertEquals(1, pending.size)
        assertEquals(WorkInfo.State.ENQUEUED, pending.single().state)
        assertEquals(0, server.requestCount)

        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(pending.single().id)
        val finished = withTimeout(5_000) {
            manager.getWorkInfoByIdFlow(pending.single().id).first { it?.state?.isFinished == true }
        }

        assertEquals(WorkInfo.State.SUCCEEDED, finished?.state)
        assertNotNull(services.repository.cachedNote("root"))
    }

    @Test fun periodicRegistrationKeepsOneJobAndDefersItsInitialOnlineCrawl() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(factory).build())
        val manager = WorkManager.getInstance(context)
        NotesSyncScheduler.ensurePeriodic(context).result.get(5, TimeUnit.SECONDS)
        val first = manager.getWorkInfosForUniqueWork(NotesSyncScheduler.PERIODIC_WORK_NAME).get().single()
        NotesSyncScheduler.ensurePeriodic(context).result.get(5, TimeUnit.SECONDS)
        val second = manager.getWorkInfosForUniqueWork(NotesSyncScheduler.PERIODIC_WORK_NAME).get().single()

        assertEquals(first.id, second.id)
        assertEquals(WorkInfo.State.ENQUEUED, second.state)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(second.id)
        assertEquals(0, server.requestCount)
        driver.setInitialDelayMet(second.id)
        withTimeout(5_000) { services.offline.state.first { it.complete } }
        assertNotNull(services.repository.cachedNote("root"))
    }

    @Test fun completedCopyIsNotCrawledAgainOnRepeatedResumeOrSameNetworkCallback() = runBlocking {
        includeChild = true
        val manager = workManager()
        NotesSyncScheduler.ensureOfflineCopy(context, services.offline.state.value)!!.result.get()
        assertEquals(WorkInfo.State.SUCCEEDED, runPending(manager, NotesSyncScheduler.ONE_TIME_WORK_NAME).state)
        val complete = services.offline.state.value
        assertTrue(complete.available && complete.complete)
        assertEquals(2, complete.completed)
        val requestCount = server.requestCount

        repeat(4) {
            services.offline.refreshReadiness()
            assertNull(NotesSyncScheduler.ensureOfflineCopy(context, services.offline.state.value))
            NotesSyncScheduler.schedulePending(context).result.get()
            assertEquals(WorkInfo.State.SUCCEEDED, runPending(manager, NotesSyncScheduler.PENDING_WORK_NAME).state)
            assertEquals(complete, services.offline.state.value)
            assertEquals(requestCount, server.requestCount)
        }
        // The completed marker also suppresses a new crawl after process recreation.
        val reopened = NotesServices(context, services.api, server.url("/").toString())
        assertTrue(reopened.offline.state.value.available)
        assertNull(NotesSyncScheduler.ensureOfflineCopy(context, reopened.offline.state.value))
        assertEquals(requestCount, server.requestCount)

        // Manual refresh remains a full crawl after that same successful initial copy.
        NotesSyncScheduler.scheduleOneTime(context).result.get()
        assertEquals(WorkInfo.State.SUCCEEDED, runPending(manager, NotesSyncScheduler.ONE_TIME_WORK_NAME).state)
        assertTrue(server.requestCount > requestCount)
    }

    @Test fun automaticRuntimeUpgradeKeepsOneSeparatePackageJobAndDoesNotCrawlNotes() = runBlocking {
        assertTrue(services.offline.refreshAll().complete)
        val manager = workManager()
        requests.clear()
        // OfflineSyncTest covers deriving this state from a real missing renderer.
        // Here the boundary is the durable scheduler input and the worker's chosen path.
        val upgrade = OfflineState(available = false, packagesOnly = true)
        repeat(3) { NotesSyncScheduler.ensureOfflineCopy(context, upgrade)!!.result.get() }
        val queued = manager.getWorkInfosForUniqueWork(NotesSyncScheduler.PACKAGES_WORK_NAME).get()
        assertEquals(1, queued.size)
        assertEquals(WorkInfo.State.ENQUEUED, queued.single().state)
        assertTrue(manager.getWorkInfosForUniqueWork(NotesSyncScheduler.ONE_TIME_WORK_NAME).get().isEmpty())

        assertEquals(WorkInfo.State.SUCCEEDED, runPending(manager, NotesSyncScheduler.PACKAGES_WORK_NAME).state)

        assertTrue(workerInputs.single().getBoolean(NotesSyncScheduler.PACKAGES_ONLY, false))
        assertFalse(workerInputs.single().getBoolean(NotesSyncScheduler.UPLOAD_ONLY, false))
        assertTrue("A package-only worker must not refetch cached notes", requests.isEmpty())
        assertTrue(services.offline.state.value.available)
        assertNull(NotesSyncScheduler.ensureOfflineCopy(context, services.offline.state.value))
    }

    @Test fun explicitRefreshRemainsFullWhileAnAutomaticPackageUpgradeIsQueued() = runBlocking {
        assertTrue(services.offline.refreshAll().complete)
        val manager = workManager()
        requests.clear()
        NotesSyncScheduler.ensureOfflineCopy(context, OfflineState(packagesOnly = true))!!.result.get()
        NotesSyncScheduler.scheduleOneTime(context).result.get()
        val packages = manager.getWorkInfosForUniqueWork(NotesSyncScheduler.PACKAGES_WORK_NAME).get().single()
        val full = manager.getWorkInfosForUniqueWork(NotesSyncScheduler.ONE_TIME_WORK_NAME).get().single()
        assertFalse(packages.id == full.id)

        assertEquals(WorkInfo.State.SUCCEEDED, runPending(manager, NotesSyncScheduler.ONE_TIME_WORK_NAME).state)

        assertFalse(workerInputs.single().getBoolean(NotesSyncScheduler.PACKAGES_ONLY, false))
        assertFalse(workerInputs.single().getBoolean(NotesSyncScheduler.UPLOAD_ONLY, false))
        assertTrue("Manual refresh must crawl the server even with a completed marker", requests.contains("GET /etapi/notes/root"))
        assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(packages.id).get()!!.state)
    }

    @Test fun automaticWorkerRetryFetchesOnlyFailedPackageWhileFreshManualWorkerStillCrawls() = runBlocking {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("mermaid.js")); zip.write("renderer".toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        rendererBytes = bytes
        rootType = "mermaid"
        failRenderer = true
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val packages = DiagramPackages(File(directory, "test-renderers"), mapOf("mermaid" to DiagramPackageSpec(
            server.url("/renderer.zip").toString(), digest, bytes.size.toLong(), 8, setOf("mermaid.js"),
        )))
        // Inject the local fixture at the service boundary without changing production construction.
        NotesServices::class.java.getDeclaredField("offline").apply { isAccessible = true }.set(services,
            OfflineSync(services.repository, services.api, services.resources, context, packages))
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertTrue(services.offline.state.value.packagesOnly)

        requests.clear()
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertTrue("A newly requested manual worker must retain its full refresh", requests.contains("GET /etapi/notes/root"))

        requests.clear()
        failRenderer = false
        val retry = worker(runAttemptCount = 1)
        assertEquals(ListenableWorker.Result.success(), retry.doWork())
        assertEquals(listOf("GET /renderer.zip"), requests.toList())
        assertTrue(services.offline.state.value.available)
    }

    @Test fun durableEditUploadsWithoutCrawlingTreeOrResettingCompletedCounters() = runBlocking {
        includeChild = true
        val manager = workManager()
        NotesSyncScheduler.scheduleOneTime(context).result.get()
        runPending(manager, NotesSyncScheduler.ONE_TIME_WORK_NAME)
        val complete = services.offline.state.value
        requests.clear()
        services.repository.saveDraft("root", "Root", "<p>Uploaded edit</p>")

        NotesSyncScheduler.schedulePending(context).result.get()
        assertEquals(WorkInfo.State.SUCCEEDED, runPending(manager, NotesSyncScheduler.PENDING_WORK_NAME).state)

        assertEquals("<p>Uploaded edit</p>", remoteHtml)
        assertEquals(0, services.repository.syncState.value.pendingCount)
        assertEquals(complete, services.offline.state.value)
        assertEquals(listOf("GET /etapi/notes/root", "GET /etapi/notes/root/content",
            "PUT /etapi/notes/root/content", "GET /etapi/notes/root"), requests.toList())
    }

    @Test fun activityResumeDoesNotRestartAnActiveCopyOrResetItsProgress() = runBlocking {
        includeChild = true
        childAttachmentsEntered = CountDownLatch(1)
        releaseChildAttachments = CountDownLatch(1)
        val manager = workManager()
        NotesSyncScheduler.scheduleOneTime(context).result.get()
        val full = manager.getWorkInfosForUniqueWork(NotesSyncScheduler.ONE_TIME_WORK_NAME).get().single()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(full.id)
        assertTrue(childAttachmentsEntered!!.await(5, TimeUnit.SECONDS))
        val progress = services.offline.state.value
        assertTrue(progress.isRunning)
        assertEquals(1, progress.completed)
        assertEquals(2, progress.total)
        val count = server.requestCount

        repeat(3) {
            assertNull(NotesSyncScheduler.ensureOfflineCopy(context, services.offline.state.value))
            NotesSyncScheduler.schedulePending(context).result.get()
            runPending(manager, NotesSyncScheduler.PENDING_WORK_NAME)
            assertEquals(progress, services.offline.state.value)
            assertEquals(count, server.requestCount)
        }
        releaseChildAttachments!!.countDown()
        val finished = withTimeout(5_000) {
            manager.getWorkInfoByIdFlow(full.id).first { it?.state?.isFinished == true }
        }
        assertEquals(WorkInfo.State.SUCCEEDED, finished?.state)
        assertEquals(2, services.offline.state.value.completed)
    }

    @Test fun realConnectionRestorationCompletesAnIncompleteCopy() = runBlocking {
        val connection = ConnectionState()
        val manager = workManager()
        unavailable = true
        // A foreground failed attempt leaves no complete marker.
        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertFalse(services.offline.state.value.available)
        assertNotNull(services.offline.state.value.error)
        assertTrue(connection.update(10, true))
        NotesSyncScheduler.ensureOfflineCopy(context, services.offline.state.value)!!.result.get()
        val pending = manager.getWorkInfosForUniqueWork(NotesSyncScheduler.ONE_TIME_WORK_NAME).get().single()
        repeat(3) { assertFalse(connection.update(10, true)) }
        assertTrue(connection.lost(10))
        unavailable = false
        assertTrue(connection.update(10, true))
        NotesSyncScheduler.ensureOfflineCopy(context, services.offline.state.value)!!.result.get()
        assertEquals(pending.id, manager.getWorkInfosForUniqueWork(NotesSyncScheduler.ONE_TIME_WORK_NAME).get().single().id)
        assertEquals(WorkInfo.State.SUCCEEDED, runPending(manager, NotesSyncScheduler.ONE_TIME_WORK_NAME).state)
        assertTrue(services.offline.state.value.complete)
        assertTrue(services.repository.offlineCopyAvailable)
    }

    @Test fun queuedWorkForAnotherLoginCannotUseCurrentCredentials() = runBlocking {
        val current = NotesServices(context, services.api, server.url("/").toString(), "current-login")
        val isolatedFactory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                NotesSyncWorker(appContext, workerParameters, current)
        }
        val stale = TestListenableWorkerBuilder<NotesSyncWorker>(context)
            .setInputData(androidx.work.workDataOf(NotesSyncScheduler.SESSION_ID to "old-login"))
            .setWorkerFactory(isolatedFactory).build()
        assertEquals(ListenableWorker.Result.success(), stale.doWork())
        assertEquals(0, server.requestCount)
    }

    @Test fun foregroundAndBackgroundLookupsShareOneRepository() {
        val app = RuntimeEnvironment.getApplication()
        val first = NotesServices.get(app)
        val second = NotesServices.get(ContextWrapper(app))
        assertSame(first, second)
        assertSame(first.repository, second.repository)
        assertSame(first.resources, second.resources)
        assertSame(first.offline, second.offline)
    }
}
