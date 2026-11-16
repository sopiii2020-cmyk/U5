package dev.voltguard.battery.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "usage_patterns")
data class UsagePatternEntity(
    @PrimaryKey val hourOfDay: Int, // 0..23
    val idleProbability: Float,
    val distinctDaysObserved: Int,
    val baselineWearScore: Float,
    val optimizerWearScore: Float
)

@Entity(tableName = "bandit_arm_states")
data class BanditArmStateEntity(
    @PrimaryKey val armName: String,
    val pullCount: Int,
    val rewardSum: Float,
    val covarianceDiag: Float,
    val lastUpdatedMs: Long
)

@Entity(tableName = "hmm_model_state")
data class HmmModelStateEntity(
    @PrimaryKey val id: Int = 1,
    val maeMinutes: Int,
    val viterbiTransitionsJson: String,
    val updatedMs: Long
)
