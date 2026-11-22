package com.rovian.mood.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import com.rovian.mood.catalog.Movie
import com.rovian.mood.catalog.MoodFit
import com.rovian.mood.cast.media.ByteArrayContent
import com.rovian.mood.cast.media.LocalMediaServer
import com.rovian.mood.cast.media.MediaResolver
import com.rovian.mood.cast.service.CastSessionService
import com.rovian.mood.net.NetworkMonitor
import com.rovian.mood.net.NetworkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DiscoveryPhase { IDLE, SEARCHING, FOUND, NONE_FOUND, NO_LOCAL_NETWORK }

/**
 * MoodCast Engine — picks the genuinely available pathway for each screen and
 * presents every adapter through one truthful state model.
 *
 * - Merges discovery from all adapters into physical [MoodDevice]s.
 * - Connects through the best pathway a screen offers (Google Cast before DLNA),
 *   or the one the user picks.
 * - Checks format compatibility before sending anything, converts photos when
 *   that's what it takes, and serves phone-local files to the receiver.
 * - Keeps a foreground service alive only while a session exists.
 *
 * All public functions are main-thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoodCastEngine(
    private val context: Context,
    private val network: NetworkMonitor,
    val adapters: List<CastAdapter>,
    private val server: LocalMediaServer,
    private val resolver: MediaResolver,
    private val recents: RecentDevices,
    private val scope: CoroutineScope,
) {
    private val discoveryActive = MutableStateFlow(false)
    private val searchTimedOut = MutableStateFlow(false)
    private var searchTimer: Job? = null

    private val active = MutableStateFlow<CastAdapter?>(null)

    val devices: StateFlow<List<MoodDevice>> =
        combine(adapters.map { it.endpoints }) { lists -> DeviceGrouping.group(lists.flatMap { it }) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val connection: StateFlow<ConnectionState> =
        active.flatMapLatest { it?.connection ?: flowOf(ConnectionState.Idle) }
            .stateIn(scope, SharingStarted.Eagerly, ConnectionState.Idle)

    val playback: StateFlow<PlaybackState?> =
        active.flatMapLatest { it?.playback ?: flowOf(null) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val availability: StateFlow<Map<CastProtocol, AdapterAvailability>> =
        combine(adapters.map { a -> a.availability.map { a.protocol to it } }) { it.toMap() }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val discovery: StateFlow<DiscoveryPhase> =
        combine(discoveryActive, searchTimedOut, devices, network.state) { searching, timedOut, found, net ->
            when {
                net is NetworkState.Offline -> DiscoveryPhase.NO_LOCAL_NETWORK
                found.isNotEmpty() -> DiscoveryPhase.FOUND
                net is NetworkState.NonLocal -> DiscoveryPhase.NO_LOCAL_NETWORK
                !searching -> DiscoveryPhase.IDLE
                timedOut -> DiscoveryPhase.NONE_FOUND
                else -> DiscoveryPhase.SEARCHING
            }
        }.stateIn(scope, SharingStarted.Eagerly, DiscoveryPhase.IDLE)

    val isSearching: StateFlow<Boolean> = discoveryActive.asStateFlow()

    val recentDevices: StateFlow<List<RecentDevice>> = recents.items

    private val _preparing = MutableStateFlow<String?>(null)
    /** Non-null while Mood is reading/converting/serving media before sending it. */
    val preparing: StateFlow<String?> = _preparing.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        // A session can begin without the user tapping (Cast resumes after an app
        // restart); whichever adapter has a live session becomes the active one.
        adapters.forEach { adapter ->
            scope.launch {
                adapter.connection.collect { state ->
                    val current = active.value
                    if (state !is ConnectionState.Idle && (current == null || current.connection.value is ConnectionState.Idle)) {
                        active.value = adapter
                    }
                }
            }
            scope.launch { adapter.messages.collect { _messages.emit(it) } }
        }
        scope.launch {
            connection.map { it.hasSession to it.endpoint }.distinctUntilChanged().collect { (live, endpoint) ->
                if (live && endpoint != null) {
                    if (connection.value is ConnectionState.Connected) recents.remember(endpoint)
                    CastSessionService.start(context, endpoint.name)
                } else if (connection.value !is ConnectionState.Connecting) {
                    server.stop()
                    CastSessionService.stop(context)
                }
            }
        }
        // Local media URLs embed the phone's address; a new network invalidates them.
        scope.launch {
            network.local.map { it?.address }.distinctUntilChanged().collect { server.stop() }
        }
    }

    // ---- Discovery -------------------------------------------------------------------

    fun startDiscovery(activeScan: Boolean = false) {
        discoveryActive.value = true
        searchTimedOut.value = false
        adapters.forEach { it.startDiscovery(activeScan) }
        searchTimer?.cancel()
        searchTimer = scope.launch {
            delay(SEARCH_TIMEOUT_MS)
            searchTimedOut.value = true
        }
    }

    fun stopDiscovery() {
        discoveryActive.value = false
        searchTimer?.cancel()
        adapters.forEach { it.stopDiscovery() }
    }

    fun searchAgain() {
        stopDiscovery()
        startDiscovery(activeScan = true)
    }

    // ---- Connection ------------------------------------------------------------------

    fun connect(endpoint: ReceiverEndpoint) {
        val adapter = adapters.firstOrNull { it.protocol == endpoint.protocol } ?: return
        val current = active.value
        if (current != null && current !== adapter) current.disconnect()
        active.value = adapter
        val state = adapter.connection.value
        if (state.hasSession && state.endpoint?.key == endpoint.key) return
        adapter.connect(endpoint)
    }

    fun disconnect() {
        active.value?.disconnect()
    }

    fun acknowledge() {
        active.value?.acknowledge()
    }

    // ---- Casting ----------------------------------------------------------------------

    fun castUris(uris: List<Uri>, hint: MediaKind) {
        if (uris.isEmpty()) return
        scope.launch {
            val endpoint = requireConnected() ?: return@launch
            runPreparation(if (uris.size == 1) "Preparing…" else "Preparing ${uris.size} items…") {
                _fitNote.value = null
                val medias = uris.mapNotNull { uri ->
                    try {
                        resolver.fromUri(uri, hint)
                    } catch (e: CastException) {
                        _messages.emit(e.message ?: "Couldn't read a file.")
                        null
                    }
                }
                send(endpoint, medias)
            }
        }
    }

    private val _fitNote = MutableStateFlow<String?>(null)
    /** Why MoodFit picked the stream currently playing (shown in the controller). */
    val fitNote: StateFlow<String?> = _fitNote.asStateFlow()

    /** Sends a catalogue movie to the connected screen, picking the stream with [MoodFit]. */
    fun castMovie(movie: Movie) {
        scope.launch {
            val endpoint = requireConnected() ?: return@launch
            runPreparation("Starting ${movie.title}…") {
                val choice = MoodFit.choose(movie, endpoint)
                    ?: throw CastException("${endpoint.name} can't play ${movie.title} in any format it's offered in.")
                _fitNote.value = choice.explanation
                send(endpoint, listOf(MoodFit.toCastMedia(movie, choice.stream)))
            }
        }
    }

    fun castUrl(url: String) {
        scope.launch {
            val endpoint = requireConnected() ?: return@launch
            runPreparation("Checking link…") { send(endpoint, listOf(resolver.fromUrl(url))) }
        }
    }

    private suspend fun runPreparation(label: String, block: suspend () -> Unit) {
        _preparing.value = label
        try {
            block()
        } catch (e: CastException) {
            _messages.emit(e.message ?: "Something went wrong.")
        } catch (e: Exception) {
            Log.e(TAG, "Cast failed", e)
            _messages.emit("Something went wrong while casting. Please try again.")
        } finally {
            _preparing.value = null
        }
    }

    private suspend fun requireConnected(): ReceiverEndpoint? {
        val state = connection.value
        if (state is ConnectionState.Connected) return state.endpoint
        _messages.emit(
            if (state is ConnectionState.Reconnecting) "Still reconnecting to ${state.endpoint.name}…" else "Connect to a screen first.",
        )
        return null
    }

    private suspend fun send(endpoint: ReceiverEndpoint, medias: List<CastMedia>) {
        if (medias.isEmpty()) return
        val adapter = active.value ?: throw CastException("Connect to a screen first.")
        val resolved = mutableListOf<ResolvedMedia>()
        val rejected = mutableListOf<String>()
        for (media in medias) {
            when (val compat = CompatibilityChecker.check(endpoint, media.kind, media.mimeType)) {
                is Compatibility.Unsupported -> rejected += compat.reason
                is Compatibility.Unverified -> {
                    if (medias.size == 1) _messages.emit(compat.note)
                    resolved += resolve(media, null)
                }
                is Compatibility.Convert -> resolved += resolve(media, compat.toMime)
                Compatibility.Supported -> resolved += resolve(media, null)
            }
        }
        if (resolved.isEmpty()) {
            throw CastException(rejected.first() + alternativeHint(endpoint, medias.first()))
        }
        if (rejected.isNotEmpty()) {
            _messages.emit("Skipped ${rejected.size} item${if (rejected.size > 1) "s" else ""} ${endpoint.name} can't play.")
        }
        adapter.load(resolved, 0).onFailure { throw it }
    }

    /** If the same screen can play this over another pathway, say so. */
    private fun alternativeHint(endpoint: ReceiverEndpoint, media: CastMedia): String {
        val device = devices.value.firstOrNull { it.owns(endpoint.key) } ?: return ""
        val other = device.endpoints.firstOrNull {
            it.protocol != endpoint.protocol && CompatibilityChecker.check(it, media.kind, media.mimeType) !is Compatibility.Unsupported
        } ?: return ""
        return " ${device.name} also supports ${other.protocol.label}, which may play it — switch in the screen list."
    }

    private suspend fun resolve(media: CastMedia, convertTo: String?): ResolvedMedia {
        return when (val source = media.source) {
            is MediaSource.Remote -> ResolvedMedia(media, source.url, media.mimeType, media.artworkUrl)
            is MediaSource.Local -> {
                val info = network.local.value
                    ?: throw CastException("Join the same Wi-Fi as the TV to cast files from this phone.")
                if (!server.start(info.address)) {
                    throw CastException("Mood couldn't share files on this network. Try reconnecting to Wi-Fi.")
                }
                val content = if (convertTo == "image/jpeg") resolver.transcodeToJpeg(media) else resolver.content(media)
                val mime = convertTo ?: media.mimeType
                val name = media.title + "." + MediaFormats.extensionFor(mime)
                val url = server.share(content, name) ?: throw CastException("Mood couldn't share that file.")
                val artwork = if (media.kind == MediaKind.AUDIO) {
                    resolver.embeddedArtwork(media)?.let { server.share(ByteArrayContent(it, "image/jpeg"), "artwork.jpg") }
                } else {
                    null
                }
                ResolvedMedia(media, url, mime, artwork)
            }
        }
    }

    // ---- Transport -----------------------------------------------------------------------

    private fun can(command: Command) = playback.value?.can(command) == true

    fun togglePlay() {
        val p = playback.value ?: return
        if (p.status == PlayerStatus.PLAYING || p.status == PlayerStatus.BUFFERING) {
            if (can(Command.PAUSE)) active.value?.pause()
        } else if (can(Command.PLAY)) {
            active.value?.play()
        }
    }

    fun seekTo(positionMs: Long) {
        if (can(Command.SEEK)) active.value?.seekTo(positionMs)
    }

    fun seekBy(deltaMs: Long) {
        val p = playback.value ?: return
        if (!p.can(Command.SEEK)) return
        val target = (p.positionMs + deltaMs).coerceIn(0, p.durationMs ?: Long.MAX_VALUE)
        active.value?.seekTo(target)
    }

    fun stopMedia() { if (can(Command.STOP)) active.value?.stop() }
    fun next() { if (can(Command.NEXT)) active.value?.next() }
    fun previous() { if (can(Command.PREVIOUS)) active.value?.previous() }
    fun setVolume(level: Float) { if (can(Command.VOLUME)) active.value?.setVolume(level) }
    fun setMuted(muted: Boolean) { if (can(Command.MUTE)) active.value?.setMuted(muted) }

    fun cycleRepeat() {
        val p = playback.value ?: return
        if (!p.can(Command.REPEAT)) return
        val next = when (p.repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE, RepeatMode.SHUFFLE -> RepeatMode.OFF
        }
        active.value?.setRepeat(next)
    }

    fun toggleShuffle() {
        val p = playback.value ?: return
        if (!p.can(Command.SHUFFLE)) return
        active.value?.setRepeat(if (p.repeat == RepeatMode.SHUFFLE) RepeatMode.ALL else RepeatMode.SHUFFLE)
    }

    private companion object {
        const val TAG = "MoodCastEngine"
        const val SEARCH_TIMEOUT_MS = 12_000L
    }
}
