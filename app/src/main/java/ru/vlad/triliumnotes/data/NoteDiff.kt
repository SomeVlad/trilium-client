package ru.vlad.triliumnotes.data

import org.jsoup.Jsoup

enum class DiffKind { SAME, REMOVED, ADDED }
data class DiffLine(val kind: DiffKind, val text: String)
data class NoteDiff(val lines: List<DiffLine>, val tooLarge: Boolean = false, val formattingOnly: Boolean = false)

/** Compare readable content; bounded memory even for an unusually large note. */
fun compareNoteContent(before: String, after: String, mime: String): NoteDiff {
    if (before.length + after.length > 1_000_000) return NoteDiff(emptyList(), tooLarge = true)
    fun readable(value: String): String {
        if (mime.substringBefore(';').trim() != "text/html") return value.replace("\r\n", "\n")
        val body = Jsoup.parseBodyFragment(value).body()
        body.select("script,style").remove()
        body.select("br").after("\n")
        body.select("p,div,li,h1,h2,h3,h4,h5,h6,tr,pre,blockquote").forEach { it.appendText("\n") }
        body.select("img").forEach { it.after("[${it.attr("alt").ifBlank { it.attr("src").substringAfterLast('/') }}]") }
        return body.wholeText().replace("\r\n", "\n").trimEnd()
    }
    val old = readable(before)
    val current = readable(after)
    if (old == current) return NoteDiff(old.lines().map { DiffLine(DiffKind.SAME, it) }, formattingOnly = before != after)
    val left = old.lines()
    val right = current.lines()
    if (left.size.toLong() * right.size > 2_000_000) return NoteDiff(emptyList(), tooLarge = true)
    // One decision byte per cell and two score rows, instead of an integer matrix.
    val choices = ByteArray(left.size * right.size)
    var next = IntArray(right.size + 1)
    var row = IntArray(right.size + 1)
    for (i in left.lastIndex downTo 0) {
        for (j in right.lastIndex downTo 0) {
            when {
                left[i] == right[j] -> { row[j] = next[j + 1] + 1; choices[i * right.size + j] = 0 }
                next[j] >= row[j + 1] -> { row[j] = next[j]; choices[i * right.size + j] = 1 }
                else -> { row[j] = row[j + 1]; choices[i * right.size + j] = 2 }
            }
        }
        val swap = next; next = row; row = swap; row[right.size] = 0
    }
    val result = mutableListOf<DiffLine>()
    var i = 0; var j = 0
    while (i < left.size || j < right.size) {
        when {
            i == left.size -> result += DiffLine(DiffKind.ADDED, right[j++])
            j == right.size -> result += DiffLine(DiffKind.REMOVED, left[i++])
            choices[i * right.size + j].toInt() == 0 -> { result += DiffLine(DiffKind.SAME, left[i++]); j++ }
            choices[i * right.size + j].toInt() == 1 -> result += DiffLine(DiffKind.REMOVED, left[i++])
            else -> result += DiffLine(DiffKind.ADDED, right[j++])
        }
    }
    return NoteDiff(result)
}
