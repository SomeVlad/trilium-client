package ru.vlad.triliumnotes.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import org.json.JSONArray
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R

/** Code retains its source; Markdown uses the visual editor with Markdown input and output. */
@Composable
fun SourceNoteEditor(
    noteKey: String,
    title: String,
    initialContent: String,
    mime: String = "text/plain",
    readOnly: Boolean = false,
    isEditing: Boolean = true,
    modifier: Modifier = Modifier,
    onTitleChanged: (String) -> Unit = {},
    onContentChanged: (String) -> Unit,
    controller: RichNoteEditorController? = null,
    onOpenNote: (String) -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    resourceLoader: (String) -> EditorResource? = { null },
    serverUrl: String = "",
    searchState: NoteSearchState? = null,
) {
    val context = LocalContext.current
    val titleDescription = AppLanguage.get(context, R.string.editor_note_title)
    val sourceDescription = AppLanguage.get(context, R.string.editor_source_text)
    key(noteKey) {
        var content by remember { mutableStateOf(initialContent) }
        var field by remember { mutableStateOf(TextFieldValue(initialContent)) }
        var localTitle by remember { mutableStateOf(title) }
        val editable = isEditing && !readOnly
        if (mime.contains("markdown", ignoreCase = true)) {
            Box(modifier.testTag(if (editable) "note-source-editor" else "note-source-reader")) {
                RichNoteEditor(noteKey = "$noteKey-markdown-preview", title = localTitle, initialHtml = content,
                    initialMarkdown = content, isEditing = isEditing, readOnly = readOnly, controller = controller,
                    modifier = Modifier.fillMaxSize(), onTitleChanged = { localTitle = it; onTitleChanged(it) },
                    onHtmlChanged = { content = it; onContentChanged(it) },
                    onOpenNote = onOpenNote, onOpenLink = onOpenLink, resourceLoader = resourceLoader, serverUrl = serverUrl,
                    searchState = searchState)
            }
        } else {
            var savedSelection by remember { mutableStateOf<TextRange?>(null) }
            DisposableEffect(controller, editable) {
                val command: (String, (Boolean) -> Unit) -> Unit = command@{ script, complete ->
                    if (!editable) { complete(false); return@command }
                    val name = script.substringBefore('(')
                    val args = JSONArray("[" + script.substringAfter('(').removeSuffix(")") + "]")
                    if (name == "saveSelection") { savedSelection = field.selection; complete(true); return@command }
                    fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
                    val insertion = when (name) {
                        "insertNoteLink" -> "<a href=\"#root/${args.getString(0)}\">${escape(args.getString(1))}</a>"
                        "reserveAttachment" -> "<a href=\"#pocket-upload-${args.getString(0)}\">${escape(args.getString(1))}</a>"
                        else -> null
                    }
                    if (name == "cancelAttachment") {
                        val target = "#pocket-upload-${args.getString(0)}"
                        val changed = Regex("<a\\b(?=[^>]*href=[\"']$target[\"'])[^>]*>[\\s\\S]*?</a>").replace(field.text, "")
                        field = TextFieldValue(changed, TextRange(changed.length)); content = changed
                    } else if (insertion != null) {
                        val selected = savedSelection ?: field.selection
                        val start = selected.min.coerceIn(0, field.text.length)
                        val end = selected.max.coerceIn(start, field.text.length)
                        val changed = field.text.replaceRange(start, end, insertion)
                        field = TextFieldValue(changed, TextRange(start + insertion.length)); content = changed
                    } else { complete(false); return@command }
                    savedSelection = null
                    onContentChanged(content)
                    complete(true)
                }
                controller?.command = command
                onDispose { controller?.let { if (it.command === command) it.command = null } }
            }
            val style = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onBackground, fontFamily = FontFamily.Monospace,
                fontSize = 15.sp, lineHeight = 24.sp,
            )
            val scroll = rememberScrollState()
            val bringMatchIntoView = remember { BringIntoViewRequester() }
            val matches = remember(content, searchState?.query) { noteSearchMatches(content, searchState?.query.orEmpty()) }
            var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
            LaunchedEffect(searchState, matches) { searchState?.updateResults(matches.size) }
            val activeMatch = searchState?.currentMatch ?: -1
            val searchColors = MaterialTheme.colorScheme
            val highlighted = remember(content, matches, activeMatch, searchColors) {
                buildAnnotatedString {
                    append(content)
                    matches.forEachIndexed { index, range ->
                        addStyle(if (index == activeMatch) SpanStyle(background = searchColors.primary, color = searchColors.onPrimary)
                            else SpanStyle(background = searchColors.tertiaryContainer, color = searchColors.onTertiaryContainer),
                            range.first, range.last + 1)
                    }
                }
            }
            LaunchedEffect(activeMatch, matches, textLayout) {
                val range = matches.getOrNull(activeMatch) ?: return@LaunchedEffect
                val layout = textLayout ?: return@LaunchedEffect
                if (range.first >= layout.layoutInput.text.length) return@LaunchedEffect
                bringMatchIntoView.bringIntoView(layout.getBoundingBox(range.first))
            }
            Column(modifier.verticalScroll(scroll).testTag(if (editable) "note-source-editor" else "note-source-reader")
                .padding(horizontal = 22.dp, vertical = 16.dp)) {
                BasicTextField(
                    value = localTitle, onValueChange = { localTitle = it; onTitleChanged(it) },
                    readOnly = !editable, textStyle = MaterialTheme.typography.headlineLarge.copy(color = style.color),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp).testTag("note-title")
                        .semantics { contentDescription = titleDescription },
                )
                if (editable) BasicTextField(
                    value = field, onValueChange = { field = it; content = it.text; onContentChanged(it.text) },
                    textStyle = style, cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().testTag("source-editor")
                        .semantics { contentDescription = sourceDescription },
                ) else SelectionContainer {
                    Text(highlighted, style = style, onTextLayout = { textLayout = it },
                        modifier = Modifier.fillMaxWidth().testTag("source-reader")
                            .bringIntoViewRequester(bringMatchIntoView))
                }
            }
        }
    }
}
