package ru.vlad.triliumnotes.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttachmentPlaceholderTest {
    private val anchor = "01234567-89ab-cdef-0123-456789abcdef"
    private val upload = PendingAttachmentUpload(anchor, "ownerAAA", "photo.png", "image/png", true,
        "attachAA", true, anchor)
    private val attachment = AttachmentMeta("attachAA", "ownerAAA", "image", "image/png", "photo.png", 0)

    @Test fun htmlInsertionStaysInsideOriginalParagraphAtReservedCursor() {
        val html = "<p class='original'><strong>Before</strong><a href=\"#pocket-upload-$anchor\">Pending</a> after</p>"
        assertEquals("<p class='original'><strong>Before</strong><img src=\"api/attachments/ownerAAA/attachAA/photo.png\" alt=\"photo.png\"> after</p>",
            replaceAttachmentPlaceholder(html, upload, attachment, false))
        assertNull(replaceAttachmentPlaceholder("<p>No placeholder</p>", upload, attachment, false))
    }

    @Test fun markdownInsertionPreservesSourceAndEscapedPlaceholderLabel() {
        val markdown = "# Title\n\n**before** [Pending\\]](#pocket-upload-$anchor) after\n"
        assertEquals("# Title\n\n**before** ![photo\\.png](<api/attachments/ownerAAA/attachAA/photo.png>) after\n",
            replaceAttachmentPlaceholder(markdown, upload, attachment, true))
    }

    @Test fun recoveryKeepsOnlyActivePickerAndQueuedUploadReservations() {
        val html = "<p>a<a href='#pocket-upload-$anchor'>Pending</a>b</p>"
        assertEquals(html, clearAbandonedAttachmentPlaceholders(html, setOf(anchor), false))
        assertEquals("<p>ab</p>", clearAbandonedAttachmentPlaceholders(html, emptySet(), false))
    }
}
