package com.cryptotradecoach.domain

import com.cryptotradecoach.data.Candle
import com.cryptotradecoach.data.MarketCandidate
import com.cryptotradecoach.data.StrategyStatus
import com.cryptotradecoach.data.StrategyType
import com.cryptotradecoach.data.TradeStrategy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SignalEngine {
    fun scan(
        candidates: List<MarketCandidate>,
        now: Long = System.currentTimeMillis(),
        minimumScore: Double = DEFAULT_MINIMUM_SCORE,
        maxResults: Int = DEFAULT_MAX_RESULTS,
    ): List<TradeStrategy> {
        return candidates
            .asSequence()
            .sortedByDescending { it.ticker.accTradePrice24h }
            .take(CANDIDATE_LIMIT)
            .mapNotNull { scoreCandidate(it, now) }
            .filter { it.score >= minimumScore }
            .sortedWith(compareByDescending<TradeStrategy> { it.score }.thenBy { it.riskPct })
            .mapIndexed { index, strategy -> strategy.copy(rank = index + 1) }
            .take(maxResults)
            .toList()
    }

    fun invalidationReason(candidate: MarketCandidate, previous: TradeStrategy): String? {
        val rescored = scoreCandidate(candidate, System.currentTimeMillis())
        val price = candidate.ticker.tradePrice
        return when {
            price <= previous.stopLoss -> "현재가가 손절가를 이탈했습니다."
            price >= previous.target2 -> null
            previous.validUntil < System.currentTimeMillis() -> "전략 유효 시간이 만료되었습니다."
            rescored == null -> "전략 조건이 더 이상 충족되지 않습니다."
            rescored.score < INVALIDATION_SCORE -> "점수가 ${INVALIDATION_SCORE.toInt()}점 미만으로 하락했습니다."
            else -> null
        }
    }

    private fun scoreCandidate(candidate: MarketCandidate, now: Long): TradeStrategy? {
        val ticker = candidate.ticker
        val price = ticker.tradePrice
        if (price <= 0.0) return null

        val one = candidate.oneMinuteCandles
        val five = candidate.fiveMinuteCandles
        val fifteen = candidate.fifteenMinuteCandles
        if (one.size < 5 || five.size < 5 || fifteen.size < 3) return null

        val oneMomentum = percentChange(one.takeLast(5).first().openingPrice, price)
        val fiveMomentum = percentChange(five.takeLast(3).first().openingPrice, price)
        val fifteenMomentum = percentChange(fifteen.takeLast(2).first().openingPrice, price)
        val recentVolume = one.takeLast(3).sumOf { it.candleAccTradeVolume }
        val previousVolume = one.dropLast(3).takeLast(10).map { it.candleAccTradeVolume }.averageOrZero() * 3.0
        val volumeRatio = if (previousVolume > 0.0) recentVolume / previousVolume else 1.0
        val high15 = fifteen.maxOf { it.highPrice }
        val low15 = fifteen.minOf { it.lowPrice }
        val rangePct = percentChange(low15, high15).coerceAtLeast(0.1)
        val nearHigh = if (high15 > 0.0) price / high15 else 0.0
        val liquidityScore = scoreLiquidity(ticker.accTradePrice24h)
        val overheatingPenalty = scoreOverheating(ticker.signedChangeRate * 100.0, oneMomentum, fiveMomentum)

        val strategyType = classifyStrategy(
            dayChangePct = ticker.signedChangeRate * 100.0,
            oneMomentum = oneMomentum,
            fiveMomentum = fiveMomentum,
            fifteenMomentum = fifteenMomentum,
            volumeRatio = volumeRatio,
            nearHigh = nearHigh,
            rangePct = rangePct,
        ) ?: return null

        val support = min(one.takeLast(10).minOf { it.lowPrice }, five.takeLast(5).minOf { it.lowPrice })
        val resistance = max(high15, five.maxOf { it.highPrice })
        val stopLoss = when (strategyType) {
            StrategyType.MOMENTUM_BREAKOUT -> max(support, price * 0.965)
            StrategyType.PULLBACK_REBOUND -> max(support * 0.995, price * 0.95)
            StrategyType.VOLUME_EXPANSION -> max(support, price * 0.97)
            StrategyType.WATCH_ONLY -> price * 0.96
        }.coerceAtMost(price * 0.995)
        val target1 = max(resistance, price * (1.0 + min(rangePct / 100.0, 0.035)))
        val target2 = max(target1 * 1.012, price * (1.0 + min(rangePct / 65.0, 0.065)))
        val riskPct = abs(percentChange(price, stopLoss)).coerceAtLeast(0.1)
        val expectedReturnPct = percentChange(price, target1).coerceAtLeast(0.1)
        val riskReward = expectedReturnPct / riskPct

        val momentumScore = scoreMomentum(oneMomentum, fiveMomentum, fifteenMomentum)
        val volumeScore = scoreVolume(volumeRatio)
        val positionScore = scorePosition(strategyType, nearHigh, price, low15, high15)
        val rewardScore = scoreRiskReward(riskReward, expectedReturnPct)
        val total = momentumScore + volumeScore + positionScore + rewardScore + liquidityScore - overheatingPenalty

        return TradeStrategy(
            id = "${ticker.market}-${strategyType.name}",
            symbol = ticker.market,
            strategyType = strategyType,
            status = StrategyStatus.ACTIVE,
            score = total.coerceIn(0.0, 100.0),
            rank = Int.MAX_VALUE,
            entryLow = when (strategyType) {
                StrategyType.PULLBACK_REBOUND -> max(support, price * 0.99)
                else -> price * 0.997
            },
            entryHigh = price * 1.003,
            stopLoss = stopLoss,
            target1 = target1,
            target2 = target2,
            expectedReturnPct = expectedReturnPct,
            riskPct = riskPct,
            riskRewardRatio = riskReward,
            reason = buildReason(strategyType, momentumScore, volumeScore, positionScore, rewardScore, liquidityScore, overheatingPenalty),
            invalidationReason = null,
            createdAt = now,
            updatedAt = now,
            validUntil = now + VALID_FOR_MS,
        )
    }

    private fun classifyStrategy(
        dayChangePct: Double,
        oneMomentum: Double,
        fiveMomentum: Double,
        fifteenMomentum: Double,
        volumeRatio: Double,
        nearHigh: Double,
        rangePct: Double,
    ): StrategyType? = when {
        fiveMomentum > 0.0 && fifteenMomentum > 0.0 && volumeRatio >= 1.15 && nearHigh >= 0.985 && fiveMomentum < 5.0 ->
            StrategyType.MOMENTUM_BREAKOUT
        dayChangePct > 0.0 && oneMomentum > 0.0 && fiveMomentum in -1.5..2.5 && volumeRatio >= 1.05 ->
            StrategyType.PULLBACK_REBOUND
        abs(dayChangePct) < 12.0 && volumeRatio >= 1.8 && rangePct >= 0.6 && fiveMomentum < 4.0 ->
            StrategyType.VOLUME_EXPANSION
        else -> null
    }

    private fun scoreMomentum(one: Double, five: Double, fifteen: Double): Double {
        return (one * 2.5 + five * 2.0 + fifteen * 1.2).coerceIn(0.0, 25.0)
    }

    private fun scoreVolume(volumeRatio: Double): Double {
        return ((volumeRatio - 1.0) * 18.0).coerceIn(0.0, 25.0)
    }

    private fun scorePosition(type: StrategyType, nearHigh: Double, price: Double, low: Double, high: Double): Double {
        val range = (high - low).coerceAtLeast(price * 0.001)
        val rangePosition = ((price - low) / range).coerceIn(0.0, 1.0)
        return when (type) {
            StrategyType.MOMENTUM_BREAKOUT -> ((nearHigh - 0.95) * 400.0).coerceIn(0.0, 20.0)
            StrategyType.PULLBACK_REBOUND -> ((1.0 - abs(rangePosition - 0.45)) * 20.0).coerceIn(0.0, 20.0)
            StrategyType.VOLUME_EXPANSION -> ((1.0 - abs(rangePosition - 0.55)) * 18.0).coerceIn(0.0, 20.0)
            StrategyType.WATCH_ONLY -> 0.0
        }
    }

    private fun scoreRiskReward(ratio: Double, expectedReturnPct: Double): Double {
        return (ratio * 5.0 + expectedReturnPct).coerceIn(0.0, 15.0)
    }

    private fun scoreLiquidity(accTradePrice24h: Double): Double {
        if (accTradePrice24h <= 0.0) return 0.0
        return (kotlin.math.ln(accTradePrice24h + 1.0) / 2.0 - 8.0).coerceIn(0.0, 15.0)
    }

    private fun scoreOverheating(dayChangePct: Double, oneMomentum: Double, fiveMomentum: Double): Double {
        var penalty = 0.0
        if (dayChangePct > 18.0) penalty += (dayChangePct - 18.0) * 0.8
        if (fiveMomentum > 5.0) penalty += (fiveMomentum - 5.0) * 2.0
        if (oneMomentum > 3.0) penalty += (oneMomentum - 3.0) * 2.0
        return penalty.coerceIn(0.0, 15.0)
    }

    private fun buildReason(
        type: StrategyType,
        momentum: Double,
        volume: Double,
        position: Double,
        reward: Double,
        liquidity: Double,
        penalty: Double,
    ): String {
        return "${type.name}: 모멘텀 ${momentum.display()} / 거래량 ${volume.display()} / 위치 ${position.display()} / 손익비 ${reward.display()} / 유동성 ${liquidity.display()} / 과열감점 ${penalty.display()}"
    }

    private fun percentChange(from: Double, to: Double): Double {
        if (from <= 0.0) return 0.0
        return ((to - from) / from) * 100.0
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private fun Double.display(): String = String.format("%.1f", this)

    companion object {
        const val DEFAULT_MAX_RESULTS = 5
        const val DEFAULT_MINIMUM_SCORE = 70.0
        private const val CANDIDATE_LIMIT = 80
        private const val INVALIDATION_SCORE = 60.0
        private const val VALID_FOR_MS = 30 * 60 * 1000L
    }
}
