package com.rovian.mood.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.rovian.mood.cast.CastMedia
import com.rovian.mood.cast.CastProtocol
import com.rovian.mood.cast.Command
import com.rovian.mood.cast.ConnectionState
import com.rovian.mood.cast.DiscoveryPhase
import com.rovian.mood.cast.MediaKind
import com.rovian.mood.cast.MediaSource
import com.rovian.mood.cast.MoodDevice
import com.rovian.mood.cast.PlaybackState
import com.rovian.mood.cast.PlayerStatus
import com.rovian.mood.cast.ReceiverCapabilities
import com.rovian.mood.cast.ReceiverEndpoint
import com.rovian.mood.cast.ReceiverEndpoint as Ep
import com.rovian.mood.cast.ReceiverKind
import com.rovian.mood.catalog.OpenFilmsCatalog
import com.rovian.mood.net.NetworkState
import com.rovian.mood.ui.catalog.CatalogScreen
import com.rovian.mood.ui.components.Glyph
import com.rovian.mood.ui.components.ImageLoader
import com.rovian.mood.ui.components.MoodIcon
import com.rovian.mood.ui.components.liquidGlass
import com.rovian.mood.ui.controller.ControllerActions
import com.rovian.mood.ui.controller.ControllerScreen
import com.rovian.mood.ui.screens.ScreensActions
import com.rovian.mood.ui.screens.ScreensScreen
import com.rovian.mood.ui.screens.ScreensState
import com.rovian.mood.ui.theme.MoodColors
import com.rovian.mood.ui.theme.MoodTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * Renders MoodPlay's screens for visual review. Network images are replaced by
 * generated artwork; every state shown is a state the real models can be in.
 */
class UiScreenshots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.NoActionBar",
    )

    private val home = runBlocking { OpenFilmsCatalog().home() }

    private fun primeArtwork() {
        val all = home.rows.flatMap { it.movies } + home.featured
        all.forEachIndexed { i, m ->
            val art = artwork(1280, 720, i)
            ImageLoader.prime(m.backdropUrl, art)
            ImageLoader.prime(m.posterUrl, art)
        }
    }

    private fun artwork(w: Int, h: Int, seed: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val palettes = listOf(
            intArrayOf(0xFF2B3A4A.toInt(), 0xFF0E1116.toInt()),
            intArrayOf(0xFF4A2B2B.toInt(), 0xFF120C0C.toInt()),
            intArrayOf(0xFF2F4A2B.toInt(), 0xFF0C120C.toInt()),
            intArrayOf(0xFF3C2B4A.toInt(), 0xFF0F0C14.toInt()),
        )
        val p = palettes[seed % palettes.size]
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), p[0], p[1], Shader.TileMode.CLAMP) })
        c.drawPaint(
            Paint().apply {
                shader = RadialGradient(w * 0.68f, h * 0.38f, h * 0.55f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            },
        )
        return bmp
    }

    private val livingRoom = Ep(
        CastProtocol.GOOGLE_CAST, "r1", "Living Room TV", "Google TV Streamer", ReceiverKind.STREAMER,
        ReceiverCapabilities(video = true, audio = true, image = true, volumeControl = true),
    )
    private val livingRoomDlna = livingRoom.copy(protocol = CastProtocol.DLNA, id = "uuid:1", model = "Samsung UE55")
    private val bedroom = Ep(
        CastProtocol.DLNA, "uuid:2", "[TV] Bedroom", "Samsung Electronics UE43", ReceiverKind.TV,
        ReceiverCapabilities(video = true, audio = true, image = true, declaredMimeTypes = setOf("video/mp4")),
    )
    private val kitchen = Ep(
        CastProtocol.GOOGLE_CAST, "r3", "Kitchen speaker", "Nest Audio", ReceiverKind.SPEAKER,
        ReceiverCapabilities(video = false, audio = true, image = false),
    )
    private val devices = listOf(
        MoodDevice("bedroom", bedroom.name, listOf(bedroom)),
        MoodDevice("kitchen", kitchen.name, listOf(kitchen)),
        MoodDevice("living", livingRoom.name, listOf(livingRoom, livingRoomDlna)),
    )

    private fun screens(connection: ConnectionState, playback: PlaybackState? = null) = ScreensState(
        network = NetworkState.LocalNetwork("Wi-Fi"),
        discovery = DiscoveryPhase.FOUND,
        searching = true,
        devices = devices,
        recents = emptyList(),
        connection = connection,
        playback = playback,
        preparing = null,
        availability = emptyMap(),
        externalDisplay = null,
    )

    private val noScreens = object : ScreensActions {
        override fun connect(endpoint: ReceiverEndpoint) = Unit
        override fun disconnect() = Unit
        override fun dismissConnectionMessage() = Unit
        override fun searchAgain() = Unit
        override fun troubleshoot() = Unit
        override fun pickVideo() = Unit
        override fun pickPhotos() = Unit
        override fun pickMusic() = Unit
        override fun enterLink() = Unit
        override fun openController() = Unit
        override fun mirror() = Unit
    }

    private val noController = object : ControllerActions {
        override fun togglePlay() = Unit
        override fun seekTo(ms: Long) = Unit
        override fun seekBy(deltaMs: Long) = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun stop() = Unit
        override fun cycleRepeat() = Unit
        override fun toggleShuffle() = Unit
        override fun setVolume(level: Float) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun disconnect() = Unit
        override fun close() = Unit
    }

    @Composable
    private fun Frame(wide: Boolean, content: @Composable () -> Unit) {
        MoodTheme {
            Box(Modifier.fillMaxSize().background(MoodColors.Night)) {
                content()
                if (wide) {
                    Box(
                        Modifier.align(Alignment.CenterStart).padding(start = 16.dp)
                            .liquidGlass(RoundedCornerShape(50)).padding(8.dp),
                    ) {
                        androidx.compose.foundation.layout.Column {
                            listOf(Glyph.Home, Glyph.Search, Glyph.Screen).forEach {
                                Box(Modifier.padding(11.dp)) { MoodIcon(it, size = 22.dp) }
                            }
                        }
                    }
                } else {
                    androidx.compose.foundation.layout.Row(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)
                            .liquidGlass(RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        listOf(Glyph.Home, Glyph.Search, Glyph.Screen).forEach {
                            Box(Modifier.padding(11.dp)) { MoodIcon(it, size = 22.dp) }
                        }
                    }
                }
                Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).liquidGlass(RoundedCornerShape(50)).padding(10.dp)) {
                    MoodIcon(Glyph.Screen, size = 20.dp)
                }
            }
        }
    }

    @Test
    fun home_phone() {
        primeArtwork()
        paparazzi.snapshot {
        Frame(wide = false) { CatalogScreen(home, null, "Watch", 24.dp, {}, {}) }
        }
    }

    @Test
    fun home_tablet() {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_C.copy(orientation = ScreenOrientation.LANDSCAPE))
        primeArtwork()
        paparazzi.snapshot { Frame(wide = true) { CatalogScreen(home, null, "Watch", 104.dp, {}, {}) } }
    }

    @Test
    fun screens_searching_connected() {
        primeArtwork()
        paparazzi.snapshot {
        Frame(wide = false) { ScreensScreen(screens(ConnectionState.Connected(livingRoom)), noScreens, 24.dp) }
        }
    }

    @Test
    fun screens_failed() {
        primeArtwork()
        paparazzi.snapshot {
        Frame(wide = false) {
            ScreensScreen(
                screens(ConnectionState.Failed(bedroom, "[TV] Bedroom didn't answer. Make sure it's on, and that media sharing (DLNA) is allowed in the TV's settings.")),
                noScreens,
                24.dp,
            )
        }
        }
    }

    private val movie = home.featured
    private val playing = PlaybackState(
        title = movie.title,
        kind = MediaKind.VIDEO,
        status = PlayerStatus.PLAYING,
        positionMs = 252_000,
        durationMs = 734_000,
        commands = setOf(Command.PLAY, Command.PAUSE, Command.SEEK, Command.STOP, Command.VOLUME, Command.MUTE),
        volume = 0.4f,
        muted = false,
        media = CastMedia(MediaKind.VIDEO, movie.title, "video/mp4", MediaSource.Remote(movie.streams[0].url), artworkUrl = movie.backdropUrl),
    )

    @Test
    fun controller_cast_video() {
        primeArtwork()
        paparazzi.snapshot {
        MoodTheme {
            ControllerScreen(
                ConnectionState.Connected(livingRoom), playing, null,
                "Adaptive stream — Living Room TV picks the best quality for its screen and connection.", null, noController,
            )
        }
        }
    }

    @Test
    fun controller_dlna_no_pause() {
        primeArtwork()
        paparazzi.snapshot {
        MoodTheme {
            ControllerScreen(
                ConnectionState.Connected(bedroom),
                playing.copy(commands = setOf(Command.PLAY, Command.STOP, Command.NEXT, Command.PREVIOUS, Command.REPEAT), queueSize = 3),
                null, null, null, noController,
            )
        }
        }
    }
}
