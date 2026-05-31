package com.cryptotradecoach.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cryptotradecoach.data.StrategyStatus
import com.cryptotradecoach.data.StrategyType

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

object StrategyEventType {
    const val NEW_ACTIVE = "NEW_ACTIVE"
    const val MANUAL_SEARCH = "MANUAL_SEARCH"
    const val RANK_UP = "RANK_UP"
    const val PRICE_PLAN_CHANGED = "PRICE_PLAN_CHANGED"
    const val WATCH_ONLY = "WATCH_ONLY"
    const val INVALIDATED = "INVALIDATED"
    const val TARGET1_HIT = "TARGET1_HIT"
    const val TRAILING_STOP_HIT = "TRAILING_STOP_HIT"
    const val HIT_TARGET = "HIT_TARGET"
    const val STOPPED_OUT = "STOPPED_OUT"
    const val EXPIRED = "EXPIRED"
}

@Entity(
    tableName = "active_strategies",
    indices = [
        Index(value = ["symbol"], unique = true),
        Index(value = ["status", "score", "rank"]),
        Index(value = ["updatedAt"]),
    ],
)
data class TradeStrategyEntity(
    @PrimaryKey
    val id: String,
    val symbol: String,
    val strategyType: StrategyType,
    val status: StrategyStatus,
    val score: Double,
    val rank: Int,
    val entryLow: Double,
    val entryHigh: Double,
    val stopLoss: Double,
    val target1: Double,
    val target2: Double,
    val trailingStop: Double,
    val expectedReturnPct: Double,
    val riskPct: Double,
    val riskRewardRatio: Double,
    val componentScores: String,
    val rankByChangeRate: Int,
    val rankByTradeValue: Int,
    val changeRate24h: Double,
    val changeRate30m: Double,
    val changeRate5m: Double,
    val volumeAcceleration: Double,
    val reason: String,
    val invalidationReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val validUntil: Long,
)

@Entity(
    tableName = "strategy_history",
    indices = [
        Index(value = ["symbol", "createdAt"]),
        Index(value = ["strategyId", "createdAt"]),
        Index(value = ["eventType", "createdAt"]),
    ],
)
data class StrategyHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val strategyId: String,
    val symbol: String,
    val eventType: String,
    val oldSummary: String?,
    val newSummary: String?,
    val message: String,
    val createdAt: Long,
)

@Entity(
    tableName = "strategy_scan_logs",
    indices = [
        Index(value = ["market", "timestamp"]),
        Index(value = ["selectedOrMissed", "timestamp"]),
        Index(value = ["strategyStatus", "timestamp"]),
    ],
)
data class StrategyScanLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val market: String,
    val strategyType: StrategyType,
    val timestamp: Long,
    val currentPrice: Double,
    val entryPrice: Double,
    val stopLossPrice: Double,
    val target1: Double,
    val target2: Double,
    val trailingStop: Double,
    val score: Double,
    val componentScores: String,
    val rankByChangeRate: Int,
    val rankByTradeValue: Int,
    val changeRate24h: Double,
    val changeRate30m: Double,
    val changeRate5m: Double,
    val volumeAcceleration: Double,
    val selectedOrMissed: String,
    val missedReason: String?,
    val topNAtScan: Int,
    val strategyStatus: StrategyStatus,
)

@Entity(
    tableName = "strategy_performance",
    indices = [
        Index(value = ["strategyId"], unique = true),
        Index(value = ["symbol", "createdAt"]),
        Index(value = ["strategyType", "createdAt"]),
        Index(value = ["isComplete", "createdAt"]),
    ],
)
data class StrategyPerformanceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val strategyId: String,
    val symbol: String,
    val strategyType: StrategyType,
    val rulesVersion: String,
    val createdAt: Long,
    val lastUpdatedAt: Long,
    val entryPrice: Double,
    val latestPrice: Double,
    val priceAfter5m: Double? = null,
    val priceAfter15m: Double? = null,
    val priceAfter30m: Double? = null,
    val priceAfter60m: Double? = null,
    val return5m: Double? = null,
    val return15m: Double? = null,
    val return30m: Double? = null,
    val return60m: Double? = null,
    val mfePct: Double = 0.0,
    val maePct: Double = 0.0,
    val target1: Double,
    val target2: Double,
    val stopLoss: Double,
    val target1Hit: Boolean = false,
    val target2Hit: Boolean = false,
    val stopHit: Boolean = false,
    val expired: Boolean = false,
    val isComplete: Boolean = false,
    val btcChangeRate24h: Double = 0.0,
    val rankByTradeValue: Int,
    val score: Double,
    val reason: String,
)

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
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val strategyName: String,
    val reviewedAt: Long,
    val sampleSize: Int,
    val avgReturn5m: Double,
    val avgReturn15m: Double,
    val avgReturn30m: Double,
    val avgReturn60m: Double,
    val winRate60m: Double,
    val stopHitRate: Double,
    val target1HitRate: Double,
    val target2HitRate: Double,
    val avgMfePct: Double,
    val avgMaePct: Double,
    val recommendation: String,
    val beforeRuleJson: String,
    val suggestedRuleJson: String,
)

@Entity(
    tableName = "guideline_changes",
    indices = [
        Index(value = ["changedAt"]),
        Index(value = ["affectedStrategyName", "changedAt"]),
    ],
)
data class GuidelineChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val affectedStrategyName: String,
    val changedAt: Long,
    val reason: String,
    val beforeRule: String,
    val afterRule: String,
    val applied: Boolean,
)

@Entity(tableName = "evolution_logs")
data class EvolutionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val changedAt: Long,
    val changeLog: String,
    val rulesJson: String,
)
