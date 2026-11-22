package com.rovian.mood.ui.controller

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovian.mood.brand.MoodWordmark
import com.rovian.mood.cast.Command
import com.rovian.mood.cast.ConnectionState
import com.rovian.mood.cast.MediaKind
import com.rovian.mood.cast.PlaybackState
import com.rovian.mood.cast.PlayerStatus
import com.rovian.mood.cast.RepeatMode
import com.rovian.mood.cast.endpoint
import com.rovian.mood.ui.components.BitmapImage
import com.rovian.mood.ui.components.CapsuleProgress
import com.rovian.mood.ui.components.Glyph
import com.rovian.mood.ui.components.GlyphButton
import com.rovian.mood.ui.components.RemoteImage
import com.rovian.mood.ui.components.formatTime
import com.rovian.mood.ui.components.liquidGlass
import com.rovian.mood.ui.theme.MoodColors
import kotlinx.coroutines.delay

interface ControllerActions {
    fun togglePlay()
    fun seekTo(ms: Long)
    fun seekBy(deltaMs: Long)
    fun next()
    fun previous()
    fun stop()
    fun cycleRepeat()
    fun toggleShuffle()
    fun setVolume(level: Float)
    fun setMuted(muted: Boolean)
    fun disconnect()
    fun close()
}

private const val HIDE_AFTER_MS = 4_000L

/**
 * The broadcast controller. Every control maps to a real receiver command and
 * appears only if [PlaybackState.commands] says the receiver supports it. The
 * overlay hides after ~4 s of inactivity and returns on touch or pointer movement.
 */
@Composable
fun ControllerScreen(
    connection: ConnectionState,
    playback: PlaybackState?,
    preparing: String?,
    fitNote: String?,
    localArtwork: Bitmap?,
    actions: ControllerActions,
) {
    BackHandler(onBack = actions::close)
    val receiver = connection.endpoint?.name ?: "TV"
    var visible by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var flash by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf(false) }
    fun poke() {
        visible = true
        lastTouch = System.currentTimeMillis()
    }
    val status = playback?.status
    val hold = preparing != null || dragging || status == PlayerStatus.LOADING || status == PlayerStatus.ERROR ||
        connection !is ConnectionState.Connected
    LaunchedEffect(lastTouch, visible, hold) {
        if (visible && !hold) {
            delay(HIDE_AFTER_MS)
            visible = false
        }
    }
    LaunchedEffect(flash) {
        if (flash != null) {
            delay(700)
            flash = null
        }
    }
    val overlay by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 260 else 520), label = "overlay")
    val canSeek = playback?.can(Command.SEEK) == true

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Move || e.type == PointerEventType.Press) {
                            if (!visible || e.type == PointerEventType.Move) poke()
                        }
                    }
                }
            }
            .pointerInput(canSeek) {
                detectTapGestures(
                    onTap = { if (visible) visible = false else poke() },
                    onDoubleTap = { o ->
                        if (canSeek) {
                            val back = o.x < size.width / 2
                            actions.seekBy(if (back) -10_000 else 10_000)
                            flash = if (back) "-10s" else "+10s"
                        }
                        poke()
                    },
                )
            },
    ) {
        // Artwork / preview
        val artUrl = playback?.media?.artworkUrl
        when {
            localArtwork != null -> BitmapImage(localArtwork, null, Modifier.fillMaxSize())
            artUrl != null -> RemoteImage(artUrl, null, Modifier.fillMaxSize())
            else -> Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(MoodColors.Red.copy(alpha = 0.35f), MoodColors.Night), radius = 1400f),
                ),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))

        flash?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 84.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Box(Modifier.fillMaxSize().graphicsLayer { alpha = overlay; translationY = (1f - overlay) * 24f }) {
            // Top bar: Mood logo, receiver, close
            Row(
                Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MoodWordmark(Modifier.width(96.dp), letterColor = Color.White)
                Spacer(Modifier.weight(1f))
                Text(
                    "This phone → $receiver",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.liquidGlass(RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp),
                )
                Spacer(Modifier.width(8.dp))
                GlyphButton(Glyph.ChevronDown, "Close controller", actions::close, Modifier.liquidGlass(RoundedCornerShape(50)), size = 40.dp)
            }

            // Bottom panel
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f))
                    .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        playback?.title ?: preparing ?: "Connected to $receiver",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(statusLine(connection, playback, preparing, receiver), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    playback?.error?.let { Text(it, color = MoodColors.Orange, fontSize = 13.sp) }
                    fitNote?.takeIf { playback?.kind == MediaKind.VIDEO }?.let {
                        Text("MoodFit · $it", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(10.dp))

                    val duration = playback?.durationMs
                    if (playback != null && duration != null && duration > 0 && playback.kind != MediaKind.IMAGE) {
                        CapsuleProgress(
                            positionMs = playback.positionMs,
                            durationMs = duration,
                            seekable = canSeek,
                            onSeek = { dragging = false; actions.seekTo(it) },
                            onInteract = { dragging = true; poke() },
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Text(formatTime(playback.positionMs), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            Text(formatTime(duration), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        }
                    }
                    if (playback != null) Controls(playback, actions, onFlash = { flash = it }, poke = ::poke)
                    Volume(playback, actions, ::poke)
                    TextButton(onClick = actions::disconnect) { Text("Disconnect", color = MoodColors.Red) }
                }
            }
        }
    }
}

private fun statusLine(connection: ConnectionState, p: PlaybackState?, preparing: String?, receiver: String): String = when {
    connection is ConnectionState.Reconnecting -> "Reconnecting to $receiver…"
    connection !is ConnectionState.Connected -> "Not connected"
    preparing != null -> preparing
    p == null -> "Choose something to play on $receiver"
    else -> when (p.status) {
        PlayerStatus.LOADING -> "Loading on $receiver…"
        PlayerStatus.BUFFERING -> "Buffering on $receiver…"
        PlayerStatus.PLAYING -> if (p.kind == MediaKind.IMAGE) "Showing on $receiver" else "Playing on $receiver"
        PlayerStatus.PAUSED -> "Paused"
        PlayerStatus.ENDED -> "Finished"
        PlayerStatus.ERROR -> "Playback problem"
        PlayerStatus.IDLE -> if (p.kind == MediaKind.IMAGE) "Showing on $receiver" else "Stopped"
    }
}

@Composable
private fun Controls(p: PlaybackState, a: ControllerActions, onFlash: (String) -> Unit, poke: () -> Unit) {
    fun tap(block: () -> Unit): () -> Unit = { poke(); block() }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (p.can(Command.SHUFFLE)) {
            GlyphButton(Glyph.Shuffle, "Shuffle", tap(a::toggleShuffle), tint = if (p.repeat == RepeatMode.SHUFFLE) MoodColors.Orange else Color.White)
        }
        if (p.can(Command.SEEK)) GlyphButton(Glyph.Back10, "Back 10 seconds", tap { a.seekBy(-10_000); onFlash("-10s") })
        if (p.can(Command.PREVIOUS)) GlyphButton(Glyph.Previous, "Previous", tap(a::previous))
        val playing = p.status == PlayerStatus.PLAYING || p.status == PlayerStatus.BUFFERING
        when {
            playing && p.can(Command.PAUSE) -> GlyphButton(Glyph.Pause, "Pause", tap(a::togglePlay), size = 64.dp, iconSize = 30.dp, filled = true)
            !playing && p.can(Command.PLAY) -> GlyphButton(Glyph.Play, "Play", tap(a::togglePlay), size = 64.dp, iconSize = 30.dp, filled = true)
            p.can(Command.STOP) && p.kind == MediaKind.IMAGE -> GlyphButton(Glyph.Stop, "Stop showing", tap(a::stop), size = 64.dp, iconSize = 26.dp, filled = true)
        }
        if (p.can(Command.NEXT)) GlyphButton(Glyph.Next, "Next", tap(a::next))
        if (p.can(Command.SEEK)) GlyphButton(Glyph.Forward10, "Forward 10 seconds", tap { a.seekBy(10_000); onFlash("+10s") })
        if (p.can(Command.REPEAT)) {
            GlyphButton(
                if (p.repeat == RepeatMode.ONE) Glyph.RepeatOne else Glyph.Repeat,
                "Repeat: ${p.repeat.name.lowercase()}",
                tap(a::cycleRepeat),
                tint = if (p.repeat == RepeatMode.OFF) Color.White else MoodColors.Orange,
            )
        }
    }
}

@Composable
private fun Volume(p: PlaybackState?, a: ControllerActions, poke: () -> Unit) {
    if (p == null || !p.can(Command.VOLUME)) return
    val receiverLevel = p.volume ?: return
    var local by remember { mutableStateOf<Float?>(null) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (p.can(Command.MUTE)) {
            val muted = p.muted == true
            GlyphButton(if (muted) Glyph.VolumeOff else Glyph.Volume, if (muted) "Unmute" else "Mute", { poke(); a.setMuted(!muted) }, size = 40.dp, iconSize = 20.dp)
        }
        Slider(
            value = local ?: receiverLevel,
            onValueChange = { local = it; poke() },
            onValueChangeFinished = { local?.let(a::setVolume); local = null },
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = MoodColors.Orange,
                activeTrackColor = MoodColors.Orange,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
        )
    }
}
