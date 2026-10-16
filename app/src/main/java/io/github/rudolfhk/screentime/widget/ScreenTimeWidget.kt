package io.github.rudolfhk.screentime.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.github.rudolfhk.screentime.R
import io.github.rudolfhk.screentime.ScreenTimeApp
import io.github.rudolfhk.screentime.core.Format
import io.github.rudolfhk.screentime.data.StatsRepository
import io.github.rudolfhk.screentime.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Plain RemoteViews widget: no extra runtime, refreshed only when a sync writes new data. */
class ScreenTimeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        val updater = (context.applicationContext as ScreenTimeApp).container.widgets
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                updater.updateAll()
            } finally {
                pending.finish()
            }
        }
    }
}

class WidgetUpdater(private val context: Context, private val stats: () -> StatsRepository) {

    suspend fun updateAll() {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, ScreenTimeWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val today = stats().todaySnapshot()

        val views = RemoteViews(context.packageName, R.layout.widget_screentime)
        views.setTextViewText(R.id.widget_time, Format.duration(today.screenTime))
        views.setTextViewText(R.id.widget_unlocks, "${today.unlocks} unlocks")
        views.setTextViewText(R.id.widget_status, if (today.tracking) "● LIVE" else "○ PAUSED")
        if (today.goalMs > 0) {
            views.setViewVisibility(R.id.widget_progress, android.view.View.VISIBLE)
            views.setProgressBar(R.id.widget_progress, 1000, (today.screenTime * 1000 / today.goalMs).toInt().coerceIn(0, 1000), false)
        } else {
            views.setViewVisibility(R.id.widget_progress, android.view.View.GONE)
        }
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        views.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
        manager.updateAppWidget(ids, views)
    }
}
