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
import androidx.compose.runtime.Composable
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
) {
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
