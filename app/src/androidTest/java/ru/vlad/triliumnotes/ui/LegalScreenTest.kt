package ru.vlad.triliumnotes.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Only packaged legal content and synthetic navigation; no account or network. */
class LegalScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun projectGplCanBeReadAndBackReturnsThroughTheLegalList() {
        var showLegal by mutableStateOf(true)
        compose.setContent {
            NotesTheme {
                if (showLegal) LegalScreen(onBack = { showLegal = false })
                else Text("Возврат из лицензий")
            }
        }
        compose.onNodeWithText("О приложении").assertIsDisplayed()
        compose.onNodeWithText("GNU GPL 3.0").performScrollTo().performClick()
        compose.onNodeWithText("Лицензия").assertIsDisplayed()
        compose.onNodeWithText("GNU GENERAL PUBLIC LICENSE", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("О приложении").assertIsDisplayed()
        compose.runOnIdle { assertTrue("First back must return to the legal list", showLegal) }
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Возврат из лицензий").assertIsDisplayed()
    }

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
