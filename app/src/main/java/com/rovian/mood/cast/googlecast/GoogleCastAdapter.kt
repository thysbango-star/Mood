package com.rovian.mood.cast.googlecast

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueData
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.images.WebImage
import com.rovian.mood.cast.AdapterAvailability
import com.rovian.mood.cast.CastAdapter
import com.rovian.mood.cast.CastException
import com.rovian.mood.cast.CastProtocol
import com.rovian.mood.cast.Command
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Google Cast via the official Cast Application Framework.
 *
 * Discovery: MediaRouter with the Cast SDK's merged selector, rendered in Mood's
 * own device list (no SDK demo UI). Connection: selecting a route makes the
 * framework start a [CastSession]; every state change comes from
 * [SessionManagerListener]. Playback: [RemoteMediaClient] — media plays on the
 * receiver itself; the phone only controls it.
 *
 * Main thread only, as required by the Cast SDK.
 */
class GoogleCastAdapter(private val context: Context) : CastAdapter {
    override val protocol = CastProtocol.GOOGLE_CAST

    private val _availability = MutableStateFlow<AdapterAvailability>(AdapterAvailability.Initializing)
    override val availability: StateFlow<AdapterAvailability> = _availability.asStateFlow()
    private val _endpoints = MutableStateFlow<List<ReceiverEndpoint>>(emptyList())
    override val endpoints: StateFlow<List<ReceiverEndpoint>> = _endpoints.asStateFlow()
    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    private val _playback = MutableStateFlow<PlaybackState?>(null)
    override val playback: StateFlow<PlaybackState?> = _playback.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val main = Handler(Looper.getMainLooper())
    private var castContext: CastContext? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var discoveryWanted = false
    private var activeScan = false

    private var session: CastSession? = null
    private var client: RemoteMediaClient? = null
    private var endpoint: ReceiverEndpoint? = null
    private var userEnding = false

    private var queue: List<ResolvedMedia> = emptyList()
    private var loading = false
    private var loadError: String? = null
    private var progressMs = 0L
    private var durationMs: Long? = null

    /** Starts the Cast framework. Non-blocking; call on the main thread. */
    fun initialize() {
        val gms = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        Diagnostics.set("cast.state", "starting (Play services status $gms)")
        if (gms != ConnectionResult.SUCCESS) {
            Diagnostics.set("cast.state", "unavailable — Google Play services status $gms")
            _availability.value = AdapterAvailability.Unavailable(
                "Google Cast needs Google Play services, which is missing or out of date on this phone.",
            )
            return
        }
        try {
            CastContext.getSharedInstance(context, ContextCompat.getMainExecutor(context))
                .addOnSuccessListener { onCastReady(it) }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Cast framework failed to initialise", e)
                    Diagnostics.set("cast.state", "failed to start: ${e.javaClass.simpleName}: ${e.message}")
                    _availability.value = AdapterAvailability.Unavailable("Google Cast couldn't start on this phone.")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Cast framework failed to initialise", e)
            _availability.value = AdapterAvailability.Unavailable("Google Cast couldn't start on this phone.")
        }
    }

    private fun onCastReady(ctx: CastContext) {
        castContext = ctx
        router = MediaRouter.getInstance(context)
        selector = ctx.mergedSelector
        ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        _availability.value = AdapterAvailability.Ready
        Diagnostics.set("cast.state", "ready, receiver ${CastOptionsProvider.RECEIVER_APP_ID}, selector ${selector != null}")
        ctx.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let { existing ->
            attach(existing)
            _connection.value = ConnectionState.Connected(endpointFor(existing))
        }
        if (discoveryWanted) registerRouterCallback()
    }

    // ---- Discovery -------------------------------------------------------------

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
    }

    override fun startDiscovery(activeScan: Boolean) {
        discoveryWanted = true
        this.activeScan = activeScan
        registerRouterCallback()
    }

    override fun stopDiscovery() {
        discoveryWanted = false
        router?.removeCallback(routerCallback)
    }

    private fun registerRouterCallback() {
        val r = router ?: return
        val sel = selector ?: return
        var flags = MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
        if (activeScan) flags = flags or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
        r.addCallback(sel, routerCallback, flags)
        Diagnostics.event("Cast discovery on (active scan: $activeScan)")
        refreshRoutes()
    }

    private fun refreshRoutes() {
        val r = router ?: return
        val sel = selector ?: return
        val all = r.routes
        val cast = all.filter { !it.isDefault && !it.isBluetooth && it.isEnabled && it.matchesSelector(sel) }
        _endpoints.value = cast.mapNotNull { toEndpoint(it) }
        Diagnostics.set("cast.routes", "${all.size} on phone, ${cast.size} Cast (${cast.joinToString { it.name }})")
        Diagnostics.set("cast.castRouteCount", cast.size.toString())
    }

    private fun toEndpoint(route: MediaRouter.RouteInfo): ReceiverEndpoint? {
        val device = CastDevice.getFromBundle(route.extras) ?: return null
        val name = device.friendlyName?.takeIf { it.isNotBlank() } ?: route.name
        return ReceiverEndpoint(
            protocol = CastProtocol.GOOGLE_CAST,
            id = route.id,
            name = name,
            model = device.modelName?.takeIf { it.isNotBlank() },
            kind = kindOf(device),
            capabilities = capabilitiesOf(device, route.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE),
        )
    }

    private fun capabilitiesOf(device: CastDevice, volume: Boolean?): ReceiverCapabilities {
        val video = device.hasCapability(CastDevice.CAPABILITY_VIDEO_OUT)
        val audio = device.hasCapability(CastDevice.CAPABILITY_AUDIO_OUT) || video
        return ReceiverCapabilities(video = video, audio = audio, image = video, volumeControl = volume)
    }

    private fun kindOf(device: CastDevice): ReceiverKind {
        val model = device.modelName.orEmpty()
        return when {
            device.hasCapability(CastDevice.CAPABILITY_MULTIZONE_GROUP) -> ReceiverKind.SPEAKER_GROUP
            !device.hasCapability(CastDevice.CAPABILITY_VIDEO_OUT) -> ReceiverKind.SPEAKER
            model.contains("chromecast", true) || model.contains("google tv streamer", true) -> ReceiverKind.STREAMER
            else -> ReceiverKind.TV
        }
    }

    // ---- Connection ------------------------------------------------------------

    private val connectTimeout = Runnable {
        val state = _connection.value
        if (state is ConnectionState.Connecting) {
            Log.w(TAG, "Session start timed out for ${state.endpoint.name}")
            _connection.value = ConnectionState.Failed(
                state.endpoint,
                "${state.endpoint.name} didn't respond. Check it's switched on and on the same Wi-Fi, then try again.",
            )
            castContext?.sessionManager?.endCurrentSession(true)
            router?.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
        }
    }

    override fun connect(endpoint: ReceiverEndpoint) {
        val r = router
        if (r == null || castContext == null) {
            _connection.value = ConnectionState.Failed(endpoint, "Google Cast is still starting. Try again in a moment.")
            return
        }
        val route = r.routes.firstOrNull { it.id == endpoint.id }
        if (route == null) {
            _connection.value = ConnectionState.Failed(
                endpoint,
                "${endpoint.name} isn't available any more. Make sure it's on and on the same Wi-Fi.",
            )
            return
        }
        if (route.isSelected && session?.isConnected == true) {
            _connection.value = ConnectionState.Connected(endpoint)
            return
        }
        userEnding = false
        this.endpoint = endpoint
        _connection.value = ConnectionState.Connecting(endpoint)
        main.removeCallbacks(connectTimeout)
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        r.selectRoute(route)
    }

    override fun disconnect() {
        main.removeCallbacks(connectTimeout)
        val ctx = castContext
        if (session != null || _connection.value is ConnectionState.Connecting || _connection.value is ConnectionState.Reconnecting) {
            userEnding = true
            ctx?.sessionManager?.endCurrentSession(true)
            router?.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
        }
        if (session == null) _connection.value = ConnectionState.Idle
    }

    override fun acknowledge() {
        val s = _connection.value
        if (s is ConnectionState.Failed || s is ConnectionState.Disconnected) _connection.value = ConnectionState.Idle
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            val ep = endpointFor(session)
            if (_connection.value !is ConnectionState.Connecting) _connection.value = ConnectionState.Connecting(ep)
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            main.removeCallbacks(connectTimeout)
            attach(session)
            _connection.value = ConnectionState.Connected(endpointFor(session))
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            main.removeCallbacks(connectTimeout)
            val ep = endpointFor(session)
            detach()
            Log.w(TAG, "Session start failed: ${CastStatusCodes.getStatusCodeString(error)}")
            Diagnostics.event("Cast session start failed: ${CastStatusCodes.getStatusCodeString(error)}")
            if (_connection.value !is ConnectionState.Failed) {
                _connection.value = ConnectionState.Failed(ep, connectFailure(error, ep.name))
            }
        }

        override fun onSessionEnding(session: CastSession) = Unit

        override fun onSessionEnded(session: CastSession, error: Int) {
            val ep = endpointFor(session)
            detach()
            val current = _connection.value
            when {
                current is ConnectionState.Failed -> Unit // keep the failure the user needs to see
                userEnding -> _connection.value = ConnectionState.Idle
                else -> {
                    Log.i(TAG, "Session ended by receiver: ${CastStatusCodes.getStatusCodeString(error)}")
                    _connection.value = ConnectionState.Disconnected(
                        ep,
                        if (error == CommonStatusCodes.SUCCESS) "The session on ${ep.name} ended." else "Lost the connection to ${ep.name}.",
                    )
                }
            }
            userEnding = false
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            _connection.value = ConnectionState.Reconnecting(endpointFor(session))
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            attach(session)
            _connection.value = ConnectionState.Connected(endpointFor(session))
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            val ep = endpointFor(session)
            detach()
            _connection.value = ConnectionState.Disconnected(ep, "Couldn't reconnect to ${ep.name}.")
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            _connection.value = ConnectionState.Reconnecting(endpointFor(session))
        }
    }

    private fun endpointFor(session: CastSession): ReceiverEndpoint {
        val device = session.castDevice
        val known = endpoint
        if (known != null && (device == null || known.name == device.friendlyName)) return known
        val discovered = _endpoints.value.firstOrNull { it.name == device?.friendlyName }
        if (discovered != null) return discovered.also { endpoint = it }
        return ReceiverEndpoint(
            protocol = CastProtocol.GOOGLE_CAST,
            id = "session:${device?.deviceId ?: "unknown"}",
            name = device?.friendlyName ?: "Cast device",
            model = device?.modelName,
            kind = device?.let { kindOf(it) } ?: ReceiverKind.TV,
            capabilities = device?.let { capabilitiesOf(it, true) }
                ?: ReceiverCapabilities(video = true, audio = true, image = true),
        ).also { endpoint = it }
    }

    private fun connectFailure(error: Int, name: String): String = when (error) {
        CommonStatusCodes.NETWORK_ERROR -> "A network error stopped Mood connecting to $name. Check both are on the same Wi-Fi."
        CommonStatusCodes.TIMEOUT -> "$name took too long to answer. Make sure it's switched on."
        CastStatusCodes.AUTHENTICATION_FAILED -> "$name refused the connection."
        CastStatusCodes.APPLICATION_NOT_FOUND -> "$name couldn't open its media player."
        CastStatusCodes.CANCELED -> "Connecting to $name was cancelled."
        else -> "Couldn't connect to $name."
    }

    // ---- Media session ---------------------------------------------------------

    private val castListener = object : Cast.Listener() {
        override fun onVolumeChanged() = publish()
    }

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = publish()
        override fun onMetadataUpdated() = publish()
        override fun onQueueStatusUpdated() = publish()
    }

    private val progressListener = RemoteMediaClient.ProgressListener { progress, duration ->
        progressMs = progress
        durationMs = duration.takeIf { it > 0 }
        publish()
    }

    private fun attach(newSession: CastSession) {
        if (session === newSession && client != null) return
        detach()
        session = newSession
        newSession.addCastListener(castListener)
        client = newSession.remoteMediaClient?.also {
            it.registerCallback(mediaCallback)
            it.addProgressListener(progressListener, PROGRESS_INTERVAL_MS)
        }
        publish()
    }

    private fun detach() {
        client?.unregisterCallback(mediaCallback)
        client?.removeProgressListener(progressListener)
        session?.removeCastListener(castListener)
        client = null
        session = null
        queue = emptyList()
        loading = false
        loadError = null
        progressMs = 0
        durationMs = null
        _playback.value = null
    }

    private fun publish() {
        val c = client
        if (c == null) {
            _playback.value = null
            return
        }
        val status = c.mediaStatus
        val info = status?.mediaInfo ?: c.mediaInfo
        if (status == null && !loading && loadError == null) {
            _playback.value = null
            return
        }
        val ours = info?.let { i -> queue.firstOrNull { it.url == i.contentUrl || it.url == i.contentId } }
        val kind = ours?.media?.kind ?: info?.metadata?.mediaType?.let { type ->
            when (type) {
                MediaMetadata.MEDIA_TYPE_MUSIC_TRACK -> MediaKind.AUDIO
                MediaMetadata.MEDIA_TYPE_PHOTO -> MediaKind.IMAGE
                else -> MediaKind.VIDEO
            }
        }
        val player = when {
            status == null -> if (loading) PlayerStatus.LOADING else PlayerStatus.ERROR
            else -> when (status.playerState) {
                MediaStatus.PLAYER_STATE_PLAYING -> PlayerStatus.PLAYING
                MediaStatus.PLAYER_STATE_PAUSED -> PlayerStatus.PAUSED
                MediaStatus.PLAYER_STATE_BUFFERING -> PlayerStatus.BUFFERING
                MediaStatus.PLAYER_STATE_LOADING -> PlayerStatus.LOADING
                MediaStatus.PLAYER_STATE_IDLE -> when (status.idleReason) {
                    MediaStatus.IDLE_REASON_FINISHED -> PlayerStatus.ENDED
                    MediaStatus.IDLE_REASON_ERROR -> PlayerStatus.ERROR
                    else -> if (loading) PlayerStatus.LOADING else PlayerStatus.IDLE
                }
                else -> if (loading) PlayerStatus.LOADING else PlayerStatus.IDLE
            }
        }
        val duration = durationMs ?: info?.streamDuration?.takeIf { it > 0 }
        val volumeVariable = endpoint?.capabilities?.volumeControl != false
        val commands = mutableSetOf<Command>()
        if (status != null) {
            val active = player == PlayerStatus.PLAYING || player == PlayerStatus.PAUSED || player == PlayerStatus.BUFFERING
            if (active && kind != MediaKind.IMAGE && status.isMediaCommandSupported(MediaStatus.COMMAND_PAUSE)) {
                commands += Command.PLAY
                commands += Command.PAUSE
            }
            if (active && kind != MediaKind.IMAGE && (duration ?: 0) > 0 && status.isMediaCommandSupported(MediaStatus.COMMAND_SEEK)) {
                commands += Command.SEEK
            }
            commands += Command.STOP
            if (status.queueItemCount > 1) {
                if (status.isMediaCommandSupported(MediaStatus.COMMAND_QUEUE_NEXT)) commands += Command.NEXT
                if (status.isMediaCommandSupported(MediaStatus.COMMAND_QUEUE_PREVIOUS)) commands += Command.PREVIOUS
                if (status.isMediaCommandSupported(MediaStatus.COMMAND_QUEUE_REPEAT_ALL)) commands += Command.REPEAT
                if (status.isMediaCommandSupported(MediaStatus.COMMAND_QUEUE_SHUFFLE)) commands += Command.SHUFFLE
            }
        }
        if (volumeVariable) {
            commands += Command.VOLUME
            commands += Command.MUTE
        }
        val repeat = when (status?.queueRepeatMode) {
            MediaStatus.REPEAT_MODE_REPEAT_ALL -> RepeatMode.ALL
            MediaStatus.REPEAT_MODE_REPEAT_SINGLE -> RepeatMode.ONE
            MediaStatus.REPEAT_MODE_REPEAT_ALL_AND_SHUFFLE -> RepeatMode.SHUFFLE
            else -> RepeatMode.OFF
        }
        val queueIndex = status?.let { s -> s.getIndexById(s.currentItemId) } ?: 0
        _playback.value = PlaybackState(
            title = ours?.media?.title ?: info?.metadata?.getString(MediaMetadata.KEY_TITLE),
            kind = kind,
            status = player,
            positionMs = if (player == PlayerStatus.LOADING) 0 else progressMs,
            durationMs = duration,
            commands = commands,
            volume = runCatching { session?.volume?.toFloat() }.getOrNull(),
            muted = runCatching { session?.isMute }.getOrNull(),
            repeat = repeat,
            queueSize = status?.queueItemCount ?: queue.size,
            queueIndex = queueIndex,
            error = loadError ?: if (player == PlayerStatus.ERROR) "The TV couldn't play this. The format may not be supported, or the TV couldn't reach it." else null,
            media = ours?.media,
        )
    }

    override suspend fun load(queue: List<ResolvedMedia>, startIndex: Int): Result<Unit> =
        suspendCancellableCoroutine { cont ->
            val c = client
            if (c == null || queue.isEmpty()) {
                cont.resume(Result.failure(CastException("Not connected to a Google Cast device.")))
                return@suspendCancellableCoroutine
            }
            this.queue = queue
            loading = true
            loadError = null
            progressMs = 0
            durationMs = null
            publish()
            val infos = queue.map { it.toMediaInfo() }
            val request = if (infos.size == 1) {
                MediaLoadRequestData.Builder().setMediaInfo(infos[0]).setAutoplay(true).build()
            } else {
                val items = infos.map { MediaQueueItem.Builder(it).setAutoplay(true).setPreloadTime(10.0).build() }
                MediaLoadRequestData.Builder()
                    .setQueueData(
                        MediaQueueData.Builder()
                            .setItems(items)
                            .setStartIndex(startIndex)
                            .setRepeatMode(MediaStatus.REPEAT_MODE_REPEAT_OFF)
                            .build(),
                    )
                    .setAutoplay(true)
                    .build()
            }
            c.load(request).setResultCallback { result ->
                loading = false
                if (result.status.isSuccess) {
                    publish()
                    cont.resume(Result.success(Unit))
                } else {
                    val code = result.status.statusCode
                    Log.w(TAG, "Load failed: ${CastStatusCodes.getStatusCodeString(code)}")
                    val message = "${endpoint?.name ?: "The TV"} couldn't play that. The format may not be supported, " +
                        "or the TV couldn't reach the media."
                    loadError = message
                    publish()
                    cont.resume(Result.failure(CastException(message)))
                }
            }
        }

    private fun ResolvedMedia.toMediaInfo(): MediaInfo {
        val type = when (media.kind) {
            MediaKind.VIDEO -> MediaMetadata.MEDIA_TYPE_MOVIE
            MediaKind.AUDIO -> MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
            MediaKind.IMAGE -> MediaMetadata.MEDIA_TYPE_PHOTO
        }
        val metadata = MediaMetadata(type).apply {
            putString(MediaMetadata.KEY_TITLE, media.title)
            media.subtitle?.let {
                putString(if (media.kind == MediaKind.AUDIO) MediaMetadata.KEY_ARTIST else MediaMetadata.KEY_SUBTITLE, it)
            }
            artworkUrl?.let { addImage(WebImage(Uri.parse(it))) }
        }
        return MediaInfo.Builder(url)
            .setContentUrl(url)
            .setContentType(mimeType)
            .setStreamType(if (media.kind == MediaKind.IMAGE) MediaInfo.STREAM_TYPE_NONE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(metadata)
            .apply { media.durationMs?.let { setStreamDuration(it) } }
            .build()
    }

    // ---- Controls (real receiver commands) -------------------------------------

    override fun play() { client?.play() }
    override fun pause() { client?.pause() }
    override fun stop() { client?.stop() }
    override fun next() { client?.queueNext(null) }
    override fun previous() { client?.queuePrev(null) }

    override fun seekTo(positionMs: Long) {
        client?.seek(MediaSeekOptions.Builder().setPosition(positionMs.coerceAtLeast(0)).build())
    }

    override fun setRepeat(mode: RepeatMode) {
        val castMode = when (mode) {
            RepeatMode.OFF -> MediaStatus.REPEAT_MODE_REPEAT_OFF
            RepeatMode.ALL -> MediaStatus.REPEAT_MODE_REPEAT_ALL
            RepeatMode.ONE -> MediaStatus.REPEAT_MODE_REPEAT_SINGLE
            RepeatMode.SHUFFLE -> MediaStatus.REPEAT_MODE_REPEAT_ALL_AND_SHUFFLE
        }
        client?.queueSetRepeatMode(castMode, null)
    }

    override fun setVolume(level: Float) {
        try {
            session?.volume = level.coerceIn(0f, 1f).toDouble()
        } catch (e: Exception) {
            Log.w(TAG, "Volume change failed", e)
            _messages.tryEmit("${endpoint?.name ?: "The TV"} didn't accept the volume change.")
        }
    }

    override fun setMuted(muted: Boolean) {
        try {
            session?.isMute = muted
        } catch (e: Exception) {
            Log.w(TAG, "Mute failed", e)
            _messages.tryEmit("${endpoint?.name ?: "The TV"} didn't accept mute.")
        }
    }

    private companion object {
        const val TAG = "MoodGoogleCast"
        const val CONNECT_TIMEOUT_MS = 25_000L
        const val PROGRESS_INTERVAL_MS = 500L
    }
}
