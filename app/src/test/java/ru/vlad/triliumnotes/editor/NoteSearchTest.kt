package ru.vlad.triliumnotes.editor

import org.junit.Assert.*
import org.junit.Test

class NoteSearchTest {
    @Test fun literalSearchKeepsOffsetsAcrossUnicodeAndLineBreaks() {
        val source = "🙂 До\nПОСЛЕ и после. [.*]"
        assertEquals(listOf(6..10, 14..18), noteSearchMatches(source, "после"))
        assertEquals(listOf(21..24), noteSearchMatches(source, "[.*]"))
        assertEquals(listOf(3..6), noteSearchMatches(source, "До\nП"))
        assertTrue(noteSearchMatches(source, "").isEmpty())
        assertTrue(noteSearchMatches(source, "missing").isEmpty())
        assertEquals(listOf(0..1, 2..3), noteSearchMatches("aaaaa", "aa"))
    }

    @Test fun sourceNavigationWrapsAndClearingResultsDisablesNavigation() {
        val state = NoteSearchState()
        state.updateResults(3)
        assertEquals(0, state.currentMatch)
        state.previous()
        assertEquals(2, state.currentMatch)
        state.next()
        assertEquals(0, state.currentMatch)
        state.updateResults(0)
        state.previous()
        assertEquals(-1, state.currentMatch)
    }
}
