package com.rovian.mood.cast.mirror

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rovian.mood.R
import com.rovian.mood.diagnostics.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Required by Android to read other apps' media sessions. Mood ignores the
 * notifications themselves; it only uses the access this grants to
 * [MediaSessionManager.getActiveSessions].
 */
class MediaWatcherService : NotificationListenerService()

/** Something playing in another app (YouTube, Netflix…), as its media session reports it. */
data class ExternalPlayback(
    val packageName: String,
    val appName: String,
    val title: String?,
    val playing: Boolean,
)

/**
 * Notices video playing in other apps while the phone is mirrored to a TV.
 *
 * Android doesn't allow one app to replace or re-render another app's video, so
 * Mood can't put its own player on YouTube. What gives the "TV plays it itself"
 * effect for those apps is their own Cast button; Mood points the user to it at
 * the right moment and offers play/pause for that app from inside Mood.
 * Needs the user to grant Notification access (Settings), nothing else.
 */
class MediaSessionWatcher(context: Context, private val mirroring: SystemMirroring) {
    private val app = context.applicationContext
    private val component = ComponentName(app, MediaWatcherService::class.java)
    private val sessions = app.getSystemService(MediaSessionManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _access = MutableStateFlow(false)
    val accessGranted: StateFlow<Boolean> = _access.asStateFlow()
    private val _active = MutableStateFlow<ExternalPlayback?>(null)
    val active: StateFlow<ExternalPlayback?> = _active.asStateFlow()

    private var controllers: List<MediaController> = emptyList()
    private var activeController: MediaController? = null
    private var listening = false
    private var lastTipKey: String? = null

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = publish()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> bind(list.orEmpty()) }

    init {
        // Mirroring + another app playing → the moment to suggest its Cast button.
        scope.launch {
            combine(mirroring.presentationDisplay, _active) { display, playback -> display to playback }.collect { (display, playback) ->
                if (display != null && playback != null && playback.playing) tip(playback, display.name)
            }
        }
    }

    /** Call when Mood comes to the foreground (the user may have just granted access). */
    fun refresh() {
        val granted = NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)
        _access.value = granted
        if (!granted) {
            stop()
            return
        }
        if (listening) return
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            listening = true
            bind(sessions.getActiveSessions(component))
        } catch (e: SecurityException) {
            Log.w(TAG, "No media session access", e)
            _access.value = false
        }
    }

    private fun stop() {
        if (listening) runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsListener) }
        listening = false
        bind(emptyList())
    }

    private fun bind(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = list.filter { it.packageName != app.packageName }
        controllers.forEach { it.registerCallback(callback, main) }
        publish()
    }

    private fun publish() {
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val c = playing ?: controllers.firstOrNull()
        activeController = c
        _active.value = c?.let {
            ExternalPlayback(
                packageName = it.packageName,
                appName = appName(it.packageName),
                title = it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                playing = it.playbackState?.state == PlaybackState.STATE_PLAYING,
            )
        }
    }

    fun togglePlay() {
        val c = activeController ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun openApp(packageName: String) {
        app.packageManager.getLaunchIntentForPackage(packageName)?.let {
            app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun appName(pkg: String): String = KNOWN[pkg] ?: runCatching {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })

    private fun tip(p: ExternalPlayback, tv: String) {
        val key = p.packageName + "|" + p.title
        if (key == lastTipKey) return
        lastTipKey = key
        Diagnostics.event("${p.appName} playing while mirrored to $tv")
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "TV quality tips", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = app.packageManager.getLaunchIntentForPackage(p.packageName)?.let {
            PendingIntent.getActivity(app, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mood)
            .setContentTitle("${p.appName} is playing on $tv")
            .setContentText("Tap Cast in ${p.appName} so the TV plays it itself — full quality, TV sound, no lag.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Mirroring sends a compressed copy of your screen. Tap the Cast button in ${p.appName} and the TV plays " +
                        "the video itself — sharper picture, sound from the TV, and no stutter.",
                ),
            )
            .setAutoCancel(true)
            .apply { open?.let { setContentIntent(it) } }
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, n) }
    }

    private companion object {
        const val TAG = "MoodMediaWatcher"
        const val CHANNEL = "mood_tv_tips"
        const val NOTIFICATION_ID = 4108
        val KNOWN = mapOf(
            "com.google.android.youtube" to "YouTube",
            "com.netflix.mediaclient" to "Netflix",
            "com.disney.disneyplus" to "Disney+",
            "com.amazon.avod.thirdpartyclient" to "Prime Video",
            "com.spotify.music" to "Spotify",
            "com.google.android.apps.youtube.music" to "YouTube Music",
        )
    }
}
