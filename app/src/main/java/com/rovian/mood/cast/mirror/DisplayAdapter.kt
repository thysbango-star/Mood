package com.rovian.mood.cast.mirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.widget.VideoView
import androidx.activity.ComponentActivity
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
import com.rovian.mood.catalog.CatalogHome
import com.rovian.mood.catalog.Movie
import com.rovian.mood.diagnostics.Diagnostics
import com.rovian.mood.ui.components.ImageLoader
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the TV display is showing. */
sealed interface TvScene {
    /** Big-screen MoodPlay home, following what's in focus on the phone. */
    data object Home : TvScene
    data class Playing(val item: ResolvedMedia, val token: Long) : TvScene
}

/** Connects the TV-side VideoView back to the adapter. */
class TvPlayerBridge {
    @Volatile var view: VideoView? = null
    var resumeAtMs: Long = 0
    var onPrepared: (durationMs: Long) -> Unit = {}
    var onCompleted: () -> Unit = {}
    var onError: (what: Int) -> Unit = {}
}

/**
 * The "TV display" pathway: when the phone is mirrored to a TV, Android exposes
 * the TV as a presentation display. Instead of the TV showing a pillarboxed copy
 * of the phone, Mood draws its own interface there — laid out for a TV, at the
 * TV's native resolution — and plays media on it directly while the phone
 * becomes the remote.
 *
 * Connected means Android reports the display and Mood's presentation is up on
 * it. Playback state is read from the TV-side player itself.
 */
class DisplayAdapter(context: Context, private val mirroring: SystemMirroring) : CastAdapter {
    override val protocol = CastProtocol.SCREEN

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    private val _scene = MutableStateFlow<TvScene>(TvScene.Home)
    val scene: StateFlow<TvScene> = _scene.asStateFlow()

    /** What the phone is showing, so the TV home can follow along. */
    val home = MutableStateFlow<CatalogHome?>(null)
    val focus = MutableStateFlow<Movie?>(null)

    val bridge = TvPlayerBridge()

    private var activity: ComponentActivity? = null
    private var presentation: TvPresentation? = null
    private var display: Display? = null

    private var queue: List<ResolvedMedia> = emptyList()
    private var index = 0
    private var repeat = RepeatMode.OFF
    private var status = PlayerStatus.IDLE
    private var durationMs: Long? = null
    private var error: String? = null
    private var pollJob: Job? = null

    init {
        scope.launch {
            mirroring.presentationDisplay.collect { d ->
                display = d
                _endpoints.value = d?.let { listOf(endpointFor(it)) } ?: emptyList()
                if (d == null) onDisplayGone() else Diagnostics.event("Mirrored display appeared: ${d.name}")
            }
        }
    }

    private fun endpointFor(d: Display): ReceiverEndpoint {
        val mode = d.mode
        return ReceiverEndpoint(
            protocol = CastProtocol.SCREEN,
            id = "display:${d.displayId}",
            name = d.name,
            model = "${mode.physicalWidth}×${mode.physicalHeight} · ${mode.refreshRate.toInt()} Hz",
            kind = ReceiverKind.TV,
            capabilities = ReceiverCapabilities(video = true, audio = true, image = true, volumeControl = false),
        )
    }

    /** The activity provides the window the presentation hangs off. */
    fun attach(activity: ComponentActivity) {
        this.activity = activity
        if (_connection.value is ConnectionState.Connected || _connection.value is ConnectionState.Reconnecting) {
            if (showPresentation()) {
                (_connection.value as? ConnectionState.Reconnecting)?.let { _connection.value = ConnectionState.Connected(it.endpoint) }
            }
        }
    }

    fun detach(activity: ComponentActivity) {
        if (this.activity !== activity) return
        // The TV falls back to plain mirroring while Mood is in the background.
        bridge.view?.let { v -> bridge.resumeAtMs = v.currentPosition.toLong() }
        dismissPresentation()
        this.activity = null
        (_connection.value as? ConnectionState.Connected)?.let { _connection.value = ConnectionState.Reconnecting(it.endpoint) }
    }

    override fun startDiscovery(activeScan: Boolean) = Unit // Android pushes display changes to us.
    override fun stopDiscovery() = Unit

    override fun connect(endpoint: ReceiverEndpoint) {
        if (display == null) {
            _connection.value = ConnectionState.Failed(endpoint, "The TV display is gone. Start screen mirroring again.")
            return
        }
        if (activity == null) {
            _connection.value = ConnectionState.Failed(endpoint, "Open Mood on your phone to use the TV display.")
            return
        }
        _connection.value = ConnectionState.Connecting(endpoint)
        if (showPresentation()) {
            _connection.value = ConnectionState.Connected(endpoint)
        } else {
            _connection.value = ConnectionState.Failed(endpoint, "Android wouldn't let Mood draw on ${endpoint.name}.")
        }
    }

    override fun disconnect() {
        stopMedia()
        dismissPresentation()
        _connection.value = ConnectionState.Idle
    }

    override fun acknowledge() {
        val s = _connection.value
        if (s is ConnectionState.Failed || s is ConnectionState.Disconnected) _connection.value = ConnectionState.Idle
    }

    private fun onDisplayGone() {
        dismissPresentation()
        stopMedia()
        val ep = when (val s = _connection.value) {
            is ConnectionState.Connected -> s.endpoint
            is ConnectionState.Reconnecting -> s.endpoint
            is ConnectionState.Connecting -> s.endpoint
            else -> null
        }
        if (ep != null) _connection.value = ConnectionState.Disconnected(ep, "Screen mirroring to ${ep.name} ended.")
    }

    private fun showPresentation(): Boolean {
        val a = activity ?: return false
        val d = display ?: return false
        if (presentation?.display?.displayId == d.displayId && presentation?.isShowing == true) return true
        dismissPresentation()
        return try {
            TvPresentation(a, d, this).also {
                it.show()
                presentation = it
            }
            Diagnostics.set("tv.display", "${d.name} ${d.mode.physicalWidth}×${d.mode.physicalHeight}")
            true
        } catch (e: WindowManager.InvalidDisplayException) {
            Log.w(TAG, "Display vanished", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Presentation failed", e)
            Diagnostics.event("TV display failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun dismissPresentation() {
        runCatching { presentation?.dismiss() }
        presentation = null
        bridge.view = null
    }

    // ---- Media -------------------------------------------------------------------

    override suspend fun load(queue: List<ResolvedMedia>, startIndex: Int): Result<Unit> {
        if (_connection.value !is ConnectionState.Connected) {
            return Result.failure(CastException("The TV display isn't connected."))
        }
        this.queue = queue
        repeat = RepeatMode.OFF
        playIndex(startIndex.coerceIn(0, queue.lastIndex))
        return Result.success(Unit)
    }

    private fun playIndex(i: Int) {
        index = i
        val item = queue[i]
        error = null
        durationMs = item.media.durationMs
        bridge.resumeAtMs = 0
        bridge.onPrepared = { d ->
            durationMs = d.takeIf { it > 0 } ?: durationMs
            status = PlayerStatus.PLAYING
            publish()
        }
        bridge.onCompleted = { status = PlayerStatus.ENDED; publish(); advance() }
        bridge.onError = { what ->
            status = PlayerStatus.ERROR
            error = "This phone couldn't play ${item.media.title} (error $what)."
            publish()
        }
        status = if (item.media.kind == MediaKind.IMAGE) PlayerStatus.PLAYING else PlayerStatus.LOADING
        _scene.value = TvScene.Playing(item, System.nanoTime())
        publish()
        startPolling()
    }

    private fun advance() {
        val next = when {
            repeat == RepeatMode.ONE -> index
            index + 1 < queue.size -> index + 1
            repeat == RepeatMode.ALL && queue.isNotEmpty() -> 0
            else -> return
        }
        playIndex(next)
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                publish()
                delay(500)
            }
        }
    }

    private fun stopMedia() {
        pollJob?.cancel()
        bridge.view?.stopPlayback()
        queue = emptyList()
        _scene.value = TvScene.Home
        _playback.value = null
    }

    private fun publish() {
        val item = queue.getOrNull(index) ?: run { _playback.value = null; return }
        val kind = item.media.kind
        val v = bridge.view
        if (kind != MediaKind.IMAGE && v != null && status != PlayerStatus.ERROR && status != PlayerStatus.ENDED && status != PlayerStatus.LOADING) {
            status = if (v.isPlaying) PlayerStatus.PLAYING else PlayerStatus.PAUSED
        }
        val position = if (kind == MediaKind.IMAGE) 0L else (v?.currentPosition?.toLong() ?: bridge.resumeAtMs)
        val commands = mutableSetOf(Command.STOP)
        if (kind != MediaKind.IMAGE && v != null && status != PlayerStatus.LOADING) {
            commands += Command.PLAY
            if (v.canPause()) commands += Command.PAUSE
            if ((durationMs ?: 0) > 0 && (v.canSeekForward() || v.canSeekBackward())) commands += Command.SEEK
        }
        if (queue.size > 1) {
            commands += Command.NEXT
            commands += Command.PREVIOUS
        }
        if (queue.size > 1 || kind != MediaKind.IMAGE) commands += Command.REPEAT
        _playback.value = PlaybackState(
            title = item.media.title,
            kind = kind,
            status = status,
            positionMs = position,
            durationMs = durationMs,
            commands = commands,
            repeat = repeat,
            queueSize = queue.size,
            queueIndex = index,
            error = error,
            media = item.media,
        )
    }

    override fun play() {
        val v = bridge.view ?: return
        if (status == PlayerStatus.ENDED) playIndex(index) else { v.start(); status = PlayerStatus.PLAYING; publish() }
    }

    override fun pause() {
        bridge.view?.pause()
        status = PlayerStatus.PAUSED
        publish()
    }

    override fun seekTo(positionMs: Long) {
        if (status == PlayerStatus.LOADING) {
            bridge.resumeAtMs = positionMs // applied the moment the TV-side player is ready
        } else {
            bridge.view?.seekTo(positionMs.toInt())
        }
        publish()
    }

    override fun stop() = stopMedia()
    override fun next() { if (queue.size > 1) playIndex((index + 1) % queue.size) }
    override fun previous() { if (queue.size > 1) playIndex((index - 1 + queue.size) % queue.size) }

    override fun setRepeat(mode: RepeatMode) {
        repeat = if (mode == RepeatMode.SHUFFLE) RepeatMode.ALL else mode
        publish()
    }

    override fun setVolume(level: Float) = Unit // The TV's own volume applies to mirroring.
    override fun setMuted(muted: Boolean) = Unit

    /** Decodes a photo for the TV at (up to) the TV's own resolution. */
    suspend fun loadImage(url: String, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (url.startsWith("http")) return@withContext ImageLoader.load(url, maxSide)
        runCatching {
            val uri = Uri.parse(url)
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.contentResolver, uri)) { decoder, info, _ ->
                    val scale = minOf(1f, maxSide.toFloat() / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                }
            } else {
                app.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "MoodTvDisplay"
    }
}
