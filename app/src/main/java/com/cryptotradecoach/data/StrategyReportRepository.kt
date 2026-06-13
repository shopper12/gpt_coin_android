package com.cryptotradecoach.data

import android.content.Context
import android.util.Log
import com.cryptotradecoach.data.local.AppDatabase
import com.cryptotradecoach.data.local.StrategyPerformanceEntity
import com.cryptotradecoach.data.local.StrategyScanLogEntity
import com.cryptotradecoach.service.ScannerStateStore
import java.io.File
import kotlin.math.abs

class StrategyReportRepository private constructor(
    private val context: Context,
    database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    private val gitHubSyncClient: GitHubSyncClient,
) {
    private val dao = database.signalHistoryDao()
    private val latestReportFile: File
        get() = File(context.filesDir, "reports/latest.json")

    suspend fun generateLatestReport(rules: StrategyRules, now: Long = System.currentTimeMillis()): StrategyReport {
        val performances = dao.getPerformanceSince(now - REPORT_WINDOW_MS)
        val logs = dao.getStrategyScanLogsSince(now - REPORT_WINDOW_MS)
        val summaries = performances
            .groupBy { it.strategyType.name }
            .map { (strategyType, rows) -> rows.toSummary(strategyType) }
            .sortedBy { it.strategyType }
        val summaryByType = summaries.associateBy { it.strategyType }
        val failedSignals = logs
            .filter { it.selectedOrMissed == "MISSED" || it.strategyStatus != StrategyStatus.ACTIVE }
            .take(300)
            .map { log -> log.toFailedSignalReport(summaryByType) }
        return StrategyReport(
            generatedAt = now,
            rulesVersion = rules.version,
            summaries = summaries,
            failedSignals = failedSignals,
        ).also { persistLatest(it) }
    }

    fun uploadLatestReport(): Boolean {
        val settings = settingsRepository.load().normalized()
        if (settings.token.isBlank()) {
            ScannerStateStore.setLastError("GitHub token is missing")
            return false
        }
        if (!settings.isConfigured || !latestReportFile.exists()) return false
        return runCatching {
            gitHubSyncClient.uploadText(
                settings = settings,
                path = settings.reportPath,
                content = latestReportFile.readText(),
                message = "Update strategy report",
            )
        }.onFailure { error ->
            val message = if (error is GitHubSyncException) {
                "Report upload failed at ${error.syncPoint}: HTTP ${error.statusCode}; endpoint=${error.endpoint}; branch=${error.branch}; path=${error.path}"
            } else {
                "Report upload failed at ${settings.reportPath}: ${error::class.java.simpleName}"
            }
            ScannerStateStore.setLastError(message)
            Log.w(TAG, message)
        }.getOrDefault(false)
    }

    private fun persistLatest(report: StrategyReport) {
        runCatching {
            latestReportFile.parentFile?.mkdirs()
            latestReportFile.writeText(report.toJson().toString(2))
        }.onFailure {
            Log.w(TAG, "Failed to write latest strategy report.")
        }
    }

    private fun List<StrategyPerformanceEntity>.toSummary(strategyType: String): StrategyPerformanceSummary {
        val completed = filter { it.isComplete }
        val target1Hits = count { it.target1Hit }
        val target2Hits = count { it.target2Hit }
        val stopHits = count { it.stopHit }
        val avgMfe = averageOf { it.mfePct }
        val avgMae = averageOf { it.maePct }
        return StrategyPerformanceSummary(
            strategyType = strategyType,
            totalSignals = size,
            completedSignals = completed.size,
            failedSignalCount = stopHits,
            avgReturn5m = averageNullable { it.return5m },
            avgReturn15m = averageNullable { it.return15m },
            avgReturn30m = averageNullable { it.return30m },
            avgReturn60m = averageNullable { it.return60m },
            target1HitRate = rate(target1Hits),
            target2HitRate = rate(target2Hits),
            targetHitRate = rate(target1Hits + target2Hits),
            stopHitRate = rate(stopHits),
            avgMfe = avgMfe,
            avgMae = avgMae,
            mfeMaeRatio = if (avgMae != 0.0) avgMfe / abs(avgMae) else 0.0,
        )
    }

    private fun StrategyScanLogEntity.toFailedSignalReport(
        summaryByType: Map<String, StrategyPerformanceSummary>,
    ): FailedSignalReport {
        val strategyType = strategyType.name
        val summary = summaryByType[strategyType]
        return FailedSignalReport(
            market = market,
            strategyType = strategyType,
            timestamp = timestamp,
            score = score,
            missedReason = missedReason ?: strategyStatus.name,
            avgReturn5m = summary?.avgReturn5m ?: 0.0,
            avgReturn15m = summary?.avgReturn15m ?: 0.0,
            avgReturn30m = summary?.avgReturn30m ?: 0.0,
            avgReturn60m = summary?.avgReturn60m ?: 0.0,
            targetHitRate = summary?.targetHitRate ?: 0.0,
            stopHitRate = summary?.stopHitRate ?: 0.0,
            avgMfe = summary?.avgMfe ?: 0.0,
            avgMae = summary?.avgMae ?: 0.0,
        )
    }

    private fun List<StrategyPerformanceEntity>.averageNullable(selector: (StrategyPerformanceEntity) -> Double?): Double {
        val values = mapNotNull(selector)
        return if (values.isEmpty()) 0.0 else values.average()
    }

    private fun List<StrategyPerformanceEntity>.averageOf(selector: (StrategyPerformanceEntity) -> Double): Double {
        return if (isEmpty()) 0.0 else map(selector).average()
    }

    private fun List<StrategyPerformanceEntity>.rate(count: Int): Double {
        return if (isEmpty()) 0.0 else count.toDouble() / size
    }

    companion object {
        private const val TAG = "StrategyReportRepo"
        private const val REPORT_WINDOW_MS = 14L * 24L * 60L * 60L * 1000L

        @Volatile
        private var instance: StrategyReportRepository? = null

        fun getInstance(context: Context): StrategyReportRepository {
            return instance ?: synchronized(this) {
                instance ?: StrategyReportRepository(
                    context.applicationContext,
                    AppDatabase.getInstance(context),
                    SettingsRepository.getInstance(context),
                    GitHubSyncClient(),
                ).also { instance = it }
            }
        }
    }
}
