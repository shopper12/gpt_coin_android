package com.cryptotradecoach.domain

import com.cryptotradecoach.data.MarketCandidate
import com.cryptotradecoach.data.StrategyScanLog
import com.cryptotradecoach.data.StrategyScanResult
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
    ): StrategyScanResult {
        val evaluations = candidates.mapNotNull { evaluateCandidate(it, now) }
        val rankedActive = evaluations
            .mapNotNull { it.strategy.takeIf { strategy -> strategy.status == StrategyStatus.ACTIVE && strategy.score >= minimumScore } }
            .sortedWith(compareByDescending<TradeStrategy> { it.score }.thenBy { it.riskPct })
            .take(maxResults.coerceIn(3, 5))
            .mapIndexed { index, strategy -> strategy.copy(rank = index + 1) }

        val rankById = rankedActive.associate { it.id to it.rank }
        val activeIds = rankedActive.map { it.id }.toSet()
        val logs = evaluations.map { evaluation ->
            val topRank = rankById[evaluation.strategy.id] ?: 0
            evaluation.log.copy(
                selectedOrMissed = if (evaluation.strategy.id in activeIds) "SELECTED" else "MISSED",
                missedReason = if (evaluation.strategy.id in activeIds) null else evaluation.log.missedReason,
                topNAtScan = topRank,
            )
        }

        return StrategyScanResult(activeStrategies = rankedActive, scanLogs = logs)
    }

    private fun evaluateCandidate(candidate: MarketCandidate, now: Long): Evaluation? {
        val ticker = candidate.ticker
        val price = ticker.tradePrice
        if (price <= 0.0) return null
        val one = candidate.oneMinuteCandles
        val five = candidate.fiveMinuteCandles
        val fifteen = candidate.fifteenMinuteCandles
        if (one.size < 6 || five.size < 3 || fifteen.size < 2) return null

        val changeRate24h = ticker.signedChangeRate * 100.0
        val changeRate30m = candidate.changeRate30m
        val changeRate5m = candidate.changeRate5m
        val snapshotMomentum = percentChange(one.takeLast(2).first().tradePrice, price)
        val high30 = one.takeLast(30).maxOf { it.highPrice }
        val low30 = one.takeLast(30).minOf { it.lowPrice }
        val high15m = fifteen.takeLast(4).maxOf { it.highPrice }
        val volumeAcceleration = candidate.volumeAcceleration
        val nearHigh = if (high30 > 0.0) price / high30 else 0.0
        val postSpikeDistancePct = percentChange(high30, price)
        val rangePct = percentChange(low30, high30).coerceAtLeast(0.1)

        val immediateMomentumScore = scoreImmediateMomentum(changeRate30m, changeRate5m, snapshotMomentum)
        val volumeAccelerationScore = scoreVolumeAcceleration(volumeAcceleration)
        val entryProximityScore = scoreEntryProximity(nearHigh, postSpikeDistancePct, rangePct)
        val changeRankScore = scoreChangeRank(candidate.rankByChangeRate)
        val riskRewardScore = scoreRiskReward(rangePct)
        val liquidityScore = scoreLiquidity(ticker.accTradePrice24h)
        val overheatPenalty = scoreOverheat(changeRate24h, changeRate30m, changeRate5m)
        val staleSnapshotPenalty = scoreStaleSnapshot(candidate)
        val postSpikeChasePenalty = scorePostSpikeChase(nearHigh, changeRate5m, changeRate30m)

        val total = (
            immediateMomentumScore +
                volumeAccelerationScore +
                entryProximityScore +
                changeRankScore +
                riskRewardScore +
                liquidityScore -
                overheatPenalty -
                staleSnapshotPenalty -
                postSpikeChasePenalty
            ).coerceIn(0.0, 100.0)

        val typeAndStatus = classifyStrategy(
            changeRate24h = changeRate24h,
            changeRate30m = changeRate30m,
            changeRate5m = changeRate5m,
            snapshotMomentum = snapshotMomentum,
            rankByChangeRate = candidate.rankByChangeRate,
            nearHigh = nearHigh,
            high15m = high15m,
            price = price,
            volumeAcceleration = volumeAcceleration,
            rangePct = rangePct,
        )

        val strategyType = typeAndStatus?.first ?: StrategyType.WATCH_ONLY
        val status = typeAndStatus?.second ?: StrategyStatus.WATCH_ONLY
        val stopLoss = price * when (strategyType) {
            StrategyType.VOLUME_EXPANSION -> 0.992
            else -> 0.990
        }
        val target1 = price * if (rangePct >= 2.0) 1.015 else 1.012
        val target2 = price * (1.03 + min(rangePct / 200.0, 0.02))
        val trailingStop = price * 0.988
        val riskPct = abs(percentChange(price, stopLoss)).coerceAtLeast(0.1)
        val expectedReturnPct = percentChange(price, target2).coerceAtLeast(0.1)
        val componentScores = listOf(
            "immediateMomentum=${immediateMomentumScore.one()}",
            "volumeAcceleration=${volumeAccelerationScore.one()}",
            "entryProximity=${entryProximityScore.one()}",
            "changeRank=${changeRankScore.one()}",
            "riskReward=${riskRewardScore.one()}",
            "liquidity=${liquidityScore.one()}",
            "overheatPenalty=${overheatPenalty.one()}",
            "staleSnapshotPenalty=${staleSnapshotPenalty.one()}",
            "postSpikeChasePenalty=${postSpikeChasePenalty.one()}",
        ).joinToString(";")
        val missedReason = missedReason(status, total, changeRate24h, changeRate30m, volumeAcceleration, nearHigh)
        val strategy = TradeStrategy(
            id = "${ticker.market}-${strategyType.name}",
            symbol = ticker.market,
            strategyType = strategyType,
            status = status,
            score = total,
            rank = Int.MAX_VALUE,
            entryLow = price * 0.998,
            entryHigh = price * 1.002,
            stopLoss = stopLoss,
            target1 = target1,
            target2 = target2,
            trailingStop = trailingStop,
            expectedReturnPct = expectedReturnPct,
            riskPct = riskPct,
            riskRewardRatio = expectedReturnPct / riskPct,
            componentScores = componentScores,
            rankByChangeRate = candidate.rankByChangeRate,
            rankByTradeValue = candidate.rankByTradeValue,
            changeRate24h = changeRate24h,
            changeRate30m = changeRate30m,
            changeRate5m = changeRate5m,
            volumeAcceleration = volumeAcceleration,
            reason = buildReason(strategyType, status, componentScores),
            invalidationReason = missedReason,
            createdAt = now,
            updatedAt = now,
            validUntil = now + VALID_FOR_MS,
        )
        val log = StrategyScanLog(
            market = ticker.market,
            timestamp = now,
            currentPrice = price,
            entryPrice = price,
            stopLossPrice = stopLoss,
            target1 = target1,
            target2 = target2,
            trailingStop = trailingStop,
            score = total,
            componentScores = componentScores,
            rankByChangeRate = candidate.rankByChangeRate,
            rankByTradeValue = candidate.rankByTradeValue,
            changeRate24h = changeRate24h,
            changeRate30m = changeRate30m,
            changeRate5m = changeRate5m,
            volumeAcceleration = volumeAcceleration,
            selectedOrMissed = "MISSED",
            missedReason = missedReason,
            topNAtScan = 0,
            strategyStatus = status,
        )
        return Evaluation(strategy, log)
    }

    private fun classifyStrategy(
        changeRate24h: Double,
        changeRate30m: Double,
        changeRate5m: Double,
        snapshotMomentum: Double,
        rankByChangeRate: Int,
        nearHigh: Double,
        high15m: Double,
        price: Double,
        volumeAcceleration: Double,
        rangePct: Double,
    ): Pair<StrategyType, StrategyStatus>? {
        val stronglyRising = changeRate30m > 0.25 && changeRate5m > 0.05 && snapshotMomentum > 0.0
        if (changeRate24h >= 20.0) {
            return if (volumeAcceleration >= 2.5) StrategyType.MOMENTUM_BREAKOUT to StrategyStatus.WATCH_ONLY else null
        }
        if (stronglyRising && volumeAcceleration >= 1.15 && nearHigh >= 0.985) {
            if (percentChange(high15m, price) > 1.2 || changeRate5m > 2.8) {
                return StrategyType.MOMENTUM_BREAKOUT to StrategyStatus.WATCH_ONLY
            }
            return StrategyType.MOMENTUM_BREAKOUT to StrategyStatus.ACTIVE
        }
        if (volumeAcceleration >= 1.6 && abs(changeRate24h) < 12.0 && rangePct in 0.35..3.5) {
            return StrategyType.VOLUME_EXPANSION to StrategyStatus.ACTIVE
        }
        val reboundConfirmed = changeRate30m > -1.0 && changeRate5m > 0.15 && price >= high15m * 0.995
        if (changeRate24h > -8.0 && reboundConfirmed && volumeAcceleration >= 1.2) {
            return StrategyType.PULLBACK_REBOUND to StrategyStatus.ACTIVE
        }
        if (rankByChangeRate <= 10 || changeRate30m > 0.7 || volumeAcceleration >= 1.5) {
            return StrategyType.WATCH_ONLY to StrategyStatus.WATCH_ONLY
        }
        return null
    }

    private fun scoreImmediateMomentum(change30m: Double, change5m: Double, snapshot: Double): Double {
        return (change30m * 5.0 + change5m * 6.0 + snapshot * 8.0).coerceIn(0.0, 25.0)
    }

    private fun scoreVolumeAcceleration(ratio: Double): Double {
        return ((ratio - 1.0) * 12.0).coerceIn(0.0, 20.0)
    }

    private fun scoreEntryProximity(nearHigh: Double, postSpikeDistancePct: Double, rangePct: Double): Double {
        val nearBreakout = ((nearHigh - 0.965) * 420.0).coerceIn(0.0, 18.0)
        val notTooFar = if (postSpikeDistancePct <= 0.8) 2.0 else 0.0
        return (nearBreakout + notTooFar - max(0.0, rangePct - 4.0)).coerceIn(0.0, 20.0)
    }

    private fun scoreChangeRank(rank: Int): Double = when {
        rank <= 10 -> 15.0
        rank <= 20 -> 10.0
        rank <= 40 -> 5.0
        else -> 0.0
    }

    private fun scoreRiskReward(rangePct: Double): Double {
        return (4.0 + min(rangePct, 6.0)).coerceIn(0.0, 10.0)
    }

    private fun scoreLiquidity(accTradePrice24h: Double): Double {
        if (accTradePrice24h <= 0.0) return 0.0
        return (kotlin.math.ln(accTradePrice24h + 1.0) / 3.0 - 4.5).coerceIn(0.0, 10.0)
    }

    private fun scoreOverheat(change24h: Double, change30m: Double, change5m: Double): Double {
        var penalty = 0.0
        if (change24h > 16.0) penalty += (change24h - 16.0) * 1.2
        if (change30m > 4.0) penalty += (change30m - 4.0) * 2.0
        if (change5m > 2.5) penalty += (change5m - 2.5) * 4.0
        return penalty.coerceIn(0.0, 25.0)
    }

    private fun scoreStaleSnapshot(candidate: MarketCandidate): Double {
        val last = candidate.oneMinuteCandles.lastOrNull()?.timestamp ?: return 15.0
        val ageMs = System.currentTimeMillis() - last
        return when {
            ageMs <= 3 * 60 * 1000L -> 0.0
            ageMs <= 10 * 60 * 1000L -> 6.0
            else -> 15.0
        }
    }

    private fun scorePostSpikeChase(nearHigh: Double, change5m: Double, change30m: Double): Double {
        var penalty = 0.0
        if (nearHigh < 0.975 && change30m > 1.5) penalty += 8.0
        if (change5m > 1.8) penalty += (change5m - 1.8) * 7.0
        return penalty.coerceIn(0.0, 20.0)
    }

    private fun missedReason(
        status: StrategyStatus,
        score: Double,
        changeRate24h: Double,
        changeRate30m: Double,
        volumeAcceleration: Double,
        nearHigh: Double,
    ): String? = when {
        status == StrategyStatus.ACTIVE -> null
        changeRate24h >= 20.0 -> "OVERHEATED_24H"
        changeRate30m <= 0.0 -> "NO_30M_CONFIRMATION"
        volumeAcceleration < 1.15 -> "NO_VOLUME_ACCELERATION"
        nearHigh < 0.985 -> "ENTRY_TOO_FAR_FROM_BREAKOUT"
        score < DEFAULT_MINIMUM_SCORE -> "SCORE_TOO_LOW"
        else -> "WATCH_ONLY"
    }

    private fun buildReason(type: StrategyType, status: StrategyStatus, scores: String): String {
        return "$type $status; $scores"
    }

    private fun percentChange(from: Double, to: Double): Double {
        if (from <= 0.0) return 0.0
        return ((to - from) / from) * 100.0
    }

    private fun Double.one(): String = String.format("%.1f", this)

    private data class Evaluation(
        val strategy: TradeStrategy,
        val log: StrategyScanLog,
    )

    companion object {
        const val DEFAULT_MAX_RESULTS = 5
        const val DEFAULT_MINIMUM_SCORE = 60.0
        private const val VALID_FOR_MS = 30 * 60 * 1000L
    }
}
