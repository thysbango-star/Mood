package com.rovian.mood.diagnostics

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.rovian.mood.cast.dlna.Ssdp
import com.rovian.mood.net.NetworkMonitor
import com.rovian.mood.net.NetworkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.Collections

/** One device that answered an SSDP "who's there?" on the network. */
data class SsdpResponder(val ip: String, val server: String?, val targets: Set<String>) {
    val isDlnaRenderer get() = targets.any { it.contains("MediaRenderer") || it.contains(":AVTransport:") }
    val isLgWebOs get() = targets.any { it.contains("webos-second-screen", true) } || server.orEmpty().contains("webos", true)
    val isDial get() = targets.any { it.contains("dial-multiscreen", true) }
    val isSamsung get() = server.orEmpty().contains("samsung", true) || targets.any { it.contains("samsung", true) }
}

/**
 * A one-tap check of what this phone can actually see on the network, using
 * Android's own mDNS (independent of the Cast SDK) and a raw SSDP census.
 */
class NetworkCheck(
    context: Context,
    private val network: NetworkMonitor,
    private val mirroring: com.rovian.mood.cast.mirror.SystemMirroring,
) {
    private val app = context.applicationContext

    suspend fun run(): List<String> = coroutineScope {
        val lines = mutableListOf<String>()
        val local = network.local.value
        lines += "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"
        lines += when (val s = network.state.value) {
            is NetworkState.LocalNetwork -> "Phone network: ${s.transport}, address ${local?.address?.hostAddress ?: "unknown"}"
            is NetworkState.NonLocal -> "Phone network: ${s.transport} — not a local network"
            NetworkState.Offline -> "Phone network: offline"
            NetworkState.Unknown -> "Phone network: unknown"
        }
        val castMdns = async { mdnsScan("_googlecast._tcp", 7_000) }
        val ssdp = async { ssdpCensus(6_000) }
        val cast = castMdns.await()
        val responders = ssdp.await()

        lines += ""
        lines += "Google Cast SDK: ${Diagnostics.facts()["cast.state"] ?: "unknown"}"
        lines += "Cast SDK routes: ${Diagnostics.facts()["cast.routes"] ?: "none reported"}"
        lines += "Android mDNS scan for Cast devices: " +
            if (cast.isEmpty()) "none found" else "${cast.size} found — ${cast.joinToString()}"
        lines += ""
        lines += "Mood DLNA search: ${Diagnostics.facts()["dlna.search"] ?: "not run"}"
        lines += "Devices answering SSDP: " + if (responders.isEmpty()) "none" else responders.size.toString()
        responders.forEach { r ->
            val roles = buildList {
                if (r.isDlnaRenderer) add("DLNA renderer")
                if (r.isLgWebOs) add("LG webOS")
                if (r.isDial) add("DIAL")
                if (r.isSamsung) add("Samsung")
            }.ifEmpty { listOf("other") }
            lines += "  • ${r.ip} ${r.server?.take(60) ?: ""} [${roles.joinToString()}]"
        }

        lines += ""
        lines += "Displays Android reports:"
        lines += mirroring.describeDisplays().map { "  • $it" }
        lines += ""
        lines += "What this means:"
        lines += verdicts(network.state.value, cast, responders)
        val events = Diagnostics.recentEvents()
        if (events.isNotEmpty()) {
            lines += ""
            lines += "Recent events:"
            lines += events.takeLast(12).map { "  $it" }
        }
        lines
    }

    private fun verdicts(state: NetworkState, cast: List<String>, responders: List<SsdpResponder>): List<String> {
        val out = mutableListOf<String>()
        val routes = Diagnostics.facts()["cast.castRouteCount"]?.toIntOrNull() ?: 0
        if (state !is NetworkState.LocalNetwork) {
            out += "• The phone isn't on Wi-Fi/Ethernet (or a VPN is on). Join the TV's Wi-Fi and turn off VPNs."
        }
        if (cast.isEmpty() && responders.isEmpty()) {
            out += "• Nothing on this network answered. The router is blocking device discovery between phones and TVs " +
                "(guest network, 'AP/client isolation', or the TV on a different network/band). Join the same main network as the TV."
        }
        if (cast.isNotEmpty() && routes == 0) {
            out += "• Android can see Cast devices, but the Cast SDK hasn't listed them to Mood. This is a Mood/Cast SDK issue — " +
                "please send this report."
        }
        if (cast.isEmpty() && responders.isNotEmpty()) {
            out += "• No Google Cast devices answered. A Chromecast that's on and on this network should appear here; " +
                "check it's on the same Wi-Fi in the Google Home app."
        }
        responders.filter { it.isLgWebOs && !it.isDlnaRenderer }.forEach {
            out += "• ${it.ip} is an LG webOS TV but it isn't offering DLNA playback. Turn on the TV's media-sharing / " +
                "'LG Connect Apps' / 'Mobile TV On' options, and keep the TV switched on (not standby)."
        }
        if (responders.any { it.isDlnaRenderer } && (Diagnostics.facts()["dlna.renderers"] ?: "0") == "0") {
            out += "• A DLNA TV answered but Mood couldn't read its details. Please send this report."
        }
        if (out.isEmpty()) out += "• Discovery looks healthy."
        return out
    }

    private suspend fun mdnsScan(type: String, durationMs: Long): List<String> {
        val nsd = app.getSystemService(NsdManager::class.java) ?: return emptyList()
        val found = Collections.synchronizedSet(linkedSetOf<String>())
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Diagnostics.event("mDNS discovery failed to start ($errorCode)")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                found += serviceInfo.serviceName.substringBeforeLast('-').ifBlank { serviceInfo.serviceName }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        }
        return try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            delay(durationMs)
            found.toList()
        } catch (e: Exception) {
            Diagnostics.event("mDNS scan error: ${e.message}")
            emptyList()
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    private suspend fun ssdpCensus(durationMs: Long): List<SsdpResponder> = withContext(Dispatchers.IO) {
        val info = network.local.value ?: return@withContext emptyList()
        val wifi = app.getSystemService(WifiManager::class.java)
        val lock = runCatching { wifi?.createMulticastLock("mood-check")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val byIp = linkedMapOf<String, Pair<String?, MutableSet<String>>>()
        try {
            DatagramSocket(null as java.net.SocketAddress?).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(info.address, 0))
                runCatching { info.network.bindSocket(socket) }
                socket.soTimeout = 500
                val group = InetAddress.getByName(Ssdp.ADDRESS)
                repeat(2) {
                    val msg = Ssdp.searchMessage("ssdp:all", mx = 3).toByteArray(Charsets.US_ASCII)
                    socket.send(DatagramPacket(msg, msg.size, group, Ssdp.PORT))
                }
                val buffer = ByteArray(4096)
                val until = System.currentTimeMillis() + durationMs
                while (System.currentTimeMillis() < until) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val headers = text.split("\r\n", "\n").drop(1).mapNotNull { l ->
                        val i = l.indexOf(':')
                        if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
                    }.toMap()
                    val ip = packet.address.hostAddress ?: continue
                    val entry = byIp.getOrPut(ip) { headers["server"] to mutableSetOf() }
                    headers["st"]?.let { entry.second += it }
                    if (entry.first == null && headers["server"] != null) byIp[ip] = headers["server"] to entry.second
                }
            }
        } catch (e: Exception) {
            Diagnostics.event("SSDP census error: ${e.message}")
        } finally {
            runCatching { lock?.release() }
        }
        byIp.map { (ip, v) -> SsdpResponder(ip, v.first, v.second) }
    }
}
