package ru.vlad.triliumnotes.editor

import org.junit.Assert.*
import org.junit.Test

class EditorResourcePolicyTest {
    @Test fun binaryRoutesPermitTriliumRelativeAndOriginalAbsoluteUrls() {
        for (url in listOf("api/images/note/image.png", "/api/attachments/owner/attachment/image.png",
            "https://notes.example.org/api/images/note/image.png", "https://example.org/image.png")) {
            assertTrue(url, EditorResourcePolicy.isResourceUrl(url))
        }
        for (url in listOf("javascript:alert(1)", "file:///etc/passwd", "http://example.org/image.png",
            "/editor/editor.js", "/api/images/../secret", "https://user:pass@example.org/picture.png")) {
            assertFalse(url, EditorResourcePolicy.isResourceUrl(url))
        }
    }
    @Test fun referencesOnlyYieldValidNoteIds() {
        assertEquals("target", EditorResourcePolicy.noteId("#root/folder/target"))
        assertNull(EditorResourcePolicy.noteId("#target"))
        assertNull(EditorResourcePolicy.noteId("javascript:alert(1)"))
        assertNull(EditorResourcePolicy.noteId("https://example.org"))
        assertNull(EditorResourcePolicy.noteId("#root"))
    }
}
