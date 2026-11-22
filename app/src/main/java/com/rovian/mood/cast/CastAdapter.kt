package com.rovian.mood.cast

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Protocol-agnostic contract every casting pathway implements. Google Cast and
 * DLNA are the first real implementations; the Mood Web Receiver and screen
 * mirroring plug in here later without touching the UI.
 *
 * Rules every adapter follows:
 * - [endpoints] only ever contains receivers the protocol actually discovered.
 * - [connection] changes only in response to real protocol events/responses.
 * - [playback] reflects what the receiver reports; [PlaybackState.commands]
 *   lists only actions the receiver can execute.
 *
 * Control methods must be called on the main thread.
 */
interface CastAdapter {
    val protocol: CastProtocol
    val availability: StateFlow<AdapterAvailability>
    val endpoints: StateFlow<List<ReceiverEndpoint>>
    val connection: StateFlow<ConnectionState>
    val playback: StateFlow<PlaybackState?>
    /** One-off, user-facing problems (a rejected command, a failed load). */
    val messages: SharedFlow<String>

    fun startDiscovery(activeScan: Boolean = false)
    fun stopDiscovery()

    fun connect(endpoint: ReceiverEndpoint)
    /** Ends the session and stops the receiver's playback. */
    fun disconnect()
    /** Clears a Failed/Disconnected state back to Idle once the user has seen it. */
    fun acknowledge()

    /** Sends [queue] to the receiver, starting at [startIndex]. Completes when the receiver accepts or rejects it. */
    suspend fun load(queue: List<ResolvedMedia>, startIndex: Int): Result<Unit>

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun stop()
    fun next()
    fun previous()
    fun setVolume(level: Float)
    fun setMuted(muted: Boolean)
    fun setRepeat(mode: RepeatMode)
}
