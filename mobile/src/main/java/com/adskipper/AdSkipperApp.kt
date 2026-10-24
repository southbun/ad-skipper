package com.adskipper

import android.app.Application
import com.adskipper.core.data.SettingsRepository
import com.adskipper.core.data.StatsRepository
import com.adskipper.core.model.ModelManager
import timber.log.Timber

class AdSkipperApp : Application() {

    lateinit var settingsRepo: SettingsRepository
        private set
    lateinit var statsRepo: StatsRepository
        private set
    lateinit var modelManager: ModelManager
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        if (Application.getProcessName().endsWith(":learning")) return
        // Pending full screenshots are temporary and never survive a process restart.
        java.io.File(cacheDir, "learning").listFiles()?.filter { it.name.endsWith(".png") }?.forEach { it.delete() }
        settingsRepo = SettingsRepository(this)
        statsRepo = StatsRepository(this)
        modelManager = ModelManager(this)
    }

    companion object {
        fun get(context: android.content.Context): AdSkipperApp =
            context.applicationContext as AdSkipperApp
    }
}
