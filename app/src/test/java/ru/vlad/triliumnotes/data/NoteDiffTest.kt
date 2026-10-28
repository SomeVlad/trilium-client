package ru.vlad.triliumnotes.data

import org.junit.Assert.*
import org.junit.Test

class NoteDiffTest {
    @Test fun htmlParagraphsAndBreaksRemainReadableSeparateLines() {
        val before = "<p>Heading<br>Old second</p><p>Same footer</p>"
        val after = "<p>Heading<br>New second</p><p>Same footer</p>"
        val result = compareNoteContent(before, after, "text/html; charset=UTF-8")
        assertFalse(result.tooLarge)
        assertEquals(listOf("Old second"), result.lines.filter { it.kind == DiffKind.REMOVED }.map { it.text })
        assertEquals(listOf("New second"), result.lines.filter { it.kind == DiffKind.ADDED }.map { it.text })
        assertTrue(result.lines.any { it.kind == DiffKind.SAME && it.text == "Heading" })
        assertTrue(result.lines.any { it.kind == DiffKind.SAME && it.text == "Same footer" })
    }

    @Test fun repeatedLinesPreserveBothVersionSequencesAcrossAdditionAndRemoval() {
        val before = "Start\nRepeated\nRemove this\nRepeated\nEnd"
        val after = "Start\nRepeated\nRepeated\nAdded line\nEnd"
        val result = compareNoteContent(before, after, "text/plain")
        assertEquals(listOf("Remove this"), result.lines.filter { it.kind == DiffKind.REMOVED }.map { it.text })
        assertEquals(listOf("Added line"), result.lines.filter { it.kind == DiffKind.ADDED }.map { it.text })
        assertEquals(before, result.lines.filter { it.kind != DiffKind.ADDED }.joinToString("\n") { it.text })
        assertEquals(after, result.lines.filter { it.kind != DiffKind.REMOVED }.joinToString("\n") { it.text })
        assertEquals(2, result.lines.count { it.kind == DiffKind.SAME && it.text == "Repeated" })
    }

    @Test fun formattingOnlyChangesNeverClaimThatWordsWereAddedOrRemoved() {
        val result = compareNoteContent("<p>Keep <strong>these words</strong></p>",
            "<p>Keep <em>these words</em></p>", "text/html")
        assertTrue(result.formattingOnly)
        assertTrue(result.lines.all { it.kind == DiffKind.SAME })
        assertEquals("Keep these words", result.lines.joinToString("\n") { it.text })
    }

    @Test fun identicalContentDoesNotClaimAFormattingChange() {
        val result = compareNoteContent("Unchanged\r\nContent", "Unchanged\r\nContent", "text/plain")
        assertFalse(result.formattingOnly)
        assertEquals(listOf(DiffLine(DiffKind.SAME, "Unchanged"), DiffLine(DiffKind.SAME, "Content")), result.lines)
    }

    @Test fun oversizedContentStopsBeforeAllocatingTheLineComparisonMatrix() {
        val result = compareNoteContent("a".repeat(500_001), "b".repeat(500_001), "text/plain")
        assertTrue(result.tooLarge)
        assertTrue(result.lines.isEmpty())
    }

    @Test fun tooManyShortLinesAreBoundedIndependentlyOfCharacterCount() {
        val before = (1..1500).joinToString("\n") { "old$it" }
        val after = (1..1500).joinToString("\n") { "new$it" }
        val result = compareNoteContent(before, after, "text/plain")
        assertTrue(result.tooLarge)
        assertTrue(result.lines.isEmpty())
    }
}
