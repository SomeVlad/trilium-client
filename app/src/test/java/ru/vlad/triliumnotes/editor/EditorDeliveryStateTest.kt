package ru.vlad.triliumnotes.editor

import org.junit.Assert.*
import org.junit.Test

class EditorDeliveryStateTest {
    @Test fun queuedOldEditorEventsCannotOverwriteAttachmentMetadataReplacement() {
        var persistedHtml = "<p>Original</p>"
        var persistedTitle = "Original"
        val oldEditor = EditorDeliveryState(persistedHtml, persistedTitle)
        val queuedBridgeEvent = { oldEditor.htmlChanged("<p>Stale text</p>", 12) { persistedHtml = it } }
        val queuedFlushSnapshot = {
            oldEditor.htmlChanged("<p>Stale snapshot</p>", 13) { persistedHtml = it }
            oldEditor.titleChanged("Stale title", 13) { persistedTitle = it }
        }
        // Metadata mutation replaces the editor epoch after its current buffer was saved.
        oldEditor.dispose()
        persistedHtml = "<p>Renamed attachment</p>"
        persistedTitle = "After rename"
        val replacement = EditorDeliveryState(persistedHtml, persistedTitle)
        queuedBridgeEvent()
        queuedFlushSnapshot()
        assertEquals("<p>Renamed attachment</p>", persistedHtml)
        assertEquals("After rename", persistedTitle)
        replacement.htmlChanged("<p>New editor input</p>", 1) { persistedHtml = it }
        assertEquals("<p>New editor input</p>", persistedHtml)
    }

    @Test fun activeEditorStillRejectsOutOfOrderSnapshotsAndDeliversEachChangedValue() {
        val editor = EditorDeliveryState("Original", "Title")
        val html = mutableListOf<String>()
        val titles = mutableListOf<String>()
        editor.htmlChanged("Current", 8) { html += it }
        editor.htmlChanged("Old snapshot", 7) { html += it }
        editor.htmlChanged("Current", 8) { html += it }
        editor.titleChanged("Current title", 3) { titles += it }
        editor.titleChanged("Old title", 2) { titles += it }
        assertEquals(listOf("Current"), html)
        assertEquals(listOf("Current title"), titles)
    }
}
