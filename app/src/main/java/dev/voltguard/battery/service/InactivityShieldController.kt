package dev.voltguard.battery.service

import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import dev.voltguard.battery.VoltGuardApp
import dev.voltguard.battery.util.OemChargeSettingsHelper
import kotlin.math.sqrt

object InactivityShieldController : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var appContext: Context? = null
    private var isWatching = false
    private var lastMotionMs = System.currentTimeMillis()
    private var lastMag = 9.81f
    private var dimmedAt8s = false
    private var lockedAt12s = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val tickRunnable = object : Runnable {
        override fun run() {
            val ctx = appContext ?: return
            val app = ctx.applicationContext as VoltGuardApp
            if (!app.preferences.isInactivityShieldEnabled() || !isWatching) return

            val pm = ctx.getSystemService(PowerManager::class.java)
            if (pm?.isInteractive == true) {
                val idleMs = System.currentTimeMillis() - lastMotionMs
                if (idleMs >= 12_000L && !lockedAt12s) {
                    lockedAt12s = true
                    trigger12sLockAndQsGuard(ctx, app)
                } else if (idleMs >= 8_000L && !dimmedAt8s) {
                    dimmedAt8s = true
                    applyMinimumBrightness(ctx, app)
                }
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

    fun syncWithPreference(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val enabled = (ctx as VoltGuardApp).preferences.isInactivityShieldEnabled()
        if (enabled && !isWatching) {
            val sm = ctx.getSystemService(SensorManager::class.java)
            val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (sm != null && accel != null) {
                sensorManager = sm
                lastMotionMs = System.currentTimeMillis()
                dimmedAt8s = false
                lockedAt12s = false
                sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
                isWatching = true
                mainHandler.removeCallbacks(tickRunnable)
                mainHandler.postDelayed(tickRunnable, 1000L)
            }
        } else if (!enabled && isWatching) {
            stopWatching()
        }
    }

    fun stopWatching() {
        isWatching = false
        mainHandler.removeCallbacks(tickRunnable)
        sensorManager?.unregisterListener(this)
    }

    fun onScreenOff() {
        lockedAt12s = true
    }

    fun onPowerButtonWake(context: Context) {
        val ctx = context.applicationContext
        val app = ctx as VoltGuardApp
        lastMotionMs = System.currentTimeMillis()
        dimmedAt8s = false
        lockedAt12s = false

        // Restore brightness immediately
        app.preferences.consumePreviousBrightness()?.let { prev ->
            if (Settings.System.canWrite(ctx)) {
                runCatching {
                    Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, prev)
                }
            }
        }

        // Restore all remembered Quick Settings toggles immediately on Power button wake
        val snap = app.preferences.consumeQuickSettingsSnapshot() ?: return
        if (snap.sync) {
            runCatching { ContentResolver.setMasterSyncAutomatically(true) }
        }
        val rootOptIn = app.preferences.isRootBridgeOptInEnabled()
        OemChargeSettingsHelper.restoreRadiosFromSnapshot(ctx, snap, rootOptIn)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val vals = event?.values ?: return
        val mag = sqrt(vals[0] * vals[0] + vals[1] * vals[1] + vals[2] * vals[2])
        if (kotlin.math.abs(mag - lastMag) > 0.35f) {
            lastMotionMs = System.currentTimeMillis()
            if (dimmedAt8s && !lockedAt12s) {
                dimmedAt8s = false
                appContext?.let { ctx ->
                    val app = ctx.applicationContext as VoltGuardApp
                    app.preferences.consumePreviousBrightness()?.let { prev ->
                        if (Settings.System.canWrite(ctx)) {
                            runCatching {
                                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, prev)
                            }
                        }
                    }
                }
            }
        }
        lastMag = mag
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun applyMinimumBrightness(context: Context, app: VoltGuardApp) {
        if (!Settings.System.canWrite(context)) return
        val current = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        app.preferences.savePreviousBrightness(current)
        runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 1)
        }
    }

    private fun trigger12sLockAndQsGuard(context: Context, app: VoltGuardApp) {
        // Snapshot current QS state (NEVER includes Lock Orientation or Hotspot)
        val wifiMgr = context.getSystemService(WifiManager::class.java)
        val btMgr = context.getSystemService(BluetoothManager::class.java)
        val locMgr = context.getSystemService(LocationManager::class.java)
        val hotspotOn = OemChargeSettingsHelper.isHotspotEnabled(context)

        val wifiWasOn = wifiMgr?.isWifiEnabled == true
        val btWasOn = runCatching { btMgr?.adapter?.isEnabled == true }.getOrDefault(false)
        val syncWasOn = runCatching { ContentResolver.getMasterSyncAutomatically() }.getOrDefault(false)
        val locWasOn = runCatching { locMgr?.isLocationEnabled == true }.getOrDefault(false)
        val dataWasOn = runCatching {
            Settings.Global.getInt(context.contentResolver, "mobile_data", 1) == 1
        }.getOrDefault(false)

        app.preferences.saveQuickSettingsSnapshot(
            wifi = wifiWasOn,
            mobileData = dataWasOn,
            bluetooth = btWasOn,
            sync = syncWasOn,
            location = locWasOn
        )

        // Freeze background sync & restrict BG network/power WITHOUT killing open background apps
        if (syncWasOn) {
            runCatching { ContentResolver.setMasterSyncAutomatically(false) }
        }
        // Suspend radios except Lock Orientation and Hotspot (Hotspot keeps Data active & manages CPU/temp)
        OemChargeSettingsHelper.suspendRadiosForLock(
            context = context,
            keepHotspotData = hotspotOn,
            rootOptIn = app.preferences.isRootBridgeOptInEnabled()
        )

        // Lock device cleanly via DevicePolicyManager if Device Admin is active
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, VoltGuardDeviceAdminReceiver::class.java)
        if (dpm?.isAdminActive(admin) == true) {
            dpm.lockNow()
        }
    }
}
