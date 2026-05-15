package com.cryptotradecoach.data

enum class StrategyStatus {
    ACTIVE,
    INVALIDATED,
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
    val expectedReturnPct: Double,
    val riskPct: Double,
    val riskRewardRatio: Double,
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
