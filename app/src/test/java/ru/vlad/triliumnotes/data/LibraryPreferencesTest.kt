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

@RunWith(RobolectricTestRunner::class)
class LibraryPreferencesTest {
    private lateinit var directory: File
    private lateinit var context: Context

    @Before fun start() {
        directory = Files.createTempDirectory("library-preferences").toFile()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = directory
        }
    }

    @After fun stop() { directory.deleteRecursively() }

    @Test fun pinsPersistAcrossRecreationAndRemainIndependentFromFavorites() = runBlocking {
        val preferences = LibraryPreferences(context)
        preferences.toggleFavorite("favorite")
        preferences.togglePinned("pin")
        preferences.togglePinned("favorite")
        preferences.opened("recent")
        preferences.sort("folder", NoteSort.MODIFIED)

        val recreated = LibraryPreferences(context)
        assertEquals(preferences.state.value, recreated.state.value)
        assertEquals(setOf("pin", "favorite"), recreated.state.value.pinned)
        recreated.toggleFavorite("favorite")
        assertEquals(setOf("pin", "favorite"), recreated.state.value.pinned)
        recreated.togglePinned("favorite")
        recreated.togglePinned("pin")

        val unpinned = LibraryPreferences(context).state.value
        assertTrue(unpinned.pinned.isEmpty())
        assertTrue(unpinned.favorites.isEmpty())
        assertEquals(listOf("recent"), unpinned.recent)
        assertEquals(mapOf("folder" to NoteSort.MODIFIED), unpinned.sorts)
    }

    @Test fun legacyPreferencesKeepFavoritesWithoutAutomaticallyPinningThem() = runBlocking {
        File(directory, "library-preferences.json").writeText(
            """{"favorites":["old-favorite"],"recent":["old-recent"],"sorts":{"folder":"TITLE"}}"""
        )
        val preferences = LibraryPreferences(context)
        assertEquals(setOf("old-favorite"), preferences.state.value.favorites)
        assertTrue(preferences.state.value.pinned.isEmpty())
        assertEquals(listOf("old-recent"), preferences.state.value.recent)
        assertEquals(mapOf("folder" to NoteSort.TITLE), preferences.state.value.sorts)

        preferences.togglePinned("new-pin")
        val recreated = LibraryPreferences(context).state.value
        assertEquals(setOf("old-favorite"), recreated.favorites)
        assertEquals(setOf("new-pin"), recreated.pinned)
    }

    @Test fun pinsUseTheExistingAccountNamespace() = runBlocking {
        val first = accountContext(context, "a".repeat(64))
        val second = accountContext(context, "b".repeat(64))
        LibraryPreferences(first).togglePinned("same-note-id")
        assertTrue(LibraryPreferences(second).state.value.pinned.isEmpty())
        LibraryPreferences(second).togglePinned("second-note")
        assertEquals(setOf("same-note-id"), LibraryPreferences(first).state.value.pinned)
        assertEquals(setOf("second-note"), LibraryPreferences(second).state.value.pinned)
        assertFalse(File(directory, "library-preferences.json").exists())
    }

    @Test fun pinsComeFirstWithTheSelectedOrderPreservedInsideBothGroups() = runBlocking {
        val rows = listOf(
            row("b", "Beta", "2026-10-28"),
            row("a", "Alpha", "2026-10-26"),
            row("d", "Delta", "2026-10-29"),
            row("c", "Charlie", "2026-10-27"),
        )
        val ordinary = mapOf(
            NoteSort.MANUAL to listOf("b", "a", "d", "c"),
            NoteSort.TITLE to listOf("a", "b", "c", "d"),
            NoteSort.MODIFIED to listOf("d", "b", "c", "a"),
        )
        val pinned = mapOf(
            NoteSort.MANUAL to listOf("d", "c", "b", "a"),
            NoteSort.TITLE to listOf("c", "d", "a", "b"),
            NoteSort.MODIFIED to listOf("d", "c", "b", "a"),
        )
        val preferences = LibraryPreferences(context)
        for (sort in NoteSort.entries) {
            assertEquals(ordinary.getValue(sort), sortTreeItems(rows, sort).map { it.note.id })
            preferences.togglePinned("c")
            preferences.togglePinned("d")
            assertEquals(pinned.getValue(sort), sortTreeItems(rows, sort, preferences.state.value.pinned).map { it.note.id })
            preferences.togglePinned("c")
            preferences.togglePinned("d")
            assertEquals(ordinary.getValue(sort), sortTreeItems(rows, sort, preferences.state.value.pinned).map { it.note.id })
        }
        assertEquals(listOf("b", "a", "d", "c"), rows.map { it.note.id })
    }

    @Test fun pinUsesNoteIdentityWithoutAddingMissingNotesOrChangingBranches() {
        val first = row("shared", "Shared", "2026-10-29").copy(branchId = "branch-one", prefix = "First")
        val second = first.copy(branchId = "branch-two", prefix = "Second")
        val other = row("other", "Other", "2026-10-28")
        val rows = listOf(other, first, second)

        for (sort in NoteSort.entries) {
            val sorted = sortTreeItems(rows, sort, setOf("shared", "absent"))
            assertEquals(setOf(first, second), sorted.take(2).toSet())
            assertEquals(other, sorted.last())
            assertEquals(rows.toSet(), sorted.toSet())
            assertEquals(rows.size, sorted.size)
            assertEquals(listOf(other), sortTreeItems(listOf(other), sort, setOf("shared")))
        }
    }

    @Test fun manualReorderPreservesOriginalGroupSlotsSoNoOpAndUnpinKeepTheirPositions() {
        val rows = listOf(
            row("a", "Alpha", "2026-10-29"),
            row("b", "Beta", "2026-10-28"),
            row("c", "Charlie", "2026-10-27"),
            row("d", "Delta", "2026-10-26"),
        )
        val pins = setOf("b", "d")
        val displayed = sortTreeItems(rows, NoteSort.MANUAL, pins)
        assertEquals(listOf("b", "d", "a", "c"), displayed.map { it.note.id })
        assertEquals(rows.map { it.branchId }, serializeReorderedGroups(rows, displayed, pins))

        val moved = listOf(rows[3], rows[1], rows[2], rows[0])
        val stored = serializeReorderedGroups(rows, moved, pins)
        assertEquals(listOf("branch-c", "branch-d", "branch-a", "branch-b"), stored)
        val byBranch = rows.associateBy { it.branchId }
        val savedRows = stored.map { byBranch.getValue(it) }
        assertEquals(moved, sortTreeItems(savedRows, NoteSort.MANUAL, pins))
        assertEquals(savedRows, sortTreeItems(savedRows, NoteSort.MANUAL, emptySet()))
        assertEquals(rows.map { it.branchId }, serializeReorderedGroups(rows, rows, emptySet()))
    }

    private fun row(id: String, title: String, modified: String) = TreeItem(
        "branch-$id", NoteMeta(id, title, "text", "text/html", false, null, modified, emptyList()), "", 0, false,
    )
}
