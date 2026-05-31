package com.cryptotradecoach.data

import android.content.Context
import com.cryptotradecoach.data.local.AppDatabase
import com.cryptotradecoach.data.local.MissedSignalEntity
import com.cryptotradecoach.data.local.MissedSignalReason
import com.cryptotradecoach.data.local.STRATEGY_VERSION
import com.cryptotradecoach.data.local.StrategyEventType
import com.cryptotradecoach.data.local.StrategyHistoryEntity
import com.cryptotradecoach.data.local.StrategyPerformanceEntity
import com.cryptotradecoach.data.local.StrategyScanLogEntity
import com.cryptotradecoach.data.local.TradeStrategyEntity
import com.cryptotradecoach.data.local.WatchOnlyEntity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ScanPersistenceResult(
    val activeStrategies: List<TradeStrategy>,
    val historyBySymbol: Map<String, List<StrategyHistoryEntity>>,
    val newEvents: List<StrategyHistoryEntity>,
)

class SignalHistoryRepository private constructor(
    private val database: AppDatabase,
) {
    private val dao = database.signalHistoryDao()
    private val watchOnlyDao = database.watchOnlyDao()

    suspend fun saveStrategyScanResult(
        scanResult: StrategyScanResult,
        currentPrices: Map<String, Double> = emptyMap(),
        now: Long = System.currentTimeMillis(),
    ): ScanPersistenceResult {
        val strategies = scanResult.activeStrategies
        val incomingBySymbol = strategies.associateBy { it.symbol }
        val scanLogByMarket = scanResult.scanLogs.associateBy { it.market }
        val events = mutableListOf<StrategyHistoryEntity>()

        if (scanResult.scanLogs.isNotEmpty()) {
            dao.insertScanLogs(scanResult.scanLogs.map { it.toEntity() })
            recordWatchOnlyCandidates(scanResult.scanLogs, now)
        }
        updateOpenPerformance(currentPrices, now)
        resolveDueWatchOnly(currentPrices, now)

        strategies.forEach { strategy ->
            val previous = dao.getLatestStrategyBySymbol(strategy.symbol)
            val strategyToSave = strategy.toEntity(
                createdAt = previous?.createdAt ?: strategy.createdAt,
                updatedAt = now,
            )
            val event = classifyEvent(previous, strategyToSave)
            dao.upsertStrategy(strategyToSave)
            ensurePerformanceRow(strategy, now)
            if (event != null) {
                insertDedupedHistory(event, now)?.let { events += it }
            }
        }

        dao.getAllCurrentlyActiveStrategies()
            .filter { it.symbol !in incomingBySymbol }
            .forEach { previous ->
                val terminalStatus = terminalStatusOrNull(
                    previous = previous,
                    currentPrice = currentPrices[previous.symbol],
                    logStatus = scanLogByMarket[previous.symbol]?.strategyStatus,
                    now = now,
                ) ?: return@forEach
                dao.invalidateStrategy(
                    strategyId = previous.id,
                    status = terminalStatus,
                    reason = terminalReason(terminalStatus),
                    updatedAt = now,
                )
                insertDedupedHistory(
                    StrategyHistoryEntity(
                        strategyId = previous.id,
                        symbol = previous.symbol,
                        eventType = eventTypeFor(terminalStatus),
                        oldSummary = previous.summary(),
                        newSummary = previous.copy(status = terminalStatus).summary(),
                        message = terminalReason(terminalStatus),
                        createdAt = now,
                    ),
                    now,
                )?.let { events += it }
            }

        return ScanPersistenceResult(
            activeStrategies = getActiveStrategies(),
            historyBySymbol = getHistoryBySymbol(),
            newEvents = events,
        )
    }

    suspend fun recordManualSearch(
        rawSymbol: String,
        strategy: TradeStrategy?,
        message: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val symbol = normalizeSymbol(rawSymbol, strategy?.symbol)
        val summary = strategy?.summary() ?: "검색결과 없음"
        insertDedupedHistory(
            StrategyHistoryEntity(
                strategyId = "MANUAL_SEARCH-$symbol-$now",
                symbol = symbol,
                eventType = StrategyEventType.MANUAL_SEARCH,
                oldSummary = null,
                newSummary = summary,
                message = message,
                createdAt = now,
            ),
            now,
        )
    }

    suspend fun getActiveStrategies(limit: Int = 5): List<TradeStrategy> {
        return dao.getActiveStrategies(limit).map { it.toModel() }
    }

    suspend fun getHistoryBySymbol(limitPerSymbol: Int = 200): Map<String, List<StrategyHistoryEntity>> {
        return dao.getAllHistory().groupBy { it.symbol }.mapValues { (_, rows) ->
            rows.take(limitPerSymbol)
        }.toSortedMap()
    }

    suspend fun getRecentPerformance(
        windowMs: Long = PERFORMANCE_WINDOW_MS,
        limit: Int = 500,
        now: Long = System.currentTimeMillis(),
    ): List<StrategyPerformanceEntity> {
        return dao.getPerformanceSince(now - windowMs, limit)
    }

    private suspend fun recordWatchOnlyCandidates(scanLogs: List<StrategyScanLog>, now: Long) {
        scanLogs
            .filter { it.strategyStatus == StrategyStatus.WATCH_ONLY }
            .filter { it.currentPrice > 0.0 }
            .forEach { log ->
                if (watchOnlyDao.countRecentForMarket(log.market, now - WATCH_ONLY_DEDUP_MS) > 0) return@forEach
                watchOnlyDao.insert(
                    WatchOnlyEntity(
                        market = log.market,
                        strategyType = log.strategyType,
                        createdAt = now,
                        checkAfterAt = now + WATCH_ONLY_CHECK_AFTER_MS,
                        entryPrice = log.currentPrice,
                        score = log.score,
                        missedReason = log.missedReason,
                        rankByChangeRate = log.rankByChangeRate,
                        rankByTradeValue = log.rankByTradeValue,
                        changeRate24h = log.changeRate24h,
                        changeRate30m = log.changeRate30m,
                        changeRate5m = log.changeRate5m,
                        volumeAcceleration = log.volumeAcceleration,
                    ),
                )
            }
    }

    private suspend fun resolveDueWatchOnly(currentPrices: Map<String, Double>, now: Long) {
        if (currentPrices.isEmpty()) return
        watchOnlyDao.getDue(now).forEach { item ->
            val currentPrice = currentPrices[item.market] ?: return@forEach
            if (currentPrice <= 0.0 || item.entryPrice <= 0.0) return@forEach
            val returnPct = percentChange(item.entryPrice, currentPrice)
            val pumped = returnPct >= WATCH_ONLY_PUMP_RETURN_PCT
            watchOnlyDao.markChecked(
                id = item.id,
                checkedAt = now,
                checkedPrice = currentPrice,
                returnPct = returnPct,
                pumped = pumped,
            )
            if (pumped) {
                dao.insertMissedSignal(
                    MissedSignalEntity(
                        market = item.market,
                        detectedAt = now,
                        currentPrice = currentPrice,
                        previousPrice = item.entryPrice,
                        changeRate = returnPct,
                        rankByChangeRate = item.rankByChangeRate,
                        rankByTradeValue = item.rankByTradeValue,
                        missedReason = item.missedReason ?: MissedSignalReason.UNKNOWN,
                        suggestedStrategy = item.strategyType.name,
                        relatedRuleBefore = "WATCH_ONLY score=${item.score.one()}; reason=${item.missedReason}; rankChange=${item.rankByChangeRate}; rankValue=${item.rankByTradeValue}",
                        suggestedRuleAfter = "WATCH_ONLY pumped after 30m; promote similar setups to ACTIVE when liquidity and volume confirm.",
                    ),
                )
            }
        }
    }

    private suspend fun ensurePerformanceRow(strategy: TradeStrategy, now: Long) {
        if (dao.getPerformanceByStrategyId(strategy.id) != null) return
        dao.insertPerformance(
            StrategyPerformanceEntity(
                strategyId = strategy.id,
                symbol = strategy.symbol,
                strategyType = strategy.strategyType,
                rulesVersion = STRATEGY_VERSION,
                createdAt = strategy.createdAt,
                lastUpdatedAt = now,
                entryPrice = strategy.entryHigh,
                latestPrice = strategy.entryHigh,
                target1 = strategy.target1,
                target2 = strategy.target2,
                stopLoss = strategy.stopLoss,
                rankByTradeValue = strategy.rankByTradeValue,
                score = strategy.score,
                reason = strategy.reason,
            ),
        )
    }

    private suspend fun updateOpenPerformance(currentPrices: Map<String, Double>, now: Long) {
        if (currentPrices.isEmpty()) return
        dao.getOpenPerformance().forEach { performance ->
            val latestPrice = currentPrices[performance.symbol] ?: return@forEach
            if (latestPrice <= 0.0 || performance.entryPrice <= 0.0) return@forEach
            val elapsed = now - performance.createdAt
            val latestReturn = percentChange(performance.entryPrice, latestPrice)
            val priceAfter5m = performance.priceAfter5m ?: latestPrice.takeIf { elapsed >= FIVE_MINUTES_MS }
            val priceAfter15m = performance.priceAfter15m ?: latestPrice.takeIf { elapsed >= FIFTEEN_MINUTES_MS }
            val priceAfter30m = performance.priceAfter30m ?: latestPrice.takeIf { elapsed >= THIRTY_MINUTES_MS }
            val priceAfter60m = performance.priceAfter60m ?: latestPrice.takeIf { elapsed >= SIXTY_MINUTES_MS }
            val target1Hit = performance.target1Hit || latestPrice >= performance.target1
            val target2Hit = performance.target2Hit || latestPrice >= performance.target2
            val stopHit = performance.stopHit || latestPrice <= performance.stopLoss
            val expired = elapsed >= SIXTY_MINUTES_MS
            dao.updatePerformance(
                id = performance.id,
                lastUpdatedAt = now,
                latestPrice = latestPrice,
                priceAfter5m = priceAfter5m,
                priceAfter15m = priceAfter15m,
                priceAfter30m = priceAfter30m,
                priceAfter60m = priceAfter60m,
                return5m = priceAfter5m?.let { percentChange(performance.entryPrice, it) },
                return15m = priceAfter15m?.let { percentChange(performance.entryPrice, it) },
                return30m = priceAfter30m?.let { percentChange(performance.entryPrice, it) },
                return60m = priceAfter60m?.let { percentChange(performance.entryPrice, it) },
                mfePct = max(performance.mfePct, latestReturn),
                maePct = min(performance.maePct, latestReturn),
                target1Hit = target1Hit,
                target2Hit = target2Hit,
                stopHit = stopHit,
                expired = expired,
                isComplete = expired || target2Hit || stopHit,
            )
        }
    }

    private fun classifyEvent(
        previous: TradeStrategyEntity?,
        current: TradeStrategyEntity,
    ): StrategyHistoryEntity? {
        val now = current.updatedAt
        if (previous == null || previous.status != StrategyStatus.ACTIVE) {
            return StrategyHistoryEntity(
                strategyId = current.id,
                symbol = current.symbol,
                eventType = StrategyEventType.NEW_ACTIVE,
                oldSummary = previous?.summary(),
                newSummary = current.summary(),
                message = "New ACTIVE strategy was created.",
                createdAt = now,
            )
        }
        if (!materiallyChanged(previous, current)) return null
        val eventType = if (current.rank < previous.rank && previous.rank - current.rank >= MIN_RANK_IMPROVEMENT_TO_LOG) {
            StrategyEventType.RANK_UP
        } else {
            StrategyEventType.PRICE_PLAN_CHANGED
        }
        return StrategyHistoryEntity(
            strategyId = current.id,
            symbol = current.symbol,
            eventType = eventType,
            oldSummary = previous.summary(),
            newSummary = current.summary(),
            message = "Entry, stop, target, score, or rank changed materially.",
            createdAt = now,
        )
    }

    private suspend fun insertDedupedHistory(event: StrategyHistoryEntity, now: Long): StrategyHistoryEntity? {
        val since = now - HISTORY_DEDUP_MS
        if (dao.countDuplicateStrategyHistory(event.symbol, event.eventType, event.newSummary, since) > 0) return null
        val id = dao.insertHistory(event)
        return if (id > 0L) event.copy(id = id) else null
    }

    private fun terminalStatusOrNull(
        previous: TradeStrategyEntity,
        currentPrice: Double?,
        logStatus: StrategyStatus?,
        now: Long,
    ): StrategyStatus? {
        return when {
            logStatus == StrategyStatus.WATCH_ONLY && now > previous.validUntil -> StrategyStatus.WATCH_ONLY
            currentPrice != null && currentPrice >= previous.target2 -> StrategyStatus.HIT_TARGET
            currentPrice != null && currentPrice >= previous.target1 -> StrategyStatus.TARGET1_HIT
            currentPrice != null && currentPrice <= previous.trailingStop && currentPrice > previous.stopLoss -> StrategyStatus.TRAILING_STOP_HIT
            currentPrice != null && currentPrice <= previous.stopLoss -> StrategyStatus.STOPPED_OUT
            now > previous.validUntil -> StrategyStatus.EXPIRED
            else -> null
        }
    }

    private fun eventTypeFor(status: StrategyStatus): String = when (status) {
        StrategyStatus.WATCH_ONLY -> StrategyEventType.WATCH_ONLY
        StrategyStatus.TARGET1_HIT -> StrategyEventType.TARGET1_HIT
        StrategyStatus.TRAILING_STOP_HIT -> StrategyEventType.TRAILING_STOP_HIT
        StrategyStatus.HIT_TARGET -> StrategyEventType.HIT_TARGET
        StrategyStatus.STOPPED_OUT -> StrategyEventType.STOPPED_OUT
        StrategyStatus.EXPIRED -> StrategyEventType.EXPIRED
        else -> StrategyEventType.INVALIDATED
    }

    private fun terminalReason(status: StrategyStatus): String = when (status) {
        StrategyStatus.WATCH_ONLY -> "Strategy was downgraded to WATCH_ONLY after its valid window."
        StrategyStatus.TARGET1_HIT -> "Target1 was reached."
        StrategyStatus.TRAILING_STOP_HIT -> "Trailing stop was reached."
        StrategyStatus.HIT_TARGET -> "Target2 was reached."
        StrategyStatus.STOPPED_OUT -> "Stop loss was reached."
        StrategyStatus.EXPIRED -> "Strategy valid window expired."
        else -> "Strategy was terminally invalidated."
    }

    private fun materiallyChanged(previous: TradeStrategyEntity, current: TradeStrategyEntity): Boolean {
        val rankImprovedMaterially = current.rank < previous.rank && previous.rank - current.rank >= MIN_RANK_IMPROVEMENT_TO_LOG
        return rankImprovedMaterially ||
            abs(current.score - previous.score) >= MIN_SCORE_CHANGE_TO_LOG ||
            priceChanged(previous.entryLow, current.entryLow) ||
            priceChanged(previous.entryHigh, current.entryHigh) ||
            priceChanged(previous.stopLoss, current.stopLoss) ||
            priceChanged(previous.target1, current.target1) ||
            priceChanged(previous.target2, current.target2) ||
            priceChanged(previous.trailingStop, current.trailingStop)
    }

    private fun priceChanged(old: Double, new: Double): Boolean {
        if (old <= 0.0) return true
        return abs((new - old) / old) >= MATERIAL_PRICE_CHANGE_RATIO
    }

    private fun TradeStrategy.toEntity(createdAt: Long, updatedAt: Long): TradeStrategyEntity {
        return TradeStrategyEntity(
            id = id,
            symbol = symbol,
            strategyType = strategyType,
            status = status,
            score = score,
            rank = rank,
            entryLow = entryLow,
            entryHigh = entryHigh,
            stopLoss = stopLoss,
            target1 = target1,
            target2 = target2,
            trailingStop = trailingStop,
            expectedReturnPct = expectedReturnPct,
            riskPct = riskPct,
            riskRewardRatio = riskRewardRatio,
            componentScores = componentScores,
            rankByChangeRate = rankByChangeRate,
            rankByTradeValue = rankByTradeValue,
            changeRate24h = changeRate24h,
            changeRate30m = changeRate30m,
            changeRate5m = changeRate5m,
            volumeAcceleration = volumeAcceleration,
            reason = reason,
            invalidationReason = invalidationReason,
            createdAt = createdAt,
            updatedAt = updatedAt,
            validUntil = validUntil,
        )
    }

    private fun TradeStrategyEntity.toModel(): TradeStrategy {
        return TradeStrategy(
            id = id,
            symbol = symbol,
            strategyType = strategyType,
            status = status,
            score = score,
            rank = rank,
            entryLow = entryLow,
            entryHigh = entryHigh,
            stopLoss = stopLoss,
            target1 = target1,
            target2 = target2,
            trailingStop = trailingStop,
            expectedReturnPct = expectedReturnPct,
            riskPct = riskPct,
            riskRewardRatio = riskRewardRatio,
            componentScores = componentScores,
            rankByChangeRate = rankByChangeRate,
            rankByTradeValue = rankByTradeValue,
            changeRate24h = changeRate24h,
            changeRate30m = changeRate30m,
            changeRate5m = changeRate5m,
            volumeAcceleration = volumeAcceleration,
            reason = reason,
            invalidationReason = invalidationReason,
            createdAt = createdAt,
            updatedAt = updatedAt,
            validUntil = validUntil,
        )
    }

    private fun StrategyScanLog.toEntity(): StrategyScanLogEntity {
        return StrategyScanLogEntity(
            id = id,
            market = market,
            strategyType = strategyType,
            timestamp = timestamp,
            currentPrice = currentPrice,
            entryPrice = entryPrice,
            stopLossPrice = stopLossPrice,
            target1 = target1,
            target2 = target2,
            trailingStop = trailingStop,
            score = score,
            componentScores = componentScores,
            rankByChangeRate = rankByChangeRate,
            rankByTradeValue = rankByTradeValue,
            changeRate24h = changeRate24h,
            changeRate30m = changeRate30m,
            changeRate5m = changeRate5m,
            volumeAcceleration = volumeAcceleration,
            selectedOrMissed = selectedOrMissed,
            missedReason = missedReason,
            topNAtScan = topNAtScan,
            strategyStatus = strategyStatus,
        )
    }

    private fun TradeStrategyEntity.summary(): String {
        return "rank=$rank score=${score.one()} entry=${entryLow.price()}-${entryHigh.price()} stop=${stopLoss.price()} target=${target1.price()}/${target2.price()} trail=${trailingStop.price()} status=$status"
    }

    private fun TradeStrategy.summary(): String {
        return "rank=$rank score=${score.one()} entry=${entryLow.price()}-${entryHigh.price()} stop=${stopLoss.price()} target=${target1.price()}/${target2.price()} trail=${trailingStop.price()} status=$status strategy=$strategyType"
    }

    private fun normalizeSymbol(rawSymbol: String, fallback: String?): String {
        val raw = rawSymbol.trim().uppercase().replace("/", "-")
        return when {
            fallback?.isNotBlank() == true -> fallback
            raw.startsWith("KRW-") -> raw
            raw.isBlank() -> "UNKNOWN"
            else -> "KRW-$raw"
        }
    }

    private fun percentChange(from: Double, to: Double): Double {
        if (from <= 0.0) return 0.0
        return ((to - from) / from) * 100.0
    }

    private fun Double.one(): String = String.format("%.1f", this)

    private fun Double.price(): String = String.format("%.2f", this)

    companion object {
        private const val HISTORY_DEDUP_MS = 2 * 60 * 60 * 1000L
        private const val MATERIAL_PRICE_CHANGE_RATIO = 0.012
        private const val MIN_SCORE_CHANGE_TO_LOG = 7.0
        private const val MIN_RANK_IMPROVEMENT_TO_LOG = 2
        private const val FIVE_MINUTES_MS = 5 * 60 * 1000L
        private const val FIFTEEN_MINUTES_MS = 15 * 60 * 1000L
        private const val THIRTY_MINUTES_MS = 30 * 60 * 1000L
        private const val SIXTY_MINUTES_MS = 60 * 60 * 1000L
        private const val PERFORMANCE_WINDOW_MS = 24 * 60 * 60 * 1000L
        private const val WATCH_ONLY_CHECK_AFTER_MS = 30 * 60 * 1000L
        private const val WATCH_ONLY_DEDUP_MS = 30 * 60 * 1000L
        private const val WATCH_ONLY_PUMP_RETURN_PCT = 5.0

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
