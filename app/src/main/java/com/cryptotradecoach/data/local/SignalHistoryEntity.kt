package com.cryptotradecoach.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val STRATEGY_VERSION = "v1"

object SignalEventType {
    const val INITIAL_SIGNAL = "INITIAL_SIGNAL"
    const val PRICE_MOVED_5_PERCENT = "PRICE_MOVED_5_PERCENT"
    const val STOP_HIT = "STOP_HIT"
    const val TARGET_HIT = "TARGET_HIT"
    const val STRATEGY_CHANGED = "STRATEGY_CHANGED"
    const val EXPIRED = "EXPIRED"
    const val INVALIDATED = "INVALIDATED"
}

object MissedSignalReason {
    const val TRADE_VALUE_FILTER_EXCLUDED = "TRADE_VALUE_FILTER_EXCLUDED"
    const val CHANGE_RATE_RULE_NOT_MATCHED = "CHANGE_RATE_RULE_NOT_MATCHED"
    const val VOLUME_SCORE_TOO_LOW = "VOLUME_SCORE_TOO_LOW"
    const val SIDEWAYS_BREAKOUT_MISSED = "SIDEWAYS_BREAKOUT_MISSED"
    const val REVERSAL_PATTERN_MISSED = "REVERSAL_PATTERN_MISSED"
    const val SCORE_TOO_LOW = "SCORE_TOO_LOW"
    const val UNKNOWN = "UNKNOWN"
}

@Entity(
    tableName = "signal_history",
    indices = [
        Index(value = ["market", "createdAt"]),
        Index(value = ["market", "eventType", "createdAt"]),
    ],
)
data class SignalHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val market: String,
    val strategyName: String,
    val strategyVersion: String,
    val baselinePrice: Double,
    val currentPrice: Double,
    val entryPrice: Double,
    val stopLossPrice: Double,
    val targetPrice: Double,
    val score: Double,
    val eventType: String,
    val reason: String,
    val mfePercent: Double,
    val maePercent: Double,
    val maxReturnPercent: Double,
    val maxDrawdownPercent: Double,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "price_snapshots",
    indices = [
        Index(value = ["market", "timestamp"]),
        Index(value = ["timestamp"]),
    ],
)
data class PriceSnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val market: String,
    val price: Double,
    val signedChangeRate: Double,
    val accTradePrice24h: Double,
    val accTradeVolume24h: Double,
    val rankByChangeRate: Int,
    val rankByTradeValue: Int,
    val timestamp: Long,
)

@Entity(
    tableName = "missed_signals",
    indices = [
        Index(value = ["market", "detectedAt"]),
    ],
)
data class MissedSignalEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val market: String,
    val detectedAt: Long,
    val currentPrice: Double,
    val previousPrice: Double,
    val changeRate: Double,
    val rankByChangeRate: Int,
    val rankByTradeValue: Int,
    val missedReason: String,
    val suggestedStrategy: String,
    val relatedRuleBefore: String,
    val suggestedRuleAfter: String,
)

@Entity(
    tableName = "strategy_reviews",
    indices = [
        Index(value = ["reviewedAt"]),
        Index(value = ["strategyName", "reviewedAt"]),
    ],
)
data class StrategyReviewEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val reviewedAt: Long,
    val strategyName: String,
    val strategyVersion: String,
    val totalActiveSignals: Int,
    val totalMissedSignals: Int,
    val targetHitCount: Int,
    val stopHitCount: Int,
    val expiredCount: Int,
    val averageMfePercent: Double,
    val averageMaePercent: Double,
    val missedMarkets: String,
    val diagnosis: String,
    val ruleChangeSuggestion: String,
)

@Entity(
    tableName = "guideline_changes",
    indices = [
        Index(value = ["changedAt"]),
        Index(value = ["affectedStrategyName", "applied"]),
    ],
)
data class GuidelineChangeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val changedAt: Long,
    val affectedStrategyName: String,
    val strategyVersionBefore: String,
    val strategyVersionAfterSuggestion: String,
    val beforeRule: String,
    val afterRule: String,
    val reason: String,
    val evidenceMarkets: String,
    val expectedEffect: String,
    val applied: Boolean = false,
)
