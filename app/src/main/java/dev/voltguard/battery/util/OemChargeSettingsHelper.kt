package dev.voltguard.battery.util

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object OemChargeSettingsHelper {

    fun enforceBlockedPackagesGuard(
        context: Context,
        blockedPackages: Set<String>,
        rootOptInEnabled: Boolean
    ) {
        val am = context.getSystemService(ActivityManager::class.java)
        for (pkg in blockedPackages) {
            runCatching { am?.killBackgroundProcesses(pkg) }
            if (rootOptInEnabled) {
                runCatching {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $pkg")).waitFor()
                }
            }
        }
    }

    fun stopOrOpenForceStop(context: Context, packageName: String, rootOptInEnabled: Boolean): Boolean {
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            am?.killBackgroundProcesses(packageName)
        }
        if (rootOptInEnabled) {
            val ok = runCatching {
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $packageName"))
                proc.waitFor() == 0
            }.getOrDefault(false)
            if (ok) return true
        }
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return false
    }

    fun hasNativeChargeLimiter(context: Context): Boolean {
        val brand = Build.BRAND.lowercase()
        return brand.contains("xiaomi") ||
            brand.contains("poco") ||
            brand.contains("redmi") ||
            brand.contains("google") ||
            brand.contains("samsung") ||
            brand.contains("oneplus")
    }

    fun openNativeBatteryProtection(context: Context) {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powercenter.PowerMainActivity"
                )
            ),
            Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return
            }
        }
    }

    fun openUsageAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openIgnoreBatteryOptimization(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun isHotspotEnabled(context: Context): Boolean {
        val wm = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java) ?: return false
        return runCatching {
            val method = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            (method.invoke(wm) as? Boolean) == true
        }.getOrDefault(false)
    }

    fun suspendRadiosForLock(context: Context, keepHotspotData: Boolean, rootOptIn: Boolean) {
        if (!rootOptIn) return
        // Optional opt-in Magisk/ADB path: NEVER touches accelerometer_rotation (Lock Orientation) or Hotspot!
        val cmds = mutableListOf("svc wifi disable", "svc bluetooth disable")
        if (!keepHotspotData) {
            cmds.add("svc data disable")
        }
        for (cmd in cmds) {
            runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() }
        }
    }

    fun restoreRadiosFromSnapshot(
        context: Context,
        snap: dev.voltguard.battery.data.prefs.QuickSettingsSnapshot,
        rootOptIn: Boolean
    ) {
        if (!rootOptIn) return
        if (snap.wifi) runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "svc wifi enable")).waitFor() }
        if (snap.mobileData) runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "svc data enable")).waitFor() }
        if (snap.bluetooth) runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "svc bluetooth enable")).waitFor() }
    }

    fun ensureShieldPermissions(context: Context) {
        if (!Settings.System.canWrite(context)) {
            val writeIntent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(writeIntent) }
            return
        }
        val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        val admin = ComponentName(context, dev.voltguard.battery.service.VoltGuardDeviceAdminReceiver::class.java)
        if (dpm != null && !dpm.isAdminActive(admin)) {
            val adminIntent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                putExtra(
                    android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Required so the 12-second Inactivity Lifespan Shield can lock the screen automatically."
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(adminIntent) }
        }
    }

    fun openDeveloperOptions(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
