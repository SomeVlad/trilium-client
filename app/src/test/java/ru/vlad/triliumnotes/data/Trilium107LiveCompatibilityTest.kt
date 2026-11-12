package ru.vlad.triliumnotes.data

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in only: TRILIUM107_TEST_CONFIG points to a private disposable-server JSON fixture. */
@RunWith(RobolectricTestRunner::class)
class Trilium107LiveCompatibilityTest {
    @Test fun productionClientWorksWithDisposableTrilium107() = runBlocking {
        val path = System.getenv("TRILIUM107_TEST_CONFIG")
        assumeTrue("Disposable Trilium fixture was not requested", !path.isNullOrBlank())
        val configFile = File(requireNotNull(path)).canonicalFile
        val config = JSONObject(configFile.readText())
        val base = config.getString("baseUrl")
        val uri = URI(base)
        require(config.optBoolean("synthetic")) { "Only an explicit synthetic fixture is permitted" }
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1024..65535 &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.path.orEmpty() in setOf("", "/")) { "Disposable fixture must be on IPv4 loopback" }
        val dataDir = File(config.getString("dataDir")).canonicalFile
        require(requireNotNull(configFile.parentFile).path.startsWith("/tmp/") && dataDir.parentFile == configFile.parentFile) {
            "Disposable fixture and data must share their own directory under /tmp"
        }
        val token = config.getString("token")
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        // Validate the actual server and isolated data directory BEFORE even a password login.
        client.newCall(Request.Builder().url("$base/etapi/app-info")
            .header("Authorization", token).build()).execute().use { response ->
            assertEquals(200, response.code)
            val info = JSONObject(requireNotNull(response.body).string())
            assertEquals("0.107.0", info.getString("appVersion"))
            assertEquals(dataDir.path, File(info.getString("dataDirectory")).canonicalPath)
        }
        val api = TriliumApi(base, token, client)
        api.validateSession()
        val auth = TriliumAuth(client)
        val password = config.getString("password")
        val missingFactor = runCatching {
            auth.authenticate(base, password, allowInsecureHttp = true)
        }.exceptionOrNull()
        assertTrue("Expected TOTP_REQUIRED; got ${missingFactor?.javaClass?.simpleName}, HTTP ${(missingFactor as? ApiException)?.status}",
            missingFactor is TotpRequiredException)
        val wrongFactor = runCatching {
            auth.authenticate(base, password, allowInsecureHttp = true, totpToken = "invalid-code")
        }.exceptionOrNull()
        assertTrue("Expected WRONG_TOTP; got ${wrongFactor?.javaClass?.simpleName}, HTTP ${(wrongFactor as? ApiException)?.status}",
            wrongFactor is WrongTotpException)
        val wrongPassword = runCatching {
            auth.authenticate(base, "synthetic-wrong-password", allowInsecureHttp = true, totpToken = "invalid-code")
        }.exceptionOrNull()
        assertTrue(wrongPassword is ApiException && wrongPassword.status == 401)
        val tokenSession = auth.authenticate(base, token, useToken = true, allowInsecureHttp = true)
        assertFalse(tokenSession.passwordLogin)

        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val parentId = "qa107p$suffix"
        val childId = "qa107n$suffix"
        val secondParentId = "qa107s$suffix"
        val trackedNotes = listOf(childId, parentId, secondParentId)
        val temp = Files.createTempDirectory("trilium107-client-$suffix").toFile()
        val filesDir = File(temp, "files").apply { mkdirs() }
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = filesDir
        }
        var loginApi: TriliumApi? = null
        var attachmentId: String? = null
        var testPassed = false
        val closedApis = mutableListOf<TriliumApi>()
        val cleanupErrors = mutableListOf<String>()
        try {
            // Reserve a code while holding the private fixture lock. A failed run must never retry it.
            val recoveryCode = RandomAccessFile(configFile, "rw").use { file ->
                file.channel.lock().use {
                    val current = JSONObject(configFile.readText())
                    val codes = current.getJSONArray("recoveryCodes")
                    check(codes.length() > 0) { "Synthetic fixture needs an unused recovery code" }
                    val code = codes.getString(codes.length() - 1)
                    codes.remove(codes.length() - 1)
                    current.put("recoveryCodesConsumedByQA", current.optInt("recoveryCodesConsumedByQA") + 1)
                    val bytes = (current.toString(2) + "\n").toByteArray()
                    file.channel.truncate(0)
                    file.channel.position(0)
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) file.channel.write(buffer)
                    file.channel.force(true)
                    code
                }
            }
            val passwordSession = auth.authenticate(base, password, allowInsecureHttp = true, totpToken = recoveryCode)
            assertTrue(passwordSession.passwordLogin)
            val authenticatedApi = TriliumApi(base, passwordSession.token, client)
            loginApi = authenticatedApi
            authenticatedApi.validateSession()
            assertTrue(runCatching {
                auth.authenticate(base, password, allowInsecureHttp = true, totpToken = recoveryCode)
            }.exceptionOrNull() is WrongTotpException)

            api.createNote(parentId, "root", "Synthetic QA $suffix", "<p>Disposable parent</p>")
            api.createNote(secondParentId, "root", "Synthetic second $suffix", "<p>Second parent</p>")
            api.createNote(childId, parentId, "Synthetic note $suffix", "<p>Original content</p>")
            assertEquals(childId, api.getNote(childId).id)
            assertTrue(api.getChildren(parentId).any { it.note.id == childId })
            val renamed = "Synthetic renamed $suffix"
            assertEquals(renamed, api.patchTitle(childId, renamed).title)
            val history = NoteHistory(api)
            history.saveCurrentRevision(childId, "Synthetic compatibility snapshot")
            api.putContent(childId, "<p>Updated content</p>")
            assertTrue(api.getContent(childId).contains("Updated content"))
            val revision = history.list(childId).first { it.title == renamed }
            assertTrue(history.preview(childId, revision.id).content.contains("Original content"))

            val payload = byteArrayOf(0, 1, 42, -1, -128, 10) + "Synthetic attachment".toByteArray()
            val upload = File(temp, "upload.bin").apply { writeBytes(payload) }
            val attachment = api.createAttachment(childId, "file", "application/octet-stream", "synthetic.bin", 0)
            attachmentId = attachment.id
            api.putAttachmentContent(attachment.id, upload)
            assertArrayEquals(payload, api.getAttachmentContent(attachment.id).bytes)
            api.patchAttachmentTitle(attachment.id, "synthetic-renamed.bin")
            assertTrue(api.getAttachments(childId).any { it.id == attachment.id && it.title == "synthetic-renamed.bin" })
            val download = File(temp, "download.bin")
            api.downloadAttachment(attachment.id, download)
            assertArrayEquals(payload, download.readBytes())

            val branch = JSONObject(api.etapiRequest("POST", listOf("branches"), JSONObject()
                .put("noteId", childId).put("parentNoteId", secondParentId).put("prefix", "QA")
                .put("notePosition", 10).put("isExpanded", false).toString(), expectedCode = 201))
            val branchId = branch.getString("branchId")
            api.etapiRequest("PATCH", listOf("branches", branchId), JSONObject()
                .put("prefix", "Updated").put("notePosition", 20).toString())
            assertEquals("Updated", JSONObject(api.etapiRequest("GET", listOf("branches", branchId))).getString("prefix"))
            assertTrue(api.search(renamed).any { it.id == childId })
            api.etapiRequest("DELETE", listOf("branches", branchId), expectedCode = 204)

            // The actual repository persists its draft across API close/reopen, and an offline sync attempt.
            val online = AtomicBoolean(true)
            val gatedClient = client.newBuilder().addInterceptor { chain ->
                if (!online.get()) throw IOException("Synthetic offline mode")
                chain.proceed(chain.request())
            }.build()
            val firstApi = TriliumApi(base, token, gatedClient).also { closedApis += it }
            val first = NotesRepository(context, firstApi)
            first.load(childId)
            online.set(false)
            val draftHtml = "<p>Durable offline draft $suffix</p>"
            first.saveDraft(childId, "Offline title $suffix", draftHtml)
            firstApi.close()
            val reopenedApi = TriliumApi(base, token, gatedClient).also { closedApis += it }
            val reopened = NotesRepository(context, reopenedApi)
            assertEquals(draftHtml, reopened.cachedNote(childId)?.html)
            assertTrue(reopened.cachedNote(childId)?.pending == true)
            assertEquals(1, reopened.syncPending().pendingCount)
            assertFalse(api.getContent(childId).contains("Durable offline draft"))
            reopenedApi.close()
            online.set(true)
            val reconnectedApi = TriliumApi(base, token, gatedClient).also { closedApis += it }
            val reconnected = NotesRepository(context, reconnectedApi)
            assertEquals(0, reconnected.syncPending().pendingCount)
            assertTrue(api.getContent(childId).contains("Durable offline draft $suffix"))
            assertEquals("Offline title $suffix", api.getNote(childId).title)
            assertFalse(reconnected.cachedNote(childId)!!.pending)

            api.deleteNote(childId)
            val missing = runCatching { api.getNote(childId) }.exceptionOrNull()
            assertTrue(missing is ApiException && missing.status == 404)
            assertTrue(JSONObject(api.etapiRequest("POST", listOf("notes", childId, "undelete"),
                expectedCode = 200)).getBoolean("success"))
            assertEquals(childId, api.getNote(childId).id)
            assertArrayEquals(payload, api.getAttachmentContent(attachment.id).bytes)
            testPassed = true
        } finally {
            closedApis.forEach { it.close() }
            attachmentId?.let { id ->
                try { api.etapiRequest("DELETE", listOf("attachments", id), expectedCode = 204) }
                catch (error: Exception) { cleanupErrors += "attachment:$id:${error.javaClass.simpleName}" }
            }
            trackedNotes.forEach { id ->
                try { api.deleteNote(id) }
                catch (error: ApiException) { if (error.status != 404) cleanupErrors += "note:$id:${error.status}" }
                catch (error: Exception) { cleanupErrors += "note:$id:${error.javaClass.simpleName}" }
            }
            loginApi?.let {
                try { it.etapiRequest("POST", listOf("auth", "logout"), expectedCode = 204) }
                catch (error: Exception) { cleanupErrors += "logout:${error.javaClass.simpleName}" }
                it.close()
            }
            api.close()
            temp.deleteRecursively()
            File(configFile.parentFile, "kotlin-live-receipt.json").writeText(JSONObject()
                .put("version", "0.107.0").put("testPassed", testPassed)
                .put("notes", JSONArray(trackedNotes)).put("attachmentId", attachmentId ?: JSONObject.NULL)
                .put("cleanupErrors", JSONArray(cleanupErrors)).toString(2) + "\n")
            assertTrue("Disposable fixture cleanup failed: ${cleanupErrors.joinToString()}", cleanupErrors.isEmpty())
        }
    }
}
