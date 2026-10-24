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

    @Test fun configuredServerSubpathOnlyPermitsTheSameResourceRoutes() {
        val base = "https://notes.example.org/notes"
        for (path in listOf("/notes/assets/v0.106.0/doc_notes/en/images/hello%20world.png",
            "/notes/api/images/noteAAAA/image.png", "/notes/api/attachments/ownerAAAA/attachAAAA/image.png")) {
            assertTrue(path, EditorResourcePolicy.isResourceUrl(path, base))
            assertFalse("A subpath must be configured explicitly", EditorResourcePolicy.isResourceUrl(path))
        }
        for (path in listOf("/other/assets/v0.106.0/doc_notes/image.png", "/notes/editor/editor.js",
            "/notes/api/images/%2e%2e/secret.png", "/notes/api/images/noteAAAA/%5csecret.png")) {
            assertFalse(path, EditorResourcePolicy.isResourceUrl(path, base))
        }
    }
}
