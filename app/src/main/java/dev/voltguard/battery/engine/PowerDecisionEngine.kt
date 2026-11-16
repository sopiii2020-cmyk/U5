package dev.voltguard.battery.engine

import android.os.PowerManager

enum class TileSelection { AUTO, PERFORMANCE, LONGEVITY, INTL }
enum class HomeProfileMode { AUTOMATIC, BALANCE, ULTRA_SAFE, INTELLIGENT }
enum class AppliedPowerMode { PERFORMANCE, BALANCED, LONGEVITY }
enum class PluggedSource { UNPLUGGED, AC, USB, WIRELESS }

data class HeavyAppUsage(
    val packageName: String,
    val appLabel: String,
    val estimatedMahPerHr: Int,
    val batteryPctPerHr: Float,
    val cpuLoadPct: Int,
    val tempDeltaC: Float,
    val isBlocked: Boolean = false
)

data class TelemetrySnapshot(
    val timestampMs: Long,
    val batteryLevelPct: Int,
    val voltageMv: Int,
    val temperatureC: Float,
    val tempTrendCPer10Min: Float = 0f,
    val currentMa: Int,
    val previousChargeCurrentMa: Int,
    val plugged: PluggedSource,
    val screenOn: Boolean,
    val refreshRateHz: Float,
    val displayBrightnessPct: Int,
    val hourOfDay: Int,
    val thermalStatus: Int, // PowerManager.THERMAL_STATUS_*
    val thermalHeadroom: Float, // 0f..1.5f via PowerManager.getThermalHeadroom()
    val isPowerSaveMode: Boolean,
    val isDeviceIdleDoze: Boolean,
    val isIgnoringBatteryOpt: Boolean,
    val foregroundPackage: String,
    val isGameOrCameraForeground: Boolean,
    val isNavigationForeground: Boolean = false,
    val isGpsActive: Boolean = false,
    val isBedtimeVideoForeground: Boolean = false,
    val isWifiConnected: Boolean,
    val stayAwakeEnabled: Boolean,
    val usbDebuggingEnabled: Boolean,
    val usageAccessGranted: Boolean,
    val aggressiveness: Int, // 0 (Battery-first) .. 100 (Performance-first)
    val tileSelection: TileSelection,
    val oemNativeChargeLimitSupported: Boolean,
    val isHotspotActive: Boolean = false,
    val isInactivityShieldEnabled: Boolean = false
)

data class WorkConstraintsDecision(
    val requiresBatteryNotLow: Boolean,
    val requiresCharging: Boolean,
    val requiresDeviceIdle: Boolean,
    val selfThrottleOwnWork: Boolean
)

sealed class ChargeNudge {
    data object None : ChargeNudge()
    data class UnplugAt80(val levelPct: Int) : ChargeNudge()
    data object OemNativeChargeLimitLink : ChargeNudge()
    data class JeitaThermalSlowdown(val tempC: Float, val currentMa: Int) : ChargeNudge()
    data class GamingBypassPosture(val packageName: String, val tempC: Float) : ChargeNudge()
    data class PredictiveChargePacing(val hoursUntilUnplug: Float, val predictedUnplugHour: Int) : ChargeNudge()
}

data class DecisionOutput(
    val appliedMode: AppliedPowerMode,
    val statusLine: String,
    val keepRadiosAndGpsFull: Boolean = false,
    val workConstraints: WorkConstraintsDecision,
    val chargeNudge: ChargeNudge,
    val confidencePct: Int = 100,
    val usedSafeFallback: Boolean = false,
    val explainabilityReason: String = statusLine
)

/**
 * Baseline Part 1 rules engine (also acts as the guaranteed Safe Fallback for Part 1B).
 */
object PowerDecisionEngine {

    fun cycleTile(current: TileSelection): TileSelection = when (current) {
        TileSelection.AUTO -> TileSelection.PERFORMANCE
        TileSelection.PERFORMANCE -> TileSelection.LONGEVITY
        TileSelection.LONGEVITY -> TileSelection.INTL
        TileSelection.INTL -> TileSelection.AUTO
    }

    fun evaluate(snapshot: TelemetrySnapshot): DecisionOutput {
        val isThermalSevere = snapshot.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ||
            snapshot.thermalHeadroom >= 1.0f

        val isOvernight = snapshot.hourOfDay >= 22 || snapshot.hourOfDay < 6
        val isNavigationContext = snapshot.screenOn && snapshot.isNavigationForeground && snapshot.isGpsActive
        val isLongevityCandidate = !snapshot.screenOn && (
            snapshot.isDeviceIdleDoze ||
                isOvernight ||
                snapshot.batteryLevelPct >= 80 ||
                (snapshot.plugged != PluggedSource.UNPLUGGED && snapshot.temperatureC >= 38f) ||
                snapshot.aggressiveness <= 35
            )

        val isVoltageSagRisk = snapshot.plugged == PluggedSource.UNPLUGGED &&
            ((snapshot.voltageMv in 1..3480 && snapshot.batteryLevelPct >= 25) || snapshot.voltageMv in 1..3380)

        val (appliedMode, statusLine) = when (snapshot.tileSelection) {
            TileSelection.INTL -> {
                AppliedPowerMode.LONGEVITY to
                    "Intl Max-Lifespan locked via Quick Settings — stays pinned until you tap the tile again."
            }
            TileSelection.PERFORMANCE -> {
                if (isThermalSevere) {
                    AppliedPowerMode.BALANCED to
                        "Thermal limit reached — self-throttling background work despite Performance tile."
                } else {
                    AppliedPowerMode.PERFORMANCE to
                        "Performance mode pinned via Quick Settings — deferral paused."
                }
            }
            TileSelection.LONGEVITY -> {
                if (isNavigationContext) {
                    AppliedPowerMode.BALANCED to
                        "Navigation + GPS active — keeping radios & location responsive."
                } else {
                    AppliedPowerMode.LONGEVITY to
                        "Longevity mode pinned via Quick Settings — non-urgent tasks deferred to idle."
                }
            }
            TileSelection.AUTO -> when {
                isVoltageSagRisk -> AppliedPowerMode.LONGEVITY to
                    "Voltage sag detected — reducing peak load to prevent abrupt cell cutoff."
                snapshot.isHotspotActive && snapshot.temperatureC >= 37.5f -> AppliedPowerMode.LONGEVITY to
                    "Hotspot active — keeping tethering ON while throttling CPU/temp to normal."
                isThermalSevere -> AppliedPowerMode.LONGEVITY to
                    "Severe thermal status — deferring own work to let SoC and battery cool."
                isNavigationContext -> AppliedPowerMode.PERFORMANCE to
                    "Navigation + GPS session active — keeping radios and location responsive."
                snapshot.screenOn && snapshot.isGameOrCameraForeground && snapshot.aggressiveness >= 25 ->
                    AppliedPowerMode.PERFORMANCE to if (snapshot.plugged != PluggedSource.UNPLUGGED) {
                        "Performance mode active — gaming/heavy app plugged, skipping deferral."
                    } else {
                        "Performance mode active — foreground workload protected from throttling."
                    }
                isLongevityCandidate -> AppliedPowerMode.LONGEVITY to when {
                    snapshot.plugged != PluggedSource.UNPLUGGED && snapshot.temperatureC >= 38f ->
                        "Longevity mode active — screen off, charging slowed intentionally to protect battery."
                    snapshot.batteryLevelPct >= 80 && snapshot.plugged != PluggedSource.UNPLUGGED ->
                        "Longevity mode active — battery past 80%, unplug recommended."
                    else ->
                        "Longevity mode active — screen off and idle, own sync deferred."
                }
                snapshot.screenOn && snapshot.aggressiveness >= 75 ->
                    AppliedPowerMode.PERFORMANCE to
                        "Performance mode active — screen on with performance-first slider."
                else -> AppliedPowerMode.BALANCED to
                    "Balanced mode active — monitoring battery, thermal, and usage context."
            }
        }

        val selfThrottle = (isThermalSevere || appliedMode == AppliedPowerMode.LONGEVITY) && !isNavigationContext
        val constraints = WorkConstraintsDecision(
            requiresBatteryNotLow = true,
            requiresCharging = appliedMode == AppliedPowerMode.LONGEVITY &&
                snapshot.plugged == PluggedSource.UNPLUGGED &&
                snapshot.batteryLevelPct < 40,
            requiresDeviceIdle = selfThrottle,
            selfThrottleOwnWork = selfThrottle
        )

        val isCharging = snapshot.plugged != PluggedSource.UNPLUGGED && snapshot.currentMa > 0
        val currentTapered = snapshot.previousChargeCurrentMa > 1500 &&
            snapshot.currentMa < (snapshot.previousChargeCurrentMa * 0.68f)

        val chargeNudge = when {
            isCharging && snapshot.temperatureC >= 38f && (currentTapered || snapshot.currentMa < 2200) ->
                ChargeNudge.JeitaThermalSlowdown(snapshot.temperatureC, snapshot.currentMa)
            isCharging && snapshot.screenOn && snapshot.isGameOrCameraForeground ->
                ChargeNudge.GamingBypassPosture(snapshot.foregroundPackage, snapshot.temperatureC)
            isCharging && snapshot.batteryLevelPct >= 80 ->
                if (snapshot.oemNativeChargeLimitSupported) ChargeNudge.OemNativeChargeLimitLink
                else ChargeNudge.UnplugAt80(snapshot.batteryLevelPct)
            else -> ChargeNudge.None
        }

        return DecisionOutput(
            appliedMode = appliedMode,
            statusLine = statusLine,
            keepRadiosAndGpsFull = isNavigationContext,
            workConstraints = constraints,
            chargeNudge = chargeNudge
        )
    }
}
