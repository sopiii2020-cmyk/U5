package dev.voltguard.battery.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import androidx.core.app.NotificationCompat
import dev.voltguard.battery.R
import dev.voltguard.battery.VoltGuardApp
import dev.voltguard.battery.data.db.BatterySampleEntity
import dev.voltguard.battery.data.db.CycleStatsEstimator
import dev.voltguard.battery.engine.AdaptiveIntelligenceEngine
import dev.voltguard.battery.engine.ChargeNudge
import dev.voltguard.battery.engine.ContextualBanditMetrics
import dev.voltguard.battery.engine.DecisionOutput
import dev.voltguard.battery.engine.HeavyAppUsage
import dev.voltguard.battery.engine.HiddenMarkovUnplugModel
import dev.voltguard.battery.engine.HmmUnplugPrediction
import dev.voltguard.battery.engine.IntelligenceLogItem
import dev.voltguard.battery.engine.KalmanFilteredTelemetry
import dev.voltguard.battery.engine.OutcomeFeedbackMetrics
import dev.voltguard.battery.engine.PluggedSource
import dev.voltguard.battery.engine.TelemetrySnapshot
import android.content.pm.ApplicationInfo
import dev.voltguard.battery.ui.MainActivity
import dev.voltguard.battery.util.OemChargeSettingsHelper
import dev.voltguard.battery.worker.DeferredMaintenanceWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

class BatteryMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                scope.launch {
                    collectAndEvaluateNow(applicationContext, intent, postNotification = true)
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    InactivityShieldController.onPowerButtonWake(applicationContext)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    InactivityShieldController.onScreenOff()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildMonitorNotification(this, "Initializing battery telemetry..."))
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, screenFilter)
        InactivityShieldController.syncWithPreference(this)
        startPeriodicLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        InactivityShieldController.syncWithPreference(this)
        if (intent?.action == ACTION_REFRESH_NOW) {
            scope.launch {
                val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (sticky != null) collectAndEvaluateNow(applicationContext, sticky, postNotification = true)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(batteryReceiver)
        unregisterReceiver(screenReceiver)
        InactivityShieldController.stopWatching()
        monitorJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPeriodicLoop() {
        monitorJob = scope.launch {
            while (isActive) {
                val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (sticky != null) collectAndEvaluateNow(applicationContext, sticky, postNotification = true)
                val currentThermal = latestSnapshot.value?.thermalStatus ?: 0
                val intervalMs = if (currentThermal >= PowerManager.THERMAL_STATUS_SEVERE) 60_000L else 15_000L
                delay(intervalMs)
            }
        }
    }

    companion object {
        const val ACTION_REFRESH_NOW = "dev.voltguard.battery.REFRESH_NOW"
        private const val NOTIFICATION_ID = 1001
        private const val ALERT_80_ID = 1002
        private val KNOWN_HEAVY_PACKAGES = listOf("genshin", "pubg", "codm", "camera", "benchmark", "3dmark", "wildrift")
        private val KNOWN_NAV_PACKAGES = listOf("osmand", "maps", "waze", "sygic")
        private val KNOWN_VIDEO_PACKAGES = listOf("youtube", "netflix", "vlc", "mxtech", "twitch")

        @Volatile private var previousChargeMa = 0
        @Volatile private var previousTempC = 30f
        @Volatile private var lastSampledChargeUah = -1L

        private val _latestSnapshot = MutableStateFlow<TelemetrySnapshot?>(null)
        val latestSnapshot: StateFlow<TelemetrySnapshot?> = _latestSnapshot.asStateFlow()

        private val _latestDecision = MutableStateFlow<DecisionOutput?>(null)
        val latestDecision: StateFlow<DecisionOutput?> = _latestDecision.asStateFlow()

        private val _latestOutcomeFeedback = MutableStateFlow<OutcomeFeedbackMetrics?>(null)
        val latestOutcomeFeedback: StateFlow<OutcomeFeedbackMetrics?> = _latestOutcomeFeedback.asStateFlow()

        private val _latestKalmanTelemetry = MutableStateFlow<KalmanFilteredTelemetry?>(null)
        val latestKalmanTelemetry: StateFlow<KalmanFilteredTelemetry?> = _latestKalmanTelemetry.asStateFlow()

        private val _latestHmmPrediction = MutableStateFlow<HmmUnplugPrediction?>(null)
        val latestHmmPrediction: StateFlow<HmmUnplugPrediction?> = _latestHmmPrediction.asStateFlow()

        private val _latestBanditMetrics = MutableStateFlow<ContextualBanditMetrics?>(null)
        val latestBanditMetrics: StateFlow<ContextualBanditMetrics?> = _latestBanditMetrics.asStateFlow()

        private val _decisionLogs = MutableStateFlow<List<IntelligenceLogItem>>(emptyList())
        val decisionLogs: StateFlow<List<IntelligenceLogItem>> = _decisionLogs.asStateFlow()

        private val _liveRuntimeSummary = MutableStateFlow("Estimating live runtime...")
        val liveRuntimeSummary: StateFlow<String> = _liveRuntimeSummary.asStateFlow()

        private val _heavyBatteryApps = MutableStateFlow<List<HeavyAppUsage>>(emptyList())
        val heavyBatteryApps: StateFlow<List<HeavyAppUsage>> = _heavyBatteryApps.asStateFlow()

        private val _heavyCpuTempApps = MutableStateFlow<List<HeavyAppUsage>>(emptyList())
        val heavyCpuTempApps: StateFlow<List<HeavyAppUsage>> = _heavyCpuTempApps.asStateFlow()

        fun markPackageStopped(packageName: String) {
            _heavyBatteryApps.value = _heavyBatteryApps.value.filterNot { it.packageName == packageName }
            _heavyCpuTempApps.value = _heavyCpuTempApps.value.filterNot { it.packageName == packageName }
        }

        private fun classifyForegroundViaManifest(context: Context, packageName: String): Triple<Boolean, Boolean, Boolean> {
            val cat = runCatching {
                context.packageManager.getApplicationInfo(packageName, 0).category
            }.getOrDefault(ApplicationInfo.CATEGORY_UNDEFINED)
            val isGameOrCam = cat == ApplicationInfo.CATEGORY_GAME ||
                KNOWN_HEAVY_PACKAGES.any { packageName.contains(it, ignoreCase = true) }
            val isNav = cat == ApplicationInfo.CATEGORY_MAPS ||
                KNOWN_NAV_PACKAGES.any { packageName.contains(it, ignoreCase = true) }
            val isVideo = cat == ApplicationInfo.CATEGORY_VIDEO ||
                KNOWN_VIDEO_PACKAGES.any { packageName.contains(it, ignoreCase = true) }
            return Triple(isGameOrCam, isNav, isVideo)
        }

        suspend fun collectAndEvaluateNow(
            context: Context,
            providedIntent: Intent? = null,
            postNotification: Boolean = false
        ) {
            val app = context.applicationContext as VoltGuardApp
            val batteryIntent = providedIntent
                ?: context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return
            val bm = context.getSystemService(BatteryManager::class.java)
            val pm = context.getSystemService(PowerManager::class.java)
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val dm = context.getSystemService(DisplayManager::class.java)
            val lm = context.getSystemService(LocationManager::class.java)

        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val levelPct = ((level * 100f) / scale).toInt().coerceIn(0, 100)
        val voltageMv = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val tempC = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300) / 10f
        val tempTrend = tempC - previousTempC
        previousTempC = tempC

        val plugged = when (batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> PluggedSource.AC
            BatteryManager.BATTERY_PLUGGED_USB -> PluggedSource.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> PluggedSource.WIRELESS
            else -> PluggedSource.UNPLUGGED
        }

        val currentMa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000
        val chargeCounterUah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else 0
        val thermalHeadroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getThermalHeadroom(10).takeIf { !it.isNaN() } ?: 0f
        } else 0f

        val primaryDisplay = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val refreshRateHz = primaryDisplay?.mode?.refreshRate ?: 60f
        val brightnessRaw = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        val brightnessPct = ((brightnessRaw / 255f) * 100).toInt().coerceIn(0, 100)

        val isWifi = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isGpsEnabled = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true

        val (fgPkg, usageGranted) = queryRecentForegroundPackage(context)
        val (isHeavyGame, isNavApp, isVideoBed) = classifyForegroundViaManifest(context, fgPkg)

        val stayAwake = Settings.Global.getInt(context.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) != 0
        val adbEnabled = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) != 0
        val hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        val snapshot = TelemetrySnapshot(
            timestampMs = System.currentTimeMillis(),
            batteryLevelPct = levelPct,
            voltageMv = voltageMv,
            temperatureC = tempC,
            tempTrendCPer10Min = tempTrend,
            currentMa = currentMa,
            previousChargeCurrentMa = previousChargeMa,
            plugged = plugged,
            screenOn = pm.isInteractive,
            refreshRateHz = refreshRateHz,
            displayBrightnessPct = brightnessPct,
            hourOfDay = hourOfDay,
            thermalStatus = thermalStatus,
            thermalHeadroom = thermalHeadroom,
            isPowerSaveMode = pm.isPowerSaveMode,
            isDeviceIdleDoze = pm.isDeviceIdleMode,
            isIgnoringBatteryOpt = pm.isIgnoringBatteryOptimizations(context.packageName),
            foregroundPackage = fgPkg,
            isGameOrCameraForeground = isHeavyGame,
            isNavigationForeground = isNavApp,
            isGpsActive = isGpsEnabled,
            isBedtimeVideoForeground = isVideoBed,
            isWifiConnected = isWifi,
            stayAwakeEnabled = stayAwake,
            usbDebuggingEnabled = adbEnabled,
            usageAccessGranted = usageGranted,
            aggressiveness = app.preferences.getAggressiveness(),
            tileSelection = app.preferences.getTileSelection(),
            oemNativeChargeLimitSupported = OemChargeSettingsHelper.hasNativeChargeLimiter(context),
            isHotspotActive = OemChargeSettingsHelper.isHotspotEnabled(context),
            isInactivityShieldEnabled = app.preferences.isInactivityShieldEnabled()
        )
        InactivityShieldController.syncWithPreference(context)

        if (plugged != PluggedSource.UNPLUGGED && currentMa > 0) previousChargeMa = currentMa

        val patternDao = app.database.usagePatternDao()
        val hourlyPattern = patternDao.getHourPattern(hourOfDay)
        val historyDays = patternDao.getDistinctDaysCount()
        val totalChargedUah = app.database.sampleDao().observeTotalChargedUah().firstOrNull() ?: 0L
        val cycles = CycleStatsEstimator.estimateCycles(totalChargedUah)

        val feedback = AdaptiveIntelligenceEngine.computeOutcomeFeedback(
            baseAggressiveness = snapshot.aggressiveness,
            tempC = tempC,
            baselineWear = hourlyPattern?.baselineWearScore ?: 48f,
            optimizerWear = hourlyPattern?.optimizerWearScore ?: 34f,
            estimatedCycles = cycles
        )

        val typicalUnplugHour = app.preferences.getTypicalUnplugHour()
        val divergenceCount = app.preferences.getDivergenceCount()
        var hoursUntilUnplug = (typicalUnplugHour - hourOfDay).toFloat()
        if (hoursUntilUnplug <= 0f) hoursUntilUnplug += 24f

        val kalman = AdaptiveIntelligenceEngine.smoothTelemetry(snapshot)
        val hmm = HiddenMarkovUnplugModel.inferForward(
            screenOn = snapshot.screenOn,
            isDozeIdle = snapshot.isDeviceIdleDoze,
            hourOfDay = hourOfDay,
            hoursUntilTypicalUnplug = hoursUntilUnplug,
            plugged = plugged,
            isHeavyForeground = isHeavyGame || isNavApp,
            isBedtimeVideo = isVideoBed,
            divergenceCount = divergenceCount
        )
        val bandit = AdaptiveIntelligenceEngine.evaluateBandit(
            snapshot = snapshot,
            kalman = kalman,
            hmm = hmm,
            effectiveAggressiveness = feedback.effectiveAggressiveness
        )

        val decision = AdaptiveIntelligenceEngine.evaluateWithLearning(
            snapshot = snapshot,
            hourlyPattern = hourlyPattern,
            historyDays = historyDays,
            typicalUnplugHour = typicalUnplugHour,
            divergenceCount = divergenceCount,
            effectiveAggressiveness = feedback.effectiveAggressiveness
        )

        _latestSnapshot.value = snapshot
        _latestDecision.value = decision
        _latestOutcomeFeedback.value = feedback
        _latestKalmanTelemetry.value = kalman
        _latestHmmPrediction.value = hmm
        _latestBanditMetrics.value = bandit

        val updatedLogs = (listOf(
            IntelligenceLogItem(
                timestampMs = snapshot.timestampMs,
                decidedMode = decision.appliedMode,
                confidencePct = decision.confidencePct,
                plainReason = decision.explainabilityReason,
                usedSafeFallback = decision.usedSafeFallback
            )
        ) + _decisionLogs.value).take(20)
        _decisionLogs.value = updatedLogs

        var deltaChargedUah = 0L
        if (lastSampledChargeUah > 0 && chargeCounterUah > lastSampledChargeUah) {
            deltaChargedUah = chargeCounterUah - lastSampledChargeUah
        }
        lastSampledChargeUah = chargeCounterUah

        app.database.sampleDao().insert(
            BatterySampleEntity(
                timestampMs = snapshot.timestampMs,
                batteryPct = levelPct,
                voltageMv = voltageMv,
                temperatureC = tempC,
                currentMa = currentMa,
                pluggedCode = plugged.ordinal,
                appliedMode = decision.appliedMode.name,
                chargedDeltaUah = deltaChargedUah
            )
        )

        DeferredMaintenanceWorker.scheduleWithDecision(context, decision.workConstraints)

        // Compute live remaining battery time from real remaining mAh & draw (zero polling overhead)
        val remainingMah = if (chargeCounterUah > 0) (chargeCounterUah / 1000).toInt() else ((5160 * levelPct) / 100)
        val absDrawMa = kotlin.math.abs(currentMa).coerceAtLeast(120)
        val watts = (absDrawMa / 1000f) * (voltageMv / 1000f)
        _liveRuntimeSummary.value = if (plugged != PluggedSource.UNPLUGGED && currentMa > 0) {
            val mahTo80 = (((5160 * 0.8f) - remainingMah).coerceAtLeast(0f)).toInt()
            val minsTo80 = ((mahTo80 / currentMa.coerceAtLeast(250).toFloat()) * 60f).toInt()
            if (mahTo80 > 0) {
                String.format(java.util.Locale.US, "Charging · ~%dm to 80%% · %.2f W", minsTo80, watts)
            } else {
                String.format(java.util.Locale.US, "At 80%%+ cap · ~%.1fh standby", remainingMah / 360f)
            }
        } else {
            val totalMins = ((remainingMah / absDrawMa.toFloat()) * 60f).toInt().coerceAtLeast(15)
            String.format(java.util.Locale.US, "%dh %02dm left · %.2f W", totalMins / 60, totalMins % 60, watts)
        }

        // Enforce persistent Force-Stop guard on all user-ticked heavy apps every cycle until unticked
        val blockedSet = app.preferences.getBlockedPackages()
        if (blockedSet.isNotEmpty()) {
            OemChargeSettingsHelper.enforceBlockedPackagesGuard(
                context = context,
                blockedPackages = blockedSet,
                rootOptInEnabled = app.preferences.isRootBridgeOptInEnabled()
            )
        }

        // Single-shot zero-overhead UsageStats ranker for Top Battery & Top CPU/Temp apps
        if (usageGranted) {
            refreshHeavyAppsOneShot(context, tempC, blockedSet)
        }

        if (postNotification && (app.preferences.isPersistentNotificationEnabled() || snapshot.tileSelection == dev.voltguard.battery.engine.TileSelection.INTL)) {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildMonitorNotification(context, decision.statusLine))
        }

        if ((decision.chargeNudge is ChargeNudge.UnplugAt80 || decision.chargeNudge is ChargeNudge.PredictiveChargePacing) &&
            app.preferences.isNudge80Enabled()
        ) {
            notifyCharge80Nudge(context, levelPct)
        }
        }

        private fun refreshHeavyAppsOneShot(context: Context, deviceTempC: Float, blockedSet: Set<String>) {
            val usm = context.getSystemService(UsageStatsManager::class.java) ?: return
            val pm = context.packageManager
            val end = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 3_600_000L, end).orEmpty()
            val ranked = stats
                .filter { it.packageName != context.packageName && !it.packageName.startsWith("com.android.systemui") && (it.totalTimeInForeground > 15_000L || blockedSet.contains(it.packageName)) }
                .groupBy { it.packageName }
                .map { (pkg, entries) ->
                    val fgMs = entries.sumOf { it.totalTimeInForeground }
                    val mins = (fgMs / 60_000L).toInt().coerceAtLeast(1)
                    val label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg.substringAfterLast('.'))
                    val isBlocked = blockedSet.contains(pkg)
                    val estMah = if (isBlocked) 0 else (mins * 12).coerceIn(45, 780)
                    val estPctHr = if (isBlocked) 0f else (estMah / 5160f) * 100f
                    val cpuScore = if (isBlocked) 0 else (mins * 2 + if (KNOWN_HEAVY_PACKAGES.any { pkg.contains(it, true) }) 35 else 8).coerceIn(6, 95)
                    val tempDelta = if (isBlocked) 0f else (((deviceTempC - 29f).coerceAtLeast(0.4f)) * (cpuScore / 100f)).coerceIn(0.4f, 6.0f)
                    HeavyAppUsage(pkg, label, estMah, estPctHr, cpuScore, tempDelta, isBlocked)
                }
            val presentPkgs = ranked.map { it.packageName }.toSet()
            val missingBlocked = blockedSet
                .filter { !presentPkgs.contains(it) }
                .map { pkg ->
                    val label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg.substringAfterLast('.'))
                    HeavyAppUsage(pkg, label, 0, 0f, 0, 0f, true)
                }
            val combined = ranked + missingBlocked
            val activeBattery = combined.filter { !it.isBlocked }.sortedByDescending { it.estimatedMahPerHr }
            val activeCpu = combined.filter { !it.isBlocked }.sortedByDescending { it.cpuLoadPct }
            val blockedItems = combined.filter { it.isBlocked }.sortedBy { it.appLabel }
            val activeSlots = maxOf(1, 3 - blockedItems.size)
            _heavyBatteryApps.value = activeBattery.take(activeSlots) + blockedItems
            _heavyCpuTempApps.value = activeCpu.take(activeSlots) + blockedItems
        }

        private fun queryRecentForegroundPackage(context: Context): Pair<String, Boolean> {
            val usm = context.getSystemService(UsageStatsManager::class.java)
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(end - 60_000L, end)
            val event = UsageEvents.Event()
            var lastPkg = "unknown"
            var sawEvent = false
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                sawEvent = true
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastPkg = event.packageName ?: lastPkg
                }
            }
            return lastPkg to sawEvent
        }

        private fun buildMonitorNotification(context: Context, statusText: String): Notification {
            val openIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, VoltGuardApp.CHANNEL_MONITOR)
                .setSmallIcon(R.drawable.ic_tile_voltguard)
                .setContentTitle("VoltGuard Battery Monitor")
                .setContentText(statusText)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(openIntent)
                .build()
        }

        private fun notifyCharge80Nudge(context: Context, levelPct: Int) {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            val notification = NotificationCompat.Builder(context, VoltGuardApp.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_tile_voltguard)
                .setContentTitle("Battery reached $levelPct% — unplug or pace charging")
                .setContentText("Stopping routine charges near 80% reduces Li-Po cathode stress.")
                .setAutoCancel(true)
                .build()
            nm.notify(ALERT_80_ID, notification)
        }
    }
}
