package dev.voltguard.battery.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.voltguard.battery.service.BatteryMonitorService
import java.util.concurrent.TimeUnit

class SilentTelemetryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        BatteryMonitorService.collectAndEvaluateNow(applicationContext, postNotification = false)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "voltguard_silent_telemetry"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SilentTelemetryWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
