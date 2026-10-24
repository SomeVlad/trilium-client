package ru.vlad.triliumnotes.data

import ru.vlad.triliumnotes.R
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import android.util.AtomicFile
import org.json.JSONObject
import java.util.UUID

class LegacyImportException(val messageId: Int, vararg val arguments: Any) : IOException("Legacy import failed")

/** Immutable services and a separate cache namespace for the active Trilium server. */
class NotesServices internal constructor(
    context: Context,
    val api: TriliumApi,
    val serverUrl: String,
    val sessionId: String? = null,
) {
    val repository = NotesRepository(context, api)
    val resources = NoteResources(context, api, serverUrl)
    val offline = OfflineSync(repository, api, resources, context)

    companion object {
        @Volatile private var instance: NotesServices? = null

        fun get(context: Context): NotesServices = current(context)
            ?: throw IllegalStateException("Sign in to Trilium first")

        fun current(context: Context): NotesServices? = instance ?: synchronized(this) {
            instance ?: SessionStore(context.applicationContext).load()?.let { session ->
                create(context.applicationContext, session).also { instance = it }
            }
        }

        suspend fun activate(context: Context, session: TriliumSession): NotesServices = withContext(Dispatchers.IO) {
            synchronized(this@Companion) {
                val app = context.applicationContext
                activateLocked(app, session)
            }
        }

        suspend fun signOut(context: Context) = withContext(Dispatchers.IO) {
            synchronized(this@Companion) {
                val app = context.applicationContext
                SessionStore(app).clear()
                val previous = instance
                previous?.api?.close()
                instance = null
                NotesSyncScheduler.cancelSession(app, previous?.sessionId)
            }
        }

        private fun activateLocked(app: Context, session: TriliumSession): NotesServices {
            // Persist first: a storage failure leaves the previous session fully usable.
            SessionStore(app).save(session)
            val previous = instance
            previous?.api?.close()
            NotesSyncScheduler.cancelSession(app, previous?.sessionId)
            return create(app, session).also { instance = it }
        }

        /** An explicit owner action is required: unscoped files cannot identify their old server. */
        suspend fun importLegacy(context: Context, session: TriliumSession): NotesServices = withContext(Dispatchers.IO) {
            synchronized(this@Companion) {
                val app = context.applicationContext
                if (instance?.serverUrl == session.serverUrl)
                    throw LegacyImportException(R.string.msg_legacy_sign_out)
                importLegacyFiles(app, session)
                activateLocked(app, session)
            }
        }

        internal fun importLegacyFiles(app: Context, session: TriliumSession) {
            if (!legacyImportAvailable(app)) throw LegacyImportException(R.string.msg_legacy_missing)
            if (legacyPendingCount(app) == null) throw LegacyImportException(R.string.msg_legacy_corrupt)
            val account = accountContext(app, session.accountId).filesDir
            val cache = File(account, "trilium-cache.json")
            if (cache.isFile || File(cache.path + ".bak").isFile) {
                val pending = runCatching {
                    JSONObject(AtomicFile(cache).readFully().toString(Charsets.UTF_8)).optJSONObject("pending")?.length() ?: 0
                }.getOrElse {
                    throw LegacyImportException(R.string.msg_legacy_current_corrupt)
                }
                if (pending > 0)
                    throw LegacyImportException(R.string.msg_legacy_pending, pending)
            }
            val stage = File(account.parentFile, ".legacy-${UUID.randomUUID()}")
            try {
                stage.mkdirs()
                val source = AtomicFile(File(app.filesDir, "trilium-cache.json"))
                File(stage, "trilium-cache.json").writeBytes(source.readFully())
                listOf("offline-copy-complete", "note-resources").forEach { name ->
                    val original = File(app.filesDir, name)
                    if (original.exists() && !original.copyRecursively(File(stage, name)))
                        throw LegacyImportException(R.string.msg_legacy_copy_failed)
                }
                val retained = File(account.parentFile, "${session.accountId}-retained-${UUID.randomUUID()}")
                val hasFiles = account.walkTopDown().any { it.isFile }
                if (hasFiles && !account.renameTo(retained))
                    throw LegacyImportException(R.string.msg_legacy_retain_failed)
                if (!hasFiles) account.deleteRecursively()
                if (!stage.renameTo(account)) {
                    if (hasFiles) retained.renameTo(account)
                    throw LegacyImportException(R.string.msg_legacy_attach_failed)
                }
                // This contains only an account hash. All original files remain available for recovery.
                val marker = AtomicFile(File(app.filesDir, "legacy-imported"))
                val output = marker.startWrite()
                try { output.write(session.accountId.toByteArray()); marker.finishWrite(output) }
                catch (error: Exception) { marker.failWrite(output); throw error }

            } finally { stage.deleteRecursively() }
        }

        fun legacyImportAvailable(context: Context): Boolean = hasLegacyCache(context) &&
            !File(context.filesDir, "legacy-imported").exists()

        fun legacyPendingCount(context: Context): Int? = runCatching {
            val json = JSONObject(AtomicFile(File(context.filesDir, "trilium-cache.json")).readFully().toString(Charsets.UTF_8))
            json.optJSONObject("pending")?.length() ?: 0
        }.getOrNull()

        /** Old builds had no server identity: retain their files without attaching them to a new login. */
        fun hasLegacyCache(context: Context): Boolean = File(context.filesDir, "trilium-cache.json").isFile ||
            File(context.filesDir, "trilium-cache.json.bak").isFile

        private fun create(context: Context, session: TriliumSession): NotesServices = NotesServices(
            accountContext(context, session.accountId),
            TriliumApi(session.serverUrl, session.token), session.serverUrl, session.id,
        )
    }
}

internal fun accountContext(context: Context, accountId: String): Context {
    require(accountId.matches(Regex("[a-f0-9]{64}")))
    return object : ContextWrapper(context) {
        override fun getFilesDir(): File = File(context.filesDir, "accounts/$accountId").apply { mkdirs() }
        override fun getCacheDir(): File = File(context.cacheDir, "accounts/$accountId").apply { mkdirs() }
    }
}
