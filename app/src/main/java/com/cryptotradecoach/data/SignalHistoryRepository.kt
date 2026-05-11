package com.cryptotradecoach.data

import android.content.Context
import com.cryptotradecoach.data.local.AppDatabase
import com.cryptotradecoach.data.local.GuidelineChangeEntity
import com.cryptotradecoach.data.local.MissedSignalEntity
import com.cryptotradecoach.data.local.MissedSignalReason
import com.cryptotradecoach.data.local.PriceSnapshotEntity
import com.cryptotradecoach.data.local.STRATEGY_VERSION
import com.cryptotradecoach.data.local.SignalEventType
import com.cryptotradecoach.data.local.SignalHistoryEntity
import com.cryptotradecoach.data.local.StrategyReviewEntity
import kotlin.math.abs

data class ScanPersistenceResult(
    val historyByMarket: Map<String, List<SignalHistoryEntity>>,
    val missedSignals: List<MissedSignalEntity>,
    val strategyReviews: List<StrategyReviewEntity>,
    val guidelineChanges: List<GuidelineChangeEntity>,
    val newlyMissedSignals: List<MissedSignalEntity>,
)

class SignalHistoryRepository private constructor(
    private val database: AppDatabase,
) {
    private val dao = database.signalHistoryDao()

    suspend fun saveScanResult(tickers: List<Ticker>, signals: List<Signal>): ScanPersistenceResult {
        val now = System.currentTimeMillis()
        val snapshots = tickers.toSnapshots(now)
        dao.insertSnapshots(snapshots)
        cleanupSnapshots(now)

        val tickerByMarket = tickers.associateBy { it.market }
        signals.forEach { signal ->
            recordSignalEvents(signal, tickerByMarket[signal.market]?.tradePrice ?: signal.entryPrice, now)
        }
        recordExpiredSignals(tickerByMarket, now)
        val newlyMissedSignals = detectMissedSignals(snapshots, signals.take(5).map { it.market }.toSet(), now)
        val reviews = buildStrategyReviews(now)
        reviews.forEach { dao.insertStrategyReview(it) }
        reviews.forEach { maybeCreateGuidelineChange(it, now) }

        return ScanPersistenceResult(
            historyByMarket = getRecentHistoryByMarket(),
            missedSignals = dao.getRecentMissedSignals(),
            strategyReviews = dao.getRecentStrategyReviews(),
            guidelineChanges = dao.getRecentGuidelineChanges(),
            newlyMissedSignals = newlyMissedSignals,
        )
    }

    suspend fun getRecentHistoryByMarket(limitPerMarket: Int = 100): Map<String, List<SignalHistoryEntity>> {
        return dao.getHistoryMarkets().associateWith { market ->
            dao.getRecentHistoryByMarket(market, limitPerMarket)
        }
    }

    suspend fun getRecentMissedSignals(): List<MissedSignalEntity> = dao.getRecentMissedSignals()

    suspend fun getRecentStrategyReviews(): List<StrategyReviewEntity> = dao.getRecentStrategyReviews()

    suspend fun getRecentGuidelineChanges(): List<GuidelineChangeEntity> = dao.getRecentGuidelineChanges()

    private suspend fun recordSignalEvents(signal: Signal, currentPrice: Double, now: Long) {
        val latest = dao.getLatestHistoryForMarket(signal.market)
        val baseline = latest?.baselinePrice ?: signal.entryPrice
        val changedStrategy = latest != null && latest.strategyName != signal.strategyName
        val shouldInitial = latest == null || latest.eventType in TERMINAL_EVENTS

        if (shouldInitial) {
            insertEvent(signal, SignalEventType.INITIAL_SIGNAL, baselinePrice = signal.entryPrice, currentPrice = currentPrice, now = now)
        } else if (changedStrategy) {
            insertEvent(
                signal = signal,
                eventType = SignalEventType.STRATEGY_CHANGED,
                baselinePrice = baseline,
                currentPrice = currentPrice,
                now = now,
                extraReason = "Strategy changed from ${latest?.strategyName} to ${signal.strategyName}",
            )
        }

        val baselineMove = percentChange(baseline, currentPrice)
        if (abs(baselineMove) >= 5.0) {
            insertEventWithCooldown(signal, SignalEventType.PRICE_MOVED_5_PERCENT, baseline, currentPrice, now, EVENT_COOLDOWN_MS)
        }
        if (currentPrice <= signal.stopLossPrice) {
            insertEventWithCooldown(signal, SignalEventType.STOP_HIT, baseline, currentPrice, now, TERMINAL_COOLDOWN_MS)
        }
        if (currentPrice >= signal.targetPrice) {
            insertEventWithCooldown(signal, SignalEventType.TARGET_HIT, baseline, currentPrice, now, TERMINAL_COOLDOWN_MS)
        }
    }

    private suspend fun recordExpiredSignals(tickerByMarket: Map<String, Ticker>, now: Long) {
        val candidates = dao.getExpiredCandidates(
            expireBefore = now - SIGNAL_EXPIRY_MS,
            terminalSince = now - SIGNAL_EXPIRY_MS,
        )
        candidates.forEach { active ->
            val currentPrice = tickerByMarket[active.market]?.tradePrice ?: active.currentPrice
            if (dao.countMarketEventSince(active.market, SignalEventType.EXPIRED, active.createdAt) == 0) {
                dao.insertHistory(
                    active.copy(
                        id = 0,
                        eventType = SignalEventType.EXPIRED,
                        currentPrice = currentPrice,
                        reason = "No target or stop hit within 6 hours of the signal.",
                        updatedAt = now,
                        createdAt = now,
                    ).withPerformance(currentPrice),
                )
            }
        }
    }

    private suspend fun detectMissedSignals(
        snapshots: List<PriceSnapshotEntity>,
        topSignalMarkets: Set<String>,
        now: Long,
    ): List<MissedSignalEntity> {
        val missed = mutableListOf<MissedSignalEntity>()
        snapshots.forEach { snapshot ->
            val previous = dao.getPreviousSnapshot(snapshot.market, snapshot.timestamp)
            val firstInThirtyMinutes = dao.getOldestSnapshotSince(snapshot.market, now - MISSED_LOOKBACK_MS)
            val previousJumpPercent = previous?.let { percentChange(it.price, snapshot.price) } ?: 0.0
            val thirtyMinutePercent = firstInThirtyMinutes?.let { percentChange(it.price, snapshot.price) } ?: 0.0
            val rankRisingWithoutSignal = snapshot.rankByTradeValue <= 10 && snapshot.market !in topSignalMarkets
            val candidate = snapshot.rankByChangeRate <= 10 ||
                previousJumpPercent >= 3.0 ||
                thirtyMinutePercent >= 5.0 ||
                rankRisingWithoutSignal

            if (!candidate) return@forEach
            if (dao.countRecentActiveSignalEvents(snapshot.market, now - MISSED_LOOKBACK_MS) > 0) return@forEach
            if (dao.countRecentMissedSignals(snapshot.market, now - MISSED_NOTIFICATION_COOLDOWN_MS) > 0) return@forEach

            val reasonCode = classifyMissedReason(snapshot, previousJumpPercent, thirtyMinutePercent, rankRisingWithoutSignal)
            val entity = MissedSignalEntity(
                market = snapshot.market,
                detectedAt = now,
                currentPrice = snapshot.price,
                previousPrice = previous?.price ?: snapshot.price,
                changeRate = maxOf(snapshot.signedChangeRate * 100.0, previousJumpPercent, thirtyMinutePercent),
                rankByChangeRate = snapshot.rankByChangeRate,
                rankByTradeValue = snapshot.rankByTradeValue,
                missedReason = missedReasonText(reasonCode, previousJumpPercent, thirtyMinutePercent),
                suggestedStrategy = suggestedStrategy(reasonCode),
                relatedRuleBefore = relatedRuleBefore(reasonCode),
                suggestedRuleAfter = suggestedRuleAfter(reasonCode),
            )
            if (dao.insertMissedSignal(entity) > 0L) {
                missed += entity
            }
        }
        return missed
    }

    private suspend fun buildStrategyReviews(now: Long): List<StrategyReviewEntity> {
        val recentHistory = dao.getRecentHistory(300)
        if (recentHistory.isEmpty()) return emptyList()
        val missed = dao.getRecentMissedSignals(100)
        return recentHistory.groupBy { it.strategyName }.map { (strategyName, rows) ->
            val targetCount = rows.count { it.eventType == SignalEventType.TARGET_HIT }
            val stopCount = rows.count { it.eventType == SignalEventType.STOP_HIT }
            val expiredCount = rows.count { it.eventType == SignalEventType.EXPIRED }
            val relatedMissed = missed.filter { it.suggestedStrategy == strategyName || strategyName in it.suggestedStrategy }
            val repeatedMissedReason = missed.groupBy { it.missedReason }.maxByOrNull { it.value.size }
            val diagnosis = when {
                stopCount > targetCount -> "$strategyName has more stop hits than target hits in recent history."
                expiredCount > targetCount -> "$strategyName has many expired signals without target or stop confirmation."
                relatedMissed.isNotEmpty() -> "$strategyName is related to repeated missed opportunities."
                else -> "$strategyName is stable in the recent sample, but needs more observations."
            }
            val suggestion = repeatedMissedReason?.let {
                "Review rule for ${it.key}; repeated ${it.value.size} time(s)."
            } ?: "No immediate rule change suggested."

            StrategyReviewEntity(
                reviewedAt = now,
                strategyName = strategyName,
                strategyVersion = STRATEGY_VERSION,
                totalActiveSignals = rows.count { it.eventType in ACTIVE_EVENTS },
                totalMissedSignals = relatedMissed.size,
                targetHitCount = targetCount,
                stopHitCount = stopCount,
                expiredCount = expiredCount,
                averageMfePercent = rows.map { it.mfePercent }.averageOrZero(),
                averageMaePercent = rows.map { it.maePercent }.averageOrZero(),
                missedMarkets = relatedMissed.map { it.market }.distinct().joinToString(", "),
                diagnosis = diagnosis,
                ruleChangeSuggestion = suggestion,
            )
        }
    }

    private suspend fun maybeCreateGuidelineChange(review: StrategyReviewEntity, now: Long) {
        if (review.ruleChangeSuggestion == "No immediate rule change suggested.") return
        if (dao.countSimilarGuidelineChanges(review.strategyName, review.ruleChangeSuggestion, now - GUIDELINE_COOLDOWN_MS) > 0) return
        dao.insertGuidelineChange(
            GuidelineChangeEntity(
                changedAt = now,
                affectedStrategyName = review.strategyName,
                strategyVersionBefore = review.strategyVersion,
                strategyVersionAfterSuggestion = "${review.strategyVersion}-proposal",
                beforeRule = "Current SignalEngine scoring and filters for ${review.strategyName}.",
                afterRule = review.ruleChangeSuggestion,
                reason = review.diagnosis,
                evidenceMarkets = review.missedMarkets.ifBlank { "Recent signal history" },
                expectedEffect = "Reduce repeated misses or weak outcomes after user review.",
                applied = false,
            ),
        )
        // Future approval flow can read unapplied rows and map accepted suggestions into SignalEngine rules.
    }

    private suspend fun insertEventWithCooldown(
        signal: Signal,
        eventType: String,
        baselinePrice: Double,
        currentPrice: Double,
        now: Long,
        cooldownMs: Long,
    ) {
        if (dao.countMarketEventSince(signal.market, eventType, now - cooldownMs) == 0) {
            insertEvent(signal, eventType, baselinePrice, currentPrice, now)
        }
    }

    private suspend fun insertEvent(
        signal: Signal,
        eventType: String,
        baselinePrice: Double,
        currentPrice: Double,
        now: Long,
        extraReason: String? = null,
    ) {
        dao.insertHistory(
            SignalHistoryEntity(
                market = signal.market,
                strategyName = signal.strategyName,
                strategyVersion = STRATEGY_VERSION,
                baselinePrice = baselinePrice,
                currentPrice = currentPrice,
                entryPrice = signal.entryPrice,
                stopLossPrice = signal.stopLossPrice,
                targetPrice = signal.targetPrice,
                score = signal.score,
                eventType = eventType,
                reason = extraReason ?: signal.reason,
                mfePercent = maxOf(0.0, percentChange(baselinePrice, currentPrice)),
                maePercent = minOf(0.0, percentChange(baselinePrice, currentPrice)),
                maxReturnPercent = maxOf(0.0, percentChange(signal.entryPrice, currentPrice)),
                maxDrawdownPercent = minOf(0.0, percentChange(signal.entryPrice, currentPrice)),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun List<Ticker>.toSnapshots(now: Long): List<PriceSnapshotEntity> {
        val changeRanks = sortedByDescending { it.signedChangeRate }.mapIndexed { index, ticker -> ticker.market to index + 1 }.toMap()
        val valueRanks = sortedByDescending { it.accTradePrice24h }.mapIndexed { index, ticker -> ticker.market to index + 1 }.toMap()
        return map { ticker ->
            PriceSnapshotEntity(
                market = ticker.market,
                price = ticker.tradePrice,
                signedChangeRate = ticker.signedChangeRate,
                accTradePrice24h = ticker.accTradePrice24h,
                accTradeVolume24h = ticker.accTradeVolume24h,
                rankByChangeRate = changeRanks[ticker.market] ?: Int.MAX_VALUE,
                rankByTradeValue = valueRanks[ticker.market] ?: Int.MAX_VALUE,
                timestamp = now,
            )
        }
    }

    private suspend fun cleanupSnapshots(now: Long) {
        dao.deleteSnapshotsOlderThan(now - SNAPSHOT_RETENTION_MS)
        dao.keepLatestSnapshots(SNAPSHOT_RETENTION_COUNT)
    }

    private fun classifyMissedReason(
        snapshot: PriceSnapshotEntity,
        previousJumpPercent: Double,
        thirtyMinutePercent: Double,
        rankRisingWithoutSignal: Boolean,
    ): String = when {
        snapshot.rankByTradeValue > 30 -> MissedSignalReason.TRADE_VALUE_FILTER_EXCLUDED
        previousJumpPercent >= 3.0 && snapshot.accTradeVolume24h <= 0.0 -> MissedSignalReason.VOLUME_SCORE_TOO_LOW
        thirtyMinutePercent >= 5.0 && snapshot.signedChangeRate < 0.03 -> MissedSignalReason.CHANGE_RATE_RULE_NOT_MATCHED
        rankRisingWithoutSignal -> MissedSignalReason.SCORE_TOO_LOW
        previousJumpPercent >= 3.0 -> MissedSignalReason.SIDEWAYS_BREAKOUT_MISSED
        snapshot.signedChangeRate < 0.0 -> MissedSignalReason.REVERSAL_PATTERN_MISSED
        else -> MissedSignalReason.UNKNOWN
    }

    private fun missedReasonText(reasonCode: String, previousJumpPercent: Double, thirtyMinutePercent: Double): String {
        return "$reasonCode: move ${previousJumpPercent.formatPercent()} from previous snapshot, ${thirtyMinutePercent.formatPercent()} over 30 minutes."
    }

    private fun suggestedStrategy(reasonCode: String): String = when (reasonCode) {
        MissedSignalReason.REVERSAL_PATTERN_MISSED -> "Rebound Watch"
        MissedSignalReason.SIDEWAYS_BREAKOUT_MISSED -> "Volume Accumulation"
        else -> "Momentum Breakout"
    }

    private fun relatedRuleBefore(reasonCode: String): String = when (reasonCode) {
        MissedSignalReason.TRADE_VALUE_FILTER_EXCLUDED -> "Only top 30 markets by 24h trade value are scanned for strategies."
        MissedSignalReason.CHANGE_RATE_RULE_NOT_MATCHED -> "Momentum requires signedChangeRate between 3% and 12%."
        MissedSignalReason.VOLUME_SCORE_TOO_LOW -> "Volume score must pass the current strategy threshold."
        else -> "Current score threshold and pattern filters did not produce a top signal."
    }

    private fun suggestedRuleAfter(reasonCode: String): String = when (reasonCode) {
        MissedSignalReason.TRADE_VALUE_FILTER_EXCLUDED -> "Include fast-rising change-rate top 10 markets even when outside trade-value top 30."
        MissedSignalReason.CHANGE_RATE_RULE_NOT_MATCHED -> "Add 30-minute momentum as a secondary breakout trigger."
        MissedSignalReason.VOLUME_SCORE_TOO_LOW -> "Allow rapid price moves with lower 24h volume score when trade value rank improves sharply."
        MissedSignalReason.SIDEWAYS_BREAKOUT_MISSED -> "Add sideways range breakout detection before full momentum confirmation."
        MissedSignalReason.REVERSAL_PATTERN_MISSED -> "Add reversal candidate scoring for fast rebounds from negative change-rate states."
        MissedSignalReason.SCORE_TOO_LOW -> "Rebalance score weights when rank and short-term move both improve."
        else -> "Review SignalEngine filters for this market pattern."
    }

    private fun SignalHistoryEntity.withPerformance(currentPrice: Double): SignalHistoryEntity {
        val baselineMove = percentChange(baselinePrice, currentPrice)
        val entryMove = percentChange(entryPrice, currentPrice)
        return copy(
            mfePercent = maxOf(mfePercent, baselineMove, 0.0),
            maePercent = minOf(maePercent, baselineMove, 0.0),
            maxReturnPercent = maxOf(maxReturnPercent, entryMove, 0.0),
            maxDrawdownPercent = minOf(maxDrawdownPercent, entryMove, 0.0),
        )
    }

    private fun percentChange(from: Double, to: Double): Double {
        if (from <= 0.0) return 0.0
        return ((to - from) / from) * 100.0
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private fun Double.formatPercent(): String = String.format("%.2f%%", this)

    companion object {
        private val TERMINAL_EVENTS = setOf(
            SignalEventType.STOP_HIT,
            SignalEventType.TARGET_HIT,
            SignalEventType.EXPIRED,
            SignalEventType.INVALIDATED,
        )
        private val ACTIVE_EVENTS = setOf(
            SignalEventType.INITIAL_SIGNAL,
            SignalEventType.STRATEGY_CHANGED,
            SignalEventType.PRICE_MOVED_5_PERCENT,
        )
        private const val SIGNAL_EXPIRY_MS = 6 * 60 * 60 * 1000L
        private const val EVENT_COOLDOWN_MS = 30 * 60 * 1000L
        private const val TERMINAL_COOLDOWN_MS = 6 * 60 * 60 * 1000L
        private const val MISSED_LOOKBACK_MS = 30 * 60 * 1000L
        private const val MISSED_NOTIFICATION_COOLDOWN_MS = 30 * 60 * 1000L
        private const val GUIDELINE_COOLDOWN_MS = 12 * 60 * 60 * 1000L
        private const val SNAPSHOT_RETENTION_MS = 24 * 60 * 60 * 1000L
        private const val SNAPSHOT_RETENTION_COUNT = 5_000

        @Volatile
        private var instance: SignalHistoryRepository? = null

        fun getInstance(context: Context): SignalHistoryRepository {
            return instance ?: synchronized(this) {
                instance ?: SignalHistoryRepository(
                    AppDatabase.getInstance(context),
                ).also { instance = it }
            }
        }
    }
}
