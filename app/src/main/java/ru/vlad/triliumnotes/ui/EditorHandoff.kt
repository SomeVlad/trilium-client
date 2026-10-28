package ru.vlad.triliumnotes.ui

/** Flush the mounted editor before an incoming share or widget request changes notes. */
internal class EditorHandoff {
    var finish: ((() -> Unit) -> Unit)? = null
    fun run(after: () -> Unit) {
        val current = finish
        if (current == null) after() else current(after)
    }
}
