package com.rovian.mood.ui.controller

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovian.mood.cast.CastProtocol
import com.rovian.mood.cast.Command
import com.rovian.mood.cast.ConnectionState
import com.rovian.mood.cast.MediaKind
import com.rovian.mood.cast.PlaybackState
import com.rovian.mood.cast.PlayerStatus
import com.rovian.mood.cast.RepeatMode
import com.rovian.mood.cast.endpoint
import com.rovian.mood.ui.components.BitmapImage
import com.rovian.mood.ui.components.Glyph
import com.rovian.mood.ui.components.GlyphButton
import com.rovian.mood.ui.components.RemoteImage
import com.rovian.mood.ui.components.liquidGlass
import com.rovian.mood.ui.player.MoodPlayerChrome
import com.rovian.mood.ui.player.PlayerAbilities
import com.rovian.mood.ui.player.PlayerActions
import com.rovian.mood.ui.player.PlayerChromeState
import com.rovian.mood.ui.theme.MoodColors

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

/**
 * The phone as the remote for whatever is on the TV, in Mood's player design.
 * Every control maps to a real receiver command and appears only if the
 * receiver reports it can execute it.
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
    val chrome = remember { PlayerChromeState() }
    val endpoint = connection.endpoint
    val receiver = endpoint?.name ?: "TV"
    val p = playback
    val status = p?.status
    val playing = status == PlayerStatus.PLAYING || status == PlayerStatus.BUFFERING
    val isImage = p?.kind == MediaKind.IMAGE

    MoodPlayerChrome(
        state = chrome,
        positionMs = p?.positionMs ?: 0,
        durationMs = p?.durationMs?.takeIf { !isImage },
        playing = playing,
        abilities = PlayerAbilities(
            playPause = p != null && ((playing && p.can(Command.PAUSE)) || (!playing && p.can(Command.PLAY))),
            seek = p?.can(Command.SEEK) == true,
            previous = p?.can(Command.PREVIOUS) == true,
            next = p?.can(Command.NEXT) == true,
            shuffle = p?.can(Command.SHUFFLE) == true,
            repeat = p?.can(Command.REPEAT) == true,
        ),
        actions = PlayerActions(
            togglePlay = actions::togglePlay,
            seekTo = actions::seekTo,
            seekBy = actions::seekBy,
            previous = actions::previous,
            next = actions::next,
            shuffle = actions::toggleShuffle,
            repeat = actions::cycleRepeat,
        ),
        holdVisible = preparing != null || status == PlayerStatus.LOADING || status == PlayerStatus.ERROR ||
            connection !is ConnectionState.Connected || p == null,
        shuffleOn = p?.repeat == RepeatMode.SHUFFLE,
        repeatOn = p?.repeat == RepeatMode.ALL || p?.repeat == RepeatMode.ONE,
        repeatOne = p?.repeat == RepeatMode.ONE,
        topEnd = {
            Text(
                "This phone → $receiver",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.liquidGlass(RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp),
            )
            Spacer(Modifier.width(8.dp))
            GlyphButton(Glyph.ChevronDown, "Close controller", actions::close, Modifier.liquidGlass(RoundedCornerShape(50)), size = 40.dp)
        },
        aboveBar = {
            Text(p?.title ?: preparing ?: "Connected to $receiver", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(statusLine(connection, p, preparing, receiver), color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
            p?.error?.let { Text(it, color = MoodColors.Orange, fontSize = 13.sp) }
            fitNote?.takeIf { p?.kind == MediaKind.VIDEO && endpoint?.protocol != CastProtocol.SCREEN }?.let {
                Text("MoodFit · $it", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
            }
            if (endpoint?.protocol == CastProtocol.SCREEN && p != null) {
                Text(
                    "Playing natively on the TV at ${endpoint.model ?: "its own resolution"} · sound from the TV",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                )
            }
        },
        belowControls = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Volume(p, actions, chrome::poke, Modifier.weight(1f))
                if (p?.can(Command.STOP) == true && isImage) {
                    TextButton(onClick = actions::stop) { Text("Stop", color = Color.White) }
                }
                TextButton(onClick = actions::disconnect) { Text("Disconnect", color = MoodColors.Red) }
            }
        },
    ) {
        // The picture on the phone: what's playing on the TV.
        val artUrl = p?.media?.artworkUrl
        when {
            localArtwork != null -> BitmapImage(localArtwork, null, Modifier.fillMaxSize())
            artUrl != null -> RemoteImage(artUrl, null, Modifier.fillMaxSize())
            else -> Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(MoodColors.Red.copy(alpha = 0.35f), MoodColors.Night), radius = 1400f),
                ),
            )
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
private fun Volume(p: PlaybackState?, a: ControllerActions, poke: () -> Unit, modifier: Modifier) {
    val level = p?.volume
    if (p == null || !p.can(Command.VOLUME) || level == null) {
        Spacer(modifier)
        return
    }
    var local by remember { mutableStateOf<Float?>(null) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (p.can(Command.MUTE)) {
            val muted = p.muted == true
            GlyphButton(if (muted) Glyph.VolumeOff else Glyph.Volume, if (muted) "Unmute" else "Mute", { poke(); a.setMuted(!muted) }, size = 40.dp, iconSize = 20.dp)
        }
        Slider(
            value = local ?: level,
            onValueChange = { local = it; poke() },
            onValueChangeFinished = { local?.let(a::setVolume); local = null },
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
            ),
        )
    }
}
