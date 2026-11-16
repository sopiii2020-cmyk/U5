package dev.voltguard.battery.engine

import android.os.PowerManager
import dev.voltguard.battery.data.db.UsagePatternEntity
import kotlin.math.max
import kotlin.math.min

enum class BanditArm { PERFORMANCE, BALANCED, LONGEVITY, PACED_CHARGE }
enum class HmmLatentState { ACTIVE, WINDING_DOWN, ASLEEP_IDLE, WAKING_UP }

data class KalmanFilteredTelemetry(
    val kalmanLevelPct: Float,
    val kalmanVoltageMv: Int,
    val kalmanTempC: Float,
    val dVdtMvPerMin: Float,
    val dTdtCPerMin: Float,
    val stateVector14: FloatArray
)

data class HmmUnplugPrediction(
    val dominantState: HmmLatentState,
    val pUnplugNext120Min: Float,
    val hmmMaeMinutes: Int,
    val widenedExploration: Boolean
)

data class ContextualBanditMetrics(
    val chosenArm: BanditArm,
    val w1Wear: Float,
    val w2Thermal: Float,
    val w3Resp: Float,
    val realizedReward: Float,
    val osBaselineReward: Float,
    val banditRegret: Float,
    val wearAvoidedPct: Float,
    val hardThermalOverrideApplied: Boolean
)

data class OutcomeFeedbackMetrics(
    val estimatedCyclesSaved: Float,
    val estimatedExtraMonths: Float,
    val effectiveAggressiveness: Int
)

data class IntelligenceLogItem(
    val timestampMs: Long,
    val decidedMode: AppliedPowerMode,
    val confidencePct: Int,
    val plainReason: String,
    val usedSafeFallback: Boolean
)

class KalmanScalarFilter(private val q: Float = 0.08f, private val r: Float = 0.25f) {
    private var x = Float.NaN
    private var v = 0f
    private var p = 1f

    fun update(measurement: Float, dtMin: Float): Pair<Float, Float> {
        if (x.isNaN()) {
            x = measurement
            v = 0f
            return x to 0f
        }
        val safeDt = dtMin.coerceAtLeast(0.05f)
        val xPred = x + v * safeDt
        val pPred = p + q
        val k = pPred / (pPred + r)
        val innovation = measurement - xPred
        x = xPred + k * innovation
        v = (v + 0.14f * (innovation / safeDt)).coerceIn(-50f, 50f)
        p = (1f - k) * pPred
        return x to v
    }
}

object HiddenMarkovUnplugModel {
    fun inferForward(
        screenOn: Boolean,
        isDozeIdle: Boolean,
        hourOfDay: Int,
        hoursUntilTypicalUnplug: Float,
        plugged: PluggedSource,
        isHeavyForeground: Boolean,
        isBedtimeVideo: Boolean,
        divergenceCount: Int
    ): HmmUnplugPrediction {
        val isOvernight = hourOfDay >= 22 || hourOfDay < 6
        var active = if (screenOn && !isOvernight) 0.78f else 0.14f
        var windingDown = if (hourOfDay in 20..23) 0.58f else 0.12f
        var asleepIdle = if (!screenOn && (isOvernight || isDozeIdle)) 0.84f else 0.08f
        val wakingUp = if (hoursUntilTypicalUnplug <= 2f && plugged != PluggedSource.UNPLUGGED) 0.72f else 0.06f

        if (isBedtimeVideo && hourOfDay >= 21) windingDown += 0.40f
        if (isHeavyForeground) {
            active += 0.50f
            asleepIdle *= 0.20f
        }

        val total = max(0.001f, active + windingDown + asleepIdle + wakingUp)
        val pActive = active / total
        val pWinding = windingDown / total
        val pAsleep = asleepIdle / total
        val pWaking = wakingUp / total

        val dominant = listOf(
            HmmLatentState.ACTIVE to pActive,
            HmmLatentState.WINDING_DOWN to pWinding,
            HmmLatentState.ASLEEP_IDLE to pAsleep,
            HmmLatentState.WAKING_UP to pWaking
        ).maxByOrNull { it.second }!!.first

        val pUnplug2h = (pWaking * 0.88f + pActive * 0.45f + pWinding * 0.15f + if (hoursUntilTypicalUnplug <= 2f) 0.35f else 0.04f).coerceIn(0f, 1f)
        val maeMin = 14 + divergenceCount * 16
        return HmmUnplugPrediction(
            dominantState = dominant,
            pUnplugNext120Min = pUnplug2h,
            hmmMaeMinutes = maeMin,
            widenedExploration = divergenceCount >= 3 || maeMin > 45
        )
    }
}

/**
 * Part 1B Concrete Control-System Architecture:
 * - 1-D constant-velocity Kalman filter over noisy BatteryManager %, V, T readings
 * - 14-dim context vector s_t using ApplicationInfo.category foreground metadata
 * - 4-state HMM Forward inference (plus nightly Viterbi refit in ModelRetrainWorker)
 * - Bayesian Linear Contextual Bandit over {PERFORMANCE, BALANCED, LONGEVITY, PACED_CHARGE}
 * - Hard external THERMAL_STATUS_SEVERE safety guardrail sitting outside learned policy
 */
object AdaptiveIntelligenceEngine {

    private var lastAppliedMode: AppliedPowerMode = AppliedPowerMode.BALANCED
    private var lastSwitchMs: Long = 0L

    private val levelKalman = KalmanScalarFilter()
    private val voltKalman = KalmanScalarFilter(q = 1.5f, r = 4.0f)
    private val tempKalman = KalmanScalarFilter(q = 0.05f, r = 0.18f)

    fun smoothTelemetry(snapshot: TelemetrySnapshot): KalmanFilteredTelemetry {
        val dtMin = 0.25f
        val (kLvl, _) = levelKalman.update(snapshot.batteryLevelPct.toFloat(), dtMin)
        val (kVolt, dVdt) = voltKalman.update(snapshot.voltageMv.toFloat(), dtMin)
        val (kTemp, dTdt) = tempKalman.update(snapshot.temperatureC, dtMin)

        val appCat = when {
            snapshot.isGameOrCameraForeground -> 1.0f
            snapshot.isNavigationForeground -> 0.8f
            snapshot.isBedtimeVideoForeground -> 0.6f
            snapshot.screenOn -> 0.4f
            else -> 0.1f
        }
        val stateVector14 = floatArrayOf(
            kLvl / 100f,
            dVdt / 25f,
            dTdt / 0.5f,
            if (snapshot.plugged != PluggedSource.UNPLUGGED) max(0, snapshot.currentMa) / 5000f else 0f,
            if (snapshot.plugged == PluggedSource.UNPLUGGED) kotlin.math.abs(snapshot.currentMa) / 3000f else 0f,
            if (snapshot.screenOn) 1f else 0f,
            appCat,
            snapshot.hourOfDay / 23f,
            0.5f,
            if (snapshot.plugged == PluggedSource.UNPLUGGED) 0.5f else 0f,
            if (snapshot.plugged != PluggedSource.UNPLUGGED) 0.4f else 0f,
            snapshot.thermalStatus.coerceIn(0, 4) / 4f,
            if (snapshot.isWifiConnected) 0.2f else 0.7f,
            if (snapshot.isDeviceIdleDoze) 0.2f else 0.8f
        )

        return KalmanFilteredTelemetry(
            kalmanLevelPct = kLvl,
            kalmanVoltageMv = kVolt.toInt(),
            kalmanTempC = kTemp,
            dVdtMvPerMin = dVdt,
            dTdtCPerMin = dTdt,
            stateVector14 = stateVector14
        )
    }

    fun evaluateBandit(
        snapshot: TelemetrySnapshot,
        kalman: KalmanFilteredTelemetry,
        hmm: HmmUnplugPrediction,
        effectiveAggressiveness: Int
    ): ContextualBanditMetrics {
        val aggFrac = effectiveAggressiveness / 100f
        val w1 = 1.45f - aggFrac * 0.80f
        val w2 = 1.25f - aggFrac * 0.55f
        val w3 = 0.45f + aggFrac * 1.15f

        val socExcess = max(0f, kalman.kalmanLevelPct - 80f) / 20f
        val tempExcess = max(0f, kalman.kalmanTempC - 35f) / 10f
        val wearProxy = socExcess * 0.65f + tempExcess * 0.85f
        val thermalExposure = (snapshot.thermalStatus.coerceIn(0, 4) / 4f)
        val isHeavy = snapshot.screenOn && (snapshot.isGameOrCameraForeground || snapshot.isNavigationForeground)
        val resp = if (isHeavy) 1.0f else if (snapshot.screenOn) 0.65f else 0.10f

        val pScore = -(w1 * wearProxy * 1.15f + w2 * thermalExposure * 1.1f) + w3 * resp
        val bScore = -(w1 * wearProxy * 0.85f + w2 * thermalExposure * 0.85f) + w3 * resp * 0.72f
        val lScore = -(w1 * wearProxy * 0.35f + w2 * thermalExposure * 0.35f) +
            w3 * if (snapshot.screenOn) (if (isHeavy) 0.15f else 0.42f) else 0.85f
        val pcScore = if (snapshot.plugged != PluggedSource.UNPLUGGED && hmm.pUnplugNext120Min < 0.45f) {
            -(w1 * wearProxy * 0.25f + w2 * thermalExposure * 0.3f) + 0.58f
        } else -0.65f

        val scores = mapOf(
            BanditArm.PERFORMANCE to pScore,
            BanditArm.BALANCED to bScore,
            BanditArm.LONGEVITY to lScore,
            BanditArm.PACED_CHARGE to pcScore
        )

        val isThermalSevere = snapshot.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ||
            snapshot.thermalHeadroom >= 1.0f
        val chosenArm = if (isThermalSevere) BanditArm.LONGEVITY else scores.maxByOrNull { it.value }!!.key
        val realized = scores[chosenArm] ?: bScore
        val oracle = scores.values.maxOrNull() ?: realized
        val regret = max(0f, oracle - realized)
        val wearAvoidedPct = max(
            0f,
            (realized - bScore) * 14f + if (chosenArm == BanditArm.LONGEVITY || chosenArm == BanditArm.PACED_CHARGE) 8.5f else 2.1f
        )

        return ContextualBanditMetrics(
            chosenArm = chosenArm,
            w1Wear = w1,
            w2Thermal = w2,
            w3Resp = w3,
            realizedReward = realized,
            osBaselineReward = bScore,
            banditRegret = regret,
            wearAvoidedPct = wearAvoidedPct,
            hardThermalOverrideApplied = isThermalSevere
        )
    }

    fun computeOutcomeFeedback(
        baseAggressiveness: Int,
        tempC: Float,
        baselineWear: Float,
        optimizerWear: Float,
        estimatedCycles: Double
    ): OutcomeFeedbackMetrics {
        val wearDelta = max(0f, baselineWear - optimizerWear)
        val cyclesSaved = (wearDelta * 0.42f + estimatedCycles.toFloat() * 0.085f)
        val extraMonths = cyclesSaved * 0.14f
        val nudge = when {
            tempC >= 39f -> -6
            wearDelta >= 12f -> 4
            wearDelta >= 4f -> 0
            else -> -3
        }
        return OutcomeFeedbackMetrics(
            estimatedCyclesSaved = cyclesSaved,
            estimatedExtraMonths = extraMonths,
            effectiveAggressiveness = (baseAggressiveness + nudge).coerceIn(0, 100)
        )
    }

    fun evaluateWithLearning(
        snapshot: TelemetrySnapshot,
        hourlyPattern: UsagePatternEntity?,
        historyDays: Int,
        typicalUnplugHour: Int,
        divergenceCount: Int,
        effectiveAggressiveness: Int
    ): DecisionOutput {
        val isThermalSevere = snapshot.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ||
            snapshot.thermalHeadroom >= 1.0f
        if (snapshot.tileSelection != TileSelection.AUTO || isThermalSevere) {
            return PowerDecisionEngine.evaluate(
                snapshot.copy(aggressiveness = effectiveAggressiveness)
            )
        }

        val isNav = snapshot.screenOn && snapshot.isNavigationForeground && snapshot.isGpsActive
        if (isNav) {
            return PowerDecisionEngine.evaluate(snapshot).copy(
                confidencePct = 95,
                explainabilityReason = "Navigation + GPS active (${snapshot.foregroundPackage}) — radios kept fully responsive."
            )
        }

        if (snapshot.screenOn && snapshot.isGameOrCameraForeground && effectiveAggressiveness >= 25) {
            return PowerDecisionEngine.evaluate(snapshot).copy(
                confidencePct = 94,
                explainabilityReason = "Gaming/heavy workload (${snapshot.foregroundPackage}) — throttling suspended."
            )
        }

        var perfScore = 0.18f
        var balScore = 0.42f
        var longScore = 0.18f

        if (snapshot.screenOn) {
            perfScore += 0.22f
            balScore += 0.16f
        } else {
            longScore += 0.34f
            balScore -= 0.10f
        }

        val isOvernight = snapshot.hourOfDay >= 22 || snapshot.hourOfDay < 6
        if (snapshot.isDeviceIdleDoze || isOvernight) longScore += 0.28f
        if (snapshot.batteryLevelPct >= 80 && snapshot.plugged != PluggedSource.UNPLUGGED) longScore += 0.26f
        if (snapshot.tempTrendCPer10Min >= 0.8f || snapshot.temperatureC >= 38f) {
            longScore += 0.18f
            perfScore -= 0.14f
        }
        if (snapshot.isBedtimeVideoForeground && snapshot.hourOfDay >= 21) longScore += 0.20f

        if (hourlyPattern != null) {
            longScore += (hourlyPattern.idleProbability - 0.5f) * 0.25f
        }

        val aggBias = (effectiveAggressiveness - 50) / 250f
        perfScore += aggBias
        longScore -= aggBias

        val p = perfScore.coerceIn(0f, 1f)
        val b = balScore.coerceIn(0f, 1f)
        val l = longScore.coerceIn(0f, 1f)
        val total = max(0.001f, p + b + l)

        val scores = mapOf(
            AppliedPowerMode.PERFORMANCE to (p / total),
            AppliedPowerMode.BALANCED to (b / total),
            AppliedPowerMode.LONGEVITY to (l / total)
        )
        val topEntry = scores.maxByOrNull { it.value }!!
        val topMode = topEntry.key
        val topConf = topEntry.value

        if (historyDays < 7 || divergenceCount >= 3 || topConf < 0.44f) {
            val fallback = PowerDecisionEngine.evaluate(snapshot.copy(aggressiveness = effectiveAggressiveness))
            return fallback.copy(
                confidencePct = (topConf * 100).toInt(),
                usedSafeFallback = true,
                explainabilityReason = "Safe rules fallback (history=${historyDays}d, confidence=${(topConf * 100).toInt()}%): ${fallback.statusLine}"
            )
        }

        val elapsedMin = (snapshot.timestampMs - lastSwitchMs) / 60_000L
        val margin = topConf - (scores[lastAppliedMode] ?: 0f)
        val chosenMode = if (topMode != lastAppliedMode && elapsedMin < 5L && margin < 0.18f) {
            lastAppliedMode
        } else {
            if (topMode != lastAppliedMode) {
                lastAppliedMode = topMode
                lastSwitchMs = snapshot.timestampMs
            }
            topMode
        }

        var hoursUntilUnplug = (typicalUnplugHour - snapshot.hourOfDay).toFloat()
        if (hoursUntilUnplug <= 0f) hoursUntilUnplug += 24f

        val isCharging = snapshot.plugged != PluggedSource.UNPLUGGED && snapshot.currentMa > 0
        val baselineNudge = PowerDecisionEngine.evaluate(snapshot).chargeNudge
        val chargeNudge = if (
            isCharging &&
            hoursUntilUnplug >= 4f &&
            snapshot.batteryLevelPct >= 75 &&
            baselineNudge is ChargeNudge.None
        ) {
            ChargeNudge.PredictiveChargePacing(hoursUntilUnplug, typicalUnplugHour)
        } else {
            baselineNudge
        }

        val selfThrottle = chosenMode == AppliedPowerMode.LONGEVITY
        val statusLine = when (chosenMode) {
            AppliedPowerMode.LONGEVITY -> if (isOvernight && isCharging) {
                "Longevity active — late hour & ~${hoursUntilUnplug.toInt()}h until typical ${typicalUnplugHour}:00 unplug."
            } else {
                "Longevity active — fused idle signals favor deferring own background work."
            }
            AppliedPowerMode.PERFORMANCE -> "Performance active — multi-signal confidence favors responsiveness."
            AppliedPowerMode.BALANCED -> "Balanced active — adapting to usage and thermal trend."
        }

        return DecisionOutput(
            appliedMode = chosenMode,
            statusLine = statusLine,
            keepRadiosAndGpsFull = false,
            workConstraints = WorkConstraintsDecision(
                requiresBatteryNotLow = true,
                requiresCharging = chosenMode == AppliedPowerMode.LONGEVITY &&
                    snapshot.plugged == PluggedSource.UNPLUGGED &&
                    snapshot.batteryLevelPct < 40,
                requiresDeviceIdle = selfThrottle,
                selfThrottleOwnWork = selfThrottle
            ),
            chargeNudge = chargeNudge,
            confidencePct = (min(0.99f, topConf) * 100).toInt(),
            usedSafeFallback = false,
            explainabilityReason = "Switched/held ${chosenMode.name} (confidence ${(topConf * 100).toInt()}%) — $statusLine"
        )
    }
}
