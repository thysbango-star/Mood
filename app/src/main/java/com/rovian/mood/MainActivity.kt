package com.rovian.mood

import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.rovian.mood.brand.launch.MoodLaunchAnimation
import com.rovian.mood.ui.home.HomeScreen
import com.rovian.mood.ui.theme.MoodColors
import com.rovian.mood.ui.theme.MoodTheme

class MainActivity : ComponentActivity() {

    /** Incremented to restart the launch animation from its first frame. */
    private var launchRun by mutableIntStateOf(0)
    private var launchVisible by mutableStateOf(false)

    /** elapsedRealtime when the app last went to the background; 0 = not backgrounded. */
    private var backgroundedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchVisible = shouldPlayOnCreate(savedInstanceState)
        applySystemBars(launchVisible)

        val app = application as MoodApp
        setContent {
            val ready by app.startup.ready.collectAsState()
            val network by app.networkMonitor.state.collectAsState()
            val showLaunch = launchVisible

            LaunchedEffect(showLaunch) { applySystemBars(showLaunch) }

            MoodTheme {
                Box(Modifier.fillMaxSize().background(MoodColors.Night)) {
                    // The real interface composes from the very first frame, so it
                    // is ready to be revealed the instant the animation ends.
                    HomeScreen(network = network, onReplayIntro = ::replayLaunch)
                    if (showLaunch) {
                        key(launchRun) {
                            MoodLaunchAnimation(
                                appReady = ready,
                                onFinished = { launchVisible = false },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val since = backgroundedAt
        backgroundedAt = 0L
        if (since > 0L && SystemClock.elapsedRealtime() - since >= REPLAY_AFTER_BACKGROUND_MS) {
            replayLaunch()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // API 26–27 save state before onStop; record the time ourselves then.
        val stoppedAt = when {
            isChangingConfigurations -> 0L
            backgroundedAt > 0L -> backgroundedAt
            else -> SystemClock.elapsedRealtime()
        }
        outState.putLong(KEY_BACKGROUNDED_AT, stoppedAt)
        outState.putBoolean(KEY_LAUNCH_VISIBLE, launchVisible)
    }

    /**
     * Fresh launch → play. Rotation/config change → don't restart (unless the
     * animation was mid-flight). Restored after the process was killed in the
     * background → treat as a reopen.
     */
    private fun shouldPlayOnCreate(saved: Bundle?): Boolean {
        if (saved == null) return true
        if (saved.getBoolean(KEY_LAUNCH_VISIBLE)) return true
        val stoppedAt = saved.getLong(KEY_BACKGROUNDED_AT)
        return stoppedAt > 0L && SystemClock.elapsedRealtime() - stoppedAt >= REPLAY_AFTER_BACKGROUND_MS
    }

    private fun replayLaunch() {
        launchRun++
        launchVisible = true
    }

    /** Dark icons over the paper launch canvas; light icons over the night UI. */
    private fun applySystemBars(launchShowing: Boolean) {
        val style = if (launchShowing) {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    private companion object {
        /**
         * Reopening Mood after it has been in the background at least this long
         * replays the launch animation. Quick app switches (e.g. into Photos to
         * pick something to cast) return straight to the controller.
         * Set to 0 to replay on every return.
         */
        const val REPLAY_AFTER_BACKGROUND_MS = 60_000L
        const val KEY_BACKGROUNDED_AT = "mood.backgroundedAt"
        const val KEY_LAUNCH_VISIBLE = "mood.launchVisible"
    }
}
