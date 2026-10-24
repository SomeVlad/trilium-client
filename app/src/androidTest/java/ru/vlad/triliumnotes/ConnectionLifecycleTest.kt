package ru.vlad.triliumnotes

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.work.WorkManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.vlad.triliumnotes.data.NotesServices
import ru.vlad.triliumnotes.data.NotesSyncScheduler
import ru.vlad.triliumnotes.data.SessionStore
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Runs only on an empty test installation, using production UI, session storage and AndroidKeyStore. */
class ConnectionLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext

    @Test fun passwordLoginRotationEncryptedRestoreAndReloginKeepTheDurableDraft() {
        val singleton = NotesServices::class.java.getDeclaredField("instance").apply { isAccessible = true }
        // Never clear an owner's session or attach an unscoped cache to the fixture.
        assertNull("Run lifecycle acceptance on an empty test installation", SessionStore(app).load())
        assertNull("Another test left active services", singleton.get(null))
        assertFalse("An existing legacy cache must be preserved", NotesServices.hasLegacyCache(app))
        val sessionIds = mutableSetOf<String>()
        var accountId: String? = null
        var activity: ActivityScenario<MainActivity>? = null
        PasswordFixture().use { server ->
            try {
                activity = ActivityScenario.launch(MainActivity::class.java)
                login(server.url)
                val firstSession = requireNotNull(SessionStore(app).load())
                sessionIds += firstSession.id
                accountId = firstSession.accountId
                assertTrue(firstSession.passwordLogin)
                assertEquals(server.url, firstSession.serverUrl)
                assertEquals(PasswordFixture.TOKEN, firstSession.token)
                assertEquals(PasswordFixture.PASSWORD, server.receivedPassword.get())
                val encrypted = File(app.filesDir, "session.encrypted").readBytes().toString(Charsets.ISO_8859_1)
                assertFalse("The persisted token must be encrypted", encrypted.contains(PasswordFixture.TOKEN))
                assertFalse("The login password must never be persisted", encrypted.contains(PasswordFixture.PASSWORD))
                assertFalse("The entire session, including URL, must be encrypted", encrypted.contains(server.url))

                openFixtureNote()
                compose.onNodeWithContentDescription("Редактировать заметку").performClick()
                compose.waitUntil(30_000) {
                    js("Boolean(window.pocket?.editor && !window.pocket.editor.isReadOnly && document.activeElement.classList.contains('ck-content'))") == "true"
                }
                instrumentation.sendStringSync(DRAFT)
                compose.waitUntil(15_000) {
                    NotesServices.get(app).repository.cachedNote("qa_child")?.let {
                        it.pending && DRAFT in it.html
                    } == true
                }
                assertTrue("The fixture rejects uploads so rotation is exercised with an unsent draft",
                    NotesServices.get(app).repository.syncState.value.pendingCount > 0)

                requireNotNull(activity).recreate()
                readyWithDraft()
                assertTrue(NotesServices.get(app).repository.cachedNote("qa_child")!!.pending)
                assertEquals("Rotation must not authenticate again", 1, server.logins.get())

                // Destroy the Activity/ViewModels and discard only this synthetic in-memory singleton.
                // The next MainActivity must reconstruct services from the real encrypted disk session.
                requireNotNull(activity).close()
                activity = null
                NotesServices.get(app).api.close()
                singleton.set(null, null)
                val diskSession = requireNotNull(SessionStore(app).load())
                assertEquals(firstSession.id, diskSession.id)
                activity = ActivityScenario.launch(MainActivity::class.java)
                openFixtureNote()
                readyWithDraft()
                assertEquals("Encrypted restoration must not ask the server for the password", 1, server.logins.get())
                assertTrue(NotesServices.get(app).repository.cachedNote("qa_child")!!.pending)

                backToLibrary()
                compose.onNodeWithTag("connection-settings").performClick()
                compose.onNodeWithTag("connection-disconnect").performClick()
                compose.onNodeWithText("Выйти", useUnmergedTree = true).performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Подключитесь к своему серверу заметок").fetchSemanticsNodes().isNotEmpty() }
                assertNull(SessionStore(app).load())
                assertTrue("Signing out must retain the server's local outbox", accountCache(accountId!!).readText().contains(DRAFT))

                login(server.url)
                val secondSession = requireNotNull(SessionStore(app).load())
                sessionIds += secondSession.id
                assertEquals(firstSession.accountId, secondSession.accountId)
                assertNotEquals(firstSession.id, secondSession.id)
                openFixtureNote()
                readyWithDraft()
                assertTrue(NotesServices.get(app).repository.cachedNote("qa_child")!!.pending)
                assertEquals(2, server.logins.get())
            } finally {
                activity?.close()
                val current = singleton.get(null) as? NotesServices
                if (current?.serverUrl == server.url) {
                    current.sessionId?.let(sessionIds::add)
                    current.api.close()
                    singleton.set(null, null)
                }
                SessionStore(app).load()?.takeIf { it.serverUrl == server.url }?.let {
                    sessionIds += it.id
                    SessionStore(app).clear()
                }
                val manager = WorkManager.getInstance(app)
                sessionIds.forEach { id ->
                    manager.cancelAllWorkByTag(NotesSyncScheduler.SESSION_TAG_PREFIX + id).result.get(10, TimeUnit.SECONDS)
                }
                accountId?.let {
                    File(app.filesDir, "accounts/$it").deleteRecursively()
                    File(app.cacheDir, "accounts/$it").deleteRecursively()
                }
            }
        }
    }

    private fun login(url: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Подключитесь к своему серверу заметок").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("connection-server").performTextInput(url)
        compose.onNodeWithTag("connection-secret").performTextInput(PasswordFixture.PASSWORD)
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithTag("connection-submit").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Synthetic lifecycle note").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openFixtureNote() {
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Synthetic lifecycle note").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("note-row-qa_child").performClick()
        compose.waitUntil(30_000) { js("Boolean(window.pocket?.ready)") == "true" }
    }

    private fun readyWithDraft() {
        compose.waitUntil(30_000) {
            js("Boolean(window.pocket?.ready && document.body.innerText.includes('$DRAFT'))") == "true"
        }
    }

    private fun backToLibrary() {
        if (compose.onAllNodesWithText("Готово").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Готово").performClick()
        }
        compose.onNodeWithContentDescription("Назад к заметкам").performClick()
        compose.onNodeWithTag("connection-settings").assertIsDisplayed()
    }

    private fun accountCache(id: String) = File(app.filesDir, "accounts/$id/trilium-cache.json")

    private fun js(expression: String): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference("")
        instrumentation.runOnMainSync {
            fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull()
            val web = activity?.window?.decorView?.let(::find)
            if (web == null) latch.countDown()
            else web.evaluateJavascript(expression) { result.set(it); latch.countDown() }
        }
        assertTrue("WebView JavaScript callback timed out", latch.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    private class PasswordFixture : Closeable {
        val logins = AtomicInteger()
        val receivedPassword = AtomicReference<String>()
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        @Volatile private var closed = false
        val url: String get() = "http://127.0.0.1:${socket.localPort}"
        private val worker = thread(name = "synthetic-password-lifecycle", isDaemon = true) {
            while (!closed) {
                try {
                    socket.accept().use { connection ->
                        connection.soTimeout = 5_000
                        val reader = connection.getInputStream().bufferedReader(Charsets.UTF_8)
                        val line = reader.readLine() ?: return@use
                        val method = line.substringBefore(' ')
                        val path = line.substringAfter(' ').substringBefore(' ')
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                            headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                        }
                        val data = CharArray(headers["content-length"]?.toIntOrNull() ?: 0)
                        var read = 0
                        while (read < data.size) {
                            val count = reader.read(data, read, data.size - read)
                            if (count < 0) break
                            read += count
                        }
                        var status = "200 OK"
                        val body = when {
                            method == "POST" && path == "/etapi/auth/login" -> {
                                receivedPassword.set(JSONObject(String(data)).optString("password"))
                                logins.incrementAndGet()
                                if (receivedPassword.get() != PASSWORD) { status = "401 Unauthorized"; "{}" }
                                else JSONObject().put("authToken", TOKEN).toString()
                            }
                            headers["authorization"] != TOKEN -> { status = "401 Unauthorized"; "{}" }
                            method != "GET" -> { status = "503 Service Unavailable"; "{}" }
                            path == "/etapi/app-info" -> """{"appVersion":"0.103.0"}"""
                            path == "/etapi/notes/root" -> """{"noteId":"root","title":"Synthetic library","type":"text","mime":"text/html","blobId":"root-v1","childBranchIds":["qa_branch"]}"""
                            path == "/etapi/branches/qa_branch" -> """{"branchId":"qa_branch","noteId":"qa_child","parentNoteId":"root","notePosition":10}"""
                            path == "/etapi/notes/qa_child" -> """{"noteId":"qa_child","title":"Synthetic lifecycle note","type":"text","mime":"text/html","blobId":"child-v1","childBranchIds":[],"parentNoteIds":["root"]}"""
                            path == "/etapi/notes/root/content" -> ""
                            path == "/etapi/notes/qa_child/content" -> "<p>Synthetic body</p>"
                            path.endsWith("/attachments") -> "[]"
                            else -> { status = "404 Not Found"; "{}" }
                        }
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        connection.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                } catch (error: SocketException) {
                    if (!closed && !socket.isClosed) continue
                } catch (_: java.io.IOException) {
                    // Cancellation during Activity destruction can close an in-flight socket.
                }
            }
        }
        override fun close() { closed = true; socket.close(); worker.join(5_000) }
        companion object {
            const val PASSWORD = "synthetic-lifecycle-password"
            const val TOKEN = "synthetic-lifecycle-token-not-a-real-credential"
        }
    }

    private companion object { const val DRAFT = "Draft survives rotation" }
}
