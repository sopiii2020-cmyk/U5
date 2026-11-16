package dev.voltguard.battery.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.voltguard.battery.VoltGuardApp
import dev.voltguard.battery.engine.TileSelection
import dev.voltguard.battery.service.BatteryMonitorService
import dev.voltguard.battery.ui.screens.DiagnosticsScreen
import dev.voltguard.battery.ui.screens.HomeScreen
import dev.voltguard.battery.ui.screens.SettingsScreen
import dev.voltguard.battery.ui.screens.TrendsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

enum class AppTab { HOME, TRENDS, DIAGNOSTICS, SETTINGS }

class MainActivity : ComponentActivity() {
    private var initialTab by mutableStateOf(AppTab.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VoltGuardApp
        if (app.preferences.isPersistentNotificationEnabled() ||
            app.preferences.getTileSelection() == TileSelection.INTL ||
            app.preferences.isInactivityShieldEnabled()
        ) {
            ContextCompat.startForegroundService(this, Intent(this, BatteryMonitorService::class.java))
        } else {
            stopService(Intent(this, BatteryMonitorService::class.java))
        }
        routeIntent(intent)

        setContent {
            MaterialTheme {
                val context = LocalContext.current
                LaunchedEffect(Unit) {
                    while (isActive) {
                        BatteryMonitorService.collectAndEvaluateNow(context.applicationContext, postNotification = false)
                        delay(15_000L)
                    }
                }
                VoltGuardMainShell(
                    selectedTab = initialTab,
                    onSelectTab = { initialTab = it }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeIntent(intent)
    }

    private fun routeIntent(intent: Intent?) {
        if (intent?.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES") {
            initialTab = AppTab.SETTINGS
        }
    }
}

@Composable
fun VoltGuardMainShell(selectedTab: AppTab, onSelectTab: (AppTab) -> Unit) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == AppTab.HOME,
                    onClick = { onSelectTab(AppTab.HOME) },
                    icon = { Icon(Icons.Outlined.BatteryChargingFull, contentDescription = null) },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.TRENDS,
                    onClick = { onSelectTab(AppTab.TRENDS) },
                    icon = { Icon(Icons.Outlined.Analytics, contentDescription = null) },
                    label = { Text("Trends") }
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.DIAGNOSTICS,
                    onClick = { onSelectTab(AppTab.DIAGNOSTICS) },
                    icon = { Icon(Icons.Outlined.HealthAndSafety, contentDescription = null) },
                    label = { Text("Diagnostics") }
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.SETTINGS,
                    onClick = { onSelectTab(AppTab.SETTINGS) },
                    icon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            AppTab.HOME -> HomeScreen(Modifier.padding(innerPadding))
            AppTab.TRENDS -> TrendsScreen(Modifier.padding(innerPadding))
            AppTab.DIAGNOSTICS -> DiagnosticsScreen(Modifier.padding(innerPadding))
            AppTab.SETTINGS -> SettingsScreen(Modifier.padding(innerPadding))
        }
    }
}
