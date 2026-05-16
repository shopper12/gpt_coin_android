package com.cryptotradecoach.data

enum class StrategyStatus {
    ACTIVE,
    WATCH_ONLY,
    INVALIDATED,
    TARGET1_HIT,
    TRAILING_STOP_HIT,
    HIT_TARGET,
    STOPPED_OUT,
    EXPIRED,
}

enum class StrategyType {
    MOMENTUM_BREAKOUT,
    PULLBACK_REBOUND,
    VOLUME_EXPANSION,
    WATCH_ONLY,
}

data class Ticker(
    val market: String,
    val tradePrice: Double,
    val signedChangeRate: Double,
    val accTradePrice24h: Double,
    val accTradeVolume24h: Double,
)

data class Candle(
    val market: String,
    val openingPrice: Double,
    val highPrice: Double,
    val lowPrice: Double,
    val tradePrice: Double,
    val candleAccTradeVolume: Double,
    val candleAccTradePrice: Double,
    val timestamp: Long,
)

data class MarketCandidate(
    val ticker: Ticker,
    val oneMinuteCandles: List<Candle>,
    val fiveMinuteCandles: List<Candle>,
    val fifteenMinuteCandles: List<Candle>,
    val rankByChangeRate: Int,
    val rankByTradeValue: Int,
    val changeRate30m: Double,
    val changeRate5m: Double,
    val volumeAcceleration: Double,
)

data class TradeStrategy(
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

data class StrategyHistory(
    val id: Long = 0,
    val strategyId: String,
    val symbol: String,
    val eventType: String,
    val oldSummary: String?,
    val newSummary: String?,
    val message: String,
    val createdAt: Long,
)

data class StrategyScanLog(
    val id: Long = 0,
    val market: String,
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

data class StrategyScanResult(
    val activeStrategies: List<TradeStrategy>,
    val scanLogs: List<StrategyScanLog>,
)
