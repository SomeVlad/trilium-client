package ru.vlad.triliumnotes.ui

import ru.vlad.triliumnotes.AppLanguage
import ru.vlad.triliumnotes.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries = remember {
        context.assets.open("licenses/index.txt").bufferedReader().useLines { lines ->
            lines.mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 } }
                .map { it[0] to it[1] }.toList()
        }
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val entry = entries.firstOrNull { it.first == selected }
    fun back() { if (entry == null) onBack() else selected = null }
    BackHandler { back() }
    Scaffold(topBar = { TopAppBar(title = { Text(if (entry == null) AppLanguage.get(context, R.string.licenses) else AppLanguage.get(context, R.string.license)) },
        navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, AppLanguage.get(context, R.string.back)) } }) }) { padding ->
        if (entry == null) {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp)) {
                items(entries, key = { it.first }) { (path, title) ->
                    Text(when (path) {
                        "licenses/NOTICE.txt" -> AppLanguage.get(context, R.string.legal_copyright_terms)
                        "licenses/THIRD_PARTY_NOTICES.md.txt" -> AppLanguage.get(context, R.string.legal_third_party)
                        "editor/vendor/NOTICE.txt" -> AppLanguage.get(context, R.string.legal_editor_notice)
                        "editor/vendor/LICENSE.md" -> AppLanguage.get(context, R.string.legal_editor_components)
                        "editor/vendor/COPYING.GPL" -> AppLanguage.get(context, R.string.legal_editor_gpl)
                        "editor/vendor/THIRD_PARTY_LICENSES.txt" -> AppLanguage.get(context, R.string.legal_editor_dependencies)
                        else -> title
                    }, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().clickable { selected = path }.padding(vertical = 14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        } else {
            val text = remember(entry.first) { context.assets.open(entry.first).bufferedReader().use { it.readText() } }
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp))
            }
        }
    }
}
