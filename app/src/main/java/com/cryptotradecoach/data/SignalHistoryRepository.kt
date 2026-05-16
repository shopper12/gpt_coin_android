package com.cryptotradecoach.data

import android.content.Context
import com.cryptotradecoach.data.local.AppDatabase
import com.cryptotradecoach.data.local.StrategyEventType
import com.cryptotradecoach.data.local.StrategyHistoryEntity
import com.cryptotradecoach.data.local.StrategyScanLogEntity
import com.cryptotradecoach.data.local.TradeStrategyEntity
import kotlin.math.abs

data class ScanPersistenceResult(
    val activeStrategies: List<TradeStrategy>,
    val historyBySymbol: Map<String, List<StrategyHistoryEntity>>,
    val newEvents: List<StrategyHistoryEntity>,
)

class SignalHistoryRepository private constructor(
    private val database: AppDatabase,
) {
    private val dao = database.signalHistoryDao()

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
        }

        strategies.forEach { strategy ->
            val previous = dao.getLatestStrategyBySymbol(strategy.symbol)
            val strategyToSave = strategy.toEntity(
                createdAt = previous?.createdAt ?: strategy.createdAt,
                updatedAt = now,
            )
            val event = classifyEvent(previous, strategyToSave)
            dao.upsertStrategy(strategyToSave)
            if (event != null) {
                insertDedupedHistory(event, now)?.let { events += it }
            }
        }

        dao.getAllCurrentlyActiveStrategies()
            .filter { it.symbol !in incomingBySymbol }
            .forEach { previous ->
                val terminalStatus = terminalStatus(
                    previous = previous,
                    currentPrice = currentPrices[previous.symbol],
                    logStatus = scanLogByMarket[previous.symbol]?.strategyStatus,
                    now = now,
                )
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

    suspend fun getActiveStrategies(limit: Int = 5): List<TradeStrategy> {
        return dao.getActiveStrategies(limit).map { it.toModel() }
    }

    suspend fun getHistoryBySymbol(limitPerSymbol: Int = 200): Map<String, List<StrategyHistoryEntity>> {
        return dao.getAllHistory().groupBy { it.symbol }.mapValues { (_, rows) ->
            rows.take(limitPerSymbol)
        }.toSortedMap()
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
        val eventType = if (current.rank < previous.rank) {
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

    private fun terminalStatus(
        previous: TradeStrategyEntity,
        currentPrice: Double?,
        logStatus: StrategyStatus?,
        now: Long,
    ): StrategyStatus {
        return when {
            logStatus == StrategyStatus.WATCH_ONLY -> StrategyStatus.WATCH_ONLY
            currentPrice != null && currentPrice >= previous.target2 -> StrategyStatus.HIT_TARGET
            currentPrice != null && currentPrice >= previous.target1 -> StrategyStatus.TARGET1_HIT
            currentPrice != null && currentPrice <= previous.trailingStop && currentPrice > previous.stopLoss -> StrategyStatus.TRAILING_STOP_HIT
            currentPrice != null && currentPrice <= previous.stopLoss -> StrategyStatus.STOPPED_OUT
            now > previous.validUntil -> StrategyStatus.EXPIRED
            else -> StrategyStatus.INVALIDATED
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
        StrategyStatus.WATCH_ONLY -> "Strategy was downgraded to WATCH_ONLY."
        StrategyStatus.TARGET1_HIT -> "Target1 was reached."
        StrategyStatus.TRAILING_STOP_HIT -> "Trailing stop was reached."
        StrategyStatus.HIT_TARGET -> "Target2 was reached."
        StrategyStatus.STOPPED_OUT -> "Stop loss was reached."
        StrategyStatus.EXPIRED -> "Strategy valid window expired."
        else -> "Score or conditions fell, so the strategy was removed from the first screen."
    }

    private fun materiallyChanged(previous: TradeStrategyEntity, current: TradeStrategyEntity): Boolean {
        return current.rank < previous.rank ||
            abs(current.score - previous.score) >= 3.0 ||
            priceChanged(previous.entryLow, current.entryLow) ||
            priceChanged(previous.entryHigh, current.entryHigh) ||
            priceChanged(previous.stopLoss, current.stopLoss) ||
            priceChanged(previous.target1, current.target1) ||
            priceChanged(previous.target2, current.target2) ||
            priceChanged(previous.trailingStop, current.trailingStop)
    }

    private fun priceChanged(old: Double, new: Double): Boolean {
        if (old <= 0.0) return true
        return abs((new - old) / old) >= 0.003
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

    private fun Double.one(): String = String.format("%.1f", this)

    private fun Double.price(): String = String.format("%.2f", this)

    companion object {
        private const val HISTORY_DEDUP_MS = 30 * 60 * 1000L

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
