package dev.voltguard.battery

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dev.voltguard.battery.data.db.BatteryDatabase
import dev.voltguard.battery.data.prefs.OptimizerPreferences
import dev.voltguard.battery.worker.ModelRetrainWorker
import dev.voltguard.battery.worker.SilentTelemetryWorker

class VoltGuardApp : Application() {

    lateinit var database: BatteryDatabase
        private set

    lateinit var preferences: OptimizerPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        database = BatteryDatabase.getInstance(this)
        preferences = OptimizerPreferences(this)
        createNotificationChannels()
        // Silent zero-notification periodic telemetry + nightly on-device model retraining
        SilentTelemetryWorker.schedule(this)
        ModelRetrainWorker.scheduleNightly(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            val monitorChannel = NotificationChannel(
                CHANNEL_MONITOR,
                getString(R.string.channel_monitor_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_monitor_desc)
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.channel_alerts_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.channel_alerts_desc)
            }

            nm.createNotificationChannel(monitorChannel)
            nm.createNotificationChannel(alertChannel)
        }
    }

    companion object {
        const val CHANNEL_MONITOR = "voltguard_monitor"
        const val CHANNEL_ALERTS = "voltguard_alerts"
    }
}
