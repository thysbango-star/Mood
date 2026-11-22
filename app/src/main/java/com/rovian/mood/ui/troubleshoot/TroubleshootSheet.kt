package com.rovian.mood.ui.troubleshoot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovian.mood.cast.AdapterAvailability
import com.rovian.mood.cast.CastProtocol
import com.rovian.mood.net.NetworkState
import com.rovian.mood.ui.components.ActionPill
import com.rovian.mood.ui.components.Glyph
import com.rovian.mood.ui.theme.MoodColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TroubleshootSheet(
    network: NetworkState,
    availability: Map<CastProtocol, AdapterAvailability>,
    onSearchAgain: () -> Unit,
    onWifiSettings: () -> Unit,
    onDismiss: () -> Unit,
    runNetworkCheck: suspend () -> List<String>,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var checking by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<List<String>?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MoodColors.Charcoal) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Can't find your TV?", color = MoodColors.Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            val networkLine = when (network) {
                is NetworkState.LocalNetwork -> "This phone is on ${network.transport}. Your TV must be on the same network."
                is NetworkState.NonLocal -> "This phone is on ${network.transport}. Join the Wi-Fi your TV uses" +
                    if (network.transport == "VPN") " and turn off the VPN." else "."
                NetworkState.Offline -> "This phone is offline. Connect to the Wi-Fi your TV uses."
                NetworkState.Unknown -> "Checking this phone's network…"
            }
            Step("Network", networkLine)
            Step("Same network, not a guest one", "Guest Wi-Fi and \"AP / client isolation\" stop phones seeing TVs. Use the main network.")
            Step("Wake the TV", "Turn the TV or streaming stick on — many stop announcing themselves in standby.")
            Step(
                "Google Cast",
                (availability[CastProtocol.GOOGLE_CAST] as? AdapterAvailability.Unavailable)?.reason
                    ?: "Works with Chromecast, Google TV and TVs with Google Cast built in (many Sony, TCL, Hisense, Philips and Vizio models).",
            )
            Step("Other smart TVs (DLNA)", "Turn on the TV's media sharing / DLNA / \"media renderer\" option, and allow this phone if the TV asks.")
            Step(
                "If your TV supports neither",
                "Plug in a Chromecast or Google TV Streamer, or an Android TV box, and Mood will cast to it. " +
                    "A browser-based Mood receiver for TVs with only a web browser is planned.",
            )
            Text("Network check", color = MoodColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Scans your Wi-Fi the way Android sees it (about 7 seconds) and explains what's blocking discovery.",
                color = MoodColors.InkSecondary,
                fontSize = 14.sp,
            )
            if (checking) {
                Text("Checking your network…", color = MoodColors.Orange, fontSize = 14.sp)
            } else {
                ActionPill(Glyph.Search, if (report == null) "Run network check" else "Run again", {
                    checking = true
                    scope.launch {
                        report = runCatching { runNetworkCheck() }.getOrElse { listOf("Check failed: ${it.message}") }
                        checking = false
                    }
                })
            }
            report?.let { lines ->
                Text(
                    lines.joinToString("\n"),
                    color = MoodColors.Ink,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x14FFFFFF)).padding(12.dp),
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) }) {
                    Text("Copy report", color = MoodColors.Orange)
                }
            }
            Row {
                ActionPill(Glyph.Refresh, "Search again", onSearchAgain, accent = true)
                Spacer(Modifier.width(8.dp))
                ActionPill(Glyph.Wifi, "Wi-Fi settings", onWifiSettings)
            }
            Spacer(Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun Step(title: String, body: String) {
    Column {
        Text(title, color = MoodColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(body, color = MoodColors.InkSecondary, fontSize = 14.sp, lineHeight = 19.sp)
    }
}
