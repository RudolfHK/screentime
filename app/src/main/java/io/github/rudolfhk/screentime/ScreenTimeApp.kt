package io.github.rudolfhk.screentime

import android.app.Application
import android.content.Context
import io.github.rudolfhk.screentime.data.AppDatabase
import io.github.rudolfhk.screentime.data.BackupManager
import io.github.rudolfhk.screentime.data.SettingsStore
import io.github.rudolfhk.screentime.data.StatsRepository
import io.github.rudolfhk.screentime.tracking.AppInfoResolver
import io.github.rudolfhk.screentime.tracking.TrackingManager
import io.github.rudolfhk.screentime.tracking.UsageEventSource
import io.github.rudolfhk.screentime.widget.WidgetUpdater

class ScreenTimeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual dependency container – the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val db: AppDatabase by lazy { AppDatabase.create(app) }
    val settings = SettingsStore(app)
    val appInfo = AppInfoResolver(app)
    val stats: StatsRepository by lazy { StatsRepository(db, settings) }
    val widgets = WidgetUpdater(app) { stats }
    val tracking: TrackingManager by lazy {
        TrackingManager(app, db, settings, UsageEventSource(app), appInfo, widgets)
    }
    val backup: BackupManager by lazy { BackupManager(app, db, settings, stats) }
}

val Context.container: AppContainer get() = (applicationContext as ScreenTimeApp).container
