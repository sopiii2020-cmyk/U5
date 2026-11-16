package dev.voltguard.battery.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BatterySampleEntity::class,
        UsagePatternEntity::class,
        BanditArmStateEntity::class,
        HmmModelStateEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class BatteryDatabase : RoomDatabase() {
    abstract fun sampleDao(): BatterySampleDao
    abstract fun usagePatternDao(): UsagePatternDao
    abstract fun banditStateDao(): BanditStateDao
    abstract fun hmmStateDao(): HmmStateDao

    companion object {
        @Volatile
        private var INSTANCE: BatteryDatabase? = null

        fun getInstance(context: Context): BatteryDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    BatteryDatabase::class.java,
                    "voltguard_battery.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}

object CycleStatsEstimator {
    // POCO X3 NFC / X3 Pro nominal design capacity = 5160 mAh = 5,160,000 µAh
    const val DEFAULT_DESIGN_CAPACITY_UAH = 5_160_000L

    fun estimateCycles(totalChargedUah: Long, designCapacityUah: Long = DEFAULT_DESIGN_CAPACITY_UAH): Double {
        return totalChargedUah.toDouble() / designCapacityUah.toDouble()
    }
}
