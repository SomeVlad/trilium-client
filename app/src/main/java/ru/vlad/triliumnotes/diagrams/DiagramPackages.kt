package ru.vlad.triliumnotes.diagrams

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ru.vlad.triliumnotes.data.NoteMeta

/** Versioned, verified renderer files shared by every account, independent of Trilium credentials. */
class DiagramPackages internal constructor(
    private val root: File,
    private val specs: Map<String, DiagramPackageSpec>,
    private val client: OkHttpClient = downloadClient,
) {
    private val locks = specs.mapValues { Mutex() }
    private val mutableChanges = MutableStateFlow(0)
    val changes = mutableChanges.asStateFlow()

    fun needsFor(notes: Iterable<NoteMeta>): Set<String> = notes.asSequence()
        .filterNot { it.isProtected || it.id.startsWith('_') }
        .map { it.type }.filter { it in specs }.toSet()

    fun isReady(kind: String): Boolean {
        val spec = spec(kind)
        val directory = directory(kind, spec)
        return runCatching {
            val marker = JSONObject(File(directory, ".complete").readText())
            val lengths = marker.getJSONObject("files")
            marker.getString("sha256") == spec.sha256 && lengths.length() == spec.files.size &&
                spec.files.all { path -> File(directory, path).let { it.isFile && it.length() == lengths.getLong(path) } }
        }.getOrDefault(false)
    }

    /** Only explicitly listed vendor assets can be served; no filesystem path comes from note content. */
    fun openAsset(path: String): InputStream? {
        if (!path.startsWith("diagrams/vendor/")) return null
        val relative = path.removePrefix("diagrams/vendor/")
        val (kind, spec) = specs.entries.firstOrNull { relative in it.value.files && isReady(it.key) } ?: return null
        return runCatching { File(directory(kind, spec), relative).inputStream() }.getOrNull()
    }

    /** Cached packages do not make even a conditional HTTP request. Failed/interrupted attempts retry. */
    suspend fun ensure(kind: String) = withContext(Dispatchers.IO) {
        val spec = spec(kind)
        locks.getValue(kind).withLock {
            if (isReady(kind)) return@withLock
            if (!root.mkdirs() && !root.isDirectory) throw IOException("Cannot create diagram package directory")
            // A process killed mid-download can leave only disposable staging files.
            root.listFiles()?.filter { it.name.startsWith(".$kind-") }?.forEach { it.deleteRecursively() }
            val stage = File(root, ".$kind-${UUID.randomUUID()}")
            if (!stage.mkdir()) throw IOException("Cannot stage diagram package")
            try {
                val archive = File(stage, ".download.zip")
                download(spec, archive)
                extract(spec, archive, stage)
                archive.delete()
                val lengths = JSONObject().apply { spec.files.forEach { put(it, File(stage, it).length()) } }
                val marker = JSONObject().put("sha256", spec.sha256).put("files", lengths).toString()
                File(stage, ".complete").outputStream().use {
                    it.write(marker.toByteArray(Charsets.UTF_8))
                    it.fd.sync()
                }
                currentCoroutineContext().ensureActive()
                val completed = directory(kind, spec)
                if (completed.exists() && !completed.deleteRecursively()) throw IOException("Cannot replace incomplete diagram package")
                if (!stage.renameTo(completed)) throw IOException("Cannot publish diagram package")
                mutableChanges.update { it + 1 }
                root.listFiles()?.filter { it.isDirectory && it != completed &&
                    it.name.matches(Regex("$kind-[a-f0-9]{64}")) }?.forEach { it.deleteRecursively() }
            } finally {
                stage.deleteRecursively()
            }
        }
    }

    private suspend fun download(spec: DiagramPackageSpec, target: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(spec.url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Diagram package HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty diagram package")
            val length = body.contentLength()
            if (length >= 0 && length != spec.size) throw IOException("Unexpected diagram package size")
            body.byteStream().use { source ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var downloaded = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) {
                            break
                        }
                        downloaded += count
                        if (downloaded > spec.size) throw IOException("Diagram package exceeds size limit")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    if (downloaded != spec.size || digest.digest().hex() != spec.sha256)
                        throw IOException("Diagram package checksum mismatch")
                    output.fd.sync()
                }
            }
        }
    }

    private suspend fun extract(spec: DiagramPackageSpec, archive: File, stage: File) {
        val seen = mutableSetOf<String>()
        var unpacked = 0L
        var entries = 0
        ZipInputStream(archive.inputStream()).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                if (++entries > 4096) throw IOException("Too many diagram package entries")
                val path = entry.name.removeSuffix(if (entry.isDirectory) "/" else "")
                if (!safePath(path) || !seen.add(path)) throw IOException("Invalid diagram package path")
                if (entry.isDirectory) {
                    if (spec.files.none { it.startsWith("$path/") }) throw IOException("Unexpected package directory")
                } else {
                    if (path !in spec.files) throw IOException("Unexpected diagram package file")
                    val target = File(stage, path)
                    if (!target.canonicalPath.startsWith(stage.canonicalPath + File.separator))
                        throw IOException("Invalid diagram package path")
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = zip.read(buffer)
                            if (count < 0) {
                                break
                            }
                            unpacked += count
                            if (unpacked > spec.unpackedSize) throw IOException("Diagram package exceeds unpacked limit")
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                zip.closeEntry()
            }
        }
        if (unpacked != spec.unpackedSize || !spec.files.all { File(stage, it).isFile })
            throw IOException("Incomplete diagram package")
    }

    private fun spec(kind: String) = specs[kind] ?: throw IllegalArgumentException("Unknown diagram kind: $kind")
    private fun directory(kind: String, spec: DiagramPackageSpec) = File(root, "$kind-${spec.sha256}")

    companion object {
        private val downloadClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS).build()
        @Volatile private var instance: DiagramPackages? = null

        fun get(context: Context): DiagramPackages = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                val manifest = app.assets.open("diagrams/packages.json").bufferedReader().use { JSONObject(it.readText()) }
                require(manifest.getInt("schemaVersion") == 1)
                val packages = manifest.getJSONObject("packages")
                val specs = listOf("mermaid", "canvas").associateWith { kind ->
                    val json = packages.getJSONObject(kind)
                    val files = json.getJSONArray("files")
                    DiagramPackageSpec(json.getString("url"), json.getString("sha256"),
                        json.getLong("size"), json.getLong("unpackedSize"),
                        (0 until files.length()).map { files.getString(it) }.toSet()).also {
                        require(it.url.startsWith("https://"))
                    }
                }
                DiagramPackages(File(app.filesDir, "diagram-packages"), specs).also { instance = it }
            }
        }

        private fun safePath(path: String) = path.isNotEmpty() && !path.startsWith('/') &&
            '\\' !in path && ':' !in path && path.none { it.isISOControl() } &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}

internal data class DiagramPackageSpec(
    val url: String,
    val sha256: String,
    val size: Long,
    val unpackedSize: Long,
    val files: Set<String>,
) {
    init {
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(size in 1..128L * 1024 * 1024 && unpackedSize in 1..256L * 1024 * 1024)
        require(files.isNotEmpty() && files.size <= 4096)
        require(files.all { path -> path.isNotEmpty() && '\\' !in path && ':' !in path &&
            path.none { it.isISOControl() } && path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
            path.substringAfterLast('.') in setOf("js", "woff2", "txt") })
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
