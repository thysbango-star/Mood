package com.rovian.mood.cast

import java.util.UUID

/** A real, implemented casting pathway. Lower [rank] is preferred when a screen offers several. */
enum class CastProtocol(val label: String, val rank: Int) {
    GOOGLE_CAST("Google Cast", 0),
    DLNA("DLNA", 1),
}

enum class MediaKind(val label: String) { VIDEO("Video"), AUDIO("Music"), IMAGE("Photo") }

enum class ReceiverKind(val label: String) {
    TV("TV"),
    STREAMER("Streaming device"),
    SPEAKER("Speaker"),
    SPEAKER_GROUP("Speaker group"),
    MEDIA_RENDERER("Media player"),
}

/** What a receiver has *told us* it can do. Nothing here is assumed. */
data class ReceiverCapabilities(
    val video: Boolean,
    val audio: Boolean,
    val image: Boolean,
    /** MIME types the receiver declared (DLNA `GetProtocolInfo`); null = it didn't say. */
    val declaredMimeTypes: Set<String>? = null,
    /** Whether the receiver accepts volume changes; null = not known. */
    val volumeControl: Boolean? = null,
) {
    fun supports(kind: MediaKind): Boolean = when (kind) {
        MediaKind.VIDEO -> video
        MediaKind.AUDIO -> audio
        MediaKind.IMAGE -> image
    }
}

/** A receiver as one protocol sees it. */
data class ReceiverEndpoint(
    val protocol: CastProtocol,
    /** Adapter-specific stable id (MediaRouter route id, UPnP UDN…). */
    val id: String,
    val name: String,
    val model: String?,
    val kind: ReceiverKind,
    val capabilities: ReceiverCapabilities,
) {
    val key: String get() = "${protocol.name}:$id"
}

/** One physical screen, possibly reachable over several protocols. */
data class MoodDevice(val key: String, val name: String, val endpoints: List<ReceiverEndpoint>) {
    val primary: ReceiverEndpoint get() = endpoints.minBy { it.protocol.rank }
    fun endpoint(protocol: CastProtocol): ReceiverEndpoint? = endpoints.firstOrNull { it.protocol == protocol }
    fun owns(endpointKey: String): Boolean = endpoints.any { it.key == endpointKey }
}

sealed interface MediaSource {
    /** A file on this phone (a `content://` URI string). */
    data class Local(val contentUri: String, val sizeBytes: Long?) : MediaSource
    /** A URL the receiver fetches itself. */
    data class Remote(val url: String) : MediaSource
}

data class CastMedia(
    val kind: MediaKind,
    val title: String,
    val mimeType: String,
    val source: MediaSource,
    val durationMs: Long? = null,
    val subtitle: String? = null,
    /** Poster/artwork URL the receiver and the controller can show. */
    val artworkUrl: String? = null,
    val id: String = UUID.randomUUID().toString(),
)

/** Media ready to send: [url] is reachable by the receiver, [mimeType] is what it will receive. */
data class ResolvedMedia(
    val media: CastMedia,
    val url: String,
    val mimeType: String,
    val artworkUrl: String? = null,
)

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Connecting(val endpoint: ReceiverEndpoint) : ConnectionState
    data class Connected(val endpoint: ReceiverEndpoint) : ConnectionState
    data class Reconnecting(val endpoint: ReceiverEndpoint) : ConnectionState
    data class Failed(val endpoint: ReceiverEndpoint, val message: String) : ConnectionState
    data class Disconnected(val endpoint: ReceiverEndpoint, val message: String?) : ConnectionState
}

val ConnectionState.endpoint: ReceiverEndpoint?
    get() = when (this) {
        ConnectionState.Idle -> null
        is ConnectionState.Connecting -> endpoint
        is ConnectionState.Connected -> endpoint
        is ConnectionState.Reconnecting -> endpoint
        is ConnectionState.Failed -> endpoint
        is ConnectionState.Disconnected -> endpoint
    }

/** True while a session with a receiver exists (possibly recovering). */
val ConnectionState.hasSession: Boolean
    get() = this is ConnectionState.Connected || this is ConnectionState.Reconnecting

enum class PlayerStatus { IDLE, LOADING, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

/** Actions the connected receiver can genuinely execute right now. */
enum class Command { PLAY, PAUSE, SEEK, STOP, NEXT, PREVIOUS, VOLUME, MUTE, REPEAT, SHUFFLE }

enum class RepeatMode { OFF, ALL, ONE, SHUFFLE }

/** Playback as reported by the receiver. Positions come from the receiver, never a local timer. */
data class PlaybackState(
    val title: String?,
    val kind: MediaKind?,
    val status: PlayerStatus,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val commands: Set<Command> = emptySet(),
    val volume: Float? = null,
    val muted: Boolean? = null,
    val repeat: RepeatMode = RepeatMode.OFF,
    val queueSize: Int = 0,
    val queueIndex: Int = 0,
    val error: String? = null,
    /** The item Mood sent, if the receiver is playing something Mood sent. */
    val media: CastMedia? = null,
) {
    fun can(command: Command) = command in commands
}

sealed interface AdapterAvailability {
    data object Initializing : AdapterAvailability
    data object Ready : AdapterAvailability
    data class Unavailable(val reason: String) : AdapterAvailability
}

/** An error with a message fit to show the user. */
class CastException(userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause)
