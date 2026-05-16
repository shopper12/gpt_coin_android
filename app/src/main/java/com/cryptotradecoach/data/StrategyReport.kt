package com.cryptotradecoach.data

import org.json.JSONArray
import org.json.JSONObject

data class StrategyReport(
    val generatedAt: Long,
    val rulesVersion: String,
    val summaries: List<StrategyPerformanceSummary>,
    val failedSignals: List<FailedSignalReport>,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("generatedAt", generatedAt)
            .put("rulesVersion", rulesVersion)
            .put("summaries", JSONArray(summaries.map { it.toJson() }))
            .put("failedSignals", JSONArray(failedSignals.map { it.toJson() }))
    }
}

data class StrategyPerformanceSummary(
    val strategyType: String,
    val totalSignals: Int,
    val completedSignals: Int,
    val failedSignalCount: Int,
    val avgReturn5m: Double,
    val avgReturn15m: Double,
    val avgReturn30m: Double,
    val avgReturn60m: Double,
    val target1HitRate: Double,
    val target2HitRate: Double,
    val targetHitRate: Double,
    val stopHitRate: Double,
    val avgMfe: Double,
    val avgMae: Double,
    val mfeMaeRatio: Double,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("strategyType", strategyType)
            .put("totalSignals", totalSignals)
            .put("completedSignals", completedSignals)
            .put("failedSignalCount", failedSignalCount)
            .put("avgReturn5m", avgReturn5m)
            .put("avgReturn15m", avgReturn15m)
            .put("avgReturn30m", avgReturn30m)
            .put("avgReturn60m", avgReturn60m)
            .put("target1HitRate", target1HitRate)
            .put("target2HitRate", target2HitRate)
            .put("targetHitRate", targetHitRate)
            .put("stopHitRate", stopHitRate)
            .put("avgMfe", avgMfe)
            .put("avgMae", avgMae)
            .put("mfeMaeRatio", mfeMaeRatio)
    }
}

data class FailedSignalReport(
    val market: String,
    val strategyType: String,
    val timestamp: Long,
    val score: Double,
    val missedReason: String,
    val avgReturn5m: Double,
    val avgReturn15m: Double,
    val avgReturn30m: Double,
    val avgReturn60m: Double,
    val targetHitRate: Double,
    val stopHitRate: Double,
    val avgMfe: Double,
    val avgMae: Double,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("market", market)
            .put("strategyType", strategyType)
            .put("timestamp", timestamp)
            .put("score", score)
            .put("missedReason", missedReason)
            .put("avgReturn5m", avgReturn5m)
            .put("avgReturn15m", avgReturn15m)
            .put("avgReturn30m", avgReturn30m)
            .put("avgReturn60m", avgReturn60m)
            .put("targetHitRate", targetHitRate)
            .put("stopHitRate", stopHitRate)
            .put("avgMfe", avgMfe)
            .put("avgMae", avgMae)
    }
}