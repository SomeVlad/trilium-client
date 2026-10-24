package ru.vlad.triliumnotes

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal fun Context.hasValidatedInternet(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    return manager.getNetworkCapabilities(manager.activeNetwork)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
}

/** Capability updates and activity resumes on one connection are not reconnections. */
internal class ConnectionState {
    private var validated: Long? = null
    fun update(network: Long, available: Boolean): Boolean {
        if (!available) { lost(network); return false }
        val restored = validated != network
        validated = network
        return restored
    }
    fun lost(network: Long): Boolean {
        if (validated != network) return false
        validated = null
        return true
    }
    fun reset() { validated = null }
}
