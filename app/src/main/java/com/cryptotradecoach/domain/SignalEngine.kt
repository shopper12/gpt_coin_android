package com.cryptotradecoach.domain

import com.cryptotradecoach.data.Signal
import com.cryptotradecoach.data.Ticker
import kotlin.math.max

class SignalEngine {
    fun scan(tickers: List<Ticker>, now: Long = System.currentTimeMillis()): List<Signal> {
        if (tickers.isEmpty()) return emptyList()

        val topByValue = tickers
            .sortedByDescending { it.accTradePrice24h }
            .take(30)

        return topByValue.mapNotNull { ticker ->
            val change = ticker.signedChangeRate
            val valueScore = normalize(ticker.accTradePrice24h)
            val volumeScore = normalize(ticker.accTradeVolume24h)

            val strategy = when {
                change in 0.03..0.12 && volumeScore > 0.18 ->
                    StrategyMatch(
                        name = "Momentum Breakout",
                        reason = "Positive trend with strong 24h trading value",
                        targetMultiplier = 1.02,
                        stopMultiplier = 0.985,
                    )

                change in -0.04..0.0 && volumeScore > 0.20 ->
                    StrategyMatch(
                        name = "Rebound Watch",
                        reason = "Pullback with active trading volume",
                        targetMultiplier = 1.018,
                        stopMultiplier = 0.98,
                    )

                change in 0.0..0.03 && valueScore > 0.35 && volumeScore > 0.15 ->
                    StrategyMatch(
                        name = "Volume Accumulation",
                        reason = "Stable price with high liquidity",
                        targetMultiplier = 1.015,
                        stopMultiplier = 0.99,
                    )

                else -> null
            }

            strategy?.let {
                val entry = ticker.tradePrice
                val score = (change * 100.0) + (valueScore * 30.0) + (volumeScore * 20.0)
                Signal(
                    market = ticker.market,
                    strategyName = it.name,
                    entryPrice = entry,
                    stopLossPrice = entry * it.stopMultiplier,
                    targetPrice = entry * it.targetMultiplier,
                    score = score,
                    reason = it.reason,
                    timestamp = now,
                )
            }
        }.sortedByDescending { it.score }
    }

    private fun normalize(value: Double): Double {
        if (value <= 0.0) return 0.0
        val log = kotlin.math.ln(value + 1.0)
        return max(0.0, log / 30.0)
    }

    private data class StrategyMatch(
        val name: String,
        val reason: String,
        val targetMultiplier: Double,
        val stopMultiplier: Double,
    )
}
