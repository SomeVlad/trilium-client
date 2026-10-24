package ru.vlad.triliumnotes

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.data.NotesServices
import ru.vlad.triliumnotes.data.TriliumApi
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.SocketException
import java.security.KeyStore
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.math.abs

/** Explicit, opt-in product acceptance. Run A, force-stop the application, run B, then C.
 * Real MainActivity/NotesViewModel/repository/screens; synthetic files and TLS loopback only.
 * No default Android Internet, production connection, owner files, or application filesDir writes.
 */
class DeleteAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext
    private val output get() = File(requireNotNull(app.getExternalFilesDir(null)), "delete-acceptance").apply { mkdirs() }
    private val directory get() = File(app.cacheDir, "delete-acceptance-synthetic")
    private val cache get() = File(directory, "trilium-cache.json")
    private val singleton get() = NotesServices::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val deleteTargets = setOf("qa_delete", "qa_folder", "qa_clone")

    @Test fun a_realActionsOfflineAndPersist() {
        requireIsolatedRun()
        LoopbackFixture().use { server ->
            directory.mkdirs()
            server.writeCache(directory)
            val services = inject(server, directory)
            val baseline = cache.readText()
            ActivityScenario.launch(MainActivity::class.java).use {
                waitTag("note-row-qa_keep")
                // A cancel must leave the actual durable store and remote state untouched.
                compose.onNodeWithTag("note-row-qa_keep").performClick()
                openDelete("qa_keep")
                capture("01-cancel-confirmation")
                compose.onNodeWithText("Отмена").performClick()
                compose.onNodeWithTag("delete-note-dialog").assertDoesNotExist()
                assertEquals("Cancel changed the durable cache", baseline, cache.readText())
                assertTrue(server.mutations().isEmpty())
                deviceBack()

                // Actual code editor input -> local save -> delete replaces pending update.
                compose.onNodeWithTag("note-row-qa_delete").performClick()
                compose.onNodeWithContentDescription("Редактировать заметку").performClick()
                waitTag("source-editor")
                compose.onNodeWithTag("note-title").performTextReplacement("Удалённая после правки QA")
                compose.onNodeWithTag("source-editor").performTextReplacement("Synthetic changed body before delete")
                compose.onNodeWithText("Готово").performClick()
                compose.waitUntil(10_000) { services.repository.cachedNote("qa_delete")?.html == "Synthetic changed body before delete" }
                confirmDelete("qa_delete")
                compose.waitUntil(10_000) { services.repository.isDeleted("qa_delete") }
                waitTag("note-list")
                assertEquals("delete", pending("qa_delete").getString("kind"))

                // Actual create button and physical text input into the new title, then delete.
                compose.onNodeWithTag("create-note").performClick()
                compose.onNodeWithTag("new-note-menu").performClick()
                waitTag("note-editor")
                compose.waitUntil(15_000) { webTitleFocused() }
                instrumentation.sendStringSync("CreatedOfflineQA")
                compose.waitUntil(10_000) {
                    val json = JSONObject(cache.readText()).getJSONObject("pending")
                    json.keys().asSequence().any { json.getJSONObject(it).optString("title") == "CreatedOfflineQA" }
                }
                val localId = JSONObject(cache.readText()).getJSONObject("pending").let { json ->
                    json.keys().asSequence().first { json.getJSONObject(it).optString("title") == "CreatedOfflineQA" }
                }
                compose.onNodeWithText("Готово").performClick()
                waitTag("note-actions-$localId")
                confirmDelete(localId)
                compose.waitUntil(10_000) { services.repository.isDeleted(localId) }
                assertFalse(JSONObject(cache.readText()).getJSONObject("pending").has(localId))
                assertTrue("Unsent local creation reached the server", server.mutations().none { it.path.contains(localId) })

                // A folder with no body still has a real delete action in its toolbar.
                compose.onNodeWithTag("note-row-qa_folder").performClick()
                waitTag("note-row-qa_exclusive")
                compose.onNodeWithTag("folder-content").assertDoesNotExist()
                openDelete("qa_folder")
                compose.onNodeWithText("Вложенные заметки тоже будут удалены", substring = true).assertIsDisplayed()
                capture("02-folder-confirmation")
                compose.onNodeWithTag("confirm-delete-note").performClick()
                compose.waitUntil(10_000) { services.repository.isDeleted("qa_folder") }
                waitTag("note-row-qa_other")
                assertTrue(services.repository.isDeleted("qa_exclusive"))
                assertFalse("A child cloned elsewhere was removed", services.repository.isDeleted("qa_shared"))

                // Delete a note with two placements using the row action, then inspect the other placement.
                confirmDelete("qa_clone")
                compose.waitUntil(10_000) { services.repository.isDeleted("qa_clone") }
                compose.onNodeWithTag("note-row-qa_other").performClick()
                waitTag("note-row-qa_shared")
                compose.onNodeWithTag("note-row-qa_clone").assertDoesNotExist()
                deviceBack()
                compose.onNodeWithTag("note-search").performTextInput("QA")
                hideKeyboard()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("note-row-qa_shared").fetchSemanticsNodes().isNotEmpty() }
                listOf("qa_delete", "qa_folder", "qa_exclusive", "qa_clone").forEach {
                    compose.onNodeWithTag("note-row-$it").assertDoesNotExist()
                }
                deviceBack()
                compose.waitUntil(10_000) { !services.repository.syncState.value.isSyncing }
                compose.onNodeWithTag("sync-status").performClick()
                capture("03-offline-deletes-queued")
                compose.onNodeWithText("Закрыть").performClick()
                val pendingJson = JSONObject(cache.readText()).getJSONObject("pending")
                assertEquals(deleteTargets, pendingJson.keys().asSequence().toSet())
                deleteTargets.forEach { assertEquals("delete", pendingJson.getJSONObject(it).getString("kind")) }
                assertTrue(server.mutations().none { it.status in 200..299 })
                File(output, "phase-a.json").writeText(JSONObject()
                    .put("status", "PASS").put("pid", android.os.Process.myPid())
                    .put("cancelDurableCacheUnchanged", true).put("realCreateEditDelete", true)
                    .put("unsentCreateCancelled", localId).put("pendingDeleteIds", JSONArray(deleteTargets.toList()))
                    .put("exclusiveChildHidden", true).put("sharedChildPreserved", true)
                    .put("allClonePlacementsHidden", true).put("requests", server.requestJson()).toString(2))
                File(output, "offline-durable-cache.json").writeText(cache.readText())
            }
            finishInjection()
        }
    }

    @Test fun b_afterProcessRestartReconnectAndRejectStaleServerData() {
        requireIsolatedRun()
        val prior = JSONObject(File(output, "phase-a.json").readText())
        assertTrue("This phase must be a different real application process", prior.getInt("pid") != android.os.Process.myPid())
        assertTrue("Durable fixture disappeared across force-stop", cache.isFile)
        LoopbackFixture().use { server ->
            server.loseDeleteResponseOnce = "qa_delete"
            val services = inject(server, directory)
            deleteTargets.forEach { assertTrue(services.repository.isDeleted(it)) }
            assertTrue(services.repository.isDeleted("qa_exclusive"))
            assertNotNull(services.repository.cachedNote("qa_shared"))
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                waitTag("note-row-qa_other")
                deleteTargets.forEach { compose.onNodeWithTag("note-row-$it").assertDoesNotExist() }
                compose.waitUntil(10_000) { !services.repository.syncState.value.isSyncing }
                // The default Android network remains off; only the fixture transport reconnects.
                server.enabled = true
                activity.onActivity { ViewModelProvider(it)[NotesViewModel::class.java].connectionAvailable() }
                compose.waitUntil(15_000) {
                    server.mutations().count { it.status == 204 } >= 2 &&
                        services.repository.syncState.value.pendingCount == 1 && !services.repository.syncState.value.isSyncing
                }
                assertEquals(setOf("qa_delete"), JSONObject(cache.readText()).getJSONObject("pending").keys().asSequence().toSet())
                // User retries an ambiguous delete; server 404 is successful idempotent completion.
                compose.onNodeWithTag("sync-status").performClick()
                compose.onNodeWithText("Обновить").performClick()
                compose.waitUntil(15_000) { services.repository.syncState.value.pendingCount == 0 }
                assertTrue(server.mutations().any { it.method == "DELETE" && it.path.endsWith("/qa_delete") && it.status == 404 })
                assertTrue(server.mutations().all { it.method == "DELETE" })
                assertEquals(deleteTargets, server.mutations().map { it.path.substringAfterLast('/') }.toSet())
                assertTrue(deleteTargets.all { it in server.remoteDeleted })
                assertTrue("Server did not remove orphan descendant", "qa_exclusive" in server.remoteDeleted)
                assertFalse("Server removed shared child", "qa_shared" in server.remoteDeleted)

                // Intentionally stale GET/tree/search after successful deletes must not resurrect them.
                server.serveStale = true
                val refresh = runBlocking { services.offline.refreshAll() }
                assertTrue("Synthetic full crawl failed: $refresh", refresh.complete)
                val search = runBlocking { services.repository.searchResult("QA") }
                assertTrue(search.notes.none { it.id in deleteTargets || it.id == "qa_exclusive" })
                deleteTargets.forEach {
                    assertTrue(services.repository.isDeleted(it))
                    assertEquals(null, services.repository.cachedNote(it))
                }
                compose.onNodeWithTag("note-row-qa_other").performClick()
                waitTag("note-row-qa_shared")
                compose.onNodeWithTag("note-row-qa_clone").assertDoesNotExist()
                capture("04-reconnected-shared-child-remains")
                File(output, "phase-b.json").writeText(JSONObject().put("status", "PASS")
                    .put("pid", android.os.Process.myPid()).put("priorPid", prior.getInt("pid"))
                    .put("forceStopPersistence", true).put("automaticReconnectDelete", true)
                    .put("ambiguousDelete404Retry", true).put("pendingCount", 0)
                    .put("staleFullCrawlAndSearchRejected", true).put("requests", server.requestJson()).toString(2))
                File(output, "after-reconnect-durable-cache.json").writeText(cache.readText())
            }
            finishInjection()
        }
    }

    @Test fun c_systemBackPreservesFolderAndSearchScroll() {
        requireIsolatedRun()
        LoopbackFixture(scrollCount = 90).use { server ->
            val scrollDirectory = File(app.cacheDir, "delete-acceptance-scroll-synthetic").apply { mkdirs() }
            server.writeCache(scrollDirectory)
            inject(server, scrollDirectory)
            ActivityScenario.launch(MainActivity::class.java).use {
                waitTag("note-list")
                repeat(5) { compose.onNodeWithTag("note-list").performTouchInput { swipeUp(durationMillis = 350) } }
                val folder = anchor()
                assertTrue("The test never scrolled beyond the first screen", folder.id.substringAfterLast('_').toInt() > 10)
                compose.onNodeWithTag(folder.id).performClick()
                waitTag("note-source-reader")
                deviceBack()
                waitTag("note-list")
                assertAnchor("folder", folder)
                capture("05-system-back-restored-scroll")
                compose.onNodeWithTag("note-list").performScrollToIndex(0)
                compose.onNodeWithTag("note-search").performTextInput("Scroll QA")
                hideKeyboard()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("note-row-qa_scroll_000").fetchSemanticsNodes().isNotEmpty() }
                repeat(5) { compose.onNodeWithTag("note-list").performTouchInput { swipeUp(durationMillis = 350) } }
                val search = anchor()
                compose.onNodeWithTag(search.id).performClick()
                waitTag("note-source-reader")
                deviceBack()
                waitTag("note-list")
                assertAnchor("search", search)
                File(output, "phase-c.json").writeText(JSONObject().put("status", "PASS")
                    .put("backInput", "Android KEYCODE_BACK, not toolbar")
                    .put("scrollInput", "real Compose touch swipes")
                    .put("folderAnchor", folder.json()).put("searchAnchor", search.json())
                    .put("positionTolerancePx", 2).toString(2))
            }
            finishInjection()
        }
    }

    private fun requireIsolatedRun() {
        assumeTrue("Explicit emulator-only acceptance", InstrumentationRegistry.getArguments().getString("syntheticDeleteAcceptance") == "yes")
        assertTrue("An emulator is required", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        assertFalse("Keep Android default Internet disabled; TLS fixture is loopback", app.hasValidatedInternet())
        WorkManager.getInstance(app).cancelAllWork().result.get(10, TimeUnit.SECONDS)
    }

    private fun inject(server: LoopbackFixture, files: File): NotesServices {
        val context = object : ContextWrapper(app) { override fun getFilesDir(): File = files }
        val services = NotesServices(context, TriliumApi(server.url, "synthetic-delete-test-token", server.client), server.url)
        singleton.set(null, services)
        return services
    }

    private fun finishInjection() {
        WorkManager.getInstance(app).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        singleton.set(null, null)
    }

    private fun pending(id: String) = JSONObject(cache.readText()).getJSONObject("pending").getJSONObject(id)
    private fun waitTag(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun openDelete(id: String) {
        waitTag("note-actions-$id")
        compose.onNodeWithTag("note-actions-$id").performClick()
        compose.onNodeWithText("Удалить").performClick()
        waitTag("delete-note-dialog")
    }
    private fun confirmDelete(id: String) {
        openDelete(id)
        compose.onNodeWithTag("confirm-delete-note").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("delete-note-dialog").fetchSemanticsNodes().isEmpty() }
    }
    private fun deviceBack() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); instrumentation.waitForIdleSync() }
    private fun hideKeyboard() {
        instrumentation.runOnMainSync {
            val view = currentActivity().window.decorView
            (app.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
            currentActivity().currentFocus?.clearFocus()
        }
        instrumentation.waitForIdleSync()
    }
    private fun currentActivity(): MainActivity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single()

    private fun findWeb(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWeb(view.getChildAt(it)) }
        else -> null
    }
    private fun webTitleFocused(): Boolean {
        val result = java.util.concurrent.atomic.AtomicReference("false")
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val web = findWeb(currentActivity().window.decorView)
            if (web == null) done.countDown() else web.evaluateJavascript("document.activeElement?.id === 'title' && !document.activeElement.readOnly") { result.set(it); done.countDown() }
        }
        return done.await(1, TimeUnit.SECONDS) && result.get() == "true"
    }

    private data class Anchor(val id: String, val top: Float) { fun json() = JSONObject().put("id", id).put("topPx", top) }
    private fun anchor(): Anchor {
        compose.waitForIdle()
        val rows = compose.onAllNodes(SemanticsMatcher("scroll rows") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("note-row-qa_scroll_") == true
        }).fetchSemanticsNodes().filter { it.boundsInRoot.height > 70f }.sortedBy { it.boundsInRoot.top }
        assertTrue("Expected multiple visible rows", rows.size >= 3)
        val node = rows[1]
        return Anchor(requireNotNull(node.config.getOrNull(SemanticsProperties.TestTag)), node.boundsInRoot.top)
    }
    private fun assertAnchor(kind: String, expected: Anchor) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(expected.id).fetchSemanticsNodes().isNotEmpty() }
        val actual = compose.onNodeWithTag(expected.id).fetchSemanticsNode().boundsInRoot.top
        assertTrue("$kind back changed ${expected.id} offset from ${expected.top} to $actual", abs(actual - expected.top) <= 2f)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val done = CountDownLatch(1)
        var window: Window? = null
        val registeredAt = System.nanoTime()
        var frames = 0
        val listener = Window.OnFrameMetricsAvailableListener { current, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP) >= registeredAt) frames++
            if (frames >= 2) done.countDown() else current.decorView.postInvalidateOnAnimation()
        }
        instrumentation.runOnMainSync {
            window = currentActivity().window
            window!!.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
            window!!.decorView.postInvalidateOnAnimation()
        }
        assertTrue("Current Android frames did not render", done.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { window!!.removeOnFrameMetricsAvailableListener(listener) }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private class LoopbackFixture(scrollCount: Int = 0) : Closeable {
        data class Recorded(val method: String, val path: String, val status: Int)
        private val requests = Collections.synchronizedList(mutableListOf<Recorded>())
        private val notes = linkedMapOf<String, JSONObject>()
        private val bodies = linkedMapOf<String, String>()
        private val branches = linkedMapOf<String, JSONObject>()
        val remoteDeleted = Collections.synchronizedSet(mutableSetOf<String>())
        @Volatile var enabled = false
        @Volatile var serveStale = false
        @Volatile var loseDeleteResponseOnce: String? = null
        @Volatile private var closed = false
        private val socket: SSLServerSocket
        private val worker: Thread
        val client: OkHttpClient
        val url: String get() = "https://127.0.0.1:${socket.localPort}"

        init {
            if (scrollCount > 0) {
                note("root", "QA library", emptyList())
                repeat(scrollCount) { index ->
                    val id = "qa_scroll_${index.toString().padStart(3, '0')}"
                    note(id, "Scroll QA ${index.toString().padStart(3, '0')}", listOf("root"))
                    branch("root", id)
                }
            } else {
                note("root", "QA library", emptyList())
                note("qa_keep", "Сохранить при отмене QA", listOf("root"))
                note("qa_delete", "Удалить после правки QA", listOf("root"))
                note("qa_folder", "Пустая папка QA", listOf("root"), body = "")
                note("qa_other", "Другая папка QA", listOf("root"), body = "")
                note("qa_clone", "Заметка в двух папках QA", listOf("root", "qa_other"))
                note("qa_exclusive", "Только внутри удалённой папки QA", listOf("qa_folder"))
                note("qa_shared", "Общая дочерняя заметка QA", listOf("qa_folder", "qa_other"))
                listOf("qa_keep", "qa_delete", "qa_folder", "qa_other", "qa_clone").forEach { branch("root", it) }
                branch("qa_folder", "qa_exclusive"); branch("qa_folder", "qa_shared")
                branch("qa_other", "qa_shared"); branch("qa_other", "qa_clone")
            }
            val password = "qa-loopback".toCharArray()
            val store = KeyStore.getInstance("PKCS12")
            InstrumentationRegistry.getInstrumentation().context.assets.open("lifecycle-loopback.p12").use { store.load(it, password) }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
            client = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust.trustManagers.filterIsInstance<X509TrustManager>().single())
                .connectTimeout(1, TimeUnit.SECONDS).callTimeout(2, TimeUnit.SECONDS).build()
            socket = tls.serverSocketFactory.createServerSocket(0, 16, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            worker = thread(name = "delete-synthetic-loopback", isDaemon = true) {
                while (!closed) {
                    try {
                        socket.accept().use { connection ->
                            connection.soTimeout = 3_000
                            val reader = connection.getInputStream().bufferedReader()
                            val line = reader.readLine() ?: return@use
                            while (!reader.readLine().isNullOrEmpty()) { /* never log headers or credentials */ }
                            val method = line.substringBefore(' ')
                            val path = line.substringAfter(' ').substringBefore(' ')
                            val (status, body) = response(method, path)
                            requests += Recorded(method, path, status)
                            val bytes = body.toByteArray(Charsets.UTF_8)
                            connection.getOutputStream().apply {
                                write("HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes); flush()
                            }
                        }
                    } catch (error: SocketException) { if (!closed) throw error }
                    catch (_: java.io.IOException) { /* a cancelled UI request is harmless */ }
                }
            }
        }

        private fun note(id: String, title: String, parents: List<String>, body: String = "Synthetic QA body") {
            notes[id] = JSONObject().put("noteId", id).put("title", title).put("type", "code").put("mime", "text/plain")
                .put("isProtected", false).put("blobId", "qa_blob_$id").put("utcDateModified", "2026-10-24 00:00:00.000Z")
                .put("childBranchIds", JSONArray()).put("parentNoteIds", JSONArray(parents))
            bodies[id] = body
        }
        private fun branch(parent: String, child: String) {
            val id = "${parent}_$child"
            notes.getValue(parent).getJSONArray("childBranchIds").put(id)
            branches[id] = JSONObject().put("branchId", id).put("parentNoteId", parent).put("noteId", child)
                .put("prefix", "").put("notePosition", branches.size * 10).put("isExpanded", false)
        }
        fun writeCache(directory: File) {
            val noteJson = JSONObject()
            notes.forEach { (id, meta) -> noteJson.put(id, JSONObject().put("meta", meta).put("html", bodies.getValue(id))) }
            val children = JSONObject()
            notes.keys.forEach { parent ->
                val array = JSONArray()
                branches.values.filter { it.getString("parentNoteId") == parent }.forEach { branch ->
                    array.put(JSONObject().put("branchId", branch.getString("branchId")).put("note", notes.getValue(branch.getString("noteId")))
                        .put("prefix", "").put("position", branch.getInt("notePosition")).put("isExpanded", false))
                }
                children.put(parent, array)
            }
            File(directory, "trilium-cache.json").writeText(JSONObject().put("counter", 0).put("notes", noteJson)
                .put("children", children).put("pending", JSONObject()).toString())
            File(directory, "offline-copy-complete").writeText("1")
        }
        @Synchronized private fun response(method: String, target: String): Pair<Int, String> {
            if (!enabled) return 503 to "{}"
            val path = target.substringBefore('?')
            val id = path.substringAfter("/etapi/notes/").substringBefore('/')
            if (method == "DELETE" && path.startsWith("/etapi/notes/")) {
                if (id in remoteDeleted || id !in notes) return 404 to "{}"
                remoteDeleted += id
                var changed: Boolean
                do {
                    changed = false
                    notes.forEach { (child, meta) ->
                        val parents = meta.getJSONArray("parentNoteIds")
                        if (parents.length() > 0 && (0 until parents.length()).all { parents.getString(it) in remoteDeleted } && remoteDeleted.add(child)) changed = true
                    }
                } while (changed)
                if (loseDeleteResponseOnce == id) { loseDeleteResponseOnce = null; return 503 to "{}" }
                return 204 to ""
            }
            if (method != "GET") return 405 to "{}"
            if (path == "/etapi/notes") {
                val result = notes.filter { serveStale || it.key !in remoteDeleted }.values.toList()
                return 200 to JSONObject().put("results", JSONArray(result)).toString()
            }
            if (path.startsWith("/etapi/branches/")) {
                val branch = branches[path.substringAfterLast('/')] ?: return 404 to "{}"
                if (!serveStale && (branch.getString("noteId") in remoteDeleted || branch.getString("parentNoteId") in remoteDeleted)) return 404 to "{}"
                return 200 to branch.toString()
            }
            if (path.startsWith("/etapi/notes/")) {
                val original = notes[id] ?: return 404 to "{}"
                if (!serveStale && id in remoteDeleted) return 404 to "{}"
                if (path.endsWith("/attachments")) return 200 to "[]"
                if (path.endsWith("/content")) return 200 to bodies.getValue(id)
                val meta = JSONObject(original.toString())
                if (!serveStale) {
                    meta.put("childBranchIds", JSONArray(branches.values.filter {
                        it.getString("parentNoteId") == id && it.getString("noteId") !in remoteDeleted
                    }.map { it.getString("branchId") }))
                    val parents = original.getJSONArray("parentNoteIds")
                    meta.put("parentNoteIds", JSONArray((0 until parents.length()).map { parents.getString(it) }.filterNot { it in remoteDeleted }))
                }
                return 200 to meta.toString()
            }
            return 404 to "{}"
        }
        fun mutations(): List<Recorded> = synchronized(requests) { requests.filter { it.method != "GET" } }
        fun requestJson(): JSONArray = synchronized(requests) {
            JSONArray(requests.map { JSONObject().put("method", it.method).put("path", it.path).put("status", it.status) })
        }
        override fun close() { closed = true; socket.close(); worker.join(5_000) }
    }
}
