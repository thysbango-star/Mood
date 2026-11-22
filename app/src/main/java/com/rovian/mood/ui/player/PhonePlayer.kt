package com.rovian.mood.ui.player

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rovian.mood.ui.components.Glyph
import com.rovian.mood.ui.components.GlyphButton
import com.rovian.mood.ui.theme.MoodColors

/** Watch on the phone itself, using the platform player. */
@Composable
fun PhonePlayer(url: String, title: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var error by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VideoView(ctx).apply {
                    val controller = MediaController(ctx)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setOnPreparedListener { start() }
                    setOnErrorListener { _, _, _ ->
                        error = "This phone couldn't play $title."
                        true
                    }
                    setVideoURI(Uri.parse(url))
                }
            },
            onRelease = { it.stopPlayback() },
        )
        error?.let {
            Text(it, color = MoodColors.Ink, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }
        GlyphButton(
            Glyph.Close,
            "Close player",
            onClose,
            Modifier.safeDrawingPadding().padding(12.dp).align(Alignment.TopStart),
        )
    }
}
