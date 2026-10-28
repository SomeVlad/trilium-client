package ru.vlad.triliumnotes.capture

import android.content.Intent
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QuickNoteRequestTest {
    private fun tap() = Intent(QuickNoteWidget.ACTION_QUICK_NOTE)
    @Test fun unconsumedTapRetainsIdentityAcrossActivityAndProcessRecreation() {
        val request = QuickNoteRequest.restore(tap(), null)
        val state = Bundle().also { QuickNoteRequest.save(it, request) }
        assertNotNull(request)
        assertEquals(request, QuickNoteRequest.restore(tap(), state))
        assertEquals(request, QuickNoteRequest.restore(Intent(Intent.ACTION_MAIN), state))
    }
    @Test fun consumedTapDoesNotReappearWhenActivityIsRecreated() {
        val state = Bundle().also { QuickNoteRequest.save(it, null) }
        assertNull(QuickNoteRequest.restore(tap(), state))
    }
    @Test fun eachNewTapHasItsOwnIdentityAndExternalExtrasCannotChooseIt() {
        val first = QuickNoteRequest.fromIntent(tap().putExtra("quick-note-request", "untrusted"))
        val second = QuickNoteRequest.fromIntent(tap())
        assertNotEquals("untrusted", first)
        assertNotEquals(first, second)
        assertNull(QuickNoteRequest.fromIntent(Intent(Intent.ACTION_MAIN)))
        assertNull(QuickNoteRequest.fromIntent(Intent(Intent.ACTION_SEND)))
    }
}
