package com.rovian.mood

import android.app.Application
import com.rovian.mood.net.NetworkMonitor
import com.rovian.mood.startup.MoodStartup
import com.rovian.mood.startup.StartupTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MoodApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var networkMonitor: NetworkMonitor
        private set
    lateinit var startup: MoodStartup
        private set

    override fun onCreate() {
        super.onCreate()
        networkMonitor = NetworkMonitor(this)
        // Casting services (discovery, session restore) register here as they
        // land; they all start at process launch, parallel to the animation.
        startup = MoodStartup(
            listOf(
                StartupTask("network") { networkMonitor.start() },
            ),
        )
        startup.start(appScope)
    }
}
