package ru.vlad.triliumnotes.capture

import android.content.Intent
import android.os.Bundle
import java.util.UUID

/** A launch is consumed before editing starts; an explicit null is also saved after consumption. */
internal object QuickNoteRequest {
    private const val STATE = "quick-note-request"
    fun restore(intent: Intent, state: Bundle?): String? =
        if (state != null) state.getString(STATE) else fromIntent(intent)
    fun fromIntent(intent: Intent): String? =
        if (intent.action == QuickNoteWidget.ACTION_QUICK_NOTE) UUID.randomUUID().toString() else null
    fun save(state: Bundle, request: String?) { state.putString(STATE, request) }
}
