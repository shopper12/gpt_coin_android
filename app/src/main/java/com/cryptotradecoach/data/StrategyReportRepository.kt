package com.cryptotradecoach.data

import android.content.Context
import android.util.Log
import com.cryptotradecoach.data.local.AppDatabase
import com.cryptotradecoach.data.local.StrategyEventType
import com.cryptotradecoach.data.local.StrategyScanLogEntity
import java.io.File
import kotlin.math.max
import kotlin.math.min

class StrategyReportRepository private constructor(
    private val context: Context,
    database: AppDatabase,
    private val settingsStore: GitHubSettingsStore,
    private val gitHubSyncClient: GitHubSyncClient,
) {
    private val dao = database.signalHistoryDao()
    private val latestReportFile: File
        get() = File(context.filesDir, "reports/latest.json")

    suspend fun generateLatestReport(rules: StrategyRules, now: Long = System.currentTimeMillis()): StrategyReport {
        val logs = dao.getStrategyScanLogsSince(now - REPORT_WINDOW_MS)
        val history = dao.getAllHistory(limit = 1000)
        val failed = logs.filter { it.selectedOrMissed == "MISSED" || it.strategyStatus != StrategyStatus.ACTIVE }
        val summaries = logs
            .groupBy { inferStrategyType(it) }
            .map { (strategyType, rows) ->
                val failedCount = rows.count { it.selectedOrMissed == "MISSED" || it.strategyStatus != StrategyStatus.ACTIVE }
                val targetHits = history.count { it.eventType in TARGET_EVENTS }
                val stopHits = history.count { it.eventType in STOP_EVENTS }
                StrategyPerformanceSummary(
                    strategyType = strategyType,
                    totalSignals = rows.size,
                    failedSignalCount = failedCount,
                    avgReturn15m = rows.averageReturnMinutes(15),
                    avgReturn30m = rows.averageReturnMinutes(30),
                    targetHitRate = if (rows.isEmpty()) 0.0 else targetHits.toDouble() / rows.size,
                    stopHitRate = if (rows.isEmpty()) 0.0 else stopHits.toDouble() / rows.size,
                    avgMfe = rows.averageMfe(),
                    avgMae = rows.averageMae(),
                )
            }
            .sortedBy { it.strategyType }
        val summaryByType = summaries.associateBy { it.strategyType }
        val failedSignals = failed.take(100).map { log ->
            val strategyType = inferStrategyType(log)
            val summary = summaryByType[strategyType]
            FailedSignalReport(
                market = log.market,
                strategyType = strategyType,
                timestamp = log.timestamp,
                score = log.score,
                missedReason = log.missedReason ?: log.strategyStatus.name,
                avgReturn15m = summary?.avgReturn15m ?: 0.0,
                avgReturn30m = summary?.avgReturn30m ?: 0.0,
                targetHitRate = summary?.targetHitRate ?: 0.0,
                stopHitRate = summary?.stopHitRate ?: 0.0,
                avgMfe = summary?.avgMfe ?: 0.0,
                avgMae = summary?.avgMae ?: 0.0,
            )
        }
        return StrategyReport(
            generatedAt = now,
            rulesVersion = rules.version,
            summaries = summaries,
            failedSignals = failedSignals,
        ).also { persistLatest(it) }
    }

    fun uploadLatestReport(): Boolean {
        val settings = settingsStore.load()
        if (!settings.isConfigured || settings.token.isBlank() || !latestReportFile.exists()) return false
        return runCatching {
            gitHubSyncClient.uploadText(
                settings = settings,
                path = settings.reportPath,
                content = latestReportFile.readText(),
                message = "Update strategy report",
            )
        }.onFailure {
            Log.w(TAG, "Report upload failed.")
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

    private fun inferStrategyType(log: StrategyScanLogEntity): String {
        return log.strategyType.name
    }

    private fun List<StrategyScanLogEntity>.averageReturnMinutes(minutes: Int): Double {
        if (isEmpty()) return 0.0
        val weight = min(1.0, max(0.0, minutes / 30.0))
        return map { log -> ((log.currentPrice - log.entryPrice) / log.entryPrice.coerceAtLeast(1.0)) * 100.0 * weight }.average()
    }

    private fun List<StrategyScanLogEntity>.averageMfe(): Double {
        if (isEmpty()) return 0.0
        return map { log -> ((max(log.target1, log.target2) - log.entryPrice) / log.entryPrice.coerceAtLeast(1.0)) * 100.0 }.average()
    }

    private fun List<StrategyScanLogEntity>.averageMae(): Double {
        if (isEmpty()) return 0.0
        return map { log -> ((log.stopLossPrice - log.entryPrice) / log.entryPrice.coerceAtLeast(1.0)) * 100.0 }.average()
    }

    companion object {
        private const val TAG = "StrategyReportRepo"
        private const val REPORT_WINDOW_MS = 24 * 60 * 60 * 1000L
        private val TARGET_EVENTS = setOf(StrategyEventType.TARGET1_HIT, StrategyEventType.HIT_TARGET)
        private val STOP_EVENTS = setOf(StrategyEventType.TRAILING_STOP_HIT, StrategyEventType.STOPPED_OUT)

        @Volatile
        private var instance: StrategyReportRepository? = null

        fun getInstance(context: Context): StrategyReportRepository {
            return instance ?: synchronized(this) {
                instance ?: StrategyReportRepository(
                    context.applicationContext,
                    AppDatabase.getInstance(context),
                    GitHubSettingsStore.getInstance(context),
                    GitHubSyncClient(),
                ).also { instance = it }
            }
        }
    }
}
