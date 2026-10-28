package ru.vlad.triliumnotes.data

import org.jsoup.Jsoup

/** Only note fragments are internal links; external URLs must never become cached backlinks. */
fun linkedNoteIds(content: String, markdown: Boolean = false): Set<String> {
    val source = if (markdown) content.replace(Regex("(?m)^(`{3,}|~{3,})[^\\n]*\\n[\\s\\S]*?^\\1[^\\n]*$"), "")
        .replace(Regex("`+[^`\\n]*`+"), "") else content
    val links = Jsoup.parseBodyFragment(source).select("a[href]").map { it.attr("href") }.toMutableList()
    if (markdown) Regex("!?\\[(?:\\\\.|[^\\]\\n])*]\\(<?([^\\s)>]+)>?(?:\\s+[^)]*)?\\)").findAll(source)
        .forEach { links += it.groupValues[1] }
    return links.mapNotNull { value ->
        if (!value.startsWith("#root/")) null else value.substringAfterLast('/').takeIf {
            it == "root" || it.matches(Regex("[A-Za-z0-9_]{4,32}"))
        }
    }.toSet()
}

/** Call with repository.cachedVisibleDocuments(); protected/hidden/deleted content never enters the index. */
fun noteBacklinks(noteId: String, visibleDocuments: List<NoteDocument>): List<NoteMeta> = visibleDocuments
    .filter { it.note.id != noteId && !it.note.isProtected && !it.remoteMissing &&
        (it.note.type == "text" || it.note.mime.contains("markdown", true)) }
    .filter { noteId in linkedNoteIds(it.html, it.note.mime.contains("markdown", true)) }
    .map { it.note }.distinctBy { it.id }.sortedBy { it.title.lowercase() }

private fun attachmentUrlMatches(url: String, attachmentId: String): Boolean =
    Regex("(?:^|/)(?:api/attachments/(?:[A-Za-z0-9_]{4,32}/)?|etapi/attachments/|attachments/)${Regex.escape(attachmentId)}(?:/|$)")
        .containsMatchIn(url)

private fun escapeMarkup(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;")

private fun replaceReferences(content: String, pattern: Regex, markdown: Boolean, transform: (MatchResult) -> String): String {
    val protected = if (markdown) {
        val fences = Regex("(?m)^ {0,3}(`{3,}|~{3,})[^\\n]*\\n[\\s\\S]*?^ {0,3}\\1[^\\n]*$")
            .findAll(content).map { it.range }.toList()
        fences + Regex("`+[^`\\n]*`+").findAll(content).map { it.range }.toList()
    } else emptyList()
    return pattern.replace(content) { match ->
        if (protected.any { match.range.first <= it.last && it.first <= match.range.last }) match.value else transform(match)
    }
}

/** Conservative deletion guard for references outside supported inline link/image syntax. */
fun containsAttachmentReference(content: String, attachmentId: String, markdown: Boolean): Boolean {
    require(attachmentId.matches(Regex("[A-Za-z0-9_]{4,32}")))
    val source = if (markdown) content.replace(Regex("(?m)^ {0,3}(`{3,}|~{3,})[^\\n]*\\n[\\s\\S]*?^ {0,3}\\1[^\\n]*$"), "")
        .replace(Regex("`+[^`\\n]*`+"), "") else content
    return Regex("(?:api/attachments/(?:[A-Za-z0-9_]{4,32}/)?|etapi/attachments/|attachments/)${Regex.escape(attachmentId)}(?:/|[?\\s\"'<>)]|$)")
        .containsMatchIn(source)
}

/** Source-preserving replacement: attachment URLs/IDs and all unrelated markup stay byte-for-byte intact. */
fun rewriteAttachmentReferences(content: String, attachmentId: String, newTitle: String?, markdown: Boolean): String {
    require(attachmentId.matches(Regex("[A-Za-z0-9_]{4,32}")))
    if (newTitle != null) require(newTitle.isNotBlank() && newTitle.none { it.isISOControl() })
    var result = content
    if (markdown) {
        val links = Regex("!?\\[(?:\\\\.|[^\\]\\n])*]\\(<?([^\\s)>]+)>?(?:\\s+[^)]*)?\\)")
        result = replaceReferences(result, links, true) { match ->
            if (!attachmentUrlMatches(match.groupValues[1], attachmentId)) match.value
            else if (newTitle == null) "" else {
                val title = newTitle.replace(Regex("([\\\\`*_{}\\[\\]()#+.!<>|~-])"), "\\\\$1")
                match.value.substringBefore('[') + "[" + title + "]" + match.value.substring(match.value.indexOf("](") + 1)
            }
        }
    }
    // Images are independent references, including inside links to unrelated external URLs.
    result = replaceReferences(result, Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE), markdown) { match ->
        val image = Jsoup.parseBodyFragment(match.value).selectFirst("img[src]")
        if (image == null || !attachmentUrlMatches(image.attr("src"), attachmentId)) match.value
        else if (newTitle == null) "" else {
            val alt = Regex("\\balt\\s*=\\s*(?:\"[^\"]*\"|'[^']*')", RegexOption.IGNORE_CASE)
            if (alt.containsMatchIn(match.value)) alt.replace(match.value) { "alt=\"${escapeMarkup(newTitle)}\"" }
            else match.value.replace(Regex("/?>$")) { " alt=\"${escapeMarkup(newTitle)}\"${it.value}" }
        }
    }
    return replaceReferences(result, Regex("<a\\b[^>]*>[\\s\\S]*?</a>", RegexOption.IGNORE_CASE), markdown) { match ->
        val link = Jsoup.parseBodyFragment(match.value).selectFirst("a[href]")
        if (link == null || !attachmentUrlMatches(link.attr("href"), attachmentId)) match.value
        else if (newTitle == null) ""
        else if (link.selectFirst("img") != null) match.value
        else match.value.substringBefore('>') + ">" + escapeMarkup(newTitle) + "</a>"
    }
}
