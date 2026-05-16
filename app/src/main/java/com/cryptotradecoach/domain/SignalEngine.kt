package com.cryptotradecoach.domain

import com.cryptotradecoach.data.MarketCandidate
import com.cryptotradecoach.data.StrategyRules
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
        rules: StrategyRules = StrategyRules.DEFAULT,
        now: Long = System.currentTimeMillis(),
        minimumScore: Double = rules.minimumScore,
        maxResults: Int = rules.maxResults,
    ): StrategyScanResult {
        val rejectionSummary = mutableMapOf<String, Int>()
        val evaluations = candidates.mapNotNull { candidate ->
            evaluateCandidate(candidate, rules, now).also { evaluation ->
                if (evaluation == null) {
                    rejectionSummary.increment("INSUFFICIENT_CANDLE_DATA")
                }
            }
        }
        val rankedActive = evaluations
            .mapNotNull { it.strategy.takeIf { strategy -> strategy.status == StrategyStatus.ACTIVE && strategy.score >= minimumScore } }
            .sortedWith(compareByDescending<TradeStrategy> { it.score }.thenBy { it.riskPct })
            .take(maxResults.coerceIn(3, 5))
            .mapIndexed { index, strategy -> strategy.copy(rank = index + 1) }

        val rankById = rankedActive.associate { it.id to it.rank }
        val activeIds = rankedActive.map { it.id }.toSet()
        val logs = evaluations.map { evaluation ->
            val topRank = rankById[evaluation.strategy.id] ?: 0
            val isSelected = evaluation.strategy.id in activeIds
            val missedReason = when {
                isSelected -> null
                evaluation.log.missedReason != null -> evaluation.log.missedReason
                evaluation.strategy.score < minimumScore -> "SCORE_TOO_LOW"
                else -> "NOT_TOP_RANKED"
            }
            if (!isSelected) {
                rejectionSummary.increment(missedReason ?: "REJECTED")
            }
            evaluation.log.copy(
                selectedOrMissed = if (isSelected) "SELECTED" else "MISSED",
                missedReason = missedReason,
                topNAtScan = topRank,
            )
        }

        return StrategyScanResult(
            activeStrategies = rankedActive,
            scanLogs = logs,
            validSignals = rankedActive,
            scannedCount = candidates.size,
            candidateCount = evaluations.size,
            rejectedCount = (candidates.size - rankedActive.size).coerceAtLeast(0),
            rejectionSummary = rejectionSummary.toSortedMap(),
            lastError = null,
        )
    }

    private fun evaluateCandidate(candidate: MarketCandidate, rules: StrategyRules, now: Long): Evaluation? {
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

        val immediateMomentumScore = scoreImmediateMomentum(changeRate30m, changeRate5m, snapshotMomentum, rules)
        val volumeAccelerationScore = scoreVolumeAcceleration(volumeAcceleration, rules)
        val entryProximityScore = scoreEntryProximity(nearHigh, postSpikeDistancePct, rangePct, rules)
        val changeRankScore = scoreChangeRank(candidate.rankByChangeRate, rules)
        val riskRewardScore = scoreRiskReward(rangePct, rules)
        val liquidityScore = scoreLiquidity(ticker.accTradePrice24h, rules)
        val overheatPenalty = scoreOverheat(changeRate24h, changeRate30m, changeRate5m, rules)
        val staleSnapshotPenalty = scoreStaleSnapshot(candidate, rules)
        val postSpikeChasePenalty = scorePostSpikeChase(nearHigh, changeRate5m, changeRate30m, rules)

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
            rules = rules,
        )

        val strategyType = typeAndStatus?.first ?: StrategyType.WATCH_ONLY
        val status = typeAndStatus?.second ?: StrategyStatus.WATCH_ONLY
        val stopLoss = price * when (strategyType) {
            StrategyType.VOLUME_EXPANSION -> rules.risk.volumeExpansionStopMultiplier
            else -> rules.risk.defaultStopMultiplier
        }
        val target1 = price * if (rangePct >= rules.risk.wideRangeThresholdPct) {
            rules.risk.wideRangeTarget1Multiplier
        } else {
            rules.risk.defaultTarget1Multiplier
        }
        val target2 = price * (rules.risk.target2BaseMultiplier + min(rangePct / rules.risk.target2RangeDivisor, rules.risk.target2RangeCap))
        val trailingStop = price * rules.risk.trailingStopMultiplier
        val riskPct = abs(percentChange(price, stopLoss)).coerceAtLeast(rules.risk.minimumRiskPct)
        val expectedReturnPct = percentChange(price, target2).coerceAtLeast(rules.risk.minimumExpectedReturnPct)
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
        val missedReason = missedReason(status, total, changeRate24h, changeRate30m, volumeAcceleration, nearHigh, rules)
        val strategy = TradeStrategy(
            id = "${ticker.market}-${strategyType.name}",
            symbol = ticker.market,
            strategyType = strategyType,
            status = status,
            score = total,
            rank = Int.MAX_VALUE,
            entryLow = price * (1.0 - rules.entryBandPct / 100.0),
            entryHigh = price * (1.0 + rules.entryBandPct / 100.0),
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
            validUntil = now + rules.validForMinutes * 60 * 1000L,
        )
        val log = StrategyScanLog(
            market = ticker.market,
            strategyType = strategyType,
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
        rules: StrategyRules,
    ): Pair<StrategyType, StrategyStatus>? {
        val positiveMomentum = changeRate24h > 0.0 || changeRate30m > 0.0 || changeRate5m > 0.0
        val reboundWindow = changeRate24h in rules.pullbackRebound.minChange24hPct..rules.pullbackRebound.maxChange24hPct
        if (changeRate24h >= rules.momentumBreakout.overheated24hPct) {
            return if (volumeAcceleration >= rules.momentumBreakout.overheatedVolumeAcceleration) {
                StrategyType.MOMENTUM_BREAKOUT to StrategyStatus.WATCH_ONLY
            } else {
                null
            }
        }
        if (
            positiveMomentum &&
            changeRate30m >= rules.momentumBreakout.minChange30mPct &&
            changeRate5m >= rules.momentumBreakout.minChange5mPct &&
            snapshotMomentum >= rules.momentumBreakout.minSnapshotMomentumPct
        ) {
            if (
                percentChange(high15m, price) > rules.momentumBreakout.watchHigh15mDistancePct ||
                changeRate5m > rules.momentumBreakout.watchChange5mPct
            ) {
                return StrategyType.MOMENTUM_BREAKOUT to StrategyStatus.WATCH_ONLY
            }
            return if (
                nearHigh >= rules.momentumBreakout.minNearHigh ||
                rankByChangeRate <= rules.momentumBreakout.maxChangeRank ||
                volumeAcceleration >= rules.momentumBreakout.minVolumeAcceleration
            ) {
                StrategyType.MOMENTUM_BREAKOUT to StrategyStatus.ACTIVE
            } else {
                StrategyType.MOMENTUM_BREAKOUT to StrategyStatus.WATCH_ONLY
            }
        }
        if (
            volumeAcceleration >= rules.volumeExpansion.minVolumeAcceleration &&
            abs(changeRate24h) < rules.volumeExpansion.maxAbsChange24hPct &&
            rangePct in rules.volumeExpansion.minRangePct..rules.volumeExpansion.maxRangePct
        ) {
            return StrategyType.VOLUME_EXPANSION to StrategyStatus.ACTIVE
        }
        val reboundConfirmed = reboundWindow &&
            changeRate30m > rules.pullbackRebound.minChange30mPct &&
            changeRate5m > rules.pullbackRebound.minChange5mPct &&
            price >= high15m * rules.pullbackRebound.high15mPriceMultiplier
        if (reboundConfirmed && volumeAcceleration >= rules.pullbackRebound.minVolumeAcceleration) {
            return StrategyType.PULLBACK_REBOUND to StrategyStatus.ACTIVE
        }
        if (
            rankByChangeRate <= rules.momentumBreakout.maxChangeRank ||
            changeRate30m > rules.momentumBreakout.minChange30mPct ||
            volumeAcceleration >= rules.momentumBreakout.minVolumeAcceleration
        ) {
            return StrategyType.WATCH_ONLY to StrategyStatus.WATCH_ONLY
        }
        return null
    }

    private fun scoreImmediateMomentum(change30m: Double, change5m: Double, snapshot: Double, rules: StrategyRules): Double {
        return (
            change30m * rules.scoring.change30mWeight +
                change5m * rules.scoring.change5mWeight +
                snapshot * rules.scoring.snapshotMomentumWeight
            ).coerceIn(0.0, rules.scoring.immediateMomentumMax)
    }

    private fun scoreVolumeAcceleration(ratio: Double, rules: StrategyRules): Double {
        return ((ratio - rules.scoring.volumeAccelerationBase) * rules.scoring.volumeAccelerationWeight)
            .coerceIn(0.0, rules.scoring.volumeAccelerationMax)
    }

    private fun scoreEntryProximity(nearHigh: Double, postSpikeDistancePct: Double, rangePct: Double, rules: StrategyRules): Double {
        val nearBreakout = ((nearHigh - rules.scoring.nearHighBase) * rules.scoring.nearHighWeight)
            .coerceIn(0.0, rules.scoring.nearHighMax)
        val notTooFar = if (postSpikeDistancePct <= rules.scoring.postSpikeDistanceBonusLimitPct) {
            rules.scoring.postSpikeDistanceBonus
        } else {
            0.0
        }
        return (nearBreakout + notTooFar - max(0.0, rangePct - rules.scoring.rangePenaltyBasePct))
            .coerceIn(0.0, rules.scoring.entryProximityMax)
    }

    private fun scoreChangeRank(rank: Int, rules: StrategyRules): Double = when {
        rank <= rules.scoring.changeRankTopLimit -> rules.scoring.changeRankTopScore
        rank <= rules.scoring.changeRankMidLimit -> rules.scoring.changeRankMidScore
        rank <= rules.scoring.changeRankLowLimit -> rules.scoring.changeRankLowScore
        else -> 0.0
    }

    private fun scoreRiskReward(rangePct: Double, rules: StrategyRules): Double {
        return (rules.scoring.riskRewardBase + min(rangePct, rules.scoring.riskRewardRangeCapPct)).coerceIn(0.0, 10.0)
    }

    private fun scoreLiquidity(accTradePrice24h: Double, rules: StrategyRules): Double {
        if (accTradePrice24h <= 0.0) return 0.0
        return (kotlin.math.ln(accTradePrice24h + 1.0) / rules.scoring.liquidityLogDivisor - rules.scoring.liquidityOffset)
            .coerceIn(0.0, 10.0)
    }

    private fun scoreOverheat(change24h: Double, change30m: Double, change5m: Double, rules: StrategyRules): Double {
        var penalty = 0.0
        if (change24h > rules.scoring.overheat24hBasePct) {
            penalty += (change24h - rules.scoring.overheat24hBasePct) * rules.scoring.overheat24hWeight
        }
        if (change30m > rules.scoring.overheat30mBasePct) {
            penalty += (change30m - rules.scoring.overheat30mBasePct) * rules.scoring.overheat30mWeight
        }
        if (change5m > rules.scoring.overheat5mBasePct) {
            penalty += (change5m - rules.scoring.overheat5mBasePct) * rules.scoring.overheat5mWeight
        }
        return penalty.coerceIn(0.0, rules.scoring.overheatMax)
    }

    private fun scoreStaleSnapshot(candidate: MarketCandidate, rules: StrategyRules): Double {
        val last = candidate.oneMinuteCandles.lastOrNull()?.timestamp ?: return rules.scoring.staleSnapshotErrorPenalty
        val ageMs = System.currentTimeMillis() - last
        return when {
            ageMs <= rules.scoring.staleSnapshotWarningMinutes * 60 * 1000L -> 0.0
            ageMs <= rules.scoring.staleSnapshotErrorMinutes * 60 * 1000L -> rules.scoring.staleSnapshotWarningPenalty
            else -> rules.scoring.staleSnapshotErrorPenalty
        }
    }

    private fun scorePostSpikeChase(nearHigh: Double, change5m: Double, change30m: Double, rules: StrategyRules): Double {
        var penalty = 0.0
        if (nearHigh < rules.scoring.postSpikeNearHighLimit && change30m > rules.scoring.postSpikeChange30mLimitPct) {
            penalty += rules.scoring.postSpikeNearHighPenalty
        }
        if (change5m > rules.scoring.postSpikeChange5mBasePct) {
            penalty += (change5m - rules.scoring.postSpikeChange5mBasePct) * rules.scoring.postSpikeChange5mWeight
        }
        return penalty.coerceIn(0.0, rules.scoring.postSpikeMax)
    }

    private fun missedReason(
        status: StrategyStatus,
        score: Double,
        changeRate24h: Double,
        changeRate30m: Double,
        volumeAcceleration: Double,
        nearHigh: Double,
        rules: StrategyRules,
    ): String? = when {
        status == StrategyStatus.ACTIVE -> null
        changeRate24h >= rules.momentumBreakout.overheated24hPct -> "OVERHEATED_24H"
        changeRate30m <= 0.0 -> "NO_30M_CONFIRMATION"
        volumeAcceleration < rules.momentumBreakout.minVolumeAcceleration -> "NO_VOLUME_ACCELERATION"
        nearHigh < rules.momentumBreakout.minNearHigh -> "ENTRY_TOO_FAR_FROM_BREAKOUT"
        score < rules.minimumScore -> "SCORE_TOO_LOW"
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

    private fun MutableMap<String, Int>.increment(key: String) {
        this[key] = (this[key] ?: 0) + 1
    }

    private data class Evaluation(
        val strategy: TradeStrategy,
        val log: StrategyScanLog,
    )

    companion object {
        val DEFAULT_MAX_RESULTS: Int = StrategyRules.DEFAULT.maxResults
        val DEFAULT_MINIMUM_SCORE: Double = StrategyRules.DEFAULT.minimumScore
    }
}
