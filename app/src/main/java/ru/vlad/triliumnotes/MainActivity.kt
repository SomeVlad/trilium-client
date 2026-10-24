package ru.vlad.triliumnotes

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import ru.vlad.triliumnotes.ui.NotesApp
import ru.vlad.triliumnotes.ui.NotesTheme

class MainActivity : ComponentActivity() {
    private val model: NotesViewModel by viewModels()
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var callbackRegistered = false
    private val connectionState = ConnectionState()
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            runOnUiThread {
                if (!callbackRegistered || isDestroyed) return@runOnUiThread
                val available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (connectionState.update(network.networkHandle, available)) model.connectionAvailable()
                else if (!hasValidatedInternet()) model.connectionUnavailable()
            }
        }
        override fun onLost(network: Network) {
            runOnUiThread {
                if (callbackRegistered && !isDestroyed && connectionState.lost(network.networkHandle) && !hasValidatedInternet())
                    model.connectionUnavailable()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NotesTheme { NotesApp(model) } }
    }

    override fun onStart() {
        super.onStart()
        if (!callbackRegistered) {
            callbackRegistered = true
            connectivity.registerDefaultNetworkCallback(networkCallback)
        }
        model.appStarted()
    }

    override fun onStop() {
        model.syncNow()
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
