package com.cryptotradecoach.domain

import com.cryptotradecoach.data.local.AppDatabase
import com.cryptotradecoach.data.local.MissedSignalEntity
import com.cryptotradecoach.data.local.MissedSignalReason

/**
 * Converts raw missed-pump rows into auditable diagnosis text and parameter suggestions.
 * This deliberately updates the existing missed_signals table instead of creating a parallel review table.
 */
class PostMortemEngine(
    private val db: AppDatabase,
) {
    suspend fun analyze(limit: Int = DEFAULT_LIMIT): List<PostMortemResult> {
        val dao = db.signalHistoryDao()
        val rows = dao.getUnanalyzedMissedSignals(limit)
        return rows.map { row ->
            val result = diagnose(row)
            dao.updateMissedSignalPostMortem(
                id = row.id,
                postMortemResult = result.diagnosis,
                suggestedParamAdjust = result.suggestedParamAdjust,
            )
            result
        }
    }

    private fun diagnose(row: MissedSignalEntity): PostMortemResult {
        val marketState = when {
            row.changeRate >= STRONG_PUMP_PCT -> "STRONG_MISSED_PUMP"
            row.changeRate >= NORMAL_PUMP_PCT -> "NORMAL_MISSED_PUMP"
            else -> "MINOR_MISSED_MOVE"
        }
        val reason = classifyReason(row)
        val param = suggestedParam(row, reason)
        val diagnosis = buildString {
            append(marketState)
            append(" | reason=").append(reason)
            append(" | market=").append(row.market)
            append(" | return=").append(row.changeRate.one()).append("%")
            append(" | rankChange=").append(row.rankByChangeRate)
            append(" | rankValue=").append(row.rankByTradeValue)
            append(" | before=").append(row.relatedRuleBefore.take(140))
        }
        return PostMortemResult(
            market = row.market,
            missedReason = reason,
            diagnosis = diagnosis,
            suggestedParamAdjust = param,
            returnPct = row.changeRate,
        )
    }

    private fun classifyReason(row: MissedSignalEntity): String {
        return when {
            row.missedReason == MissedSignalReason.TRADE_VALUE_FILTER_EXCLUDED -> "TRADE_VALUE_UNIVERSE_TOO_NARROW"
            row.missedReason == MissedSignalReason.SCORE_TOO_LOW -> "SCORE_THRESHOLD_TOO_HIGH"
            row.missedReason == MissedSignalReason.VOLUME_SCORE_TOO_LOW -> "VOLUME_ACCELERATION_TOO_STRICT"
            row.rankByTradeValue > 55 && row.rankByChangeRate <= 35 -> "EARLY_ROTATION_LIQUIDITY_FILTERED"
            row.rankByChangeRate > 60 && row.rankByTradeValue <= 45 -> "CHANGE_RANK_FILTER_TOO_NARROW"
            row.changeRate >= STRONG_PUMP_PCT -> "LATE_REACTION_TO_STRONG_PUMP"
            row.changeRate >= NORMAL_PUMP_PCT -> "MISSED_MODERATE_PUMP"
            else -> row.missedReason.ifBlank { MissedSignalReason.UNKNOWN }
        }
    }

    private fun suggestedParam(row: MissedSignalEntity, reason: String): String {
        return when (reason) {
            "TRADE_VALUE_UNIVERSE_TOO_NARROW", "EARLY_ROTATION_LIQUIDITY_FILTERED" ->
                "candidateSelection.topTradeValueCount +10; prePumpRotation.maxTradeValueRank +10; monitor liquidity false negatives"
            "SCORE_THRESHOLD_TOO_HIGH" ->
                "minimumScore -2; prePumpRotation.minRotation30mPct -10%; require volume confirmation to avoid noise"
            "VOLUME_ACCELERATION_TOO_STRICT" ->
                "prePumpRotation.minVolumeAcceleration *0.92; minFiveMinuteVolumeRatio *0.92"
            "CHANGE_RANK_FILTER_TOO_NARROW" ->
                "candidateSelection.topChangeRateCount +5; prePumpRotation.maxChangeRank +10"
            "LATE_REACTION_TO_STRONG_PUMP" ->
                if (row.rankByTradeValue <= 45) "promote strong liquidity movers earlier; maxChange30mPct +0.5 cautiously" else "expand candidate universe before loosening entry rules"
            else ->
                "collect more samples before changing rules"
        }
    }

    private fun Double.one(): String = String.format(java.util.Locale.US, "%.1f", this)

    companion object {
        private const val DEFAULT_LIMIT = 50
        private const val NORMAL_PUMP_PCT = 4.5
        private const val STRONG_PUMP_PCT = 7.0
    }
}

data class PostMortemResult(
    val market: String,
    val missedReason: String,
    val diagnosis: String,
    val suggestedParamAdjust: String,
    val returnPct: Double,
)
