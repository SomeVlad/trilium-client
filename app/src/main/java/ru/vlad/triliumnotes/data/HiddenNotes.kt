package ru.vlad.triliumnotes.data

import java.io.IOException

class HiddenNoteException : IOException("Системные заметки скрыты")

/** Trilium reserves underscore IDs for its deterministic system tree (hidden_subtree.ts).
 * Ordinary clones remain visible when any known path leaves that tree. Missing ordinary
 * ancestors are not evidence of hiding; this also preserves legacy offline search caches.
 */
internal class HiddenNotes(
    notes: Collection<NoteMeta>,
    children: Map<String, List<TreeItem>> = emptyMap(),
) {
    private val parents = mutableMapOf<String, Set<String>>()
    private val memo = mutableMapOf<String, Boolean>()

    init {
        notes.forEach { parents[it.id] = it.parentNoteIds.toSet() }
        // Legacy caches can know the tree edge without parentNoteIds on the note itself.
        val edges = mutableMapOf<String, MutableSet<String>>()
        children.forEach { (parent, items) -> items.forEach { edges.getOrPut(it.note.id) { mutableSetOf() } += parent } }
        edges.forEach { (id, values) -> if (parents[id].isNullOrEmpty()) parents[id] = values }
    }

    fun isHidden(id: String): Boolean = hidden(id, mutableSetOf())

    private fun hidden(id: String, visiting: MutableSet<String>): Boolean {
        if (id == "root") return false
        if (id.startsWith('_')) return true
        memo[id]?.let { return it }
        if (!visiting.add(id)) return false
        val knownParents = parents[id]
        val result = !knownParents.isNullOrEmpty() && knownParents.all { hidden(it, visiting) }
        visiting.remove(id)
        memo[id] = result
        return result
    }
}
