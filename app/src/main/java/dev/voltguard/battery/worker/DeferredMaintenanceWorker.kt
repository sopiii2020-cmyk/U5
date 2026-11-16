package dev.voltguard.battery.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.voltguard.battery.VoltGuardApp
import dev.voltguard.battery.engine.WorkConstraintsDecision
import java.util.concurrent.TimeUnit

class DeferredMaintenanceWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = (applicationContext as VoltGuardApp).database
        val thirtyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        db.sampleDao().pruneOlderThan(thirtyDaysAgo)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "voltguard_maintenance"

        fun scheduleWithDecision(context: Context, decision: WorkConstraintsDecision) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(decision.requiresBatteryNotLow)
                .setRequiresCharging(decision.requiresCharging)
                .setRequiresDeviceIdle(decision.requiresDeviceIdle)
                .build()

            val request = PeriodicWorkRequestBuilder<DeferredMaintenanceWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
