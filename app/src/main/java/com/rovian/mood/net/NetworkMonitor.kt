package com.rovian.mood.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** What the phone's current default network means for casting. */
sealed interface NetworkState {
    /** Not yet determined. */
    data object Unknown : NetworkState
    data object Offline : NetworkState
    /** Wi-Fi or Ethernet — a network TVs and receivers can share with the phone. */
    data class LocalNetwork(val transport: String) : NetworkState
    /** Connected, but not on a local network (e.g. mobile data). */
    data class NonLocal(val transport: String) : NetworkState
}

/**
 * Real-time view of the default network via [ConnectivityManager]. Started once
 * at launch; casting pathways read [state] to decide what is possible.
 */
class NetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val started = AtomicBoolean(false)
    private val _state = MutableStateFlow<NetworkState>(NetworkState.Unknown)
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _state.value = capabilities.toState()
        }

        override fun onLost(network: Network) {
            _state.value = NetworkState.Offline
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val current = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        _state.value = current?.toState() ?: NetworkState.Offline
        connectivity.registerDefaultNetworkCallback(callback)
    }

    private fun NetworkCapabilities.toState(): NetworkState = when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkState.LocalNetwork("Wi-Fi")
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkState.LocalNetwork("Ethernet")
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkState.NonLocal("Mobile data")
        hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkState.NonLocal("VPN")
        else -> NetworkState.NonLocal("Other network")
    }
}
