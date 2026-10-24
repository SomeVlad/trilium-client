package ru.vlad.triliumnotes.ui

import android.content.Intent
import android.net.Uri
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
import ru.vlad.triliumnotes.BuildConfig

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
    Scaffold(topBar = { TopAppBar(title = { Text(if (entry == null) "О приложении" else "Лицензия") },
        navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") } }) }) { padding ->
        if (entry == null) {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp)) {
                item {
                    Text("Trilium Client ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Text("Свободное приложение для Trilium.\n© 2026 участники проекта trilium-client.\nGNU GPL 3.0 или более поздняя версия. Программа предоставляется без гарантий.",
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/SomeVlad/trilium-client"))) }
                    }) { Text("Исходный код") }
                    Spacer(Modifier.height(12.dp))
                    Text("Лицензии и уведомления", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                }
                items(entries, key = { it.first }) { (path, title) ->
                    Text(title, style = MaterialTheme.typography.bodyMedium,
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
