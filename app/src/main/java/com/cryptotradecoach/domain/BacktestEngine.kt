package com.cryptotradecoach.domain

import com.cryptotradecoach.data.local.AppDatabase
import com.cryptotradecoach.data.local.PerformanceCheckpointEntity
import com.cryptotradecoach.data.local.StrategyPerformanceEntity
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

// Analyzes completed recommendation outcomes and ranks each strategy by real realized performance.
data class BacktestResult(
    val strategyType: String,
    val totalSignals: Int,
    val completedSignals: Int,
    val winRate: Double,
    val avgReturn60m: Double,
    val avgReturn240m: Double,
    val avgMfe: Double,
    val avgMae: Double,
    val stopHitRate: Double,
    val target1HitRate: Double,
    val target2HitRate: Double,
    val expectancy: Double,
    val profitFactor: Double,
    val scoreCorrelation: Double,
    val sampleSize: Int,
    val lastUpdated: Long,
    val bestScoreRange: String,
    val bestTimeOfDay: String,
    val avgHoldMinutes: Double,
    val bearRegimeLossRate: Double = 0.0,
    val bearRegimeSampleSize: Int = 0,
)

class BacktestEngine(private val db: AppDatabase) {
    suspend fun runAll(now: Long = System.currentTimeMillis()): List<BacktestResult> {
        val rows = db.signalHistoryDao()
            .getPerformanceSince(now - ANALYSIS_WINDOW_MS, ANALYSIS_LIMIT)
            .filter { it.isComplete }
        val checkpoints = db.performanceCheckpointDao()
            .getSince(now - ANALYSIS_WINDOW_MS, ANALYSIS_LIMIT * 6)
            .groupBy { it.strategyId }
        return rows
            .groupBy { it.strategyType.name }
            .map { (type, items) -> analyze(type, items, checkpoints) }
            .sortedByDescending { it.expectancy }
    }

    private fun analyze(
        type: String,
        rows: List<StrategyPerformanceEntity>,
        checkpointsByStrategy: Map<String, List<PerformanceCheckpointEntity>>,
    ): BacktestResult {
        if (rows.isEmpty()) return emptyResult(type)
        val returnsForDecision = rows.map { row -> decisionReturn(row, checkpointsByStrategy[row.strategyId].orEmpty()) }
        val wins = returnsForDecision.filter { it > 0.0 }
        val losses = returnsForDecision.filter { it <= 0.0 }
        val winRate = wins.size.toDouble() / rows.size
        val avgWin = wins.averageOrZero()
        val avgLoss = losses.map { abs(it) }.averageOrZero()
        val totalWin = wins.sum()
        val totalLoss = losses.sumOf { abs(it) }
        val profitFactor = when {
            totalLoss <= 0.0 && totalWin > 0.0 -> 99.0
            totalLoss <= 0.0 -> 0.0
            totalWin <= 0.0 -> 0.0
            else -> totalWin / totalLoss
        }
        val scoreRanges = rows.zip(returnsForDecision).groupBy { (row, _) -> scoreRange(row.score) }
        val bestRange = scoreRanges.maxByOrNull { (_, values) ->
            if (values.isEmpty()) 0.0 else values.count { (_, ret) -> ret > 0.0 }.toDouble() / values.size
        }?.key ?: "N/A"
        val scores = rows.map { it.score }
        val corr = if (returnsForDecision.size == rows.size) pearsonCorr(scores, returnsForDecision) else 0.0
        val avgHoldMins = rows.map { row -> holdMinutes(row, checkpointsByStrategy[row.strategyId].orEmpty()) }.averageOrZero()
        val bearPairs = rows.zip(returnsForDecision).filter { (row, _) -> row.btcRegimeAtSignal == "BEAR" || row.btcRegimeAtSignal == "CRASH" }
        val bearLossRate = if (bearPairs.isEmpty()) 0.0 else bearPairs.count { (_, ret) -> ret <= 0.0 }.toDouble() / bearPairs.size
        return BacktestResult(
            strategyType = type,
            totalSignals = rows.size,
            completedSignals = rows.count { it.isComplete },
            winRate = winRate,
            avgReturn60m = rows.map { row -> checkpointReturn(row, checkpointsByStrategy[row.strategyId].orEmpty(), 60) ?: row.return60m ?: 0.0 }.averageOrZero(),
            avgReturn240m = rows.map { row -> checkpointReturn(row, checkpointsByStrategy[row.strategyId].orEmpty(), 240) ?: row.return60m ?: 0.0 }.averageOrZero(),
            avgMfe = rows.map { it.mfePct }.averageOrZero(),
            avgMae = rows.map { it.maePct }.averageOrZero(),
            stopHitRate = rows.count { it.stopHit }.toDouble() / rows.size,
            target1HitRate = rows.count { it.target1Hit }.toDouble() / rows.size,
            target2HitRate = rows.count { it.target2Hit }.toDouble() / rows.size,
            expectancy = winRate * avgWin - (1.0 - winRate) * avgLoss,
            profitFactor = profitFactor,
            scoreCorrelation = corr,
            sampleSize = rows.size,
            lastUpdated = System.currentTimeMillis(),
            bestScoreRange = bestRange,
            bestTimeOfDay = bestHourBucket(rows, returnsForDecision),
            avgHoldMinutes = avgHoldMins,
            bearRegimeLossRate = bearLossRate,
            bearRegimeSampleSize = bearPairs.size,
        )
    }

    private fun decisionReturn(row: StrategyPerformanceEntity, checkpoints: List<PerformanceCheckpointEntity>): Double {
        if (row.stopHit) {
            return checkpoints.firstOrNull { it.stopHit }?.returnPct
                ?: row.return60m
                ?: percentChange(row.entryPrice, row.stopLoss).takeIf { it != 0.0 }
                ?: -row.riskPct.coerceAtLeast(DEFAULT_STOP_FALLBACK_PCT)
        }
        if (row.target2Hit) {
            return checkpoints.firstOrNull { it.target2Hit }?.returnPct
                ?: row.return60m
                ?: percentChange(row.entryPrice, row.target2).takeIf { it != 0.0 }
                ?: row.riskPct * 2.4
        }
        if (row.target1Hit) {
            return checkpoints.firstOrNull { it.target1Hit }?.returnPct
                ?: row.return60m
                ?: percentChange(row.entryPrice, row.target1).takeIf { it != 0.0 }
                ?: row.riskPct * 1.5
        }
        return checkpointReturn(row, checkpoints, 240)
            ?: checkpointReturn(row, checkpoints, 120)
            ?: row.return60m
            ?: row.return30m
            ?: row.return15m
            ?: row.return5m
            ?: 0.0
    }

    private fun checkpointReturn(row: StrategyPerformanceEntity, checkpoints: List<PerformanceCheckpointEntity>, minutes: Int): Double? {
        return checkpoints.firstOrNull { it.elapsedMinutes == minutes }?.returnPct
            ?: when (minutes) {
                60 -> row.return60m
                30 -> row.return30m
                15 -> row.return15m
                5 -> row.return5m
                else -> null
            }
    }

    private fun holdMinutes(row: StrategyPerformanceEntity, checkpoints: List<PerformanceCheckpointEntity>): Double {
        checkpoints.firstOrNull { it.target2Hit || it.stopHit }?.let { return it.elapsedMinutes.toDouble() }
        return when {
            row.target2Hit -> 60.0
            row.stopHit -> 60.0
            checkpointReturn(row, checkpoints, 240) != null -> 240.0
            checkpointReturn(row, checkpoints, 120) != null -> 120.0
            row.return60m != null -> 60.0
            row.return30m != null -> 30.0
            row.return15m != null -> 15.0
            row.return5m != null -> 5.0
            else -> 0.0
        }
    }

    private fun bestHourBucket(rows: List<StrategyPerformanceEntity>, returns: List<Double>): String {
        return rows.zip(returns)
            .groupBy { (row, _) -> java.text.SimpleDateFormat("HH", java.util.Locale.US).format(java.util.Date(row.createdAt)) }
            .maxByOrNull { (_, values) -> values.map { (_, ret) -> ret }.averageOrZero() }
            ?.key
            ?.let { "${it}시" }
            ?: "N/A"
    }

    private fun pearsonCorr(x: List<Double>, y: List<Double>): Double {
        if (x.size != y.size || x.isEmpty()) return 0.0
        val mx = x.average()
        val my = y.average()
        val numerator = x.zip(y).sumOf { (a, b) -> (a - mx) * (b - my) }
        val dx = sqrt(x.sumOf { (it - mx).pow(2) })
        val dy = sqrt(y.sumOf { (it - my).pow(2) })
        return if (dx * dy == 0.0) 0.0 else numerator / (dx * dy)
    }

    private fun scoreRange(score: Double): String = when {
        score >= 85.0 -> "85+"
        score >= 75.0 -> "75-85"
        score >= 65.0 -> "65-75"
        score >= 55.0 -> "55-65"
        else -> "<55"
    }

    private fun emptyResult(type: String) = BacktestResult(
        strategyType = type,
        totalSignals = 0,
        completedSignals = 0,
        winRate = 0.0,
        avgReturn60m = 0.0,
        avgReturn240m = 0.0,
        avgMfe = 0.0,
        avgMae = 0.0,
        stopHitRate = 0.0,
        target1HitRate = 0.0,
        target2HitRate = 0.0,
        expectancy = 0.0,
        profitFactor = 0.0,
        scoreCorrelation = 0.0,
        sampleSize = 0,
        lastUpdated = System.currentTimeMillis(),
        bestScoreRange = "N/A",
        bestTimeOfDay = "N/A",
        avgHoldMinutes = 0.0,
        bearRegimeLossRate = 0.0,
        bearRegimeSampleSize = 0,
    )

    private fun percentChange(from: Double, to: Double): Double {
        if (from <= 0.0 || to <= 0.0) return 0.0
        return ((to - from) / from) * 100.0
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    companion object {
        private const val ANALYSIS_WINDOW_MS = 14L * 24L * 60L * 60L * 1000L
        private const val ANALYSIS_LIMIT = 5000
        private const val DEFAULT_STOP_FALLBACK_PCT = 1.5
    }
}
