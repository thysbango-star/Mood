package com.rovian.mood

import android.app.Application
import com.rovian.mood.cast.MoodCastEngine
import com.rovian.mood.cast.RecentDevices
import com.rovian.mood.cast.dlna.DlnaAdapter
import com.rovian.mood.cast.googlecast.GoogleCastAdapter
import com.rovian.mood.cast.media.LocalMediaServer
import com.rovian.mood.cast.media.MediaResolver
import com.rovian.mood.cast.mirror.SystemMirroring
import com.rovian.mood.diagnostics.NetworkCheck
import com.rovian.mood.net.NetworkMonitor
import com.rovian.mood.startup.MoodStartup
import com.rovian.mood.startup.StartupTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

class MoodApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var networkMonitor: NetworkMonitor
        private set
    lateinit var startup: MoodStartup
        private set
    lateinit var castEngine: MoodCastEngine
        private set
    lateinit var mediaResolver: MediaResolver
        private set
    lateinit var mirroring: SystemMirroring
        private set
    lateinit var networkCheck: NetworkCheck
        private set

    override fun onCreate() {
        super.onCreate()
        networkMonitor = NetworkMonitor(this)
        mediaResolver = MediaResolver(this)
        mirroring = SystemMirroring(this)
        networkCheck = NetworkCheck(this, networkMonitor)
        val googleCast = GoogleCastAdapter(this)
        val dlna = DlnaAdapter(this, networkMonitor)
        castEngine = MoodCastEngine(
            context = this,
            network = networkMonitor,
            adapters = listOf(googleCast, dlna),
            server = LocalMediaServer { msg, e -> android.util.Log.i("MoodMediaServer", msg, e) },
            resolver = mediaResolver,
            recents = RecentDevices(this),
            scope = mainScope,
        )
        // Everything starts at process launch, in parallel with the launch
        // animation; none of it blocks the first frame.
        startup = MoodStartup(
            listOf(
                StartupTask("network") { networkMonitor.start() },
                StartupTask("google-cast") { withContext(Dispatchers.Main) { googleCast.initialize() } },
                StartupTask("displays") { withContext(Dispatchers.Main) { mirroring.start() } },
            ),
        )
        startup.start(appScope)
    }
}
