package com.rovian.mood.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean

/** What the phone's current default network means for casting. */
sealed interface NetworkState {
    /** Not yet determined. */
    data object Unknown : NetworkState
    data object Offline : NetworkState
    /** Wi-Fi or Ethernet — a network TVs and receivers can share with the phone. */
    data class LocalNetwork(val transport: String) : NetworkState
    /** Connected, but not on a local network (e.g. mobile data, VPN). */
    data class NonLocal(val transport: String) : NetworkState
}

/** The local network casting runs on: the [network] handle and this phone's IPv4 on it. */
data class LocalNetworkInfo(val network: Network, val address: Inet4Address)

/**
 * Real-time view of the default network via [ConnectivityManager]. Started once
 * at launch; casting pathways read [state] and [local] to decide what is possible.
 */
class NetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val started = AtomicBoolean(false)
    private val _state = MutableStateFlow<NetworkState>(NetworkState.Unknown)
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val _local = MutableStateFlow<LocalNetworkInfo?>(null)
    val local: StateFlow<LocalNetworkInfo?> = _local.asStateFlow()

    @Volatile private var currentNetwork: Network? = null
    @Volatile private var currentLinks: LinkProperties? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            currentNetwork = network
            _state.value = capabilities.toState()
            if (currentLinks == null) currentLinks = connectivity.getLinkProperties(network)
            updateLocal()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            currentNetwork = network
            currentLinks = linkProperties
            updateLocal()
        }

        override fun onLost(network: Network) {
            currentNetwork = null
            currentLinks = null
            _state.value = NetworkState.Offline
            _local.value = null
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val active = connectivity.activeNetwork
        val caps = active?.let { connectivity.getNetworkCapabilities(it) }
        _state.value = caps?.toState() ?: NetworkState.Offline
        currentNetwork = active
        currentLinks = active?.let { connectivity.getLinkProperties(it) }
        updateLocal()
        connectivity.registerDefaultNetworkCallback(callback)
    }

    private fun updateLocal() {
        val network = currentNetwork
        val address = currentLinks?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
        _local.value = if (network != null && address != null && _state.value is NetworkState.LocalNetwork) {
            LocalNetworkInfo(network, address)
        } else {
            null
        }
    }

    private fun NetworkCapabilities.toState(): NetworkState = when {
        hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkState.NonLocal("VPN")
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkState.LocalNetwork("Wi-Fi")
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkState.LocalNetwork("Ethernet")
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkState.NonLocal("Mobile data")
        else -> NetworkState.NonLocal("Other network")
    }
}
