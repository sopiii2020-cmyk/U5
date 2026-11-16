package dev.voltguard.battery.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.voltguard.battery.VoltGuardApp
import dev.voltguard.battery.service.BatteryMonitorService
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun TrendsScreen(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as VoltGuardApp
    var windowDays by remember { mutableStateOf(1) }
    val sinceMs = remember(windowDays) {
        System.currentTimeMillis() - TimeUnit.DAYS.toMillis(windowDays.toLong())
    }
    val samples by app.database.sampleDao().observeSince(sinceMs).collectAsState(emptyList())
    val outcome by BatteryMonitorService.latestOutcomeFeedback.collectAsState()
    val banditMetrics by BatteryMonitorService.latestBanditMetrics.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Battery & Thermal Trends", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedCard(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Cycles Saved", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = String.format(Locale.US, "%.1f", outcome?.estimatedCyclesSaved ?: 0f),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            OutlinedCard(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Extra Lifespan", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = String.format(Locale.US, "+%.1f mo", outcome?.estimatedExtraMonths ?: 0f),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            OutlinedCard(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Wear Avoided", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = String.format(Locale.US, "%.1f%%", banditMetrics?.wearAvoidedPct ?: 0f),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "24h", 7 to "7d", 30 to "30d").forEach { (days, label) ->
                FilterChip(
                    selected = windowDays == days,
                    onClick = { windowDays = days },
                    label = { Text(label) }
                )
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Charge Level (%) — ${samples.size} Room samples", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(12.dp))
                Canvas(modifier = Modifier.fillMaxWidth().height(150.dp)) {
                    if (samples.size < 2) return@Canvas
                    val path = Path()
                    val stepX = size.width / (samples.size - 1).coerceAtLeast(1)
                    samples.forEachIndexed { idx, s ->
                        val x = idx * stepX
                        val y = size.height - (s.batteryPct / 100f) * size.height
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path = path, color = Color(0xFF0891B2), style = Stroke(width = 4f))
                }
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Battery Temperature (°C)", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(12.dp))
                Canvas(modifier = Modifier.fillMaxWidth().height(130.dp)) {
                    if (samples.size < 2) return@Canvas
                    val path = Path()
                    val stepX = size.width / (samples.size - 1).coerceAtLeast(1)
                    samples.forEachIndexed { idx, s ->
                        val x = idx * stepX
                        val normTemp = ((s.temperatureC - 20f) / 30f).coerceIn(0f, 1f)
                        val y = size.height - normTemp * size.height
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path = path, color = Color(0xFFD97706), style = Stroke(width = 4f))
                    drawLine(
                        color = Color(0x22DC2626),
                        start = Offset(0f, size.height * 0.3f),
                        end = Offset(size.width, size.height * 0.3f),
                        strokeWidth = 2f
                    )
                }
            }
        }
    }
}
