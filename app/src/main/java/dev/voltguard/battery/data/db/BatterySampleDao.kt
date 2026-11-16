package dev.voltguard.battery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BatterySampleDao {
    @Insert
    suspend fun insert(sample: BatterySampleEntity)

    @Query("SELECT * FROM battery_samples WHERE timestampMs >= :sinceMs ORDER BY timestampMs ASC")
    fun observeSince(sinceMs: Long): Flow<List<BatterySampleEntity>>

    @Query("SELECT COALESCE(SUM(chargedDeltaUah), 0) FROM battery_samples")
    fun observeTotalChargedUah(): Flow<Long>

    @Query("DELETE FROM battery_samples WHERE timestampMs < :cutoffMs")
    suspend fun pruneOlderThan(cutoffMs: Long)
}

@Dao
interface UsagePatternDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pattern: UsagePatternEntity)

    @Query("SELECT * FROM usage_patterns WHERE hourOfDay = :hour LIMIT 1")
    suspend fun getHourPattern(hour: Int): UsagePatternEntity?

    @Query("SELECT COALESCE(MAX(distinctDaysObserved), 0) FROM usage_patterns")
    suspend fun getDistinctDaysCount(): Int
}

@Dao
interface BanditStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: BanditArmStateEntity)

    @Query("SELECT * FROM bandit_arm_states")
    suspend fun getAll(): List<BanditArmStateEntity>
}

@Dao
interface HmmStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: HmmModelStateEntity)

    @Query("SELECT * FROM hmm_model_state WHERE id = 1 LIMIT 1")
    suspend fun get(): HmmModelStateEntity?
}
