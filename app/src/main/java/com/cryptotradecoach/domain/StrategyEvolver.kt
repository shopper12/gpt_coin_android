package com.cryptotradecoach.domain

import com.cryptotradecoach.data.StrategyRules
import com.cryptotradecoach.data.StrategyRulesRepository
import com.cryptotradecoach.data.local.AppDatabase
import com.cryptotradecoach.data.local.EvolutionLogEntity

// Applies conservative rule adjustments from backtest evidence and records every change.
class StrategyEvolver(
    private val rulesRepository: StrategyRulesRepository,
    private val db: AppDatabase,
) {
    private var lastEvolvedAt: Long = 0L

    suspend fun maybeEvolve(backtestResults: List<BacktestResult>, now: Long = System.currentTimeMillis()) {
        if (now - lastEvolvedAt < EVOLUTION_INTERVAL_MS) return
        if (backtestResults.none { it.sampleSize >= MIN_SAMPLES_FOR_EVOLUTION }) return
        val current = rulesRepository.loadLastKnownGood()
        val recentSignalCount = db.signalHistoryDao().countPerformanceCreatedAfter(now - 24L * 60L * 60L * 1000L)
        val evolved = evolveRules(current, backtestResults, recentSignalCount)
        if (evolved != current) {
            val changeLog = generateChangeLog(current, evolved, backtestResults, recentSignalCount, now)
            rulesRepository.persistLocal(evolved)
            db.signalHistoryDao().insertEvolutionLog(
                EvolutionLogEntity(
                    changedAt = now,
                    changeLog = changeLog,
                    rulesJson = evolved.toJson().toString(2),
                ),
            )
            lastEvolvedAt = now
        }
    }

    private fun evolveRules(rules: StrategyRules, results: List<BacktestResult>, recentSignalCount: Int): StrategyRules {
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
        }
        if (recentSignalCount < 3) {
            val prePump = updated.prePumpRotation
            updated = updated.copy(
                minimumScore = (updated.minimumScore - 3.0).coerceAtLeast(50.0),
                prePumpRotation = prePump.copy(
                    minVolumeAcceleration = (prePump.minVolumeAcceleration * 0.95).coerceAtLeast(1.10),
                    maxTradeValueRank = (prePump.maxTradeValueRank + 5).coerceAtMost(60),
                ),
            )
        }
        return updated
    }

    private fun generateChangeLog(
        old: StrategyRules,
        new: StrategyRules,
        results: List<BacktestResult>,
        recentSignalCount: Int,
        now: Long,
    ): String {
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(now))
        val lines = mutableListOf<String>()
        lines += "=== 자가진화 로그 $time ==="
        lines += "최근 24시간 신호 수=$recentSignalCount"
        results.filter { it.sampleSize >= MIN_SAMPLES_FOR_EVOLUTION }.forEach { r ->
            lines += "${r.strategyType}: winRate=${String.format(java.util.Locale.US, "%.1f", r.winRate * 100.0)}% sample=${r.sampleSize} expectancy=${String.format(java.util.Locale.US, "%.2f", r.expectancy)}%"
        }
        if (old.minimumScore != new.minimumScore) {
            lines += "minimumScore: ${String.format(java.util.Locale.US, "%.1f", old.minimumScore)} -> ${String.format(java.util.Locale.US, "%.1f", new.minimumScore)}"
        }
        if (old.prePumpRotation != new.prePumpRotation) {
            lines += "prePumpRotation conditions adjusted"
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
        const val EVOLUTION_INTERVAL_MS = 6L * 60L * 60L * 1000L
    }
}
