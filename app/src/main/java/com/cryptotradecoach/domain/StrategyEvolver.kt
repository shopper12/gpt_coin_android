package com.cryptotradecoach.domain

import com.cryptotradecoach.data.StrategyRules
import com.cryptotradecoach.data.StrategyRulesRepository
import com.cryptotradecoach.data.local.AppDatabase
import com.cryptotradecoach.data.local.EvolutionLogEntity
import com.cryptotradecoach.service.ScannerStateStore

// Applies conservative rule adjustments from realized backtest and missed-signal evidence.
class StrategyEvolver(
    private val rulesRepository: StrategyRulesRepository,
    private val db: AppDatabase,
) {
    private var lastEvolvedAt: Long = 0L

    suspend fun maybeEvolve(
        backtestResults: List<BacktestResult>,
        now: Long = System.currentTimeMillis(),
        currentRegime: BtcRegime = ScannerStateStore.currentBtcRegime.value,
    ) {
        if (now - lastEvolvedAt < EVOLUTION_INTERVAL_MS) return
        val current = rulesRepository.loadLastKnownGood()
        val postMortem = PostMortemEngine(db).analyze()
        val recentSignalCount = db.signalHistoryDao()
            .getPerformanceSince(now - DAY_MS, 5000)
            .size
        val missedRows = db.signalHistoryDao().getRecentMissedSignals(100)
            .filter { it.detectedAt >= now - DAY_MS }
        if (backtestResults.none { it.sampleSize >= MIN_SAMPLES_FOR_EVOLUTION } && missedRows.size < MIN_MISSED_FOR_EVOLUTION) return

        val evolved = evolveRules(
            rules = current,
            results = backtestResults,
            recentSignalCount = recentSignalCount,
            recentMissedCount = missedRows.size,
            postMortemTags = postMortem.map { it.missedReason } + missedRows.map { it.postMortemResult },
            currentRegime = currentRegime,
        )
        if (evolved != current) {
            val changeLog = generateChangeLog(
                old = current,
                new = evolved,
                results = backtestResults,
                recentSignalCount = recentSignalCount,
                recentMissedCount = missedRows.size,
                postMortemTags = postMortem.map { it.missedReason }.take(8),
                now = now,
            )
            rulesRepository.persistLocal(evolved)
            db.evolutionLogDao().insert(
                EvolutionLogEntity(
                    changedAt = now,
                    changeLog = changeLog,
                    rulesJson = evolved.toJson().toString(2),
                ),
            )
            lastEvolvedAt = now
        }
    }

    private fun evolveRules(
        rules: StrategyRules,
        results: List<BacktestResult>,
        recentSignalCount: Int,
        recentMissedCount: Int,
        postMortemTags: List<String>,
        currentRegime: BtcRegime = BtcRegime.NEUTRAL,
    ): StrategyRules {
        var updated = rules
        results.forEach { result ->
            if (result.sampleSize < MIN_SAMPLES_FOR_EVOLUTION) return@forEach
            if (result.winRate < 0.35 && result.sampleSize >= 20) {
                updated = updated.withStrategyActiveFlag(result.strategyType, false)
            }
            if (result.winRate > 0.65 && result.expectancy > 0.0) {
                updated = updated.withStrategyActiveFlag(result.strategyType, true)
            }
            if (result.scoreCorrelation < 0.20 && result.winRate < 0.50) {
                updated = updated.copy(minimumScore = (updated.minimumScore * 0.90).coerceAtLeast(50.0))
            }
            if (result.scoreCorrelation > 0.50 && result.winRate > 0.60) {
                updated = updated.copy(minimumScore = (updated.minimumScore * 1.10).coerceAtMost(85.0))
            }
            if (result.stopHitRate > 0.50) {
                val prePump = updated.prePumpRotation
                updated = updated.copy(
                    prePumpRotation = prePump.copy(
                        minHighProximityMultiplier = (prePump.minHighProximityMultiplier * 0.98).coerceAtLeast(0.90),
                    ),
                )
            }
            if (result.bearRegimeSampleSize >= 5) {
                val warnKey = "bear_warn_${result.strategyType}"
                if (result.bearRegimeLossRate > 0.65) {
                    val warnCount = (updated.customFlags[warnKey]?.toIntOrNull() ?: 0) + 1
                    updated = updated.withCustomFlag(warnKey, warnCount.toString())
                    if (warnCount >= 3) {
                        updated = updated.withStrategyActiveFlag(result.strategyType, false)
                    }
                } else if (result.bearRegimeLossRate < 0.35) {
                    val warnCount = ((updated.customFlags[warnKey]?.toIntOrNull() ?: 0) - 1).coerceAtLeast(0)
                    updated = updated.withCustomFlag(warnKey, warnCount.toString())
                    if (warnCount <= 0) {
                        updated = updated.withStrategyActiveFlag(result.strategyType, true)
                    }
                }
            }
        }
        if (recentSignalCount < 3) {
            val prePump = updated.prePumpRotation
            updated = updated.copy(
                minimumScore = (updated.minimumScore - 3.0).coerceAtLeast(50.0),
                prePumpRotation = prePump.copy(
                    minVolumeAcceleration = (prePump.minVolumeAcceleration * 0.95).coerceAtLeast(1.10),
                    maxTradeValueRank = (prePump.maxTradeValueRank + 5).coerceAtMost(75),
                ),
            )
        }
        if (recentMissedCount >= MIN_MISSED_FOR_EVOLUTION) {
            val prePump = updated.prePumpRotation
            val candidates = updated.candidateSelection
            val liquidityMiss = postMortemTags.any { it.contains("TRADE_VALUE") || it.contains("LIQUIDITY") }
            val scoreMiss = postMortemTags.any { it.contains("SCORE_THRESHOLD") }
            val volumeMiss = postMortemTags.any { it.contains("VOLUME_ACCELERATION") }
            val changeRankMiss = postMortemTags.any { it.contains("CHANGE_RANK") }
            updated = updated.copy(
                minimumScore = (updated.minimumScore - if (scoreMiss) 2.5 else 1.0).coerceAtLeast(55.0),
                maxResults = (updated.maxResults + 1).coerceAtMost(8),
                candidateSelection = candidates.copy(
                    maxCandleTargets = (candidates.maxCandleTargets + 10).coerceAtMost(80),
                    topTradeValueCount = (candidates.topTradeValueCount + if (liquidityMiss) 15 else 5).coerceAtMost(100),
                    topChangeRateCount = (candidates.topChangeRateCount + if (changeRankMiss) 10 else 5).coerceAtMost(80),
                    volumeBuildupCount = (candidates.volumeBuildupCount + 5).coerceAtMost(80),
                    quietAccumulationCount = (candidates.quietAccumulationCount + 5).coerceAtMost(80),
                    medianTradeValueMultiplier = (candidates.medianTradeValueMultiplier * if (liquidityMiss) 0.90 else 0.96).coerceAtLeast(0.75),
                    maxQuietAbsChangeRatePct = (candidates.maxQuietAbsChangeRatePct + 0.3).coerceAtMost(5.0),
                ),
                prePumpRotation = prePump.copy(
                    maxTradeValueRank = (prePump.maxTradeValueRank + if (liquidityMiss) 12 else 6).coerceAtMost(80),
                    maxChangeRank = (prePump.maxChangeRank + if (changeRankMiss) 12 else 6).coerceAtMost(80),
                    minRotation30mPct = (prePump.minRotation30mPct * if (scoreMiss) 0.82 else 0.92).coerceAtLeast(0.10),
                    minVolumeAcceleration = (prePump.minVolumeAcceleration * if (volumeMiss) 0.88 else 0.94).coerceAtLeast(1.05),
                    minFiveMinuteVolumeRatio = (prePump.minFiveMinuteVolumeRatio * if (volumeMiss) 0.88 else 0.94).coerceAtLeast(1.05),
                    minFifteenMinuteVolumeRatio = (prePump.minFifteenMinuteVolumeRatio * if (volumeMiss) 0.90 else 0.96).coerceAtLeast(1.03),
                    maxRangePct = (prePump.maxRangePct + 0.6).coerceAtMost(9.0),
                    minRangePosition = (prePump.minRangePosition - 0.04).coerceAtLeast(0.30),
                    minHighProximityMultiplier = (prePump.minHighProximityMultiplier - 0.004).coerceAtLeast(0.960),
                    minCloseStairCount = (prePump.minCloseStairCount - 1).coerceAtLeast(1),
                ),
            )
        }
        if (currentRegime.isRisky()) {
            val delta = BtcRegimeDetector.minimumScoreDelta(currentRegime) * 0.5
            updated = updated.copy(minimumScore = (updated.minimumScore + delta).coerceAtMost(88.0))
        }
        return updated
    }

    private fun generateChangeLog(
        old: StrategyRules,
        new: StrategyRules,
        results: List<BacktestResult>,
        recentSignalCount: Int,
        recentMissedCount: Int,
        postMortemTags: List<String>,
        now: Long,
    ): String {
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(now))
        val lines = mutableListOf<String>()
        lines += "=== 자가진화 로그 $time ==="
        lines += "최근 24시간 신호 수=$recentSignalCount"
        lines += "최근 24시간 누락 신호 수=$recentMissedCount"
        if (postMortemTags.isNotEmpty()) lines += "PostMortem tags=${postMortemTags.joinToString()}"
        results.filter { it.sampleSize >= MIN_SAMPLES_FOR_EVOLUTION }.forEach { r ->
            lines += "${r.strategyType}: winRate=${String.format(java.util.Locale.US, "%.1f", r.winRate * 100.0)}% sample=${r.sampleSize} expectancy=${String.format(java.util.Locale.US, "%.2f", r.expectancy)}% bearLoss=${String.format(java.util.Locale.US, "%.1f", r.bearRegimeLossRate * 100.0)}% bearN=${r.bearRegimeSampleSize}"
        }
        if (old.minimumScore != new.minimumScore) {
            lines += "minimumScore: ${String.format(java.util.Locale.US, "%.1f", old.minimumScore)} -> ${String.format(java.util.Locale.US, "%.1f", new.minimumScore)}"
        }
        if (old.candidateSelection != new.candidateSelection) {
            lines += "candidateSelection adjusted from missed-signal evidence"
        }
        if (old.prePumpRotation != new.prePumpRotation) {
            lines += "prePumpRotation conditions adjusted"
        }
        if (old.customFlags != new.customFlags) {
            lines += "customFlags adjusted=${new.customFlags}"
        }
        return lines.joinToString("\n")
    }

    private fun StrategyRules.withStrategyActiveFlag(typeName: String, enabled: Boolean): StrategyRules = when (typeName) {
        "PRE_PUMP_ROTATION" -> copy(prePumpRotation = prePumpRotation.copy(enabled = enabled))
        "TREND_PULLBACK" -> copy(trendPullback = trendPullback.copy(enabled = enabled))
        "BEAR_DECOUPLING_BOUNCE" -> copy(bearDecouplingBounce = bearDecouplingBounce.copy(enabled = enabled))
        "COMPRESSION_BREAKOUT" -> copy(compressionBreakout = compressionBreakout.copy(enabled = enabled))
        "SWEEP_RECLAIM" -> copy(sweepReclaim = sweepReclaim.copy(enabled = enabled))
        else -> this
    }

    companion object {
        const val MIN_SAMPLES_FOR_EVOLUTION = 15
        private const val MIN_MISSED_FOR_EVOLUTION = 2
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        const val EVOLUTION_INTERVAL_MS = 6L * 60L * 60L * 1000L
    }
}
