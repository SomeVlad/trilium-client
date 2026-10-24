package ru.vlad.triliumnotes.ui

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Checks the legal files shipped in the APK without opening any UI or account. */
class PackagedLegalAssetsTest {
    @Test fun everyLegalIndexEntryHasANonemptyPackagedAssetIncludingDependencyNotices() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val lines = assets.open("licenses/index.txt").bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
        assertTrue("Legal index must contain entries", lines.isNotEmpty())
        val entries = lines.map { line ->
            val fields = line.split('\t', limit = 2)
            assertEquals("Malformed legal index entry: $line", 2, fields.size)
            assertTrue("Missing title for ${fields[0]}", fields[1].isNotBlank())
            fields[0] to fields[1]
        }
        val paths = entries.map { it.first }
        assertEquals("Legal asset paths must be unique", paths.size, paths.toSet().size)
        val required = setOf("licenses/LICENSE.txt", "licenses/NOTICE.txt", "licenses/THIRD_PARTY_NOTICES.md.txt",
            "editor/vendor/NOTICE.txt", "editor/vendor/LICENSE.md", "editor/vendor/COPYING.GPL",
            "editor/vendor/THIRD_PARTY_LICENSES.txt")
        assertTrue("Project and editor license assets must be indexed", paths.containsAll(required))
        assertTrue("Resolved runtime dependency notices must be indexed",
            entries.any { (path, title) -> path.startsWith("licenses/") && path !in required && title.count { it == ':' } >= 2 })
        for (path in paths) {
            assertFalse("Asset path must be relative: $path", path.startsWith('/'))
            assertFalse("Asset path must not traverse: $path", path.split('/').any { it == ".." || it == "." })
            assets.open(path).use { input -> assertNotEquals("Packaged legal asset is empty: $path", -1, input.read()) }
        }
    }
}
