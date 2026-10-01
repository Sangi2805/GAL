package com.sangar.gal

import android.app.Application
import android.content.Context
import com.sangar.gal.data.DailyUsageWork
import com.sangar.gal.data.SettingsRepository
import com.sangar.gal.data.UsageRepository
import com.sangar.gal.data.db.AppDatabase
import com.sangar.gal.overlay.DefaultExclusions
import com.sangar.gal.phrases.DataStoreRecentIdStore
import com.sangar.gal.phrases.EnginePhraseSource
import com.sangar.gal.service.GalNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GalApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        GalNotifications.createChannels(this)
        DailyUsageWork.scheduleNightly(this)
        DailyUsageWork.runNow(this)
        container.appScope.launch {
            container.phrases.preload()
        }
        container.appScope.launch {
            container.settings.seedExclusionsIfNeeded { DefaultExclusions.resolve(this@GalApp) }
        }
    }
}

/** Hand-rolled dependency container. Small app, no DI framework needed. */
class AppContainer(val app: Application) {
    /** Outlives any single screen or service instance, for writes that must finish. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app)
    val database: AppDatabase by lazy { AppDatabase.build(app) }

    val usage: UsageRepository by lazy { UsageRepository(database) }

    val phrases: EnginePhraseSource by lazy {
        EnginePhraseSource(app, DataStoreRecentIdStore(settings), usage)
    }
}

val Context.container: AppContainer
    get() = (applicationContext as GalApp).container
