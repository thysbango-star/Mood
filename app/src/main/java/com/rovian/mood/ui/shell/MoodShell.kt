package com.rovian.mood.ui.shell

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovian.mood.cast.ConnectionState
import com.rovian.mood.cast.MediaSource
import com.rovian.mood.cast.MoodCastEngine
import com.rovian.mood.cast.ReceiverEndpoint
import com.rovian.mood.cast.endpoint
import com.rovian.mood.cast.hasSession
import com.rovian.mood.cast.media.MediaResolver
import com.rovian.mood.cast.mirror.DisplayAdapter
import com.rovian.mood.cast.mirror.SystemMirroring
import com.rovian.mood.cast.CastProtocol
import com.rovian.mood.catalog.CatalogHome
import com.rovian.mood.catalog.Movie
import com.rovian.mood.catalog.MovieCatalog
import com.rovian.mood.net.NetworkMonitor
import com.rovian.mood.diagnostics.NetworkCheck
import com.rovian.mood.ui.catalog.CatalogScreen
import com.rovian.mood.ui.catalog.GlassPill
import com.rovian.mood.ui.catalog.SearchScreen
import com.rovian.mood.ui.catalog.meta
import com.rovian.mood.ui.components.Glyph
import com.rovian.mood.ui.components.MoodIcon
import com.rovian.mood.ui.components.RemoteImage
import com.rovian.mood.ui.components.liquidGlass
import com.rovian.mood.ui.controller.ControllerActions
import com.rovian.mood.ui.controller.ControllerScreen
import com.rovian.mood.ui.player.PhonePlayer
import com.rovian.mood.ui.screens.DeviceList
import com.rovian.mood.ui.screens.ScreensActions
import com.rovian.mood.ui.screens.ScreensScreen
import com.rovian.mood.ui.screens.ScreensState
import com.rovian.mood.ui.theme.MoodColors
import com.rovian.mood.ui.troubleshoot.TroubleshootSheet

enum class Tab(val label: String, val glyph: Glyph) {
    HOME("Home", Glyph.Home),
    SEARCH("Search", Glyph.Search),
    SCREENS("Screens", Glyph.Screen),
}

/** Things only the Activity can do (system pickers, settings). */
interface PlatformActions {
    fun pickVideo()
    fun pickPhotos()
    fun pickMusic()
    fun openMirroring()
    fun openWifiSettings()
    fun replayIntro()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodShell(
    engine: MoodCastEngine,
    network: NetworkMonitor,
    catalog: MovieCatalog,
    resolver: MediaResolver,
    mirroring: SystemMirroring,
    networkCheck: NetworkCheck,
    tvDisplay: DisplayAdapter,
    platform: PlatformActions,
    showController: Boolean,
    onShowController: (Boolean) -> Unit,
) {
    val connection by engine.connection.collectAsState()
    val playback by engine.playback.collectAsState()
    val preparing by engine.preparing.collectAsState()
    val fitNote by engine.fitNote.collectAsState()
    val devices by engine.devices.collectAsState()
    val discovery by engine.discovery.collectAsState()
    val searching by engine.isSearching.collectAsState()
    val recents by engine.recentDevices.collectAsState()
    val availability by engine.availability.collectAsState()
    val networkState by network.state.collectAsState()
    val external by mirroring.externalDisplay.collectAsState()

    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var infoMovie by remember { mutableStateOf<Movie?>(null) }
    var pickFor by remember { mutableStateOf<Movie?>(null) }
    var pendingMovie by remember { mutableStateOf<Movie?>(null) }
    var phoneMovie by remember { mutableStateOf<Movie?>(null) }
    var showTroubleshoot by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }

    val home by produceState<Result<CatalogHome>?>(null, catalog) { value = runCatching { catalog.home() } }
    val results by produceState(emptyList<Movie>(), query) { value = runCatching { catalog.search(query) }.getOrDefault(emptyList()) }

    LaunchedEffect(Unit) { engine.messages.collect { snackbar.showSnackbar(it) } }

    // Keep the TV view in step with the phone.
    LaunchedEffect(home) { tvDisplay.home.value = home?.getOrNull() }
    LaunchedEffect(infoMovie) { infoMovie?.let { tvDisplay.focus.value = it } }

    // Screen mirroring detected → give the TV MoodPlay's own big-screen view,
    // unless the user is already casting somewhere else.
    val mirroredScreen = devices.flatMap { it.endpoints }.firstOrNull { it.protocol == CastProtocol.SCREEN }
    LaunchedEffect(mirroredScreen?.id) {
        val ep = mirroredScreen ?: return@LaunchedEffect
        val current = engine.connection.value
        if (!current.hasSession && current !is ConnectionState.Connecting) {
            engine.connect(ep)
            snackbar.showSnackbar("Mirroring to ${ep.name} — the TV now shows MoodPlay's big-screen view.")
        }
    }

    // While the user is choosing a screen, scan actively (as Google recommends for
    // device pickers); the rest of the time, cheaper passive discovery runs.
    val choosing = tab == Tab.SCREENS || pickFor != null
    LaunchedEffect(choosing) { engine.startDiscovery(activeScan = choosing) }

    // Watch → pick a screen → cast as soon as the connection is real.
    LaunchedEffect(connection, pendingMovie) {
        val movie = pendingMovie ?: return@LaunchedEffect
        when (connection) {
            is ConnectionState.Connected -> {
                pendingMovie = null
                engine.castMovie(movie)
                onShowController(true)
            }
            is ConnectionState.Failed, is ConnectionState.Disconnected -> pendingMovie = null
            else -> Unit
        }
    }

    val connectedName = (connection as? ConnectionState.Connected)?.endpoint?.name

    fun watch(movie: Movie) {
        infoMovie = null
        if (connection is ConnectionState.Connected) {
            engine.castMovie(movie)
            onShowController(true)
        } else {
            pickFor = movie
            engine.startDiscovery()
        }
    }

    val screensActions = remember(engine, platform) {
        object : ScreensActions {
            override fun connect(endpoint: ReceiverEndpoint) = engine.connect(endpoint)
            override fun disconnect() = engine.disconnect()
            override fun dismissConnectionMessage() = engine.acknowledge()
            override fun searchAgain() = engine.searchAgain()
            override fun troubleshoot() { showTroubleshoot = true }
            override fun pickVideo() = platform.pickVideo()
            override fun pickPhotos() = platform.pickPhotos()
            override fun pickMusic() = platform.pickMusic()
            override fun enterLink() { linkDialog = true }
            override fun openController() = onShowController(true)
            override fun mirror() = platform.openMirroring()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MoodColors.Night)) {
        val wide = maxWidth >= 640.dp
        val contentStart = if (wide) 104.dp else 24.dp
        when (tab) {
            Tab.HOME -> CatalogScreen(
                home = home?.getOrNull(),
                error = home?.exceptionOrNull()?.let { "Couldn't load movies. Check your connection." },
                watchLabel = "Watch",
                contentStart = contentStart,
                onWatch = ::watch,
                onInfo = { infoMovie = it },
            )
            Tab.SEARCH -> SearchScreen(query, { query = it }, results, contentStart, onOpen = { infoMovie = it })
            Tab.SCREENS -> ScreensScreen(
                ScreensState(networkState, discovery, searching, devices, recents, connection, playback, preparing, availability, external),
                screensActions,
                contentStart,
            )
        }

        // Top-right: live connection chip
        if (tab != Tab.SCREENS) {
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)) {
                ConnectionChip(connection) { tab = Tab.SCREENS }
            }
        }

        if (wide) {
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = 16.dp).liquidGlass(RoundedCornerShape(50)).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { Tab.entries.forEach { NavItem(it, tab == it) { tab = it } } }
        } else {
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp)
                    .liquidGlass(RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) { Tab.entries.forEach { NavItem(it, tab == it) { tab = it } } }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 76.dp))

        if (showController && connection.endpoint != null) {
            val media = playback?.media
            val localArt by produceState<Bitmap?>(null, media?.id) {
                value = media?.takeIf { it.source is MediaSource.Local }?.let { resolver.artwork(it) }
            }
            ControllerScreen(
                connection, playback, preparing, fitNote, localArt,
                remember(engine) {
                    object : ControllerActions {
                        override fun togglePlay() = engine.togglePlay()
                        override fun seekTo(ms: Long) = engine.seekTo(ms)
                        override fun seekBy(deltaMs: Long) = engine.seekBy(deltaMs)
                        override fun next() = engine.next()
                        override fun previous() = engine.previous()
                        override fun stop() = engine.stopMedia()
                        override fun cycleRepeat() = engine.cycleRepeat()
                        override fun toggleShuffle() = engine.toggleShuffle()
                        override fun setVolume(level: Float) = engine.setVolume(level)
                        override fun setMuted(muted: Boolean) = engine.setMuted(muted)
                        override fun disconnect() { engine.disconnect(); onShowController(false) }
                        override fun close() = onShowController(false)
                    }
                },
            )
        }
        phoneMovie?.let { m ->
            val tvName = (connection as? ConnectionState.Connected)?.endpoint
                ?.takeIf { it.protocol == CastProtocol.SCREEN }?.name
            PhonePlayer(
                urls = m.streams.sortedBy { it.adaptive }.map { it.url },
                title = m.title,
                subtitle = m.meta(),
                tvName = tvName,
                onHandOffToTv = { at ->
                    phoneMovie = null
                    engine.castMovie(m, startAtMs = at)
                    onShowController(true)
                },
                onClose = { phoneMovie = null },
            )
        }
    }

    infoMovie?.let { movie ->
        ModalBottomSheet(onDismissRequest = { infoMovie = null }, containerColor = MoodColors.Charcoal) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding()) {
                RemoteImage(movie.backdropUrl, movie.title, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(20.dp)))
                Spacer(Modifier.height(16.dp))
                Text(movie.title, color = MoodColors.Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(movie.meta(), color = MoodColors.InkSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Text(movie.synopsis, color = MoodColors.Ink, fontSize = 15.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(8.dp))
                Text(movie.credit, color = MoodColors.InkSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassPill(onClick = { watch(movie) }) {
                        MoodIcon(Glyph.Screen, size = 20.dp, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(connectedName?.let { "Watch on $it" } ?: "Watch on TV", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    GlassPill(onClick = {
                        infoMovie = null
                        if ((connection as? ConnectionState.Connected)?.endpoint?.protocol == CastProtocol.SCREEN) watch(movie) else phoneMovie = movie
                    }) {
                        Text("Play on phone", color = Color.White, fontSize = 16.sp)
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    pickFor?.let { movie ->
        ModalBottomSheet(onDismissRequest = { pickFor = null }, containerColor = MoodColors.Charcoal) {
            Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Watch ${movie.title} on…", color = MoodColors.Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (devices.isEmpty()) {
                    Text(
                        if (searching) "Looking for screens on your Wi-Fi…" else "No screens found.",
                        color = MoodColors.InkSecondary,
                        fontSize = 14.sp,
                    )
                }
                DeviceList(devices, connection) { ep ->
                    pendingMovie = movie
                    pickFor = null
                    engine.connect(ep)
                    tab = Tab.SCREENS
                }
                Row {
                    TextButton(onClick = { pickFor = null; phoneMovie = movie }) { Text("Play on this phone", color = MoodColors.Orange) }
                    TextButton(onClick = { pickFor = null; showTroubleshoot = true }) { Text("Don't see your TV?", color = MoodColors.InkSecondary) }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showTroubleshoot) {
        TroubleshootSheet(
            networkState,
            availability,
            onSearchAgain = { engine.searchAgain(); showTroubleshoot = false },
            onWifiSettings = platform::openWifiSettings,
            onDismiss = { showTroubleshoot = false },
            runNetworkCheck = networkCheck::run,
        )
    }

    if (linkDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { linkDialog = false },
            title = { Text("Cast a link") },
            text = {
                OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("https://…/video.mp4") })
            },
            confirmButton = {
                TextButton(onClick = {
                    linkDialog = false
                    engine.castUrl(text)
                    onShowController(true)
                }) { Text("Cast") }
            },
            dismissButton = { TextButton(onClick = { linkDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NavItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = tab.label },
        contentAlignment = Alignment.Center,
    ) {
        MoodIcon(tab.glyph, size = 22.dp, tint = if (selected) Color.White else Color.White.copy(alpha = 0.7f))
    }
}

@Composable
private fun ConnectionChip(connection: ConnectionState, onClick: () -> Unit) {
    val ep = connection.endpoint
    val (label, color) = when (connection) {
        is ConnectionState.Connected -> ep!!.name to MoodColors.Orange
        is ConnectionState.Connecting -> "Connecting…" to MoodColors.InkSecondary
        is ConnectionState.Reconnecting -> "Reconnecting…" to MoodColors.InkSecondary
        else -> null to Color.White
    }
    Row(
        Modifier.liquidGlass(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .semantics { contentDescription = label?.let { "Screen: $it" } ?: "Choose a screen" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MoodIcon(Glyph.Screen, size = 20.dp, tint = color)
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1)
        }
    }
}
