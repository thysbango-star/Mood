package com.rovian.mood.ui.player

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.rovian.mood.ui.components.Glyph
import com.rovian.mood.ui.components.GlyphButton
import com.rovian.mood.ui.components.liquidGlass
import com.rovian.mood.ui.theme.MoodColors
import kotlinx.coroutines.delay

/**
 * Watching on the phone, in Mood's own player. The platform decoder renders the
 * picture; everything you see and touch is Mood's design.
 *
 * If the phone starts being mirrored to a TV while this plays ([tvName] becomes
 * non-null), playback is handed to the TV at the current position — the TV then
 * plays the film itself at its own resolution, with sound from the TV.
 */
@Composable
fun PhonePlayer(
    urls: List<String>,
    title: String,
    subtitle: String?,
    tvName: String?,
    onHandOffToTv: (positionMs: Long) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val chrome = remember { PlayerChromeState() }
    var view by remember { mutableStateOf<VideoView?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Real playback state, read from the player.
    LaunchedEffect(view) {
        val v = view ?: return@LaunchedEffect
        while (true) {
            position = v.currentPosition.toLong()
            playing = v.isPlaying
            delay(250)
        }
    }

    // Mirroring started → the TV takes over, like a big-screen hand-off.
    LaunchedEffect(tvName, prepared) {
        if (tvName != null && prepared) {
            val at = view?.currentPosition?.toLong() ?: 0L
            view?.pause()
            onHandOffToTv(at)
        }
    }

    MoodPlayerChrome(
        state = chrome,
        positionMs = position,
        durationMs = duration.takeIf { it > 0 },
        playing = playing,
        abilities = PlayerAbilities(playPause = prepared, seek = prepared && duration > 0, repeat = prepared),
        actions = PlayerActions(
            togglePlay = { view?.let { if (it.isPlaying) it.pause() else it.start() } },
            seekTo = { view?.seekTo(it.toInt()) },
            seekBy = { d -> view?.let { it.seekTo((it.currentPosition + d).coerceIn(0L, duration).toInt()) } },
            repeat = { repeat = !repeat },
        ),
        holdVisible = !playing || error != null,
        repeatOn = repeat,
        repeatOne = repeat,
        topEnd = {
            GlyphButton(Glyph.Close, "Close player", onClose, Modifier.liquidGlass(RoundedCornerShape(50)), size = 40.dp)
        },
        aboveBar = {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp) }
            val e = error
            when {
                e != null -> Text(e, color = MoodColors.Orange, fontSize = 13.sp)
                !prepared -> Text("Loading…", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            }
        },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize().align(Alignment.Center),
            factory = { ctx ->
                VideoView(ctx).apply {
                    var index = 0
                    setOnPreparedListener { mp ->
                        duration = mp.duration.toLong()
                        prepared = true
                        start()
                    }
                    setOnCompletionListener {
                        if (repeat) {
                            seekTo(0)
                            start()
                        }
                    }
                    setOnErrorListener { _, _, _ ->
                        // Fall back to the next stream (e.g. MP4 if the adaptive one fails).
                        index++
                        prepared = false
                        if (index < urls.size) setVideoURI(Uri.parse(urls[index]))
                        else error = "$title isn't available from its host right now."
                        true
                    }
                    setVideoURI(Uri.parse(urls.first()))
                    view = this
                }
            },
            onRelease = { it.stopPlayback() },
        )
    }
}
