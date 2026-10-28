package ru.vlad.triliumnotes.data

import org.junit.Assert.*
import org.junit.Test

class NoteLinksTest {
    @Test fun backlinksUseOnlyInternalLinksAndIgnoreMarkdownCode() {
        assertEquals(setOf("targetAA"), linkedNoteIds("""
            [Target](#root/parentAA/targetAA)
            [External](https://example.org/#root/otherAAA)
            `[#root/fakeAAAA](#root/fakeAAAA)`
            ```markdown
            [Code](#root/codeAAAA)
            ```
        """.trimIndent(), markdown = true))
    }

    @Test fun backlinkIndexDeduplicatesAndExcludesProtectedAndMissingSources() {
        fun document(id: String, protected: Boolean = false, missing: Boolean = false) = NoteDocument(
            NoteMeta(id, id, "text", "text/html", protected, null, null, emptyList()),
            "<p><a href=\"#root/targetAA\">target</a></p>", remoteMissing = missing)
        assertEquals(listOf("sourceAA"), noteBacklinks("targetAA", listOf(document("sourceAA"), document("sourceAA"),
            document("targetAA"), document("secretAA", protected = true), document("missingA", missing = true))).map { it.id })
    }

    @Test fun renamePreservesAttachmentIdentityAndUnrelatedMarkupExactly() {
        val before = "<p class='kept'>Before <a href='api/attachments/ownerAAA/attachAA/original.pdf'>Old</a> after</p>\n" +
            "<img src=\"api/attachments/ownerAAA/attachAA/a.png\" alt=\"Old\"><a href='#root/otherAAA'>Other</a>"
        val renamed = rewriteAttachmentReferences(before, "attachAA", "New <&>", false)
        assertTrue(renamed.contains("href='api/attachments/ownerAAA/attachAA/original.pdf'>New &lt;&amp;&gt;</a>"))
        assertTrue(renamed.startsWith("<p class='kept'>Before "))
        assertTrue(renamed.endsWith("<a href='#root/otherAAA'>Other</a>"))
        assertTrue(renamed.contains("alt=\"New &lt;&amp;&gt;\""))
    }

    @Test fun markdownRemovalDeletesOnlyMatchingReferences() {
        val before = "# Heading\n\n![Photo](<api/attachments/ownerAAA/attachAA/photo.png>)\n\n" +
            "[File](api/attachments/ownerAAA/otherAAA/file.pdf)\n"
        assertEquals("# Heading\n\n\n\n[File](api/attachments/ownerAAA/otherAAA/file.pdf)\n",
            rewriteAttachmentReferences(before, "attachAA", null, true))
    }

    @Test fun markdownRenameEscapesVisibleTitleWithoutChangingTarget() {
        val before = "before [File](<api/attachments/ownerAAA/attachAA/original.pdf>) after"
        assertEquals("before [a\\]b\\*](<api/attachments/ownerAAA/attachAA/original.pdf>) after",
            rewriteAttachmentReferences(before, "attachAA", "a]b*", true))
    }

    @Test fun attachmentOperationsPreserveMarkdownCodeExamplesAndImageWrappers() {
        val source = "```html\n<a href='api/attachments/ownerAAA/attachAA/original.pdf'>Example</a>\n```\n" +
            "`[example](api/attachments/ownerAAA/attachAA/x.pdf)`\n" +
            "[Live](api/attachments/ownerAAA/attachAA/x.pdf)"
        val changed = rewriteAttachmentReferences(source, "attachAA", null, true)
        assertEquals(source.substringBefore("[Live]"), changed)
        val image = "<a href='api/attachments/ownerAAA/attachAA/x.png'><img src='api/attachments/ownerAAA/attachAA/x.png'></a>"
        val renamed = rewriteAttachmentReferences(image, "attachAA", "Photo", false)
        assertTrue(renamed.startsWith("<a href='api/attachments/ownerAAA/attachAA/x.png'><img"))
        assertTrue(renamed.contains("alt=\"Photo\""))
        assertTrue(renamed.endsWith("</a>"))
    }


    @Test fun nestedImageReferencesAreRemovedAndRenamedInsideExternalLinks() {
        val source = "<p class='kept'>Before<a href='https://external.example/path'><strong>Caption</strong>" +
            "<img src='api/attachments/ownerAAA/attachAA/x.png'></a>After</p>"
        assertEquals("<p class='kept'>Before<a href='https://external.example/path'><strong>Caption</strong></a>After</p>",
            rewriteAttachmentReferences(source, "attachAA", null, false))
        assertEquals(source.replace("x.png'>", "x.png' alt=\"Renamed\">"),
            rewriteAttachmentReferences(source, "attachAA", "Renamed", false))
    }

}
