package com.dotforge.screens

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.dotforge.AppGraph

/** The app graph without the broadcast receivers, with WorkManager set up the way Robolectric needs it. */
class ScreenshotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // WorkManager is static: the first test in a sandbox sets it up, later ones reuse it
        runCatching { WorkManager.initialize(this, Configuration.Builder().setExecutor { it.run() }.build()) }
        AppGraph.init(this)
    }
}
