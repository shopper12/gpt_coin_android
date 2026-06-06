package com.cryptotradecoach.domain

import com.cryptotradecoach.data.Candle
import com.cryptotradecoach.data.CandleData
import com.cryptotradecoach.data.MarketCandidate
import com.cryptotradecoach.data.StrategyRules
import com.cryptotradecoach.data.StrategyScanLog
import com.cryptotradecoach.data.StrategyScanResult
import com.cryptotradecoach.data.StrategyStatus
import com.cryptotradecoach.data.StrategyType
import com.cryptotradecoach.data.Ticker
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
        val tickers = candidates.map { it.ticker }
        val candleData = candidates.associate { candidate ->
            candidate.ticker.market to mapOf(
                1 to candidate.oneMinuteCandles,
                5 to candidate.fiveMinuteCandles,
                15 to candidate.fifteenMinuteCandles,
                240 to candidate.fourHourCandles,
            )
        }
        val candidateBtcChange = candidates.firstOrNull { it.btcChangeRate24h != 0.0 }?.btcChangeRate24h
        return scan(
            tickers = tickers,
            candleData = candleData,
            rules = rules,
            now = now,
            minimumScore = minimumScore,
            maxResults = maxResults,
            btcChangeRateOverride = candidateBtcChange,
        )
    }

    fun scan(
        tickers: List<Ticker>,
        candleData: CandleData,
        rules: StrategyRules = StrategyRules.DEFAULT,
        now: Long = System.currentTimeMillis(),
        minimumScore: Double = rules.minimumScore,
        maxResults: Int = rules.maxResults,
        btcChangeRateOverride: Double? = null,
    ): StrategyScanResult {
        val rejectionSummary = mutableMapOf<String, Int>()
        val ranks = CandidateRanks.from(tickers)
        val btcChangeRate24h = btcChangeRateOverride
            ?: tickers.firstOrNull { it.market == "KRW-BTC" }?.signedChangeRate?.times(100.0)
            ?: 0.0
        val evaluations = tickers.mapNotNull { ticker ->
            evaluateTicker(
                ticker = ticker,
                candles = candleData[ticker.market].orEmpty(),
                ranks = ranks,
                btcChangeRate24h = btcChangeRate24h,
                rules = rules,
                now = now,
            ).also { evaluation ->
                if (evaluation == null) rejectionSummary.increment("INSUFFICIENT_CANDLE_DATA")
            }
        }

        val rankedActive = evaluations
            .mapNotNull { evaluation ->
                evaluation.strategy.takeIf { strategy ->
                    strategy.status == StrategyStatus.ACTIVE && strategy.score >= minimumScore
                }
            }
            .sortedWith(compareByDescending<TradeStrategy> { it.score }.thenBy { it.riskPct })
            .take(maxResults.coerceIn(1, 20))
            .mapIndexed { index, strategy -> strategy.copy(rank = index + 1) }

        val rankById = rankedActive.associate { it.id to it.rank }
        val activeIds = rankedActive.map { it.id }.toSet()
        val logs = evaluations.map { evaluation ->
            val selected = evaluation.strategy.id in activeIds
            val missedReason = when {
                selected -> null
                evaluation.log.missedReason != null -> evaluation.log.missedReason
                evaluation.strategy.score < minimumScore -> "SCORE_TOO_LOW"
                else -> "NOT_TOP_RANKED"
            }
            if (!selected) rejectionSummary.increment(missedReason ?: "REJECTED")
            evaluation.log.copy(
                selectedOrMissed = if (selected) "SELECTED" else "MISSED",
                missedReason = missedReason,
                topNAtScan = rankById[evaluation.strategy.id] ?: 0,
            )
        }

        return StrategyScanResult(
            activeStrategies = rankedActive,
            scanLogs = logs,
            validSignals = rankedActive,
            scannedCount = tickers.size,
            candidateCount = evaluations.size,
            rejectedCount = (tickers.size - rankedActive.size).coerceAtLeast(0),
            rejectionSummary = rejectionSummary.toSortedMap(),
            lastError = null,
        )
    }

    private fun evaluateTicker(
        ticker: Ticker,
        candles: Map<Int, List<Candle>>,
        ranks: CandidateRanks,
        btcChangeRate24h: Double,
        rules: StrategyRules,
        now: Long,
    ): Evaluation? {
        val one = candles[1].orEmpty().sortedBy { it.timestamp }
        val five = candles[5].orEmpty().sortedBy { it.timestamp }
        val fifteen = candles[15].orEmpty().sortedBy { it.timestamp }
        val fourHour = candles[240].orEmpty().sortedBy { it.timestamp }
        if (five.size < 25 || fifteen.size < 25) return null

        val price = ticker.tradePrice.takeIf { it > 0.0 } ?: five.last().close
        if (price <= 0.0) return null

        val rankByChangeRate = ranks.change[ticker.market] ?: Int.MAX_VALUE
        val rankByTradeValue = ranks.tradeValue[ticker.market] ?: Int.MAX_VALUE
        val changeRate24h = ticker.signedChangeRate * 100.0
        val changeRate30m = percentChange(five.takeLast(6).first().open, price)
        val changeRate5m = percentChange(five.last().open, price)
        val recentVolume = five.takeLast(3).sumOf { it.tradePrice }
        val baseVolume = five.dropLast(3).takeLast(20).sumOf { it.tradePrice } / 6.67
        val volumeAcceleration = if (baseVolume > 0.0) recentVolume / baseVolume else 1.0
        val oneMinuteSpike = volumeSpikeEarly(
            price = price,
            changeRate5m = changeRate5m,
            one = one,
            five = five,
            rules = rules,
        )

        val overheatPenalty = overheatPenalty(changeRate24h, changeRate30m, changeRate5m, rules)
        val liquidityPenalty = liquidityPenalty(ticker.accTradePrice24h)
        val setups = listOf(
            compressionBreakout(price, changeRate24h, volumeAcceleration, five, fifteen, rules),
            sweepReclaim(price, volumeAcceleration, five, fifteen, rules),
            trendPullback(price, volumeAcceleration, five, fifteen, fourHour, rules),
            bearDecouplingBounce(
                price = price,
                btcChangeRate24h = btcChangeRate24h,
                changeRate24h = changeRate24h,
                rankByTradeValue = rankByTradeValue,
                five = five,
                fourHour = fourHour,
                rules = rules,
            ),
            prePumpRotation(
                price = price,
                changeRate24h = changeRate24h,
                changeRate30m = changeRate30m,
                changeRate5m = changeRate5m,
                volumeAcceleration = volumeAcceleration,
                oneMinuteSpike = oneMinuteSpike,
                rankByChangeRate = rankByChangeRate,
                rankByTradeValue = rankByTradeValue,
                five = five,
                fifteen = fifteen,
                rules = rules,
            ),
            btcShortRegime(
                price = price,
                market = ticker.market,
                changeRate24h = changeRate24h,
                changeRate30m = changeRate30m,
                changeRate5m = changeRate5m,
                volumeAcceleration = volumeAcceleration,
                five = five,
                fifteen = fifteen,
                fourHour = fourHour,
                rules = rules,
            ),
        )
        val best = setups.maxByOrNull { it.rawScore - it.penaltySensitiveMultiplier * (overheatPenalty + liquidityPenalty) } ?: return null
        val score = (best.rawScore - best.penaltySensitiveMultiplier * (overheatPenalty + liquidityPenalty)).coerceIn(0.0, 100.0)
        val status = if (best.active) StrategyStatus.ACTIVE else StrategyStatus.WATCH_ONLY
        val isShort = best.strategyType == StrategyType.BTC_SHORT_REGIME
        val stopLoss = if (isShort) {
            max(best.stopLoss.takeIf { it > 0.0 } ?: price * 1.012, price * 1.001)
        } else {
            min(best.stopLoss.takeIf { it > 0.0 } ?: price * rules.risk.defaultStopMultiplier, price * 0.999)
        }
        val riskPct = abs(percentChange(price, stopLoss)).coerceAtLeast(rules.risk.minimumRiskPct)
        val target1 = if (isShort) price * (1.0 - riskPct * 1.5 / 100.0) else price * (1.0 + riskPct * 1.5 / 100.0)
        val target2 = if (isShort) price * (1.0 - riskPct * 2.4 / 100.0) else price * (1.0 + riskPct * 2.4 / 100.0)
        val expectedReturnPct = abs(percentChange(price, target2)).coerceAtLeast(rules.risk.minimumExpectedReturnPct)
        val trailingStop = if (isShort) {
            price * (1.0 + max(riskPct * 0.55, rules.risk.minimumRiskPct) / 100.0)
        } else {
            price * (1.0 - max(riskPct * 0.55, rules.risk.minimumRiskPct) / 100.0)
        }
        val componentScores = (
            listOf(
                "trendScore=${best.trendScore.one()}",
                "compressionScore=${best.compressionScore.one()}",
                "relativeVolumeScore=${best.relativeVolumeScore.one()}",
                "breakoutProximityScore=${best.breakoutProximityScore.one()}",
                "reclaimScore=${best.reclaimScore.one()}",
                "riskRewardScore=${best.riskRewardScore.one()}",
                "overheatPenalty=${overheatPenalty.one()}",
                "liquidityPenalty=${liquidityPenalty.one()}",
            ) + best.diagnostics
        ).joinToString(";")
        val missedReason = when {
            status == StrategyStatus.ACTIVE -> null
            best.strategyType == StrategyType.COMPRESSION_BREAKOUT -> "NO_COMPRESSION_BREAKOUT"
            best.strategyType == StrategyType.SWEEP_RECLAIM -> "NO_SWEEP_RECLAIM"
            best.strategyType == StrategyType.TREND_PULLBACK -> "NO_TREND_PULLBACK"
            best.strategyType == StrategyType.BEAR_DECOUPLING_BOUNCE -> "NO_BEAR_DECOUPLING_BOUNCE"
            best.strategyType == StrategyType.PRE_PUMP_ROTATION -> "NO_PRE_PUMP_ROTATION"
            best.strategyType == StrategyType.BTC_SHORT_REGIME -> "NO_BTC_SHORT_REGIME"
            else -> "WATCH_ONLY"
        }
        val reason = buildReason(best, score, overheatPenalty, liquidityPenalty)
        val strategy = TradeStrategy(
            id = "${ticker.market}-${best.strategyType.name}",
            symbol = ticker.market,
            strategyType = best.strategyType,
            status = status,
            score = score,
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
            rankByChangeRate = rankByChangeRate,
            rankByTradeValue = rankByTradeValue,
            changeRate24h = changeRate24h,
            changeRate30m = changeRate30m,
            changeRate5m = changeRate5m,
            volumeAcceleration = volumeAcceleration,
            reason = reason,
            invalidationReason = missedReason,
            createdAt = now,
            updatedAt = now,
            validUntil = now + rules.validForMinutes * 60 * 1000L,
        )
        val log = StrategyScanLog(
            market = ticker.market,
            strategyType = best.strategyType,
            timestamp = now,
            currentPrice = price,
            entryPrice = price,
            stopLossPrice = stopLoss,
            target1 = target1,
            target2 = target2,
            trailingStop = trailingStop,
            score = score,
            componentScores = componentScores,
            rankByChangeRate = rankByChangeRate,
            rankByTradeValue = rankByTradeValue,
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

    private fun compressionBreakout(
        price: Double,
        changeRate24h: Double,
        volumeAcceleration: Double,
        five: List<Candle>,
        fifteen: List<Candle>,
        rules: StrategyRules,
    ): StrategySetup {
        val recent = fifteen.takeLast(20)
        val previous = fifteen.dropLast(20).takeLast(20)
        val recentHigh = recent.maxOfOrNull { it.high } ?: price
        val recentLow = recent.minOfOrNull { it.low } ?: price
        val previousHigh = previous.maxOfOrNull { it.high } ?: recentHigh
        val previousLow = previous.minOfOrNull { it.low } ?: recentLow
        val distanceToHighPct = ((recentHigh - price) / recentHigh * 100.0).takeIf { recentHigh > 0.0 } ?: 99.0
        val recentRangePct = percentChange(recentLow, recentHigh).coerceAtLeast(0.0)
        val previousRangePct = percentChange(previousLow, previousHigh).coerceAtLeast(0.0)
        val r = rules.compressionBreakout
        val compressed = previousRangePct > 0.0 && recentRangePct <= previousRangePct * r.rangeCompressionRatio
        val nearHigh = distanceToHighPct in 0.0..r.maxDistanceTo15mHighPct
        val volumeRising = five.last().tradePrice >= five.dropLast(1).takeLast(20).averageTradePrice() * r.minFiveMinuteVolumeRatio ||
            volumeAcceleration >= r.minVolumeAcceleration
        val compressionScore = if (compressed) 18.0 else max(0.0, 12.0 - recentRangePct * 2.0)
        val breakoutProximityScore = if (nearHigh) (18.0 - distanceToHighPct * 3.0).coerceAtLeast(8.0) else 0.0
        val relativeVolumeScore = ((volumeAcceleration - 1.0) * 18.0).coerceIn(0.0, 20.0)
        val riskRewardScore = riskRewardScore(price, recentLow)
        val rawScore = compressionScore + breakoutProximityScore + relativeVolumeScore + riskRewardScore
        return StrategySetup(
            strategyType = StrategyType.COMPRESSION_BREAKOUT,
            active = r.enabled && nearHigh && compressed && volumeRising,
            stopLoss = recentLow * 0.997,
            rawScore = rawScore,
            trendScore = if (changeRate24h > 0.0) 4.0 else 0.0,
            compressionScore = compressionScore,
            relativeVolumeScore = relativeVolumeScore,
            breakoutProximityScore = breakoutProximityScore,
            reclaimScore = 0.0,
            riskRewardScore = riskRewardScore,
            passed = listOf(
                "15mHighDistance=${distanceToHighPct.one()}%",
                "rangeCompressed=$compressed",
                "5mVolumeRising=$volumeRising",
            ),
            failed = listOfNotNull(
                if (!r.enabled) "STRATEGY_DISABLED" else null,
                if (!nearHigh) "NOT_WITHIN_${r.maxDistanceTo15mHighPct.one()}PCT_OF_15M_HIGH" else null,
                if (!compressed) "RANGE_NOT_COMPRESSED" else null,
                if (!volumeRising) "NO_5M_VOLUME_RISE" else null,
            ),
        )
    }

    private fun sweepReclaim(
        price: Double,
        volumeAcceleration: Double,
        five: List<Candle>,
        fifteen: List<Candle>,
        rules: StrategyRules,
    ): StrategySetup {
        val r = rules.sweepReclaim
        val fiveSetup = reclaimOn(five, lookback = r.fiveMinuteLookback, requireVolumeAboveAverage = r.requireVolumeAboveAverage)
        val fifteenSetup = reclaimOn(fifteen, lookback = r.fifteenMinuteLookback, requireVolumeAboveAverage = r.requireVolumeAboveAverage)
        val reclaim = listOfNotNull(fiveSetup, fifteenSetup).maxByOrNull { it.reclaimScore }
        val active = r.enabled && reclaim != null
        val reclaimScore = reclaim?.reclaimScore ?: 0.0
        val relativeVolumeScore = ((volumeAcceleration - 1.0) * 16.0).coerceIn(0.0, 18.0)
        val riskRewardScore = riskRewardScore(price, reclaim?.sweepLow ?: price * 0.99)
        return StrategySetup(
            strategyType = StrategyType.SWEEP_RECLAIM,
            active = active,
            stopLoss = (reclaim?.sweepLow ?: price * 0.99) * 0.997,
            rawScore = reclaimScore + relativeVolumeScore + riskRewardScore,
            trendScore = 0.0,
            compressionScore = 0.0,
            relativeVolumeScore = relativeVolumeScore,
            breakoutProximityScore = 0.0,
            reclaimScore = reclaimScore,
            riskRewardScore = riskRewardScore,
            passed = listOfNotNull(
                reclaim?.let { "unit=${it.unit}mSweepLow=${it.sweepLow.one()}" },
                reclaim?.let { "reclaimCloseAbovePriorLow=${it.priorLow.one()}" },
                reclaim?.let { "volumeAboveAverage=${it.volumeAboveAverage}" },
            ),
            failed = listOfNotNull(
                if (!r.enabled) "STRATEGY_DISABLED" else null,
                if (active) null else "NO_RECENT_LOW_SWEEP_AND_RECLAIM",
            ),
        )
    }

    private fun trendPullback(
        price: Double,
        volumeAcceleration: Double,
        five: List<Candle>,
        fifteen: List<Candle>,
        fourHour: List<Candle>,
        rules: StrategyRules,
    ): StrategySetup {
        val r = rules.trendPullback
        val ma = fourHour.takeLast(r.higherTimeframeMaPeriod).map { it.close }.averageOrNull()
        val ema = fourHour.map { it.close }.ema(period = r.higherTimeframeMaPeriod)
        val aboveHigherTimeframe = listOfNotNull(ma, ema).any { price > it }
        val fifteenMa = fifteen.takeLast(r.fifteenMinuteMaPeriod).map { it.close }.averageOrNull() ?: price
        val fifteenTrendOk = fifteen.last().close >= fifteenMa * r.min15mMaMultiplier &&
            fifteen.takeLast(r.reclaimLookback).minOf { it.low } >= fifteen.dropLast(r.reclaimLookback).takeLast(12).minOfOrNull { it.low }.orZero() * r.minPriorLowMultiplier
        val recentFive = five.takeLast(r.pullbackLookback)
        val priorShortHigh = recentFive.dropLast(1).takeLast(r.reclaimLookback).maxOfOrNull { it.high } ?: price
        val pullbackLow = recentFive.minOfOrNull { it.low } ?: price
        val pullbackReclaimed = five.last().close >= priorShortHigh && pullbackLow < priorShortHigh
        val active = r.enabled && aboveHigherTimeframe && fifteenTrendOk && pullbackReclaimed
        val trendScore = when {
            aboveHigherTimeframe && fifteenTrendOk -> 26.0
            aboveHigherTimeframe -> 14.0
            else -> 0.0
        }
        val reclaimScore = if (pullbackReclaimed) 16.0 else 0.0
        val relativeVolumeScore = ((volumeAcceleration - 1.0) * 12.0).coerceIn(0.0, 14.0)
        val riskRewardScore = riskRewardScore(price, pullbackLow)
        return StrategySetup(
            strategyType = StrategyType.TREND_PULLBACK,
            active = active,
            stopLoss = pullbackLow * 0.997,
            rawScore = trendScore + reclaimScore + relativeVolumeScore + riskRewardScore,
            trendScore = trendScore,
            compressionScore = 0.0,
            relativeVolumeScore = relativeVolumeScore,
            breakoutProximityScore = 0.0,
            reclaimScore = reclaimScore,
            riskRewardScore = riskRewardScore,
            passed = listOf(
                "240mAboveMAorEMA=$aboveHigherTimeframe",
                "15mTrendIntact=$fifteenTrendOk",
                "5mPullbackHighReclaimed=$pullbackReclaimed",
            ),
            failed = listOfNotNull(
                if (!r.enabled) "STRATEGY_DISABLED" else null,
                if (!aboveHigherTimeframe) "PRICE_BELOW_240M_MA_EMA" else null,
                if (!fifteenTrendOk) "15M_TREND_BROKEN" else null,
                if (!pullbackReclaimed) "NO_5M_PULLBACK_RECLAIM" else null,
            ),
        )
    }

    private fun bearDecouplingBounce(
        price: Double,
        btcChangeRate24h: Double,
        changeRate24h: Double,
        rankByTradeValue: Int,
        five: List<Candle>,
        fourHour: List<Candle>,
        rules: StrategyRules,
    ): StrategySetup {
        val r = rules.bearDecouplingBounce
        if (fourHour.size < 22 || five.size < 3) {
            return blankSetup(StrategyType.BEAR_DECOUPLING_BOUNCE, price, rules, "INSUFFICIENT_240M_OR_5M_CANDLES")
        }
        val latest4h = fourHour.last()
        val previous4h = fourHour.dropLast(1).last()
        val base4h = fourHour.dropLast(1).takeLast(20)
        val avgTradeValue4h20 = base4h.averageTradePrice()
        val volumeMultiple4h = if (avgTradeValue4h20 > 0.0) latest4h.tradePrice / avgTradeValue4h20 else 0.0
        val previousVolumeMultiple4h = if (avgTradeValue4h20 > 0.0) previous4h.tradePrice / avgTradeValue4h20 else 0.0
        val ma20_240m = fourHour.takeLast(20).map { it.close }.averageOrNull() ?: price
        val notAtTop = price <= ma20_240m * (1.0 + r.maxPriceOver240mMa20Pct / 100.0)
        val notAfterPump = previousVolumeMultiple4h < r.maxPreviousFourHourVolumeMultiple
        val last5 = five.last()
        val candleRange = (last5.high - last5.low).coerceAtLeast(0.0)
        val upperWickPct = if (candleRange > 0.0) {
            (last5.high - max(last5.open, last5.close)) / candleRange * 100.0
        } else {
            0.0
        }
        val upperWickTooHeavy = upperWickPct > r.maxBearishUpperWickPct && last5.close < last5.open
        val btcWeak = btcChangeRate24h < r.btcWeakBelowPct
        val altStrong = changeRate24h > r.altStrongAbovePct
        val rankOk = rankByTradeValue <= r.maxTradeValueRank
        val strong4hVolume = volumeMultiple4h >= r.minFourHourVolumeMultiple
        val decoupled = btcWeak && altStrong
        val decouplingScore = if (decoupled) {
            (12.0 + min(abs(btcChangeRate24h), 5.0) * 1.5 + min(changeRate24h, 10.0) * 0.7).coerceAtMost(r.decouplingScoreCap)
        } else {
            0.0
        }
        val rankScore = when {
            rankByTradeValue <= 10 -> 18.0
            rankByTradeValue <= 20 -> 14.0
            rankByTradeValue <= r.maxTradeValueRank -> 10.0
            else -> 0.0
        }
        val fourHourVolumeScore = ((volumeMultiple4h - 1.0) * 8.0).coerceIn(0.0, 24.0)
        val notAfterPumpScore = if (notAfterPump) 12.0 else 0.0
        val notAtTopScore = if (notAtTop) 10.0 else 0.0
        val wickPenalty = if (upperWickTooHeavy) r.wickPenalty else 0.0
        val recentLow = min(latest4h.low, five.takeLast(10).minOfOrNull { it.low } ?: latest4h.low)
        val riskRewardScore = riskRewardScore(price, recentLow)
        val rawScore = decouplingScore + rankScore + fourHourVolumeScore + notAfterPumpScore + notAtTopScore + riskRewardScore - wickPenalty
        val active = r.enabled && decoupled && rankOk && strong4hVolume && notAfterPump && notAtTop && !upperWickTooHeavy
        return StrategySetup(
            strategyType = StrategyType.BEAR_DECOUPLING_BOUNCE,
            active = active,
            stopLoss = recentLow * 0.997,
            rawScore = rawScore,
            trendScore = decouplingScore,
            compressionScore = rankScore,
            relativeVolumeScore = fourHourVolumeScore,
            breakoutProximityScore = notAfterPumpScore,
            reclaimScore = notAtTopScore,
            riskRewardScore = riskRewardScore,
            passed = listOf(
                "btcChange=${btcChangeRate24h.one()}%",
                "altChange24h=${changeRate24h.one()}%",
                "rankByTradeValue=$rankByTradeValue",
                "4hVolumeMultiple=${volumeMultiple4h.one()}x",
                "prev4hVolumeMultiple=${previousVolumeMultiple4h.one()}x",
                "notAfterPump=$notAfterPump",
                "notAtTop=$notAtTop",
                "upperWickPct=${upperWickPct.one()}%",
            ),
            failed = listOfNotNull(
                if (!r.enabled) "STRATEGY_DISABLED" else null,
                if (!btcWeak) "BTC_NOT_WEAK" else null,
                if (!altStrong) "ALT_NOT_UP_OVER_${r.altStrongAbovePct.one()}PCT" else null,
                if (!rankOk) "TRADE_VALUE_RANK_OVER_${r.maxTradeValueRank}" else null,
                if (!strong4hVolume) "4H_VOLUME_UNDER_${r.minFourHourVolumeMultiple.one()}X" else null,
                if (!notAfterPump) "PREVIOUS_4H_ALREADY_PUMPED" else null,
                if (!notAtTop) "PRICE_OVER_240M_MA20_PLUS_${r.maxPriceOver240mMa20Pct.one()}PCT" else null,
                if (upperWickTooHeavy) "5M_UPPER_WICK_TOO_HEAVY" else null,
            ),
        )
    }

    private fun prePumpRotation(
        price: Double,
        changeRate24h: Double,
        changeRate30m: Double,
        changeRate5m: Double,
        volumeAcceleration: Double,
        oneMinuteSpike: EarlySpikeSignal,
        rankByChangeRate: Int,
        rankByTradeValue: Int,
        five: List<Candle>,
        fifteen: List<Candle>,
        rules: StrategyRules,
    ): StrategySetup {
        val last5 = five.last()
        val prev20High = five.dropLast(1).takeLast(20).maxOfOrNull { it.high } ?: price
        val prev20Low = five.dropLast(1).takeLast(20).minOfOrNull { it.low } ?: price
        val rangePct = percentChange(prev20Low, prev20High).coerceAtLeast(0.0)
        val rangePos = if (prev20High > prev20Low) ((price - prev20Low) / (prev20High - prev20Low)).coerceIn(0.0, 1.0) else 0.5
        val fiveVolumeRatio = ratio(last5.tradePrice, five.dropLast(1).takeLast(20).averageTradePrice())
        val fifteenVolumeRatio = ratio(fifteen.last().tradePrice, fifteen.dropLast(1).takeLast(20).averageTradePrice())
        val closesUp = five.takeLast(4).zipWithNext().count { it.second.close > it.first.close }
        val r = rules.prePumpRotation
        val notAlreadyPumped = changeRate24h in r.minChange24hPct..r.maxChange24hPct &&
            changeRate30m < r.maxChange30mPct &&
            changeRate5m < r.maxChange5mPct
        val liquidityOk = rankByTradeValue <= r.maxTradeValueRank
        val rotationOk = rankByChangeRate <= r.maxChangeRank || changeRate30m > r.minRotation30mPct
        val volumeIgnition = volumeAcceleration >= r.minVolumeAcceleration ||
            fiveVolumeRatio >= r.minFiveMinuteVolumeRatio ||
            fifteenVolumeRatio >= r.minFifteenMinuteVolumeRatio ||
            oneMinuteSpike.confirmed
        val structureOk = rangePct <= r.maxRangePct &&
            rangePos >= r.minRangePosition &&
            price >= prev20High * r.minHighProximityMultiplier
        val closeStairOk = closesUp >= r.minCloseStairCount || oneMinuteSpike.confirmed
        val active = r.enabled && notAlreadyPumped && liquidityOk && rotationOk && volumeIgnition && structureOk && closeStairOk
        val structureScore = when {
            structureOk -> 22.0
            rangePos >= 0.5 -> 12.0
            else -> 0.0
        }
        val baseVolumeScore = ((maxOf(volumeAcceleration, fiveVolumeRatio, fifteenVolumeRatio) - 1.0) * 18.0).coerceIn(0.0, 24.0)
        val volumeScore = (baseVolumeScore + oneMinuteSpike.score).coerceIn(0.0, 32.0)
        val rotationScore = when {
            rankByChangeRate <= 10 -> 20.0
            rankByChangeRate <= 25 -> 15.0
            changeRate30m > 0.7 -> 12.0
            else -> 0.0
        }
        val liquidityScore = when {
            rankByTradeValue <= 10 -> 16.0
            rankByTradeValue <= 25 -> 10.0
            else -> 0.0
        }
        val notPumpedScore = if (notAlreadyPumped) 14.0 else 0.0
        val stop = min(prev20Low, five.takeLast(8).minOfOrNull { it.low } ?: prev20Low) * 0.997
        return StrategySetup(
            strategyType = StrategyType.PRE_PUMP_ROTATION,
            active = active,
            stopLoss = stop,
            rawScore = structureScore + volumeScore + rotationScore + liquidityScore + notPumpedScore,
            trendScore = rotationScore,
            compressionScore = structureScore,
            relativeVolumeScore = volumeScore,
            breakoutProximityScore = liquidityScore,
            reclaimScore = notPumpedScore,
            riskRewardScore = riskRewardScore(price, stop),
            passed = listOf(
                "prePump=notYet10pct",
                "24h=${changeRate24h.one()}%",
                "30m=${changeRate30m.one()}%",
                "5m=${changeRate5m.one()}%",
                "rangePct=${rangePct.one()}%",
                "rangePos=${(rangePos * 100.0).one()}%",
                "volAccel=${volumeAcceleration.one()}x",
                "5mVolRatio=${fiveVolumeRatio.one()}x",
                "15mVolRatio=${fifteenVolumeRatio.one()}x",
                "1mSpike=${oneMinuteSpike.confirmed}",
                "1mVolRatio=${oneMinuteSpike.volumeRatio.one()}x",
                "1mMove=${oneMinuteSpike.priceMovePct.one()}%",
                "rankChange=$rankByChangeRate",
                "rankValue=$rankByTradeValue",
            ),
            failed = listOfNotNull(
                if (!notAlreadyPumped) "ALREADY_PUMPED_OR_TOO_WEAK" else null,
                if (!liquidityOk) "TRADE_VALUE_RANK_OVER_${r.maxTradeValueRank}" else null,
                if (!rotationOk) "NO_RELATIVE_ROTATION" else null,
                if (!volumeIgnition) "NO_VOLUME_IGNITION" else null,
                if (!structureOk) "NO_TIGHT_RANGE_BREAK_SETUP" else null,
                if (!closeStairOk) "NO_5M_CLOSE_STAIR_OR_1M_SPIKE" else null,
            ),
            penaltySensitiveMultiplier = 0.35,
            diagnostics = oneMinuteSpike.diagnostics,
        )
    }

    private fun btcShortRegime(
        price: Double,
        market: String,
        changeRate24h: Double,
        changeRate30m: Double,
        changeRate5m: Double,
        volumeAcceleration: Double,
        five: List<Candle>,
        fifteen: List<Candle>,
        fourHour: List<Candle>,
        rules: StrategyRules,
    ): StrategySetup {
        if (market != "KRW-BTC") return blankSetup(StrategyType.BTC_SHORT_REGIME, price, rules, "ONLY_KRW_BTC")
        if (fourHour.size < 22) return blankSetup(StrategyType.BTC_SHORT_REGIME, price, rules, "INSUFFICIENT_240M_CANDLES")
        val last5 = five.last()
        val prev5 = five.dropLast(1).last()
        val ma20_15m = fifteen.takeLast(20).map { it.close }.averageOrNull() ?: price
        val ma20_240m = fourHour.takeLast(20).map { it.close }.averageOrNull() ?: price
        val lowerHigh = five.takeLast(8).maxOf { it.high } < five.dropLast(8).takeLast(8).maxOfOrNull { it.high }.orZero()
        val belowShortMa = price < ma20_15m * 0.998
        val belowFourHourMa = price < ma20_240m * 0.995
        val downsideMomentum = changeRate30m <= -0.55 || changeRate5m <= -0.22
        val sellVolume = volumeAcceleration >= 1.35 && last5.close < last5.open
        val failedBounce = prev5.close > prev5.open && last5.close < prev5.open
        val active = belowShortMa && belowFourHourMa && downsideMomentum && (sellVolume || failedBounce || lowerHigh)
        val trendScore = if (belowFourHourMa) 22.0 else 0.0
        val momentumScore = (abs(min(changeRate30m, 0.0)) * 18.0 + abs(min(changeRate5m, 0.0)) * 28.0).coerceIn(0.0, 24.0)
        val volumeScore = ((volumeAcceleration - 1.0) * 18.0).coerceIn(0.0, 18.0)
        val failureScore = listOf(lowerHigh, failedBounce, belowShortMa).count { it } * 8.0
        val stop = five.takeLast(12).maxOfOrNull { it.high }?.times(1.003) ?: price * 1.012
        return StrategySetup(
            strategyType = StrategyType.BTC_SHORT_REGIME,
            active = active,
            stopLoss = stop,
            rawScore = 16.0 + trendScore + momentumScore + volumeScore + failureScore,
            trendScore = trendScore,
            compressionScore = failureScore,
            relativeVolumeScore = volumeScore,
            breakoutProximityScore = momentumScore,
            reclaimScore = if (failedBounce) 10.0 else 0.0,
            riskRewardScore = riskRewardScore(price, stop),
            passed = listOf(
                "direction=SHORT",
                "24h=${changeRate24h.one()}%",
                "30m=${changeRate30m.one()}%",
                "5m=${changeRate5m.one()}%",
                "below15mMA20=$belowShortMa",
                "below240mMA20=$belowFourHourMa",
                "lowerHigh=$lowerHigh",
                "sellVolume=$sellVolume",
                "failedBounce=$failedBounce",
            ),
            failed = listOfNotNull(
                if (!belowShortMa) "BTC_NOT_BELOW_15M_MA20" else null,
                if (!belowFourHourMa) "BTC_NOT_BELOW_240M_MA20" else null,
                if (!downsideMomentum) "NO_DOWNSIDE_MOMENTUM" else null,
                if (!(sellVolume || failedBounce || lowerHigh)) "NO_SHORT_TRIGGER" else null,
            ),
            penaltySensitiveMultiplier = 0.0,
        )
    }

    private fun blankSetup(type: StrategyType, price: Double, rules: StrategyRules, reason: String): StrategySetup {
        return StrategySetup(
            strategyType = type,
            active = false,
            stopLoss = price * rules.risk.defaultStopMultiplier,
            rawScore = 0.0,
            trendScore = 0.0,
            compressionScore = 0.0,
            relativeVolumeScore = 0.0,
            breakoutProximityScore = 0.0,
            reclaimScore = 0.0,
            riskRewardScore = 0.0,
            passed = emptyList(),
            failed = listOf(reason),
        )
    }

    private fun reclaimOn(candles: List<Candle>, lookback: Int, requireVolumeAboveAverage: Boolean): ReclaimSetup? {
        if (candles.size < lookback + 3) return null
        val latest = candles.last()
        val priorWindow = candles.dropLast(2).takeLast(lookback)
        val priorLow = priorWindow.minOfOrNull { it.low } ?: return null
        val sweep = candles.dropLast(1).takeLast(4).filter { it.low < priorLow }.minByOrNull { it.low } ?: return null
        val volumeAverage = candles.dropLast(1).takeLast(20).averageVolume()
        val volumeAboveAverage = latest.volume >= volumeAverage
        if (latest.close <= priorLow || (requireVolumeAboveAverage && !volumeAboveAverage)) return null
        val reclaimScore = 18.0 + min(abs(percentChange(priorLow, sweep.low)) * 4.0, 8.0)
        return ReclaimSetup(
            unit = latest.unit,
            priorLow = priorLow,
            sweepLow = sweep.low,
            volumeAboveAverage = volumeAboveAverage,
            reclaimScore = reclaimScore,
        )
    }

    private fun volumeSpikeEarly(
        price: Double,
        changeRate5m: Double,
        one: List<Candle>,
        five: List<Candle>,
        rules: StrategyRules,
    ): EarlySpikeSignal {
        if (one.size < 21) {
            return EarlySpikeSignal(
                confirmed = false,
                score = 0.0,
                volumeRatio = 0.0,
                priceMovePct = 0.0,
                diagnostics = listOf("1mCandles=${one.size}", "1mSpike=false", "1mReason=INSUFFICIENT_1M_CANDLES"),
            )
        }
        val latest = one.last()
        val baseTradePrice = one.dropLast(1).takeLast(20).averageTradePrice()
        val volumeRatio = ratio(latest.tradePrice, baseTradePrice)
        val priceMovePct = percentChange(latest.open, price)
        val prev20High = five.dropLast(1).takeLast(20).maxOfOrNull { it.high } ?: price
        val prev20Low = five.dropLast(1).takeLast(20).minOfOrNull { it.low } ?: price
        val rangePct = percentChange(prev20Low, prev20High).coerceAtLeast(0.0)
        val structureOk = rangePct <= rules.prePumpRotation.maxRangePct * 1.35 && price <= prev20High * 1.015
        val notAlreadyMoved = priceMovePct <= EARLY_SPIKE_MAX_1M_MOVE_PCT && changeRate5m <= rules.prePumpRotation.maxChange5mPct
        val confirmed = volumeRatio >= EARLY_SPIKE_MIN_VOLUME_RATIO && notAlreadyMoved && structureOk
        val score = if (confirmed) ((volumeRatio - 1.0) * 5.0).coerceIn(6.0, 16.0) else 0.0
        return EarlySpikeSignal(
            confirmed = confirmed,
            score = score,
            volumeRatio = volumeRatio,
            priceMovePct = priceMovePct,
            diagnostics = listOf(
                "1mCandles=${one.size}",
                "1mSpike=$confirmed",
                "1mVolRatio=${volumeRatio.one()}x",
                "1mMove=${priceMovePct.one()}%",
                "1mStructureOk=$structureOk",
                "1mScore=${score.one()}",
            ),
        )
    }

    private fun riskRewardScore(price: Double, stop: Double): Double {
        val riskPct = abs(percentChange(price, stop)).coerceAtLeast(0.1)
        val viable = riskPct in 0.1..4.0
        return if (viable) (18.0 - riskPct * 2.0).coerceIn(6.0, 18.0) else 2.0
    }

    private fun overheatPenalty(change24h: Double, change30m: Double, change5m: Double, rules: StrategyRules): Double {
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

    private fun liquidityPenalty(accTradePrice24h: Double): Double {
        val billionKrw = accTradePrice24h / 1_000_000_000.0
        return when {
            billionKrw >= 20.0 -> 0.0
            billionKrw >= 10.0 -> 3.0
            billionKrw >= 5.0 -> 6.0
            else -> 10.0
        }
    }

    private fun buildReason(setup: StrategySetup, score: Double, overheatPenalty: Double, liquidityPenalty: Double): String {
        val state = if (setup.active) "ACTIVE" else "WATCH_ONLY"
        val failed = if (setup.failed.isEmpty()) "none" else setup.failed.joinToString(",")
        val diagnostics = if (setup.diagnostics.isEmpty()) "none" else setup.diagnostics.joinToString(",")
        return "${setup.strategyType} $state; passed=${setup.passed.joinToString(",")}; failed=$failed; diagnostics=$diagnostics; score=${score.one()}; overheatPenalty=${overheatPenalty.one()}; liquidityPenalty=${liquidityPenalty.one()}"
    }

    private fun percentChange(from: Double, to: Double): Double {
        if (from <= 0.0) return 0.0
        return ((to - from) / from) * 100.0
    }

    private fun ratio(value: Double, base: Double): Double = if (base > 0.0) value / base else 1.0

    private fun List<Candle>.averageTradePrice(): Double = if (isEmpty()) 0.0 else sumOf { it.tradePrice } / size

    private fun List<Candle>.averageVolume(): Double = if (isEmpty()) 0.0 else sumOf { it.volume } / size

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    private fun List<Double>.ema(period: Int): Double? {
        if (period <= 0 || size < period) return null
        val multiplier = 2.0 / (period + 1.0)
        val seed = take(period).average()
        return drop(period).fold(seed) { ema, close -> (close - ema) * multiplier + ema }
    }

    private fun Double?.orZero(): Double = this ?: 0.0

    private fun Double.one(): String = String.format("%.1f", this)

    private fun MutableMap<String, Int>.increment(key: String) {
        this[key] = (this[key] ?: 0) + 1
    }

    private data class CandidateRanks(
        val change: Map<String, Int>,
        val tradeValue: Map<String, Int>,
    ) {
        companion object {
            fun from(tickers: List<Ticker>): CandidateRanks {
                return CandidateRanks(
                    change = tickers.sortedByDescending { it.signedChangeRate }
                        .mapIndexed { index, ticker -> ticker.market to index + 1 }
                        .toMap(),
                    tradeValue = tickers.sortedByDescending { it.accTradePrice24h }
                        .mapIndexed { index, ticker -> ticker.market to index + 1 }
                        .toMap(),
                )
            }
        }
    }

    private data class StrategySetup(
        val strategyType: StrategyType,
        val active: Boolean,
        val stopLoss: Double,
        val rawScore: Double,
        val trendScore: Double,
        val compressionScore: Double,
        val relativeVolumeScore: Double,
        val breakoutProximityScore: Double,
        val reclaimScore: Double,
        val riskRewardScore: Double,
        val passed: List<String>,
        val failed: List<String>,
        val penaltySensitiveMultiplier: Double = 1.0,
        val diagnostics: List<String> = emptyList(),
    )

    private data class ReclaimSetup(
        val unit: Int,
        val priorLow: Double,
        val sweepLow: Double,
        val volumeAboveAverage: Boolean,
        val reclaimScore: Double,
    )

    private data class EarlySpikeSignal(
        val confirmed: Boolean,
        val score: Double,
        val volumeRatio: Double,
        val priceMovePct: Double,
        val diagnostics: List<String>,
    )

    private data class Evaluation(
        val strategy: TradeStrategy,
        val log: StrategyScanLog,
    )

    companion object {
        val DEFAULT_MAX_RESULTS: Int = StrategyRules.DEFAULT.maxResults
        val DEFAULT_MINIMUM_SCORE: Double = StrategyRules.DEFAULT.minimumScore
        private const val EARLY_SPIKE_MIN_VOLUME_RATIO = 2.8
        private const val EARLY_SPIKE_MAX_1M_MOVE_PCT = 0.9
    }
}
