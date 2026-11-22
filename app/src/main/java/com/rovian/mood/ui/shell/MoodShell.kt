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
import com.rovian.mood.cast.mirror.MediaSessionWatcher
import com.rovian.mood.library.LibraryItem
import com.rovian.mood.library.LibraryStore
import com.rovian.mood.ui.library.LibraryScreen
import com.rovian.mood.cast.MediaKind
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.rovian.mood.ui.catalog.ratingLabel
import com.rovian.mood.ui.components.LocalHazeState
import com.rovian.mood.ui.components.floatingGlass
import com.rovian.mood.brand.MoodWordmark
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.Crossfade
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    LIBRARY("Library", Glyph.Library),
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
    fun enableMediaAccess()
    fun addToLibrary()
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
    mediaWatcher: MediaSessionWatcher,
    library: LibraryStore,
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
    val otherApp by mediaWatcher.active.collectAsState()
    val mediaAccess by mediaWatcher.accessGranted.collectAsState()

    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var infoMovie by remember { mutableStateOf<Movie?>(null) }
    var pickFor by remember { mutableStateOf<Movie?>(null) }
    var pendingMovie by remember { mutableStateOf<Movie?>(null) }
    var phonePlayback by remember { mutableStateOf<PhonePlayback?>(null) }
    var photo by remember { mutableStateOf<LibraryItem?>(null) }
    var libraryItem by remember { mutableStateOf<LibraryItem?>(null) }
    val libraryItems by library.items.collectAsState()
    val importing by library.importing.collectAsState()
    var showTroubleshoot by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haze = remember { HazeState() }
    var ambientUrl by remember { mutableStateOf<String?>(null) }
    var searchingCatalog by remember { mutableStateOf(false) }

    val home by produceState<Result<CatalogHome>?>(null, catalog) { value = runCatching { catalog.home() } }
    val results by produceState(emptyList<Movie>(), query) {
        if (query.isBlank()) {
            value = emptyList()
            return@produceState
        }
        searchingCatalog = true
        delay(350) // wait for typing to settle
        value = runCatching { catalog.search(query) }.getOrDefault(emptyList())
        searchingCatalog = false
    }

    /** Fetches playable files/details on demand (the archive lists them per film). */
    suspend fun withDetails(movie: Movie): Movie? {
        val full = runCatching { catalog.details(movie) }.getOrElse {
            snackbar.showSnackbar("Couldn't open ${movie.title}. Check your internet connection.")
            return null
        }
        if (full.streams.isEmpty()) {
            snackbar.showSnackbar("${movie.title} has no playable file on ${catalog.providerName}.")
            return null
        }
        return full
    }

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

    fun watch(movie: Movie, chooseScreen: Boolean = false) {
        infoMovie = null
        scope.launch {
            val full = withDetails(movie) ?: return@launch
            if (connection is ConnectionState.Connected && !chooseScreen) {
                engine.castMovie(full)
                onShowController(true)
            } else {
                pickFor = full
                engine.startDiscovery()
            }
        }
    }

    fun playOnPhone(movie: Movie) {
        infoMovie = null
        scope.launch {
            val full = withDetails(movie) ?: return@launch
            if ((connection as? ConnectionState.Connected)?.endpoint?.protocol == CastProtocol.SCREEN) {
                engine.castMovie(full) // mirrored TV: play natively on it
                onShowController(true)
            } else {
                phonePlayback = PhonePlayback(
                    urls = full.streams.sortedBy { it.adaptive }.map { it.url },
                    title = full.title,
                    subtitle = full.meta(),
                    handOff = { at -> engine.castMovie(full, startAtMs = at) },
                )
            }
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
            override fun enableMediaAccess() = platform.enableMediaAccess()
            override fun toggleOtherApp() = mediaWatcher.togglePlay()
            override fun openOtherApp(packageName: String) = mediaWatcher.openApp(packageName)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MoodColors.Night)) {
        val wide = maxWidth >= 640.dp
        val contentStart = if (wide) 64.dp else 22.dp
        // Everything the glass navigation floats over is the blur source.
        Box(Modifier.fillMaxSize().haze(haze)) {
            AmbientBackdrop(ambientUrl)
            when (tab) {
                Tab.HOME -> CatalogScreen(
                    home = home?.getOrNull(),
                    error = home?.exceptionOrNull()?.let { "Couldn't reach ${catalog.providerName}. Check your internet connection." },
                    contentStart = contentStart,
                    posterWidth = if (wide) 176.dp else 138.dp,
                    onWatch = { watch(it) },
                    onInfo = { infoMovie = it },
                    onChooseScreen = { watch(it, chooseScreen = true) },
                    onHeroChange = { ambientUrl = it.backdropUrl; tvDisplay.focus.value = it },
                )
                Tab.LIBRARY -> LibraryScreen(
                    items = libraryItems,
                    importing = importing,
                    contentStart = contentStart,
                    thumbnail = { resolver.artwork(it.toCastMedia()) },
                    onAdd = platform::addToLibrary,
                    onOpen = { libraryItem = it },
                )
                Tab.SEARCH -> SearchScreen(query, { query = it }, results, searchingCatalog, contentStart, onOpen = { infoMovie = it })
                Tab.SCREENS -> ScreensScreen(
                    ScreensState(networkState, discovery, searching, devices, recents, connection, playback, preparing, availability, external, otherApp, mediaAccess),
                    screensActions,
                    contentStart,
                )
            }
        }

        CompositionLocalProvider(LocalHazeState provides haze) {
            TopBar(tab, connection, wide, Modifier.align(Alignment.TopCenter)) { tab = it }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp))

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
        phonePlayback?.let { pb ->
            val tvName = (connection as? ConnectionState.Connected)?.endpoint
                ?.takeIf { it.protocol == CastProtocol.SCREEN }?.name
            PhonePlayer(
                urls = pb.urls,
                title = pb.title,
                subtitle = pb.subtitle,
                tvName = tvName,
                onHandOffToTv = { at ->
                    phonePlayback = null
                    pb.handOff(at)
                    onShowController(true)
                },
                onClose = { phonePlayback = null },
            )
        }
        photo?.let { item ->
            val bmp by produceState<Bitmap?>(null, item.id) { value = resolver.artwork(item.toCastMedia()) }
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { photo = null }) {
                bmp?.let { Image(it.asImageBitmap(), item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
        }
    }

    infoMovie?.let { listed ->
        val movie by produceState(listed, listed.id) { value = runCatching { catalog.details(listed) }.getOrDefault(listed) }
        ModalBottomSheet(onDismissRequest = { infoMovie = null }, containerColor = Color(0xFF17120F)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding()) {
                RemoteImage(movie.backdropUrl, movie.title, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(20.dp)))
                Spacer(Modifier.height(16.dp))
                Text(movie.title, color = MoodColors.Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    movie.ratingLabel()?.let {
                        Text(it, color = MoodColors.Orange, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        movie.ratingCount?.let { n -> Text(" ($n ratings)", color = MoodColors.InkSecondary, fontSize = 13.sp) }
                        Text("  ·  ", color = MoodColors.InkSecondary, fontSize = 14.sp)
                    }
                    Text(movie.meta(), color = MoodColors.InkSecondary, fontSize = 14.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(movie.synopsis, color = MoodColors.Ink, fontSize = 15.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(8.dp))
                Text(movie.credit, color = MoodColors.InkSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassPill(onClick = { watch(movie, chooseScreen = connectedName == null) }) {
                        MoodIcon(Glyph.Screen, size = 20.dp, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(connectedName?.let { "Watch on $it" } ?: "Watch on TV", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    GlassPill(onClick = { playOnPhone(movie) }) {
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
                    TextButton(onClick = { pickFor = null; playOnPhone(movie) }) { Text("Play on this phone", color = MoodColors.Orange) }
                    TextButton(onClick = { pickFor = null; showTroubleshoot = true }) { Text("Don't see your TV?", color = MoodColors.InkSecondary) }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    libraryItem?.let { item ->
        ModalBottomSheet(onDismissRequest = { libraryItem = null }, containerColor = Color(0xFF17120F)) {
            Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(
                    listOfNotNull(item.kind.label, item.durationMs?.let { com.rovian.mood.ui.components.formatTime(it) }).joinToString(" · "),
                    color = MoodColors.InkSecondary,
                    fontSize = 14.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable(role = Role.Button) {
                            libraryItem = null
                            if (item.kind == MediaKind.IMAGE) {
                                photo = item
                            } else {
                                phonePlayback = PhonePlayback(
                                    urls = listOf(item.uri),
                                    title = item.title,
                                    subtitle = item.kind.label,
                                    handOff = { at -> engine.castUris(listOf(Uri.parse(item.uri)), item.kind, startAtMs = at) },
                                )
                            }
                        }.padding(horizontal = 22.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MoodIcon(Glyph.Play, size = 18.dp, tint = Color.Black)
                        Spacer(Modifier.width(8.dp))
                        Text(if (item.kind == MediaKind.IMAGE) "View" else "Play", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    GlassPill(onClick = {
                        libraryItem = null
                        if (connection is ConnectionState.Connected) {
                            engine.castUris(listOf(Uri.parse(item.uri)), item.kind)
                            onShowController(true)
                        } else {
                            tab = Tab.SCREENS
                            scope.launch { snackbar.showSnackbar("Choose a screen, then send ${item.title} to it.") }
                        }
                    }) {
                        MoodIcon(Glyph.Screen, size = 20.dp, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(connectedName?.let { "On $it" } ?: "Watch on TV", color = Color.White, fontSize = 16.sp)
                    }
                }
                TextButton(onClick = { library.remove(item.id); libraryItem = null }) {
                    Text("Remove from Library", color = MoodColors.Red)
                }
                Spacer(Modifier.height(12.dp))
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

/** The backdrop's colour, washed through the whole app (Liquid Glass picks it up). */
@Composable
private fun AmbientBackdrop(url: String?) {
    Crossfade(url, label = "ambient") { u ->
        Box(Modifier.fillMaxSize()) {
            if (u != null) {
                RemoteImage(u, null, Modifier.fillMaxSize().blur(90.dp).graphicsLayer { alpha = 0.6f })
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color(0xF00D0A09))),
                ),
            )
        }
    }
}

/** Logo on the left; one floating glass capsule of navigation on the right. */
@Composable
internal fun TopBar(tab: Tab, connection: ConnectionState, wide: Boolean, modifier: Modifier, onTab: (Tab) -> Unit) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = if (wide) 40.dp else 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MoodWordmark(Modifier.width(if (wide) 118.dp else 92.dp), letterColor = Color.White)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.floatingGlass(RoundedCornerShape(50)).padding(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavTab(Tab.HOME, tab == Tab.HOME, showLabel = wide || tab == Tab.HOME) { onTab(Tab.HOME) }
            NavTab(Tab.LIBRARY, tab == Tab.LIBRARY, showLabel = wide || tab == Tab.LIBRARY) { onTab(Tab.LIBRARY) }
            NavTab(
                Tab.SCREENS,
                tab == Tab.SCREENS,
                showLabel = wide || tab == Tab.SCREENS,
                live = connection is ConnectionState.Connected,
            ) { onTab(Tab.SCREENS) }
            Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(22.dp).background(Color.White.copy(alpha = 0.2f)))
            NavTab(Tab.SEARCH, tab == Tab.SEARCH, showLabel = false) { onTab(Tab.SEARCH) }
        }
    }
}

@Composable
private fun NavTab(tab: Tab, selected: Boolean, showLabel: Boolean, live: Boolean = false, onClick: () -> Unit) {
    val fg = if (selected) Color.Black else Color.White.copy(alpha = 0.85f)
    Row(
        Modifier
            .height(42.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = if (showLabel) 16.dp else 11.dp)
            .semantics { contentDescription = tab.label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            MoodIcon(tab.glyph, size = 20.dp, tint = fg)
            if (live) Box(Modifier.align(Alignment.TopEnd).size(7.dp).clip(CircleShape).background(MoodColors.Orange))
        }
        if (showLabel) {
            Spacer(Modifier.width(8.dp))
            Text(tab.label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Something to play in the phone's Mood player, and how to continue it on a TV. */
private data class PhonePlayback(
    val urls: List<String>,
    val title: String,
    val subtitle: String?,
    val handOff: (positionMs: Long) -> Unit,
)
