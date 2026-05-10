package com.cryptotradecoach.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cryptotradecoach.data.Signal

@Entity(
    tableName = "strategy_history",
    indices = [
        Index(value = ["market", "timestamp"]),
    ],
)
data class SignalHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val market: String,
    val strategyName: String,
    val entryPrice: Double,
    val stopLossPrice: Double,
    val targetPrice: Double,
    val score: Double,
    val reason: String,
    val timestamp: Long,
)

fun Signal.toHistoryEntity(): SignalHistoryEntity {
    return SignalHistoryEntity(
        market = market,
        strategyName = strategyName,
        entryPrice = entryPrice,
        stopLossPrice = stopLossPrice,
        targetPrice = targetPrice,
        score = score,
        reason = reason,
        timestamp = timestamp,
    )
}

fun SignalHistoryEntity.toSignal(): Signal {
    return Signal(
        market = market,
        strategyName = strategyName,
        entryPrice = entryPrice,
        stopLossPrice = stopLossPrice,
        targetPrice = targetPrice,
        score = score,
        reason = reason,
        timestamp = timestamp,
    )
}
