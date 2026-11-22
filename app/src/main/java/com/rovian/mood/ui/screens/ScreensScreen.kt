package com.rovian.mood.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovian.mood.cast.AdapterAvailability
import com.rovian.mood.cast.CastProtocol
import com.rovian.mood.cast.ConnectionState
import com.rovian.mood.cast.DiscoveryPhase
import com.rovian.mood.cast.MoodDevice
import com.rovian.mood.cast.PlaybackState
import com.rovian.mood.cast.ReceiverEndpoint
import com.rovian.mood.cast.RecentDevice
import com.rovian.mood.cast.endpoint
import com.rovian.mood.net.NetworkState
import com.rovian.mood.ui.components.ActionPill
import com.rovian.mood.ui.components.GlassCard
import com.rovian.mood.ui.components.Glyph
import com.rovian.mood.ui.components.MoodIcon
import com.rovian.mood.ui.components.SearchingCapsule
import com.rovian.mood.ui.components.liquidGlass
import com.rovian.mood.ui.theme.MoodColors

data class ScreensState(
    val network: NetworkState,
    val discovery: DiscoveryPhase,
    val searching: Boolean,
    val devices: List<MoodDevice>,
    val recents: List<RecentDevice>,
    val connection: ConnectionState,
    val playback: PlaybackState?,
    val preparing: String?,
    val availability: Map<CastProtocol, AdapterAvailability>,
    val externalDisplay: String?,
)

interface ScreensActions {
    fun connect(endpoint: ReceiverEndpoint)
    fun disconnect()
    fun dismissConnectionMessage()
    fun searchAgain()
    fun troubleshoot()
    fun pickVideo()
    fun pickPhotos()
    fun pickMusic()
    fun enterLink()
    fun openController()
    fun mirror()
}

fun ReceiverEndpoint.summary(): String {
    val c = capabilities
    val what = when {
        c.video && c.audio -> "Video, photos & music"
        c.audio -> "Music only"
        c.video -> "Video & photos"
        else -> "Limited"
    }
    return listOfNotNull(kind.label, model?.take(28), what).joinToString(" · ")
}

@Composable
fun ScreensScreen(state: ScreensState, actions: ScreensActions, contentStart: Dp, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = contentStart, end = 24.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.statusBarsPadding().padding(top = 76.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Screens", color = MoodColors.Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (state.searching) SearchingCapsule()
            }
        }
        item { NetworkLine(state.network) }
        item { SessionCard(state, actions) }
        item { DeviceSection(state, actions) }
        item { MirrorCard(state.externalDisplay, actions) }
    }
}

@Composable
private fun NetworkLine(network: NetworkState) {
    val (dot, label) = when (network) {
        NetworkState.Unknown -> MoodColors.InkSecondary to "Checking network…"
        NetworkState.Offline -> MoodColors.Red to "Offline — connect to the same Wi-Fi as your TV"
        is NetworkState.LocalNetwork -> MoodColors.Orange to "On ${network.transport}"
        is NetworkState.NonLocal -> MoodColors.Red to "On ${network.transport} — join your TV's Wi-Fi to cast"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(label, color = MoodColors.InkSecondary, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionCard(state: ScreensState, actions: ScreensActions) {
    val c = state.connection
    val ep = c.endpoint ?: return
    GlassCard(Modifier.animateContentSize()) {
        val (title, detail) = when (c) {
            is ConnectionState.Connecting -> "Connecting to ${ep.name}…" to "Waiting for ${ep.name} to accept."
            is ConnectionState.Connected -> "Connected to ${ep.name}" to "via ${ep.protocol.label}"
            is ConnectionState.Reconnecting -> "Reconnecting to ${ep.name}…" to "The connection dropped. Mood is trying to restore it."
            is ConnectionState.Failed -> "Couldn't connect" to c.message
            is ConnectionState.Disconnected -> "Disconnected" to (c.message ?: "${ep.name} is no longer connected.")
            ConnectionState.Idle -> return@GlassCard
        }
        Text(title, color = MoodColors.Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Text(detail, color = MoodColors.InkSecondary, fontSize = 14.sp, lineHeight = 19.sp)
        when (c) {
            is ConnectionState.Connected -> {
                state.preparing?.let { Text(it, color = MoodColors.Orange, fontSize = 14.sp) }
                state.playback?.title?.let { now ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x14FFFFFF))
                            .clickable(role = Role.Button, onClick = actions::openController).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MoodIcon(Glyph.Home, size = 22.dp, tint = MoodColors.Orange)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Now on ${ep.name}", color = MoodColors.InkSecondary, fontSize = 12.sp)
                            Text(now, color = MoodColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                }
                Text("Cast from this phone", color = MoodColors.InkSecondary, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ep.capabilities.video) ActionPill(Glyph.Video, "Video", actions::pickVideo)
                    if (ep.capabilities.image) ActionPill(Glyph.Photo, "Photos", actions::pickPhotos)
                    if (ep.capabilities.audio) ActionPill(Glyph.Music, "Music", actions::pickMusic)
                    ActionPill(Glyph.Link, "Link", actions::enterLink)
                }
                TextButton(onClick = actions::disconnect) { Text("Disconnect", color = MoodColors.Red) }
            }
            is ConnectionState.Connecting, is ConnectionState.Reconnecting -> {
                SearchingCapsule()
                TextButton(onClick = actions::disconnect) { Text("Cancel", color = MoodColors.InkSecondary) }
            }
            is ConnectionState.Failed, is ConnectionState.Disconnected -> Row {
                TextButton(onClick = { actions.connect(ep) }) { Text("Try again", color = MoodColors.Orange) }
                TextButton(onClick = actions::troubleshoot) { Text("Help", color = MoodColors.InkSecondary) }
                TextButton(onClick = actions::dismissConnectionMessage) { Text("Dismiss", color = MoodColors.InkSecondary) }
            }
            ConnectionState.Idle -> Unit
        }
    }
}

@Composable
private fun DeviceSection(state: ScreensState, actions: ScreensActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Nearby", color = MoodColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        DeviceList(state.devices, state.connection, actions::connect)
        val presentKeys = state.devices.flatMap { d -> d.endpoints.map { it.key } }.toSet()
        val absent = state.recents.filter { it.endpointKey !in presentKeys }
        when (state.discovery) {
            DiscoveryPhase.SEARCHING -> Hint("Looking for TVs, Chromecasts and media players on your network…")
            DiscoveryPhase.NONE_FOUND -> Column {
                Hint("No screens found on this network yet.")
                Row {
                    TextButton(onClick = actions::searchAgain) { Text("Search again", color = MoodColors.Orange) }
                    TextButton(onClick = actions::troubleshoot) { Text("Troubleshoot", color = MoodColors.InkSecondary) }
                }
            }
            DiscoveryPhase.NO_LOCAL_NETWORK -> Column {
                Hint("Mood finds screens over Wi-Fi. Join the same Wi-Fi network as your TV.")
                TextButton(onClick = actions::troubleshoot) { Text("Troubleshoot", color = MoodColors.InkSecondary) }
            }
            DiscoveryPhase.FOUND -> TextButton(onClick = actions::troubleshoot) {
                Text("Don't see your TV?", color = MoodColors.InkSecondary)
            }
            DiscoveryPhase.IDLE -> Unit
        }
        (state.availability[CastProtocol.GOOGLE_CAST] as? AdapterAvailability.Unavailable)?.let { Hint(it.reason) }
        if (absent.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Recent", color = MoodColors.InkSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            absent.forEach { r ->
                Text(
                    "${r.name} · ${r.protocol.label} — not found on this network right now",
                    color = MoodColors.InkSecondary.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/** The device selector: every row is a receiver a protocol actually discovered. */
@Composable
fun DeviceList(devices: List<MoodDevice>, connection: ConnectionState, onConnect: (ReceiverEndpoint) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        devices.forEach { device -> DeviceRow(device, connection, onConnect) }
    }
}

@Composable
private fun DeviceRow(device: MoodDevice, connection: ConnectionState, onConnect: (ReceiverEndpoint) -> Unit) {
    val activeKey = connection.endpoint?.key
    val mine = activeKey != null && device.owns(activeKey)
    val status = when {
        !mine -> null
        connection is ConnectionState.Connected -> "Connected"
        connection is ConnectionState.Connecting -> "Connecting…"
        connection is ConnectionState.Reconnecting -> "Reconnecting…"
        connection is ConnectionState.Failed -> "Couldn't connect"
        else -> null
    }
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.fillMaxWidth()
            .liquidGlass(shape, strength = if (mine) 1.3f else 0.8f)
            .then(if (mine && connection is ConnectionState.Connected) Modifier.border(1.5.dp, MoodColors.Orange, shape) else Modifier)
            .clickable(role = Role.Button) { onConnect(device.primary) }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MoodIcon(if (device.primary.capabilities.video) Glyph.Screen else Glyph.Music, size = 26.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, color = MoodColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(device.primary.summary(), color = MoodColors.InkSecondary, fontSize = 13.sp, maxLines = 1)
            }
            status?.let { Text(it, color = if (it == "Connected") MoodColors.Orange else MoodColors.InkSecondary, fontSize = 13.sp) }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            device.endpoints.forEach { ep ->
                val isActive = ep.key == activeKey
                Text(
                    ep.protocol.label,
                    color = if (isActive) Color.Black else MoodColors.Ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (isActive) MoodColors.Orange else Color(0x1FFFFFFF))
                        .clickable(role = Role.Button) { onConnect(ep) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun MirrorCard(externalDisplay: String?, actions: ScreensActions) {
    GlassCard(title = "Mirror your whole screen") {
        Text(
            "Start mirroring from Android's Cast screen. Mood notices the TV and switches it to " +
                "MoodPlay's big-screen view at the TV's own resolution, with this phone as the remote.",
            color = MoodColors.InkSecondary,
            fontSize = 14.sp,
            lineHeight = 19.sp,
        )
        if (externalDisplay != null) {
            Text("Mirroring to $externalDisplay — the TV is showing MoodPlay's TV view.", color = MoodColors.Orange, fontSize = 14.sp)
        }
        ActionPill(Glyph.Mirror, "Open screen mirroring", actions::mirror)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = MoodColors.InkSecondary, fontSize = 14.sp, lineHeight = 19.sp)
}
