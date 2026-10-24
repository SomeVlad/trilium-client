package ru.vlad.triliumnotes.data

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

@RunWith(RobolectricTestRunner::class)
class SessionStoreTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
    // JVM tests use a real AES/GCM key; device persistence uses AndroidKeyStore exclusively.
    private val cipher = object : SessionCipher {
        override fun encrypt(clear: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key)
            iv + doFinal(clear)
        }
        override fun decrypt(encrypted: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            doFinal(encrypted.copyOfRange(12, encrypted.size))
        }
    }
    @Before fun start() {
        directory = Files.createTempDirectory("sessions").toFile()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = directory
            override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
        }
    }
    @After fun stop() { directory.deleteRecursively() }
    private fun session(url: String) = TriliumSession("generation", accountIdFor(url), url, "private-token", true)

    @Test fun processRestartRestoresEncryptedTokenAndLocalSignoutPreservesAccountData() {
        val session = session("https://notes.example/subpath")
        SessionStore(context, cipher).save(session)
        val raw = File(directory, "session.encrypted").readText()
        assertFalse(raw.contains("private-token"))
        assertFalse(raw.contains("notes.example"))
        assertEquals(session, SessionStore(context, cipher).load())
        val account = accountContext(context, session.accountId)
        File(account.filesDir, "draft").writeText("unsent work")
        SessionStore(context, cipher).clear()
        assertNull(SessionStore(context, cipher).load())
        assertEquals("unsent work", File(account.filesDir, "draft").readText())
    }

    @Test fun corruptEncryptedSessionIsPreservedAndCannotBeLoaded() {
        SessionStore(context, cipher).save(session("https://notes.example"))
        val file = File(directory, "session.encrypted")
        val bytes = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(bytes)
        try { SessionStore(context, cipher).load(); fail("Expected authentication failure") }
        catch (_: SessionStorageException) { }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun serverIsolationKeepsDraftsAndLegacyCacheOutOfOtherAccounts() = runBlocking {
        val first = session("https://first.example")
        val second = session("https://second.example")
        File(directory, "trilium-cache.json").writeText("legacy data")
        val firstContext = accountContext(context, first.accountId)
        val firstRepo = NotesRepository(firstContext, TriliumApi(first.serverUrl, first.token))
        firstRepo.create("root", "Private draft", "<p>Unsent</p>")
        val secondRepo = NotesRepository(accountContext(context, second.accountId), TriliumApi(second.serverUrl, second.token))
        assertEquals(1, firstRepo.syncState.value.pendingCount)
        assertEquals(0, secondRepo.syncState.value.pendingCount)
        assertEquals(1, NotesRepository(accountContext(context, first.accountId), TriliumApi(first.serverUrl, first.token)).syncState.value.pendingCount)
        assertEquals("legacy data", File(directory, "trilium-cache.json").readText())
        assertTrue(NotesServices.hasLegacyCache(context))
    }
    @Test fun explicitLegacyImportCopiesDraftsAndResourcesAndRetainsOriginals() = runBlocking {
        val target = session("https://notes.example")
        val original = NotesRepository(context, TriliumApi(target.serverUrl, target.token))
        original.create("root", "Legacy draft", "<p>Keep me</p>")
        val resources = File(directory, "note-resources").apply { mkdirs() }
        File(resources, "attachment.data").writeText("binary fixture")
        assertEquals(1, NotesServices.legacyPendingCount(context))
        assertTrue(NotesServices.legacyImportAvailable(context))
        NotesServices.importLegacyFiles(context, target)
        val account = accountContext(context, target.accountId)
        val imported = NotesRepository(account, TriliumApi(target.serverUrl, target.token))
        assertEquals(1, imported.syncState.value.pendingCount)
        assertEquals("binary fixture", File(account.filesDir, "note-resources/attachment.data").readText())
        assertEquals("binary fixture", File(resources, "attachment.data").readText())
        assertEquals(1, original.syncState.value.pendingCount)
        assertFalse(NotesServices.legacyImportAvailable(context))
        assertTrue(NotesServices.hasLegacyCache(context))
    }

    @Test fun legacyImportNeverOverwritesAnAccountWithPendingEdits() = runBlocking {
        val target = session("https://notes.example")
        val original = NotesRepository(context, TriliumApi(target.serverUrl, target.token))
        original.create("root", "Legacy draft", "old")
        val account = accountContext(context, target.accountId)
        val destination = NotesRepository(account, TriliumApi(target.serverUrl, target.token))
        destination.create("root", "Existing draft", "new")
        val before = File(account.filesDir, "trilium-cache.json").readBytes()
        try { NotesServices.importLegacyFiles(context, target); fail("Expected existing data rejection") }
        catch (_: java.io.IOException) { }
        assertArrayEquals(before, File(account.filesDir, "trilium-cache.json").readBytes())
        assertTrue(NotesServices.legacyImportAvailable(context))
    }

    @Test fun replacingCleanAccountRetainsPreviousCacheAndResources() = runBlocking {
        val target = session("https://notes.example")
        val original = NotesRepository(context, TriliumApi(target.serverUrl, target.token))
        original.create("root", "Legacy draft", "old")
        val account = accountContext(context, target.accountId)
        val previousCache = """{"pending":{},"notes":{},"children":{},"counter":42}"""
        File(account.filesDir, "trilium-cache.json").writeText(previousCache)
        File(account.filesDir, "note-resources").mkdirs()
        File(account.filesDir, "note-resources/cached.data").writeText("previous resource")
        NotesServices.importLegacyFiles(context, target)
        assertEquals(1, NotesRepository(account, TriliumApi(target.serverUrl, target.token)).syncState.value.pendingCount)
        val retained = account.filesDir.parentFile!!.listFiles()!!.single { it.name.startsWith(target.accountId + "-retained-") }
        assertEquals(previousCache, File(retained, "trilium-cache.json").readText())
        assertEquals("previous resource", File(retained, "note-resources/cached.data").readText())
    }

}
