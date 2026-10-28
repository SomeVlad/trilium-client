package ru.vlad.triliumnotes

import android.content.Context
import android.content.Intent
import ru.vlad.triliumnotes.capture.CaptureInbox
import ru.vlad.triliumnotes.capture.QuickNoteWidget
import ru.vlad.triliumnotes.capture.QuickNoteRequest
import androidx.compose.ui.platform.LocalContext
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.vlad.triliumnotes.ui.ConnectionScreen
import ru.vlad.triliumnotes.ui.ConnectionSettingsScreen
import ru.vlad.triliumnotes.ui.NotesApp
import ru.vlad.triliumnotes.ui.NotesTheme

class MainActivity : ComponentActivity() {
    private val connection: ConnectionViewModel by viewModels()
    private var model: NotesViewModel? = null
    private val captureInbox by lazy { CaptureInbox(applicationContext) }
    private var quickNoteRequest by mutableStateOf<String?>(null)
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var callbackRegistered = false
    private val connectionState = ConnectionState()
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            runOnUiThread {
                if (!callbackRegistered || isDestroyed) return@runOnUiThread
                val available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (connectionState.update(network.networkHandle, available)) model?.connectionAvailable()
                else if (!hasValidatedInternet()) model?.connectionUnavailable()
            }
        }
        override fun onLost(network: Network) {
            runOnUiThread {
                if (callbackRegistered && !isDestroyed && connectionState.lost(network.networkHandle) && !hasValidatedInternet())
                    model?.connectionUnavailable()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.context(newBase))
    }

    private fun selectLanguage(language: String) {
        val previous = AppLanguage.language(this)
        // Persist even when the automatic default already matches the explicit choice.
        AppLanguage.select(this, language)
        connection.refreshLanguage()
        QuickNoteWidget.updateAll(this)
        if (previous != language) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        quickNoteRequest = QuickNoteRequest.restore(intent, savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            NotesTheme {
                val state by connection.state.collectAsStateWithLifecycle()
                val services = state.services
                when {
                    state.restoring -> Surface { Box(Modifier.fillMaxSize()) }
                    services == null -> ConnectionScreen(state.busy, state.error, state.storageFailed,
                        onResetStorage = connection::disconnect, onConnect = connection::connect,
                        onLanguageSelected = ::selectLanguage)
                    else -> key(services.sessionId ?: services.serverUrl) {
                        val notes = remember { connection.notesModel(services) }
                        var settings by rememberSaveable { mutableStateOf(false) }
                        val sync by notes.syncState.collectAsStateWithLifecycle()
                        val uploads by notes.uploadState.collectAsStateWithLifecycle()
                        val attachmentEdits by services.attachments.state.collectAsStateWithLifecycle()
                        DisposableEffect(notes) {
                            model = notes
                            notes.appStarted()
                            onDispose { if (model === notes) model = null }
                        }
                        if (settings) ConnectionSettingsScreen(services.serverUrl, sync.pendingCount + uploads.pendingCount + attachmentEdits.pendingCount,
                            state.busy, state.error, onBack = { settings = false }, onDisconnect = connection::disconnect,
                            onLanguageSelected = ::selectLanguage)
                        else NotesApp(notes, onConnectionSettings = { settings = true },
                            captureInbox = captureInbox, quickNoteRequest = quickNoteRequest,
                            onQuickNoteHandled = { quickNoteRequest = null })
                    }
                }
                state.legacySession?.let { session ->
                    AlertDialog(onDismissRequest = {}, title = { Text(AppLanguage.get(context, R.string.legacy_title)) },
                        text = { Text(AppLanguage.get(context, R.string.legacy_message, session.serverUrl) +
                            (state.legacyPendingCount?.let { "\n\n" + AppLanguage.get(context, R.string.legacy_pending, it) } ?: "") +
                            (state.error?.let { "\n\n$it" } ?: "")) },
                        confirmButton = { TextButton(onClick = { connection.finishLegacyImport(true) }, enabled = !state.busy) {
                            Text(AppLanguage.get(context, R.string.legacy_confirm))
                        } },
                        dismissButton = { TextButton(onClick = { connection.finishLegacyImport(false) }, enabled = !state.busy) {
                            Text(AppLanguage.get(context, R.string.legacy_skip))
                        } })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureInbox.refresh()
        QuickNoteRequest.fromIntent(intent)?.let { quickNoteRequest = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        QuickNoteRequest.save(outState, quickNoteRequest)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        captureInbox.refresh()
        if (!callbackRegistered) {
            callbackRegistered = true
            connectivity.registerDefaultNetworkCallback(networkCallback)
        }
        model?.appStarted()
    }

    override fun onStop() {
        model?.syncNow()
        super.onStop()
    }

    override fun onDestroy() {
        // Keep observing real network changes while this activity is stopped.
        // Re-registering on every resume invents a reconnection to the same network.
        if (callbackRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        callbackRegistered = false
        super.onDestroy()
    }

}
