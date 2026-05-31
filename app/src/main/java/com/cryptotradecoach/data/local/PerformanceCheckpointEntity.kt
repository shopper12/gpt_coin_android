package com.cryptotradecoach.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Stores realized price checkpoints for each recommendation at fixed elapsed-minute horizons.
@Entity(
    tableName = "performance_checkpoints",
    indices = [
        Index(value = ["strategyId", "elapsedMinutes"], unique = true),
        Index(value = ["strategyType", "elapsedMinutes", "createdAt"]),
        Index(value = ["symbol", "createdAt"]),
    ],
)
data class PerformanceCheckpointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val strategyId: String,
    val symbol: String,
    val strategyType: String,
    val createdAt: Long,
    val checkpointAt: Long,
    val elapsedMinutes: Int,
    val entryPrice: Double,
    val checkpointPrice: Double,
    val returnPct: Double,
    val target1Hit: Boolean = false,
    val target2Hit: Boolean = false,
    val stopHit: Boolean = false,
    val exitReason: String = "CHECKPOINT",
)
