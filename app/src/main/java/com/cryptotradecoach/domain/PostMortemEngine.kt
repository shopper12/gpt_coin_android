package com.cryptotradecoach.domain

import com.cryptotradecoach.data.local.AppDatabase
import com.cryptotradecoach.data.local.EvolutionLogEntity
import com.cryptotradecoach.data.local.MissedSignalEntity
import com.cryptotradecoach.data.local.MissedSignalReason

/**
 * Converts raw missed-pump rows into auditable diagnosis text and parameter suggestions.
 * Results are written back to missed_signals and summarized through evolution_log so the existing Performance UI exposes them.
 */
class PostMortemEngine(
    private val db: AppDatabase,
) {
    suspend fun analyze(limit: Int = DEFAULT_LIMIT): List<PostMortemResult> {
        val dao = db.signalHistoryDao()
        val rows = dao.getUnanalyzedMissedSignals(limit)
            .filter { !it.isTracking || it.peakReturnPct >= MIN_PEAK_RETURN_PCT }
            .sortedByDescending { maxOf(it.peakReturnPct, it.changeRate) }
        val results = rows.map { row ->
            val result = diagnose(row)
            dao.updateMissedSignalPostMortem(
                id = row.id,
                postMortemResult = result.diagnosis,
                suggestedParamAdjust = result.suggestedParamAdjust,
            )
            result
        }
        if (results.isNotEmpty()) {
            db.evolutionLogDao().insert(
                EvolutionLogEntity(
                    changedAt = System.currentTimeMillis(),
                    changeLog = buildUiSummary(results),
                    rulesJson = "{}",
                ),
            )
        }
        return results
    }

    private fun buildUiSummary(results: List<PostMortemResult>): String {
        val grouped = results.groupingBy { it.missedReason }.eachCount().entries
            .sortedByDescending { it.value }
            .joinToString { "${it.key}=${it.value}" }
        val examples = results.sortedByDescending { it.returnPct }
            .take(5)
            .joinToString("\n") { r ->
                "- ${r.market} ${r.returnPct.one()}% | ${r.missedReason} | ${r.suggestedParamAdjust}"
            }
        return """
            === PostMortem missed-signal analysis ===
            analyzed=${results.size}
            reasonStats=$grouped
            $examples
        """.trimIndent()
    }

    private fun diagnose(row: MissedSignalEntity): PostMortemResult {
        val effectiveReturn = maxOf(row.peakReturnPct, row.changeRate)
        val marketState = when {
            effectiveReturn >= STRONG_PUMP_PCT -> "STRONG_MISSED_PUMP"
            effectiveReturn >= NORMAL_PUMP_PCT -> "NORMAL_MISSED_PUMP"
            else -> "MINOR_MISSED_MOVE"
        }
        val reason = classifyReason(row, effectiveReturn)
        val param = suggestedParam(row, reason)
        val diagnosis = buildString {
            append(marketState)
            append(" | reason=").append(reason)
            append(" | market=").append(row.market)
            append(" | return=").append(row.changeRate.one()).append("%")
            append(" | peak=").append(effectiveReturn.one()).append("%")
            append(" | regime=").append(row.btcRegimeAtMiss)
            append(" | rankChange=").append(row.rankByChangeRate)
            append(" | rankValue=").append(row.rankByTradeValue)
            append(" | before=").append(row.relatedRuleBefore.take(140))
        }
        return PostMortemResult(
            market = row.market,
            missedReason = reason,
            diagnosis = diagnosis,
            suggestedParamAdjust = param,
            returnPct = effectiveReturn,
        )
    }

    private fun classifyReason(row: MissedSignalEntity, effectiveReturn: Double): String {
        return when {
            row.missedReason == MissedSignalReason.TRADE_VALUE_FILTER_EXCLUDED -> "TRADE_VALUE_UNIVERSE_TOO_NARROW"
            row.missedReason == MissedSignalReason.SCORE_TOO_LOW -> "SCORE_THRESHOLD_TOO_HIGH"
            row.missedReason == MissedSignalReason.VOLUME_SCORE_TOO_LOW -> "VOLUME_ACCELERATION_TOO_STRICT"
            row.rankByTradeValue > 55 && row.rankByChangeRate <= 35 -> "EARLY_ROTATION_LIQUIDITY_FILTERED"
            row.rankByChangeRate > 60 && row.rankByTradeValue <= 45 -> "CHANGE_RANK_FILTER_TOO_NARROW"
            effectiveReturn >= STRONG_PUMP_PCT -> "LATE_REACTION_TO_STRONG_PUMP"
            effectiveReturn >= NORMAL_PUMP_PCT -> "MISSED_MODERATE_PUMP"
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
        private const val MIN_PEAK_RETURN_PCT = 3.0
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
