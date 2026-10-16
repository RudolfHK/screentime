package io.github.rudolfhk.screentime.tracking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.rudolfhk.screentime.ScreenTimeApp
import java.util.concurrent.TimeUnit

/**
 * Short, periodic job that copies new events from the system usage log into the database.
 * It runs for a fraction of a second every interval and needs no wake locks or services.
 * WorkManager persists the schedule across reboots.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ScreenTimeApp).container
        return try {
            container.tracking.sync()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

object SyncScheduler {
    private const val WORK_NAME = "usage-sync"

    fun schedule(context: Context, intervalMinutes: Int) {
        val minutes = intervalMinutes.coerceAtLeast(15).toLong()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
