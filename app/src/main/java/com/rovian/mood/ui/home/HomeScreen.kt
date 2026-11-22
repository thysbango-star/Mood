package com.rovian.mood.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovian.mood.brand.MoodWordmark
import com.rovian.mood.net.NetworkState
import com.rovian.mood.ui.theme.MoodColors

/**
 * Mood's home shell — what the launch animation reveals. Everything shown is
 * real: the network row reflects the live default network. Casting discovery
 * is not part of this build and the screen says so rather than faking a list.
 */
@Composable
fun HomeScreen(
    network: NetworkState,
    onReplayIntro: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(MoodColors.Red.copy(alpha = 0.16f), Color.Transparent),
                    radius = 1100f,
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            MoodWordmark(Modifier.width(132.dp), letterColor = MoodColors.Ink)
            Spacer(Modifier.height(10.dp))
            Text(
                "One screen. Every connection.",
                color = MoodColors.InkSecondary,
                fontSize = 15.sp,
            )

            Spacer(Modifier.height(36.dp))
            GlassCard(title = "This phone") {
                NetworkRow(network)
            }

            Spacer(Modifier.height(16.dp))
            GlassCard(title = "Nearby screens") {
                Text(
                    "Screen discovery isn't in this build yet. Google Cast, DLNA and the " +
                        "Mood Web Receiver are the next pieces to land.",
                    color = MoodColors.InkSecondary,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                )
            }

            Spacer(Modifier.weight(1f))
            TextButton(onClick = onReplayIntro, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Replay intro", color = MoodColors.InkSecondary)
            }
        }
    }
}

@Composable
private fun GlassCard(title: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MoodColors.Glass, shape)
            .border(1.dp, MoodColors.GlassEdge, shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title,
            color = MoodColors.Ink,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@Composable
private fun NetworkRow(network: NetworkState) {
    val (dot, label) = when (network) {
        NetworkState.Unknown -> MoodColors.InkSecondary to "Checking network…"
        NetworkState.Offline -> MoodColors.Red to "Offline — connect to Wi-Fi to cast"
        is NetworkState.LocalNetwork -> MoodColors.Orange to "On ${network.transport} — local network available"
        is NetworkState.NonLocal -> MoodColors.Red to "On ${network.transport} — join the TV's Wi-Fi to cast"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
    }
}
