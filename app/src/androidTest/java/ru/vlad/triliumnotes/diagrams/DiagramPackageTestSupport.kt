package ru.vlad.triliumnotes.diagrams

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Private test storage and an interceptor: renderer tests never contact a real package host. */
internal class DiagramTestPackages(
    files: Map<String, Map<String, ByteArray>>,
    respond: ((Request, ByteArray) -> Response)? = null,
) : Closeable {
    val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "diagram-package-test-${System.nanoTime()}").apply { check(mkdirs()) }
    val requests = CopyOnWriteArrayList<Request>()
    private val archives = files.mapValues { (_, entries) -> zip(entries) }
    private val specs = files.mapValues { (kind, entries) ->
        val bytes = archives.getValue(kind)
        DiagramPackageSpec("https://diagram-package-test.invalid/$kind.zip",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            bytes.size.toLong(), entries.values.sumOf { it.size.toLong() }, entries.keys)
    }
    val packages = DiagramPackages(directory, specs, OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val kind = request.url.pathSegments.last().removeSuffix(".zip")
        val archive = archives.getValue(kind)
        respond?.invoke(request, archive) ?: response(request, 200, archive)
    }.build())

    override fun close() { directory.deleteRecursively() }

    companion object {
        fun response(request: Request, code: Int, bytes: ByteArray = ByteArray(0)): Response = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(code).message(if (code == 200) "OK" else "Unavailable")
            .body(bytes.toResponseBody("application/zip".toMediaType())).build()

        private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                files.toSortedMap().forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
                }
            }
        }.toByteArray()
    }
}

/** The full locked vendor tree is a test-APK fixture; only the small shell ships in the app APK. */
internal fun officialDiagramTestPackages(): DiagramTestPackages {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val manifest = instrumentation.targetContext.assets.open("diagrams/packages.json")
        .bufferedReader().use { JSONObject(it.readText()) }.getJSONObject("packages")
    val files = listOf("mermaid", "canvas").associateWith { kind ->
        val names = manifest.getJSONObject(kind).getJSONArray("files")
        (0 until names.length()).associate { index ->
            val name = names.getString(index)
            name to instrumentation.context.assets.open("diagrams/vendor/$name").use { it.readBytes() }
        }
    }
    return DiagramTestPackages(files).also { fixture ->
        try { runBlocking { files.keys.forEach { fixture.packages.ensure(it) } } }
        catch (error: Throwable) { fixture.close(); throw error }
    }
}
