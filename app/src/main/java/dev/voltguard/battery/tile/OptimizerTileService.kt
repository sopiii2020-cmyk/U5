package dev.voltguard.battery.tile

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import dev.voltguard.battery.R
import dev.voltguard.battery.VoltGuardApp
import dev.voltguard.battery.engine.PowerDecisionEngine
import dev.voltguard.battery.engine.TileSelection
import dev.voltguard.battery.service.BatteryMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OptimizerTileService : TileService() {

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartListening() {
        super.onStartListening()
        syncTileUi()
        tileScope.launch {
            BatteryMonitorService.collectAndEvaluateNow(applicationContext, postNotification = false)
            syncTileUi()
        }
    }

    override fun onClick() {
        super.onClick()
        val app = application as VoltGuardApp
        val next = PowerDecisionEngine.cycleTile(app.preferences.getTileSelection())
        app.preferences.setTileSelection(next)

        if (app.preferences.isPersistentNotificationEnabled() ||
            next == TileSelection.INTL ||
            app.preferences.isInactivityShieldEnabled()
        ) {
            val refreshIntent = Intent(this, BatteryMonitorService::class.java).apply {
                action = BatteryMonitorService.ACTION_REFRESH_NOW
            }
            ContextCompat.startForegroundService(this, refreshIntent)
        } else {
            stopService(Intent(this, BatteryMonitorService::class.java))
            tileScope.launch {
                BatteryMonitorService.collectAndEvaluateNow(applicationContext, postNotification = false)
                syncTileUi()
            }
        }

        syncTileUi()
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }

    private fun syncTileUi() {
        val tile = qsTile ?: return
        val app = application as VoltGuardApp
        val selection = app.preferences.getTileSelection()
        val snap = BatteryMonitorService.latestSnapshot.value

        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_voltguard)
        when (selection) {
            TileSelection.AUTO -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "VoltGuard: Auto"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = if (snap != null) "${snap.batteryLevelPct}% · ${snap.temperatureC.toInt()}°C" else "Adaptive rules"
                }
            }
            TileSelection.PERFORMANCE -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "VoltGuard: Perf"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Deferral paused"
                }
            }
            TileSelection.LONGEVITY -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "VoltGuard: Long"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Cycle protection"
                }
            }
            TileSelection.INTL -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "VoltGuard: Intl"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Max Life · Locked"
                }
            }
        }
        tile.updateTile()
    }
}
