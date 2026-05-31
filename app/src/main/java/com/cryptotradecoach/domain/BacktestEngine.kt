package com.cryptotradecoach.domain

import com.cryptotradecoach.data.local.AppDatabase
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
)

class BacktestEngine(private val db: AppDatabase) {
    suspend fun runAll(now: Long = System.currentTimeMillis()): List<BacktestResult> {
        val rows = db.signalHistoryDao()
            .getPerformanceSince(now - ANALYSIS_WINDOW_MS, ANALYSIS_LIMIT)
            .filter { it.isComplete }
        return rows
            .groupBy { it.strategyType.name }
            .map { (type, items) -> analyze(type, items) }
            .sortedByDescending { it.expectancy }
    }

    private fun analyze(type: String, rows: List<StrategyPerformanceEntity>): BacktestResult {
        if (rows.isEmpty()) return emptyResult(type)
        val wins = rows.filter { (it.return60m ?: 0.0) > 0.0 }
        val losses = rows.filter { (it.return60m ?: 0.0) <= 0.0 }
        val winRate = wins.size.toDouble() / rows.size
        val avgWin = wins.mapNotNull { it.return60m }.averageOrZero()
        val avgLoss = losses.mapNotNull { it.return60m }.map { abs(it) }.averageOrZero()
        val scoreRanges = rows.groupBy { scoreRange(it.score) }
        val bestRange = scoreRanges.maxByOrNull { (_, values) ->
            if (values.isEmpty()) 0.0 else values.count { (it.return60m ?: 0.0) > 0.0 }.toDouble() / values.size
        }?.key ?: "N/A"
        val scores = rows.map { it.score }
        val returns = rows.mapNotNull { it.return60m }
        val corr = if (returns.size == rows.size) pearsonCorr(scores, returns) else 0.0
        val avgHoldMins = rows.mapNotNull { row ->
            when {
                (row.target2Hit) -> 60.0
                (row.target1Hit) -> 30.0
                (row.return60m ?: 0.0) > (row.return30m ?: Double.NEGATIVE_INFINITY) -> 60.0
                (row.return30m ?: 0.0) > (row.return15m ?: Double.NEGATIVE_INFINITY) -> 30.0
                (row.return15m ?: 0.0) > (row.return5m ?: Double.NEGATIVE_INFINITY) -> 15.0
                (row.return5m ?: 0.0) > 0.0 -> 5.0
                else -> null
            }
        }.averageOrZero()
        return BacktestResult(
            strategyType = type,
            totalSignals = rows.size,
            completedSignals = rows.count { it.isComplete },
            winRate = winRate,
            avgReturn60m = rows.mapNotNull { it.return60m }.averageOrZero(),
            avgReturn240m = rows.mapNotNull { it.return60m }.averageOrZero(),
            avgMfe = rows.map { it.mfePct }.averageOrZero(),
            avgMae = rows.map { it.maePct }.averageOrZero(),
            stopHitRate = rows.count { it.stopHit }.toDouble() / rows.size,
            target1HitRate = rows.count { it.target1Hit }.toDouble() / rows.size,
            target2HitRate = rows.count { it.target2Hit }.toDouble() / rows.size,
            expectancy = winRate * avgWin - (1.0 - winRate) * avgLoss,
            profitFactor = if (avgLoss > 0.0 && winRate < 1.0) avgWin * winRate / (avgLoss * (1.0 - winRate)) else 99.0,
            scoreCorrelation = corr,
            sampleSize = rows.size,
            lastUpdated = System.currentTimeMillis(),
            bestScoreRange = bestRange,
            bestTimeOfDay = bestHourBucket(rows),
            avgHoldMinutes = avgHoldMins,
        )
    }

    private fun bestHourBucket(rows: List<StrategyPerformanceEntity>): String {
        return rows.groupBy { java.text.SimpleDateFormat("HH", java.util.Locale.US).format(java.util.Date(it.createdAt)) }
            .maxByOrNull { (_, values) -> values.mapNotNull { it.return60m }.averageOrZero() }
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
        profitFactor = 99.0,
        scoreCorrelation = 0.0,
        sampleSize = 0,
        lastUpdated = System.currentTimeMillis(),
        bestScoreRange = "N/A",
        bestTimeOfDay = "N/A",
        avgHoldMinutes = 0.0,
    )

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    companion object {
        private const val ANALYSIS_WINDOW_MS = 14L * 24L * 60L * 60L * 1000L
        private const val ANALYSIS_LIMIT = 5000
    }
}
