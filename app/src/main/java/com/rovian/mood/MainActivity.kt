package com.rovian.mood

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import com.rovian.mood.cast.MediaKind
import com.rovian.mood.catalog.InternetArchiveCatalog
import com.rovian.mood.ui.shell.MoodShell
import com.rovian.mood.ui.shell.PlatformActions
import com.rovian.mood.ui.theme.MoodTheme

class MainActivity : ComponentActivity() {

    /** Incremented to restart the launch animation from its first frame. */
    private var launchRun by mutableIntStateOf(0)
    private var launchVisible by mutableStateOf(false)
    private var controllerVisible by mutableStateOf(false)

    /** elapsedRealtime when the app last went to the background; 0 = not backgrounded. */
    private var backgroundedAt = 0L

    private val app get() = application as MoodApp
    private val catalog = InternetArchiveCatalog()

    private val videoPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) castPicked(uris, MediaKind.VIDEO)
    }
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) castPicked(uris, MediaKind.IMAGE)
    }
    private val musicPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) castPicked(uris, MediaKind.AUDIO)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun castPicked(uris: List<android.net.Uri>, kind: MediaKind) {
        app.castEngine.castUris(uris, kind)
        controllerVisible = true
    }

    private val platform = object : PlatformActions {
        override fun pickVideo() = videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        override fun pickPhotos() = photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        override fun pickMusic() = musicPicker.launch(arrayOf("audio/*"))

        override fun openMirroring() {
            if (!app.mirroring.openSystemPicker(this@MainActivity)) {
                Toast.makeText(
                    this@MainActivity,
                    "Open Quick Settings and tap Screen Cast (or Smart View) to mirror your screen.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        override fun openWifiSettings() {
            val intent = if (Build.VERSION.SDK_INT >= 29) {
                Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            } else {
                Intent(Settings.ACTION_WIFI_SETTINGS)
            }
            runCatching { startActivity(intent) }
        }

        override fun replayIntro() = replayLaunch()

        override fun enableMediaAccess() {
            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchVisible = shouldPlayOnCreate(savedInstanceState)
        controllerVisible = savedInstanceState?.getBoolean(KEY_CONTROLLER) ?: false
        applySystemBars(launchVisible)

        setContent {
            val ready by app.startup.ready.collectAsState()
            val showLaunch = launchVisible

            LaunchedEffect(showLaunch) { applySystemBars(showLaunch) }

            MoodTheme {
                Box(Modifier.fillMaxSize()) {
                    // The real interface composes from the very first frame, so it
                    // is ready to be revealed the instant the animation ends.
                    MoodShell(
                        engine = app.castEngine,
                        network = app.networkMonitor,
                        catalog = catalog,
                        resolver = app.mediaResolver,
                        mirroring = app.mirroring,
                        networkCheck = app.networkCheck,
                        tvDisplay = app.tvDisplay,
                        mediaWatcher = app.mediaWatcher,
                        platform = platform,
                        showController = controllerVisible,
                        onShowController = { controllerVisible = it },
                    )
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
        // Discover only while Mood is on screen; sessions continue in the background.
        app.castEngine.startDiscovery()
        app.tvDisplay.attach(this)
        app.mediaWatcher.refresh()
        val since = backgroundedAt
        backgroundedAt = 0L
        if (since > 0L && SystemClock.elapsedRealtime() - since >= REPLAY_AFTER_BACKGROUND_MS) {
            replayLaunch()
        }
    }

    override fun onStop() {
        super.onStop()
        app.castEngine.stopDiscovery()
        app.tvDisplay.detach(this)
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
        outState.putBoolean(KEY_CONTROLLER, controllerVisible)
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
        const val KEY_CONTROLLER = "mood.controller"
    }
}
