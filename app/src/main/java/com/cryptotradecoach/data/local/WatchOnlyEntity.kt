package com.cryptotradecoach.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cryptotradecoach.data.StrategyType

/** Tracks WATCH_ONLY candidates so later pumps can be attributed to missed signals. */
@Entity(
    tableName = "watch_only_candidates",
    indices = [
        Index(value = ["market", "createdAt"]),
        Index(value = ["checked", "createdAt"]),
        Index(value = ["pumped", "createdAt"]),
    ],
)
data class WatchOnlyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val market: String,
    val strategyType: StrategyType,
    val createdAt: Long,
    val checkAfterAt: Long,
    val entryPrice: Double,
    val score: Double,
    val missedReason: String?,
    val rankByChangeRate: Int,
    val rankByTradeValue: Int,
    val changeRate24h: Double,
    val changeRate30m: Double,
    val changeRate5m: Double,
    val volumeAcceleration: Double,
    val checked: Boolean = false,
    val checkedAt: Long? = null,
    val checkedPrice: Double? = null,
    val returnPct: Double? = null,
    val pumped: Boolean = false,
)
