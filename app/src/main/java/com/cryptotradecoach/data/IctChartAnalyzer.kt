package com.cryptotradecoach.data

import kotlin.math.abs

/**
 * Deterministic ICT-style chart analysis based only on supplied OHLC candles.
 *
 * This is a reproducible approximation of commonly used ICT concepts. It detects
 * confirmed swing highs/lows, BOS/CHOCH proxies, liquidity sweeps, three-candle
 * fair-value gaps, and premium/discount location. It does not claim discretionary
 * trader-level certainty.
 */
data class IctCandle(
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
)

data class IctAnalysis(
    val bias: String,
    val structure: String,
    val structureEvent: String,
    val liquidityEvent: String,
    val fairValueGap: String,
    val dealingRangeLocation: String,
    val preferredEntryLow: Double?,
    val preferredEntryHigh: Double?,
    val invalidation: Double?,
    val scoreAdjustment: Double,
    val summary: String,
)

object IctChartAnalyzer {
    fun analyze(input: List<IctCandle>): IctAnalysis {
        val candles = input.filter { it.high > 0.0 && it.low > 0.0 && it.high >= it.low }.takeLast(160)
        if (candles.size < 20) return insufficient()

        val swingHighs = mutableListOf<Pair<Int, Double>>()
        val swingLows = mutableListOf<Pair<Int, Double>>()
        for (index in 2 until candles.lastIndex - 1) {
            val candle = candles[index]
            val highWindow = candles.subList(index - 2, index + 3).map { it.high }
            val lowWindow = candles.subList(index - 2, index + 3).map { it.low }
            if (candle.high >= highWindow.maxOrNull()!!) swingHighs += index to candle.high
            if (candle.low <= lowWindow.minOrNull()!!) swingLows += index to candle.low
        }

        val last = candles.last()
        val lastSwingHigh = swingHighs.lastOrNull()?.second
        val priorSwingHigh = swingHighs.dropLast(1).lastOrNull()?.second
        val lastSwingLow = swingLows.lastOrNull()?.second
        val priorSwingLow = swingLows.dropLast(1).lastOrNull()?.second

        val structure = when {
            lastSwingHigh != null && priorSwingHigh != null && lastSwingLow != null && priorSwingLow != null &&
                lastSwingHigh > priorSwingHigh && lastSwingLow > priorSwingLow -> "BULLISH_HH_HL"
            lastSwingHigh != null && priorSwingHigh != null && lastSwingLow != null && priorSwingLow != null &&
                lastSwingHigh < priorSwingHigh && lastSwingLow < priorSwingLow -> "BEARISH_LH_LL"
            else -> "RANGE_OR_TRANSITION"
        }

        val structureEvent = when {
            lastSwingHigh != null && last.close > lastSwingHigh -> if (structure.startsWith("BEARISH")) "CHOCH_UP" else "BOS_UP"
            lastSwingLow != null && last.close < lastSwingLow -> if (structure.startsWith("BULLISH")) "CHOCH_DOWN" else "BOS_DOWN"
            else -> "NO_CONFIRMED_BREAK"
        }

        val liquidityEvent = when {
            lastSwingLow != null && last.low < lastSwingLow && last.close > lastSwingLow -> "SELL_SIDE_LIQUIDITY_SWEEP"
            lastSwingHigh != null && last.high > lastSwingHigh && last.close < lastSwingHigh -> "BUY_SIDE_LIQUIDITY_SWEEP"
            else -> "NO_LATEST_SWEEP"
        }

        val fvg = latestFairValueGap(candles)
        val dealing = candles.takeLast(60)
        val rangeHigh = dealing.maxOf { it.high }
        val rangeLow = dealing.minOf { it.low }
        val equilibrium = (rangeHigh + rangeLow) / 2.0
        val range = (rangeHigh - rangeLow).takeIf { it > 0.0 } ?: 1.0
        val normalizedLocation = (last.close - rangeLow) / range
        val location = when {
            normalizedLocation < 0.45 -> "DISCOUNT"
            normalizedLocation > 0.55 -> "PREMIUM"
            else -> "EQUILIBRIUM"
        }

        var score = 0
        if (structure == "BULLISH_HH_HL") score += 2
        if (structure == "BEARISH_LH_LL") score -= 2
        if (structureEvent in setOf("BOS_UP", "CHOCH_UP")) score += 2
        if (structureEvent in setOf("BOS_DOWN", "CHOCH_DOWN")) score -= 2
        if (liquidityEvent == "SELL_SIDE_LIQUIDITY_SWEEP") score += 2
        if (liquidityEvent == "BUY_SIDE_LIQUIDITY_SWEEP") score -= 2
        if (location == "DISCOUNT") score += 1
        if (location == "PREMIUM") score -= 1
        if (fvg.direction == "BULLISH") score += 1
        if (fvg.direction == "BEARISH") score -= 1

        val bias = when {
            score >= 3 -> "BULLISH"
            score <= -3 -> "BEARISH"
            else -> "NEUTRAL"
        }
        val scoreAdjustment = when (bias) {
            "BULLISH" -> 5.0
            "BEARISH" -> -8.0
            else -> 0.0
        }

        val preferredZone = when (bias) {
            "BULLISH" -> {
                if (fvg.direction == "BULLISH") fvg.low to fvg.high
                else (rangeLow + range * 0.35) to equilibrium
            }
            "BEARISH" -> {
                if (fvg.direction == "BEARISH") fvg.low to fvg.high
                else equilibrium to (rangeLow + range * 0.65)
            }
            else -> null
        }
        val invalidation = when (bias) {
            "BULLISH" -> lastSwingLow ?: rangeLow
            "BEARISH" -> lastSwingHigh ?: rangeHigh
            else -> null
        }

        val summary = buildString {
            append("ICT ")
            append(when (bias) { "BULLISH" -> "상승 우위"; "BEARISH" -> "하락 우위"; else -> "중립" })
            append(" · 구조=").append(structure)
            append(" · 전환=").append(structureEvent)
            append(" · 유동성=").append(liquidityEvent)
            append(" · 위치=").append(location)
            if (fvg.direction != "NONE") append(" · FVG=").append(fvg.direction)
        }

        return IctAnalysis(
            bias = bias,
            structure = structure,
            structureEvent = structureEvent,
            liquidityEvent = liquidityEvent,
            fairValueGap = fvg.text,
            dealingRangeLocation = location,
            preferredEntryLow = preferredZone?.first,
            preferredEntryHigh = preferredZone?.second,
            invalidation = invalidation,
            scoreAdjustment = scoreAdjustment,
            summary = summary,
        )
    }

    private data class FairValueGap(val direction: String, val low: Double, val high: Double, val text: String)

    private fun latestFairValueGap(candles: List<IctCandle>): FairValueGap {
        for (index in candles.lastIndex downTo maxOf(2, candles.size - 45)) {
            val older = candles[index - 2]
            val current = candles[index]
            if (current.low > older.high) {
                return FairValueGap("BULLISH", older.high, current.low, "BULLISH ${format(older.high)}~${format(current.low)}")
            }
            if (current.high < older.low) {
                return FairValueGap("BEARISH", current.high, older.low, "BEARISH ${format(current.high)}~${format(older.low)}")
            }
        }
        return FairValueGap("NONE", 0.0, 0.0, "NONE")
    }

    private fun insufficient() = IctAnalysis(
        bias = "UNKNOWN",
        structure = "INSUFFICIENT_DATA",
        structureEvent = "NONE",
        liquidityEvent = "NONE",
        fairValueGap = "NONE",
        dealingRangeLocation = "UNKNOWN",
        preferredEntryLow = null,
        preferredEntryHigh = null,
        invalidation = null,
        scoreAdjustment = 0.0,
        summary = "ICT 분석에 필요한 OHLC 데이터가 부족합니다.",
    )

    private fun format(value: Double): String = if (abs(value) >= 1000.0) "%,.0f".format(value) else "%.2f".format(value)
}
