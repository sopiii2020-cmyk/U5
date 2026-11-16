package dev.voltguard.battery.ui.screens

import android.content.Intent
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.voltguard.battery.VoltGuardApp
import dev.voltguard.battery.engine.TileSelection
import dev.voltguard.battery.service.BatteryMonitorService
import dev.voltguard.battery.util.OemChargeSettingsHelper

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = (context.applicationContext as VoltGuardApp).preferences

    var autoMode by remember { mutableStateOf(prefs.getTileSelection() == TileSelection.AUTO) }
    var aggressiveness by remember { mutableFloatStateOf(prefs.getAggressiveness().toFloat()) }
    var persistentNotif by remember { mutableStateOf(prefs.isPersistentNotificationEnabled()) }
    var nudge80 by remember { mutableStateOf(prefs.isNudge80Enabled()) }
    var rootOptIn by remember { mutableStateOf(prefs.isRootBridgeOptInEnabled()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Optimizer Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Automatic Context Mode", fontWeight = FontWeight.Medium)
                Text("Let AdaptiveIntelligenceEngine fuse signals and switch modes automatically.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = autoMode, onCheckedChange = {
                autoMode = it
                prefs.setTileSelection(if (it) TileSelection.AUTO else TileSelection.LONGEVITY)
            })
        }

        Column {
            Text("Aggressiveness: ${aggressiveness.toInt()} (Battery-first ↔ Performance-first)", fontWeight = FontWeight.Medium)
            Slider(
                value = aggressiveness,
                onValueChange = { aggressiveness = it },
                onValueChangeFinished = { prefs.setAggressiveness(aggressiveness.toInt()) },
                valueRange = 0f..100f
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Persistent Shade Notification Card", fontWeight = FontWeight.Medium)
                Text("Off (Recommended): Runs silently via Quick Settings Tile & WorkManager with zero notification card in the shade.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = persistentNotif, onCheckedChange = {
                persistentNotif = it
                prefs.setPersistentNotificationEnabled(it)
                val serviceIntent = Intent(context, BatteryMonitorService::class.java)
                if (it) {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    context.stopService(serviceIntent)
                }
            })
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("80% & Predictive Pacing Alerts", fontWeight = FontWeight.Medium)
                Text("Off by default: enable only if you want a notification when charge reaches 80%.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = nudge80, onCheckedChange = {
                nudge80 = it
                prefs.setNudge80Enabled(it)
            })
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Transparent Permissions", fontWeight = FontWeight.SemiBold)
                Text("Usage Access lets VoltGuard pause throttling during games/navigation. Battery Optimization exemption prevents premature service termination.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { OemChargeSettingsHelper.openUsageAccessSettings(context) }) {
                        Text("Usage Access")
                    }
                    OutlinedButton(onClick = { OemChargeSettingsHelper.openIgnoreBatteryOptimization(context) }) {
                        Text("Monitor Exemption")
                    }
                }
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Advanced (Root) — Optional Manual Opt-In", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Inert unless you have already rooted this device manually (e.g., Magisk) and approve the superuser prompt yourself.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (rootOptIn) "Manual Opt-In Armed (Requires Magisk grant)" else "Inactive (Stock Non-Root Mode)")
                    Switch(checked = rootOptIn, onCheckedChange = {
                        rootOptIn = it
                        prefs.setRootBridgeOptInEnabled(it)
                    })
                }
            }
        }
    }
}
