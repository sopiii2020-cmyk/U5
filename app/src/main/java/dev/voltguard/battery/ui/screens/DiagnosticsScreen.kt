package dev.voltguard.battery.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.voltguard.battery.service.BatteryMonitorService
import dev.voltguard.battery.util.OemChargeSettingsHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val snapshot by BatteryMonitorService.latestSnapshot.collectAsState()
    val decisionLogs by BatteryMonitorService.decisionLogs.collectAsState()
    val kalman by BatteryMonitorService.latestKalmanTelemetry.collectAsState()
    val hmm by BatteryMonitorService.latestHmmPrediction.collectAsState()
    val bandit by BatteryMonitorService.latestBanditMetrics.collectAsState()
    val formatter = SimpleDateFormat("HH:mm", Locale.US)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Diagnostics & Explainability", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        if (bandit != null || hmm != null || kalman != null) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Internal Control Diagnostics (Bandit · HMM · Kalman)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    bandit?.let { b ->
                        Text(
                            text = String.format(
                                Locale.US,
                                "Arm=%s · Realized=%.2f vs Baseline=%.2f · Regret=%.3f · Guardrail=%s",
                                b.chosenArm.name,
                                b.realizedReward,
                                b.osBaselineReward,
                                b.banditRegret,
                                if (b.hardThermalOverrideApplied) "OVERRIDE" else "NORMAL"
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    hmm?.let { h ->
                        Text(
                            text = String.format(
                                Locale.US,
                                "HMM State=%s · P(unplug <=2h)=%.0f%% · Error(MAE)=%dm · Explore=%s",
                                h.dominantState.name,
                                h.pUnplugNext120Min * 100f,
                                h.hmmMaeMinutes,
                                if (h.widenedExploration) "WIDENED" else "CONVERGED"
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    kalman?.let { k ->
                        Text(
                            text = String.format(
                                Locale.US,
                                "Kalman slopes: dT/dt=%+.2f °C/min · dV/dt=%+.1f mV/min (14-dim state)",
                                k.dTdtCPerMin,
                                k.dVdtMvPerMin
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        if (decisionLogs.isNotEmpty()) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Intelligence Decision Log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    decisionLogs.take(4).forEach { log ->
                        Text(
                            text = "${formatter.format(Date(log.timestampMs))} · ${log.decidedMode.name} (${log.confidencePct}%): ${log.plainReason}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        val current = snapshot
        if (current == null) {
            Text("Waiting for first BatteryMonitorService telemetry sample...")
            return@Column
        }

        if (current.stayAwakeEnabled || current.usbDebuggingEnabled) {
            DiagnosticCard(
                title = "Developer Options Flagged",
                detail = "Stay Awake or USB Debugging is enabled in Settings.Global, keeping display/wakelocks active while plugged.",
                actionLabel = "Open Developer Options",
                onAction = { OemChargeSettingsHelper.openDeveloperOptions(context) }
            )
        }

        if (current.thermalHeadroom >= 0.85f) {
            DiagnosticCard(
                title = "Elevated Thermal Headroom",
                detail = "PowerManager.getThermalHeadroom() reports sustained heat. Reduce 120Hz gaming wattage or screen brightness.",
                actionLabel = "Open Battery Settings",
                onAction = { OemChargeSettingsHelper.openNativeBatteryProtection(context) }
            )
        }

        if (!current.usageAccessGranted) {
            DiagnosticCard(
                title = "Usage Access Not Granted",
                detail = "Grant Usage Access so VoltGuard can detect foreground games/navigation and identify background wakeup culprits.",
                actionLabel = "Grant Usage Access",
                onAction = { OemChargeSettingsHelper.openUsageAccessSettings(context) }
            )
        }

        DiagnosticCard(
            title = "Doze & Battery Optimization Cooperation",
            detail = "Power Save: ${current.isPowerSaveMode} · Doze Idle: ${current.isDeviceIdleDoze} · Exempted: ${current.isIgnoringBatteryOpt}.",
            actionLabel = "View Exemption Setting",
            onAction = { OemChargeSettingsHelper.openIgnoreBatteryOptimization(context) }
        )
    }
}

@Composable
private fun DiagnosticCard(
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}
