package com.cryptotradecoach.data

data class Ticker(
    val market: String,
    val tradePrice: Double,
    val signedChangeRate: Double,
    val accTradePrice24h: Double,
    val accTradeVolume24h: Double,
)

data class Signal(
    val market: String,
    val strategyName: String,
    val entryPrice: Double,
    val stopLossPrice: Double,
    val targetPrice: Double,
    val score: Double,
    val reason: String,
    val timestamp: Long,
)
