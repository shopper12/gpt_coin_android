package com.cryptotradecoach.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

// Records automatic strategy-rule evolution changes for UI review and audit.
@Entity(tableName = "evolution_log")
data class EvolutionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val changedAt: Long,
    val changeLog: String,
    val rulesJson: String,
)
