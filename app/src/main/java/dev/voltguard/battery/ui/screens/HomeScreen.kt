package dev.voltguard.battery.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.voltguard.battery.VoltGuardApp
import dev.voltguard.battery.data.db.CycleStatsEstimator
import dev.voltguard.battery.engine.ChargeNudge
import dev.voltguard.battery.engine.HeavyAppUsage
import dev.voltguard.battery.engine.HomeProfileMode
import dev.voltguard.battery.engine.TileSelection
import dev.voltguard.battery.service.BatteryMonitorService
import dev.voltguard.battery.util.OemChargeSettingsHelper
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as VoltGuardApp
    val scope = rememberCoroutineScope()

    val snapshot by BatteryMonitorService.latestSnapshot.collectAsState()
    val decision by BatteryMonitorService.latestDecision.collectAsState()
    val liveRuntime by BatteryMonitorService.liveRuntimeSummary.collectAsState()
    val batteryApps by BatteryMonitorService.heavyBatteryApps.collectAsState()
    val cpuTempApps by BatteryMonitorService.heavyCpuTempApps.collectAsState()
    val totalChargedUah by app.database.sampleDao().observeTotalChargedUah().collectAsState(0L)
    val cycles = CycleStatsEstimator.estimateCycles(totalChargedUah)

    var selectedProfile by remember { mutableStateOf(app.preferences.getHomeProfile()) }
    var blockedSet by remember { mutableStateOf(app.preferences.getBlockedPackages()) }
    var inactivityShieldOn by remember { mutableStateOf(app.preferences.isInactivityShieldEnabled()) }

    val onSelectProfile: (HomeProfileMode) -> Unit = { profile ->
        selectedProfile = profile
        app.preferences.setHomeProfile(profile)
        scope.launch {
            BatteryMonitorService.collectAndEvaluateNow(context.applicationContext, postNotification = false)
        }
    }

    val onTapHeavyApp: (HeavyAppUsage) -> Unit = { item ->
        OemChargeSettingsHelper.stopOrOpenForceStop(
            context = context,
            packageName = item.packageName,
            rootOptInEnabled = app.preferences.isRootBridgeOptInEnabled()
        )
    }

    val onToggleBlockedApp: (HeavyAppUsage) -> Unit = { item ->
        val nowBlocked = app.preferences.toggleBlockedPackage(item.packageName)
        blockedSet = app.preferences.getBlockedPackages()
        if (nowBlocked) {
            OemChargeSettingsHelper.stopOrOpenForceStop(
                context = context,
                packageName = item.packageName,
                rootOptInEnabled = app.preferences.isRootBridgeOptInEnabled()
            )
        }
        scope.launch {
            BatteryMonitorService.collectAndEvaluateNow(context.applicationContext, postNotification = false)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "VoltGuard Telemetry",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "ACTIVE: ${decision?.appliedMode?.name ?: "BALANCED"} (${decision?.confidencePct ?: 100}% conf.)",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileButton("Automatic", selectedProfile == HomeProfileMode.AUTOMATIC, Modifier.weight(1f)) {
                        onSelectProfile(HomeProfileMode.AUTOMATIC)
                    }
                    ProfileButton("Balance", selectedProfile == HomeProfileMode.BALANCE, Modifier.weight(1f)) {
                        onSelectProfile(HomeProfileMode.BALANCE)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileButton("Ultra Safe", selectedProfile == HomeProfileMode.ULTRA_SAFE, Modifier.weight(1f)) {
                        onSelectProfile(HomeProfileMode.ULTRA_SAFE)
                    }
                    ProfileButton("Intelligent", selectedProfile == HomeProfileMode.INTELLIGENT, Modifier.weight(1f)) {
                        onSelectProfile(HomeProfileMode.INTELLIGENT)
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Live Battery Life", style = MaterialTheme.typography.labelMedium)
                        Text(liveRuntime, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
                if (snapshot?.tileSelection == TileSelection.INTL) {
                    Text(
                        text = "Intl Tile Locked — Max battery lifespan pinned until Quick Tile is tapped off.",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (snapshot?.let { it.voltageMv in 1..3480 && it.batteryLevelPct >= 25 } == true) {
                    Text(
                        text = "Voltage Sag Warning (${snapshot?.voltageMv} mV at ${snapshot?.batteryLevelPct}%): Sudden drop to 1% reflects worn cell impedance — peak load reduced.",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = inactivityShieldOn,
                        onCheckedChange = { checked ->
                            inactivityShieldOn = checked
                            app.preferences.setInactivityShieldEnabled(checked)
                            if (checked) {
                                OemChargeSettingsHelper.ensureShieldPermissions(context)
                                androidx.core.content.ContextCompat.startForegroundService(
                                    context,
                                    android.content.Intent(context, BatteryMonitorService::class.java)
                                )
                            }
                            scope.launch {
                                BatteryMonitorService.collectAndEvaluateNow(context.applicationContext, postNotification = false)
                            }
                        }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Inactivity Lifespan Shield (8s Min Light -> 12s Lock)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Active in Auto/Perf/Long/Intl · Freezes open BG apps & saves QS (keeps Lock Orient & Hotspot)",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Battery", "${snapshot?.batteryLevelPct ?: "--"}%", Modifier.weight(1f))
            MetricCard("Voltage", "${snapshot?.voltageMv ?: "--"} mV", Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                "Temp",
                snapshot?.let { String.format(Locale.US, "%.1f °C", it.temperatureC) } ?: "--",
                Modifier.weight(1f)
            )
            MetricCard("Current", "${snapshot?.currentMa ?: "--"} mA", Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Est. Cycles", String.format(Locale.US, "%.2f", cycles), Modifier.weight(1f))
            MetricCard("Display", "${snapshot?.refreshRateHz?.toInt() ?: 60} Hz", Modifier.weight(1f))
        }

        // Two Side-by-Side Columns: Top Battery Consumers & Top CPU/Temp Load (with Persistent Guard Checkbox)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedCard(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Top Battery Apps", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    if (batteryApps.isEmpty()) {
                        Text(
                            text = if (snapshot?.usageAccessGranted == false) "Grant Usage Access" else "No active drains",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (snapshot?.usageAccessGranted == false) {
                            OutlinedButton(onClick = { OemChargeSettingsHelper.openUsageAccessSettings(context) }) {
                                Text("Enable")
                            }
                        }
                    } else {
                        batteryApps.forEach { item ->
                            val isChecked = blockedSet.contains(item.packageName) || item.isBlocked
                            HeavyAppRow(
                                title = item.appLabel,
                                stat = if (isChecked) "Force-Stop Guarded" else "-${item.estimatedMahPerHr} mAh · ${String.format(Locale.US, "%.1f", item.batteryPctPerHr)}%/h",
                                checked = isChecked,
                                onToggleCheck = { onToggleBlockedApp(item) },
                                onClick = { onTapHeavyApp(item) }
                            )
                        }
                    }
                }
            }

            OutlinedCard(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Top CPU / Temp", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    if (cpuTempApps.isEmpty()) {
                        Text(
                            text = if (snapshot?.usageAccessGranted == false) "Grant Usage Access" else "CPU & temp idle",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        cpuTempApps.forEach { item ->
                            val isChecked = blockedSet.contains(item.packageName) || item.isBlocked
                            HeavyAppRow(
                                title = item.appLabel,
                                stat = if (isChecked) "Force-Stop Guarded" else "${item.cpuLoadPct}% CPU · +${String.format(Locale.US, "%.1f", item.tempDeltaC)}°C",
                                checked = isChecked,
                                onToggleCheck = { onToggleBlockedApp(item) },
                                onClick = { onTapHeavyApp(item) }
                            )
                        }
                    }
                }
            }
        }

        when (val nudge = decision?.chargeNudge) {
            is ChargeNudge.PredictiveChargePacing -> {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Predictive Charge Pacing (~${nudge.hoursUntilUnplug.toInt()}h to unplug)", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("Your learned schedule predicts unplugging around ${nudge.predictedUnplugHour}:00. Enable OEM Battery Protection so the cell does not sit at 100% overnight.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { OemChargeSettingsHelper.openNativeBatteryProtection(context) }) {
                            Text("Open OEM Battery Protection")
                        }
                    }
                }
            }
            is ChargeNudge.OemNativeChargeLimitLink -> {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Native Battery Protection Available", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("Your device firmware exposes a built-in charge cap. Enable it in System Settings.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { OemChargeSettingsHelper.openNativeBatteryProtection(context) }) {
                            Text("Open OEM Battery Protection")
                        }
                    }
                }
            }
            is ChargeNudge.JeitaThermalSlowdown -> {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Charging slowed intentionally", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("Battery is ${nudge.tempC}°C and tapered to ${nudge.currentMa} mA. This protects cell chemistry.")
                    }
                }
            }
            is ChargeNudge.UnplugAt80 -> {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Battery reached ${nudge.levelPct}% — unplug for longevity", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("Stopping routine charges around 80% extends Li-Po cycle lifespan.")
                    }
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun ProfileButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = if (selected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    }
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = colors
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun HeavyAppRow(
    title: String,
    stat: String,
    checked: Boolean,
    onToggleCheck: () -> Unit,
    onClick: () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggleCheck() })
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stat, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    OutlinedCard(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
