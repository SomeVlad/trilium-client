package ru.vlad.triliumnotes

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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.vlad.triliumnotes.ui.ConnectionScreen
import ru.vlad.triliumnotes.ui.ConnectionSettingsScreen
import ru.vlad.triliumnotes.ui.LegalScreen
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.vlad.triliumnotes.ui.NotesApp
import ru.vlad.triliumnotes.ui.NotesTheme

class MainActivity : ComponentActivity() {
    private val connection: ConnectionViewModel by viewModels()
    private var model: NotesViewModel? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NotesTheme {
                val state by connection.state.collectAsStateWithLifecycle()
                val services = state.services
                var about by rememberSaveable { mutableStateOf(false) }
                when {
                    state.restoring -> Surface { Box(Modifier.fillMaxSize()) }
                    services == null -> ConnectionScreen(state.busy, state.error, state.storageFailed,
                        onResetStorage = connection::disconnect, onConnect = connection::connect,
                        onAbout = { about = true })
                    else -> key(services) {
                        val notes = remember { connection.notesModel(services) }
                        var settings by rememberSaveable { mutableStateOf(false) }
                        val sync by notes.syncState.collectAsStateWithLifecycle()
                        DisposableEffect(notes) {
                            model = notes
                            notes.appStarted()
                            onDispose { if (model === notes) model = null }
                        }
                        if (settings) ConnectionSettingsScreen(services.serverUrl, sync.pendingCount,
                            state.busy, state.error, onBack = { settings = false }, onDisconnect = connection::disconnect,
                            onAbout = { about = true })
                        else NotesApp(notes, onConnectionSettings = { settings = true })
                    }
                }
                if (about) Dialog(onDismissRequest = { about = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                    val view = LocalView.current
                    val dark = isSystemInDarkTheme()
                    SideEffect {
                        (view.parent as? DialogWindowProvider)?.window?.let { window ->
                            WindowCompat.getInsetsController(window, view).apply {
                                isAppearanceLightStatusBars = !dark
                                isAppearanceLightNavigationBars = !dark
                            }
                        }
                    }
                    LegalScreen(onBack = { about = false })
                }
                state.legacySession?.let { session ->
                    AlertDialog(onDismissRequest = {}, title = { Text("Заметки прежней версии") },
                        text = { Text("На телефоне найдена прежняя локальная копия" +
                            (state.legacyPendingCount?.let { ". Не отправлено изменений: $it." } ?: ".") +
                            " Это данные сервера ${session.serverUrl}? Перенос разрешит их синхронизацию." +
                            " Уже загруженная копия этого сервера будет заменена, если в ней нет неотправленных правок." +
                            (state.error?.let { "\n\n$it" } ?: "")) },
                        confirmButton = { TextButton(onClick = { connection.finishLegacyImport(true) }, enabled = !state.busy) {
                            Text("Это мой прежний сервер")
                        } },
                        dismissButton = { TextButton(onClick = { connection.finishLegacyImport(false) }, enabled = !state.busy) {
                            Text("Не переносить")
                        } })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
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
