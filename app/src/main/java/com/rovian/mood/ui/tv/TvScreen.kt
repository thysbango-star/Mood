package com.rovian.mood.ui.tv

import android.graphics.Bitmap
import android.net.Uri
import android.view.Display
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.rovian.mood.brand.MoodWordmark
import com.rovian.mood.cast.MediaKind
import com.rovian.mood.cast.PlayerStatus
import com.rovian.mood.cast.ResolvedMedia
import com.rovian.mood.cast.mirror.DisplayAdapter
import com.rovian.mood.cast.mirror.TvPlayerBridge
import com.rovian.mood.cast.mirror.TvScene
import com.rovian.mood.catalog.Movie
import com.rovian.mood.ui.catalog.meta
import com.rovian.mood.ui.components.CapsuleProgress
import com.rovian.mood.ui.components.MoodGradient
import com.rovian.mood.ui.components.formatTime
import com.rovian.mood.ui.components.RemoteImage
import com.rovian.mood.ui.components.liquidGlass
import com.rovian.mood.ui.theme.MoodColors
import kotlinx.coroutines.delay

/**
 * MoodPlay as it appears on a mirrored TV: designed for a wide screen viewed
 * from across the room, not a copy of the phone. The phone is the remote.
 */
@Composable
fun TvScreen(adapter: DisplayAdapter, display: Display) {
    val scene by adapter.scene.collectAsState()
    val home by adapter.home.collectAsState()
    val focus by adapter.focus.collectAsState()
    val mode = display.mode
    val resolution = "${mode.physicalWidth}×${mode.physicalHeight}"

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = scene) {
            TvScene.Home -> TvHome(focus ?: home?.featured, home?.rows?.firstOrNull()?.movies.orEmpty(), resolution)
            is TvScene.Playing -> TvPlaying(s, adapter, maxOf(mode.physicalWidth, mode.physicalHeight))
        }
    }
}

@Composable
internal fun TvHome(hero: Movie?, row: List<Movie>, resolution: String) {
    Box(Modifier.fillMaxSize()) {
        if (hero != null) {
            RemoteImage(hero.backdropUrl, null, Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(MoodColors.Red.copy(alpha = 0.3f), Color.Black), radius = 1800f)))
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.8f), 0.6f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black)))

        Row(Modifier.fillMaxWidth().padding(horizontal = 56.dp, vertical = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            MoodWordmark(Modifier.width(150.dp), letterColor = Color.White)
            Spacer(Modifier.weight(1f))
            Text(
                "Controlled from your phone · $resolution",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 16.sp,
                modifier = Modifier.liquidGlass(RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        Column(Modifier.align(Alignment.BottomStart).padding(start = 72.dp, end = 72.dp, bottom = 48.dp)) {
            if (hero != null) {
                Text(hero.title, color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Bold, lineHeight = 76.sp)
                Spacer(Modifier.height(12.dp))
                Text(hero.meta(), color = Color.White.copy(alpha = 0.85f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Text(
                    hero.synopsis,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 820.dp),
                )
                Spacer(Modifier.height(36.dp))
            } else {
                Text("Ready", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Bold)
                Text("Pick something on your phone to play it here.", color = Color.White.copy(alpha = 0.8f), fontSize = 22.sp)
                Spacer(Modifier.height(36.dp))
            }
            if (row.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    row.take(5).forEach { m ->
                        val focused = m.id == hero?.id
                        Column(Modifier.width(260.dp)) {
                            RemoteImage(
                                m.posterUrl,
                                m.title,
                                Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(20.dp))
                                    .then(if (focused) Modifier.border(3.dp, MoodColors.Orange, RoundedCornerShape(20.dp)) else Modifier),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(m.title, color = if (focused) Color.White else Color.White.copy(alpha = 0.7f), fontSize = 18.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvPlaying(scene: TvScene.Playing, adapter: DisplayAdapter, maxSide: Int) {
    val item = scene.item
    var showTitle by remember(scene.token) { mutableStateOf(true) }
    LaunchedEffect(scene.token) {
        delay(4_000)
        showTitle = false
    }
    Box(Modifier.fillMaxSize()) {
        when (item.media.kind) {
            MediaKind.IMAGE -> {
                val bitmap by produceState<Bitmap?>(null, item.url) { value = adapter.loadImage(item.url, maxSide) }
                bitmap?.let {
                    Image(it.asImageBitmap(), item.media.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
            MediaKind.VIDEO -> TvVideo(item, scene.token, adapter.bridge, Modifier.fillMaxSize())
            MediaKind.AUDIO -> {
                val art = item.media.artworkUrl
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(MoodColors.Red.copy(alpha = 0.35f), Color.Black), radius = 1600f)))
                if (art != null) RemoteImage(art, null, Modifier.align(Alignment.Center).size(420.dp).clip(RoundedCornerShape(28.dp)))
                else Box(Modifier.align(Alignment.Center).size(width = 320.dp, height = 110.dp).clip(RoundedCornerShape(50)).background(MoodGradient))
                // Audio plays through the same player; it needs a surface but nothing to show.
                TvVideo(item, scene.token, adapter.bridge, Modifier.size(2.dp))
                Column(Modifier.align(Alignment.BottomStart).padding(64.dp)) {
                    Text(item.media.title, color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    item.media.subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 24.sp) }
                }
            }
        }
        val playback by adapter.playback.collectAsState()
        val pb = playback
        AnimatedVisibility(
            visible = item.media.kind != MediaKind.IMAGE && (showTitle || pb?.status != PlayerStatus.PLAYING),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 96.dp, vertical = 28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.media.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        when (pb?.status) {
                            PlayerStatus.PAUSED -> "Paused"
                            PlayerStatus.LOADING, PlayerStatus.BUFFERING -> "Loading…"
                            PlayerStatus.ERROR -> pb.error ?: "Playback problem"
                            else -> ""
                        },
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 20.sp,
                    )
                }
                val d = pb?.durationMs
                if (pb != null && d != null && d > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(pb.positionMs), color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp)
                        Box(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                            CapsuleProgress(pb.positionMs, d, seekable = false, onSeek = {}, onInteract = {})
                        }
                        Text(formatTime(d), color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = showTitle && item.media.kind != MediaKind.AUDIO,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Row(Modifier.padding(48.dp), verticalAlignment = Alignment.CenterVertically) {
                MoodWordmark(Modifier.width(120.dp), letterColor = Color.White)
                Spacer(Modifier.width(24.dp))
                Text(item.media.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TvVideo(item: ResolvedMedia, token: Long, bridge: TvPlayerBridge, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            VideoView(ctx).apply {
                setOnPreparedListener { mp ->
                    if (bridge.resumeAtMs > 0) seekTo(bridge.resumeAtMs.toInt())
                    start()
                    bridge.onPrepared(mp.duration.toLong())
                }
                setOnCompletionListener { bridge.onCompleted() }
                setOnErrorListener { _, what, _ -> bridge.onError(what); true }
                bridge.view = this
            }
        },
        update = { v ->
            if (v.tag != token) {
                v.tag = token
                v.setVideoURI(Uri.parse(item.url))
            }
        },
        onRelease = { v ->
            v.stopPlayback()
            if (bridge.view === v) bridge.view = null
        },
    )
}
