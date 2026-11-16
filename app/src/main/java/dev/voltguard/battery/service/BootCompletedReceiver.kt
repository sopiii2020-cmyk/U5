package dev.voltguard.battery.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.voltguard.battery.VoltGuardApp
import dev.voltguard.battery.engine.TileSelection
import dev.voltguard.battery.worker.ModelRetrainWorker
import dev.voltguard.battery.worker.SilentTelemetryWorker

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = (context.applicationContext as VoltGuardApp).preferences
        SilentTelemetryWorker.schedule(context)
        ModelRetrainWorker.scheduleNightly(context)
        val keepAlive = prefs.getTileSelection() == TileSelection.INTL ||
            prefs.isInactivityShieldEnabled() ||
            (prefs.isAutoStartEnabled() && prefs.isPersistentNotificationEnabled())
        if (keepAlive) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BatteryMonitorService::class.java)
            )
        }
    }
}
