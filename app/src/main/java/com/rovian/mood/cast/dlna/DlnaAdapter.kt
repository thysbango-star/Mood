package com.rovian.mood.cast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.rovian.mood.cast.AdapterAvailability
import com.rovian.mood.cast.CastAdapter
import com.rovian.mood.cast.CastException
import com.rovian.mood.cast.CastProtocol
import com.rovian.mood.cast.Command
import com.rovian.mood.cast.CompatibilityChecker
import com.rovian.mood.cast.ConnectionState
import com.rovian.mood.cast.MediaKind
import com.rovian.mood.cast.PlaybackState
import com.rovian.mood.cast.PlayerStatus
import com.rovian.mood.cast.ReceiverCapabilities
import com.rovian.mood.cast.ReceiverEndpoint
import com.rovian.mood.cast.ReceiverKind
import com.rovian.mood.cast.RepeatMode
import com.rovian.mood.cast.ResolvedMedia
import com.rovian.mood.diagnostics.Diagnostics
import com.rovian.mood.net.LocalNetworkInfo
import com.rovian.mood.net.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * DLNA / UPnP AV media casting to smart TVs that expose a MediaRenderer.
 *
 * This is *media casting*, not screen mirroring: the TV's built-in player
 * fetches the media URL and plays it. There is no session concept in UPnP, so
 * "Connected" means the TV has actually answered an AVTransport request; the
 * connection is then supervised by polling the TV every second, which is also
 * how changes made with the TV's own remote reach Mood.
 */
class DlnaAdapter(context: Context, private val network: NetworkMonitor) : CastAdapter {
    override val protocol = CastProtocol.DLNA

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val soap = SoapClient { url -> openOnLocalNetwork(url) }

    private val _availability = MutableStateFlow<AdapterAvailability>(AdapterAvailability.Ready)
    override val availability: StateFlow<AdapterAvailability> = _availability.asStateFlow()
    private val _endpoints = MutableStateFlow<List<ReceiverEndpoint>>(emptyList())
    override val endpoints: StateFlow<List<ReceiverEndpoint>> = _endpoints.asStateFlow()
    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    private val _playback = MutableStateFlow<PlaybackState?>(null)
    override val playback: StateFlow<PlaybackState?> = _playback.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val messages: SharedFlow<String> = _messages.asSharedFlow()

    private class Found(val renderer: DlnaRenderer, val formats: Set<String>?, @Volatile var lastSeen: Long)

    private val found = ConcurrentHashMap<String, Found>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private var discoveryJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var searchesSent = 0
    @Volatile private var repliesSeen = 0

    private fun recordSearch() {
        Diagnostics.set("dlna.search", "$searchesSent searches sent, $repliesSeen replies, ${found.size} DLNA renderers")
        Diagnostics.set("dlna.renderers", found.size.toString())
    }

    // Session state (guarded by being touched only from the session coroutine + lock).
    private val soapLock = Mutex()
    private var sessionJob: Job? = null
    @Volatile private var control: DlnaControl? = null
    @Volatile private var current: ReceiverEndpoint? = null
    private var queue: List<ResolvedMedia> = emptyList()
    private var index = 0
    private var repeat = RepeatMode.OFF
    private var loading = false
    private var loadStartedAt = 0L
    private var wasPlaying = false
    private var lastStatus = PlayerStatus.IDLE
    private var lastPosition: DlnaPosition? = null
    private var seenActions = mutableSetOf<String>()
    private var actionsKnown = false
    private var pauseRejected = false
    private var seekRejected = false
    private var volume: Int? = null
    private var muted: Boolean? = null
    private var lastError: String? = null

    // ---- Discovery -----------------------------------------------------------------

    override fun startDiscovery(activeScan: Boolean) {
        if (discoveryJob?.isActive == true && !activeScan) return
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            network.local.collectLatest { info ->
                if (info == null) {
                    found.clear()
                    publishEndpoints()
                    _availability.value = AdapterAvailability.Unavailable("DLNA needs the phone on Wi-Fi.")
                } else {
                    _availability.value = AdapterAvailability.Ready
                    discoverOn(info)
                }
            }
        }
    }

    override fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        releaseMulticast()
    }

    private suspend fun discoverOn(info: LocalNetworkInfo) {
        acquireMulticast()
        val socket = try {
            DatagramSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(info.address, 0))
                soTimeout = 800
            }.also { s -> runCatching { info.network.bindSocket(s) } }
        } catch (e: IOException) {
            Log.w(TAG, "SSDP socket failed", e)
            Diagnostics.event("DLNA socket failed: ${e.message}")
            _availability.value = AdapterAvailability.Unavailable("This network blocked device discovery.")
            return
        }
        try {
            val group = InetAddress.getByName(Ssdp.ADDRESS)
            val buffer = ByteArray(4096)
            while (currentCoroutineActive()) {
                for (target in listOf(Ssdp.MEDIA_RENDERER, Ssdp.AV_TRANSPORT)) {
                    val msg = Ssdp.searchMessage(target).toByteArray(Charsets.US_ASCII)
                    runCatching { socket.send(DatagramPacket(msg, msg.size, group, Ssdp.PORT)) }
                        .onSuccess { searchesSent++ }
                        .onFailure {
                            Log.d(TAG, "M-SEARCH send failed: ${it.message}")
                            Diagnostics.event("DLNA search send failed: ${it.message}")
                        }
                }
                val listenUntil = System.currentTimeMillis() + LISTEN_WINDOW_MS
                while (System.currentTimeMillis() < listenUntil && currentCoroutineActive()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    repliesSeen++
                    Ssdp.parse(text)?.let { onAnnouncement(it, info) }
                }
                prune()
                recordSearch()
                delay(SEARCH_INTERVAL_MS)
            }
        } finally {
            socket.close()
        }
    }

    private suspend fun currentCoroutineActive(): Boolean = kotlin.coroutines.coroutineContext.isActive

    private fun onAnnouncement(response: SsdpResponse, info: LocalNetworkInfo) {
        val udn = response.udn
        found[udn]?.let { it.lastSeen = System.currentTimeMillis(); return }
        if (!inFlight.add(udn)) return
        scope.launch {
            try {
                val url = URL(response.location)
                // Only follow announcements that point at the local network.
                val host = InetAddress.getByName(url.host)
                if (!(host.isSiteLocalAddress || host.isLinkLocalAddress)) return@launch
                val xml = httpGet(url, info)
                val renderer = DlnaDescription.parse(xml, response.location) ?: return@launch
                val formats = runCatching { DlnaControl(renderer, soap).sinkFormats() }.getOrNull()
                found[udn] = Found(renderer, formats, System.currentTimeMillis())
                publishEndpoints()
                Log.i(TAG, "Found renderer ${renderer.friendlyName} (${renderer.manufacturer} ${renderer.modelName})")
            } catch (e: Exception) {
                Log.d(TAG, "Ignoring ${response.location}: ${e.message}")
                Diagnostics.event("DLNA: couldn't read ${response.location}: ${e.javaClass.simpleName}")
            } finally {
                inFlight.remove(udn)
            }
        }
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        val activeId = current?.id
        val removed = found.entries.removeIf { it.value.lastSeen < cutoff && it.key != activeId }
        if (removed) publishEndpoints()
    }

    private fun publishEndpoints() {
        _endpoints.value = found.entries
            .map { (udn, f) -> toEndpoint(udn, f) }
            .sortedBy { it.name.lowercase() }
    }

    private fun toEndpoint(udn: String, f: Found): ReceiverEndpoint {
        val r = f.renderer
        val formats = f.formats
        val video = formats?.let { CompatibilityChecker.declaresKind(it, MediaKind.VIDEO) } ?: true
        val audio = formats?.let { CompatibilityChecker.declaresKind(it, MediaKind.AUDIO) } ?: true
        val image = formats?.let { CompatibilityChecker.declaresKind(it, MediaKind.IMAGE) } ?: true
        val model = listOfNotNull(r.manufacturer, r.modelName).joinToString(" ").ifBlank { null }
        val looksLikeTv = (model.orEmpty() + " " + r.friendlyName).let { text ->
            TV_BRANDS.any { text.contains(it, ignoreCase = true) } || Regex("\\bTV\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
        return ReceiverEndpoint(
            protocol = CastProtocol.DLNA,
            id = udn,
            name = r.friendlyName,
            model = model,
            kind = when {
                !video && audio -> ReceiverKind.SPEAKER
                looksLikeTv -> ReceiverKind.TV
                else -> ReceiverKind.MEDIA_RENDERER
            },
            capabilities = ReceiverCapabilities(
                video = video,
                audio = audio,
                image = image,
                declaredMimeTypes = formats,
                volumeControl = r.renderingControl != null,
            ),
        )
    }

    // ---- Connection ------------------------------------------------------------------

    override fun connect(endpoint: ReceiverEndpoint) {
        val entry = found[endpoint.id]
        if (entry == null) {
            _connection.value = ConnectionState.Failed(endpoint, "${endpoint.name} isn't on this network any more.")
            return
        }
        endSession(stopTv = current != null && current?.id != endpoint.id)
        _connection.value = ConnectionState.Connecting(endpoint)
        sessionJob = scope.launch {
            val c = DlnaControl(entry.renderer, soap)
            try {
                withTimeout(CONNECT_TIMEOUT_MS) { soapLock.withLock { c.transportState() } }
            } catch (e: Exception) {
                Log.w(TAG, "Connect to ${endpoint.name} failed", e)
                _connection.value = ConnectionState.Failed(
                    endpoint,
                    "${endpoint.name} didn't answer. Make sure it's on, and that media sharing (DLNA) is allowed in the TV's settings.",
                )
                return@launch
            }
            control = c
            current = endpoint
            resetMediaState()
            _connection.value = ConnectionState.Connected(endpoint)
            supervise(c, endpoint)
        }
    }

    override fun disconnect() {
        endSession(stopTv = true)
        _connection.value = ConnectionState.Idle
    }

    override fun acknowledge() {
        val s = _connection.value
        if (s is ConnectionState.Failed || s is ConnectionState.Disconnected) _connection.value = ConnectionState.Idle
    }

    private fun endSession(stopTv: Boolean) {
        sessionJob?.cancel()
        sessionJob = null
        val c = control
        if (stopTv && c != null) scope.launch { runCatching { soapLock.withLock { c.stop() } } }
        control = null
        current = null
        resetMediaState()
        _playback.value = null
    }

    private fun resetMediaState() {
        queue = emptyList()
        index = 0
        repeat = RepeatMode.OFF
        loading = false
        wasPlaying = false
        lastStatus = PlayerStatus.IDLE
        lastPosition = null
        seenActions = mutableSetOf()
        actionsKnown = false
        pauseRejected = false
        seekRejected = false
        lastError = null
    }

    /** Polls the TV: the single source of truth for connection and playback state. */
    private suspend fun supervise(c: DlnaControl, endpoint: ReceiverEndpoint) {
        var failures = 0
        var failingSince = 0L
        var tick = 0
        while (currentCoroutineActive()) {
            try {
                val (state, position, actions) = soapLock.withLock {
                    val s = c.transportState()
                    val p = c.position()
                    val a = if (tick % 2 == 0) c.transportActions() else null
                    if (tick % 3 == 0) {
                        volume = runCatching { c.volume() }.getOrNull()
                        muted = runCatching { c.muted() }.getOrNull()
                    }
                    Triple(s, p, a)
                }
                if (actions != null) {
                    actionsKnown = true
                    seenActions.addAll(actions)
                }
                if (failures > 0) {
                    failures = 0
                    _connection.value = ConnectionState.Connected(endpoint)
                }
                found[endpoint.id]?.lastSeen = System.currentTimeMillis()
                onTvState(state, position)
            } catch (e: IOException) {
                failures++
                if (failures == 1) failingSince = System.currentTimeMillis()
                Log.d(TAG, "Poll failed ($failures): ${e.message}")
                if (failures >= 3) _connection.value = ConnectionState.Reconnecting(endpoint)
                if (System.currentTimeMillis() - failingSince > LOST_AFTER_MS) {
                    control = null
                    current = null
                    _playback.value = null
                    _connection.value = ConnectionState.Disconnected(
                        endpoint,
                        "Lost contact with ${endpoint.name}. It may have been switched off or left the Wi-Fi.",
                    )
                    return
                }
            }
            tick++
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun onTvState(state: String?, position: DlnaPosition) {
        lastPosition = position
        val item = queue.getOrNull(index)
        val trackUri = position.trackUri?.takeIf { it.isNotBlank() && it != "NOT_IMPLEMENTED" }
        val foreign = item != null && trackUri != null && trackUri != item.url
        val status = when (state?.uppercase()) {
            "PLAYING" -> PlayerStatus.PLAYING
            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> PlayerStatus.PAUSED
            "TRANSITIONING" -> PlayerStatus.BUFFERING
            "STOPPED", "NO_MEDIA_PRESENT" -> when {
                loading && System.currentTimeMillis() - loadStartedAt < LOAD_TIMEOUT_MS -> PlayerStatus.LOADING
                loading -> {
                    loading = false
                    lastError = "${current?.name ?: "The TV"} didn't start playing. It may not support this media."
                    PlayerStatus.ERROR
                }
                wasPlaying && item?.media?.kind != MediaKind.IMAGE -> PlayerStatus.ENDED
                else -> PlayerStatus.IDLE
            }
            else -> if (loading) PlayerStatus.LOADING else PlayerStatus.IDLE
        }
        if (status == PlayerStatus.PLAYING || status == PlayerStatus.PAUSED) {
            if (loading) loading = false
            wasPlaying = true
            lastError = null
        }
        if (item?.media?.kind == MediaKind.IMAGE && (status == PlayerStatus.PLAYING || status == PlayerStatus.PAUSED)) {
            wasPlaying = false
        }
        val justEnded = status == PlayerStatus.ENDED && lastStatus != PlayerStatus.ENDED
        lastStatus = status
        publish(status, position, foreign)
        if (justEnded && !foreign) advanceAfterEnd()
    }

    private suspend fun advanceAfterEnd() {
        wasPlaying = false
        val nextIndex = when {
            repeat == RepeatMode.ONE -> index
            index + 1 < queue.size -> index + 1
            repeat == RepeatMode.ALL && queue.isNotEmpty() -> 0
            else -> return
        }
        playIndex(nextIndex)
    }

    private fun publish(status: PlayerStatus, position: DlnaPosition, foreign: Boolean) {
        val item = if (foreign) null else queue.getOrNull(index)
        val kind = item?.media?.kind
        val duration = position.durationMs?.takeIf { it > 0 } ?: item?.media?.durationMs
        val active = status == PlayerStatus.PLAYING || status == PlayerStatus.PAUSED || status == PlayerStatus.BUFFERING
        val commands = mutableSetOf<Command>()
        if (item != null && kind != MediaKind.IMAGE) {
            val canPause = if (actionsKnown) "Pause" in seenActions else !pauseRejected
            val canSeek = if (actionsKnown) "Seek" in seenActions else !seekRejected
            if (active || status == PlayerStatus.IDLE || status == PlayerStatus.ENDED) commands += Command.PLAY
            if (active && canPause && !pauseRejected) commands += Command.PAUSE
            if (active && canSeek && !seekRejected && (duration ?: 0) > 0) commands += Command.SEEK
        }
        commands += Command.STOP
        if (queue.size > 1 && !foreign) {
            commands += Command.NEXT
            commands += Command.PREVIOUS
        }
        if (!foreign && item != null && (queue.size > 1 || kind != MediaKind.IMAGE)) commands += Command.REPEAT
        if (volume != null) commands += Command.VOLUME
        if (muted != null) commands += Command.MUTE
        _playback.value = PlaybackState(
            title = item?.media?.title ?: if (foreign) "Something else is playing on the TV" else null,
            kind = kind,
            status = status,
            positionMs = position.positionMs ?: 0,
            durationMs = duration,
            commands = commands,
            volume = volume?.let { it / 100f },
            muted = muted,
            repeat = repeat,
            queueSize = if (foreign) 0 else queue.size,
            queueIndex = index,
            error = lastError,
            media = item?.media,
        )
    }

    // ---- Media ---------------------------------------------------------------------------

    override suspend fun load(queue: List<ResolvedMedia>, startIndex: Int): Result<Unit> {
        if (control == null || queue.isEmpty()) return Result.failure(CastException("Not connected to a DLNA TV."))
        this.queue = queue
        repeat = RepeatMode.OFF
        return playIndex(startIndex.coerceIn(0, queue.lastIndex))
    }

    private suspend fun playIndex(i: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val c = control ?: return@withContext Result.failure(CastException("Not connected to a DLNA TV."))
        val item = queue[i]
        index = i
        loading = true
        loadStartedAt = System.currentTimeMillis()
        wasPlaying = false
        lastError = null
        seenActions = mutableSetOf()
        actionsKnown = false
        publish(PlayerStatus.LOADING, DlnaPosition(0, item.media.durationMs, null), foreign = false)
        try {
            soapLock.withLock {
                runCatching { c.stop() }
                c.setUri(item.url, Didl.item(item.media.title, item.url, item.mimeType, item.media.kind, item.media.durationMs))
                try {
                    c.play()
                } catch (e: UpnpException) {
                    delay(700) // many TVs need a moment after SetAVTransportURI
                    c.play()
                }
            }
            Result.success(Unit)
        } catch (e: IOException) {
            loading = false
            val reason = (e as? UpnpException)?.message ?: "it stopped responding"
            val message = "${current?.name ?: "The TV"} couldn't play ${item.media.title}: $reason."
            lastError = message
            publish(PlayerStatus.ERROR, DlnaPosition(0, null, null), foreign = false)
            Result.failure(CastException(message, e))
        }
    }

    private fun command(name: String, onRejected: (() -> Unit)? = null, block: DlnaControl.() -> Unit) {
        val c = control ?: return
        scope.launch {
            try {
                soapLock.withLock { c.block() }
            } catch (e: IOException) {
                Log.w(TAG, "$name rejected", e)
                onRejected?.invoke()
                val reason = (e as? UpnpException)?.message ?: "it didn't respond"
                _messages.tryEmit("${current?.name ?: "The TV"} couldn't $name: $reason.")
            }
        }
    }

    override fun play() {
        val p = _playback.value
        if (p?.status == PlayerStatus.ENDED || p?.status == PlayerStatus.IDLE) {
            scope.launch { playIndex(index) }
        } else {
            command("play") { play() }
        }
    }

    override fun pause() = command("pause", onRejected = { pauseRejected = true }) { pause() }
    override fun stop() = command("stop") { stop() }
    override fun seekTo(positionMs: Long) = command("seek", onRejected = { seekRejected = true }) { seek(positionMs) }

    override fun next() {
        if (queue.size < 2) return
        scope.launch { playIndex((index + 1) % queue.size) }
    }

    override fun previous() {
        if (queue.size < 2) return
        val pos = lastPosition?.positionMs ?: 0
        scope.launch { playIndex(if (pos > 3_000) index else (index - 1 + queue.size) % queue.size) }
    }

    override fun setRepeat(mode: RepeatMode) {
        // Implemented by Mood's own queue logic, which advances the TV for real.
        repeat = if (mode == RepeatMode.SHUFFLE) RepeatMode.ALL else mode
        _playback.value = _playback.value?.copy(repeat = repeat)
    }

    override fun setVolume(level: Float) {
        val v = (level.coerceIn(0f, 1f) * 100).toInt()
        command("change the volume") { setVolume(v) }
        volume = v
    }

    override fun setMuted(muted: Boolean) {
        command("mute") { setMuted(muted) }
        this.muted = muted
    }

    // ---- Networking helpers ------------------------------------------------------------

    private fun openOnLocalNetwork(url: URL): HttpURLConnection =
        (network.local.value?.network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection

    private fun httpGet(url: URL, info: LocalNetworkInfo): String {
        val conn = info.network.openConnection(url) as HttpURLConnection
        try {
            conn.connectTimeout = 3_000
            conn.readTimeout = 4_000
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    @Synchronized
    private fun acquireMulticast() {
        if (multicastLock?.isHeld == true) return
        try {
            val wifi = app.getSystemService(WifiManager::class.java) ?: return
            multicastLock = wifi.createMulticastLock("mood-ssdp").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Multicast lock unavailable", e)
        }
    }

    @Synchronized
    private fun releaseMulticast() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    private companion object {
        const val TAG = "MoodDlna"
        const val LISTEN_WINDOW_MS = 3_000L
        const val SEARCH_INTERVAL_MS = 7_000L
        const val STALE_AFTER_MS = 35_000L
        const val CONNECT_TIMEOUT_MS = 8_000L
        const val POLL_INTERVAL_MS = 1_000L
        const val LOST_AFTER_MS = 15_000L
        const val LOAD_TIMEOUT_MS = 20_000L
        val TV_BRANDS = listOf("samsung", "lg ", "lge", "sony", "bravia", "hisense", "philips", "tcl", "panasonic", "sharp", "vizio", "toshiba", "skyworth", "xiaomi")
    }
}
