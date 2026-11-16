package dev.voltguard.battery.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "battery_samples",
    indices = [Index(value = ["timestampMs"])]
)
data class BatterySampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val batteryPct: Int,
    val voltageMv: Int,
    val temperatureC: Float,
    val currentMa: Int,
    val pluggedCode: Int,
    val appliedMode: String,
    val chargedDeltaUah: Long
)
