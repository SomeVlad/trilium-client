package ru.vlad.triliumnotes.data

import org.junit.Assert.*
import org.junit.Test

class HiddenNotesTest {
    private fun note(id: String, vararg parents: String, title: String = "Hidden Notes") =
        NoteMeta(id, title, "text", "text/html", false, null, null, emptyList(), parents.toList())

    @Test fun systemIdentityAndAncestorPathsDoNotDependOnTitlesOrTypes() {
        val visibility = HiddenNotes(listOf(
            note("root"), note("_hidden", "root"), note("_options", "root"),
            note("hiddenParent", "_hidden"), note("deepHidden", "hiddenParent"),
            note("visibleUser", "root"), note("visibleClone", "hiddenParent", "root"),
            note("unknownUser", "notCachedParent"),
        ))
        assertFalse(visibility.isHidden("root"))
        assertTrue(visibility.isHidden("_hidden"))
        assertTrue(visibility.isHidden("_options"))
        assertTrue(visibility.isHidden("deepHidden"))
        assertFalse(visibility.isHidden("visibleUser"))
        assertFalse(visibility.isHidden("visibleClone"))
        assertFalse(visibility.isHidden("unknownUser"))
    }

    @Test fun oldCacheTreeEdgesIdentifyDescendantsWithoutParentMetadata() {
        val legacy = note("legacyLeaf")
        val visibility = HiddenNotes(listOf(legacy), mapOf(
            "_hidden" to listOf(TreeItem("branchLegacy", legacy, "", 1, false)),
        ))
        assertTrue(visibility.isHidden("legacyLeaf"))
    }
}
