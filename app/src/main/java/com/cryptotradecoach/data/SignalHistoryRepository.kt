package com.cryptotradecoach.data

import android.content.Context
import com.cryptotradecoach.data.local.AppDatabase
import com.cryptotradecoach.data.local.StrategyEventType
import com.cryptotradecoach.data.local.StrategyHistoryEntity
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
        strategies: List<TradeStrategy>,
        currentPrices: Map<String, Double> = emptyMap(),
        now: Long = System.currentTimeMillis(),
    ): ScanPersistenceResult {
        val incomingBySymbol = strategies.associateBy { it.symbol }
        val events = mutableListOf<StrategyHistoryEntity>()

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
                val terminalStatus = terminalStatus(previous, currentPrices[previous.symbol], now)
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
                message = "신규 ACTIVE 전략이 생성되었습니다.",
                createdAt = now,
            )
        }
        if (current.rank < previous.rank) {
            return StrategyHistoryEntity(
                strategyId = current.id,
                symbol = current.symbol,
                eventType = StrategyEventType.RANK_UP,
                oldSummary = previous.summary(),
                newSummary = current.summary(),
                message = "전략 순위가 ${previous.rank}위에서 ${current.rank}위로 상승했습니다.",
                createdAt = now,
            )
        }
        if (pricePlanChanged(previous, current)) {
            return StrategyHistoryEntity(
                strategyId = current.id,
                symbol = current.symbol,
                eventType = StrategyEventType.PRICE_PLAN_CHANGED,
                oldSummary = previous.summary(),
                newSummary = current.summary(),
                message = "진입가, 손절가 또는 목표가가 변경되었습니다.",
                createdAt = now,
            )
        }
        return null
    }

    private suspend fun insertDedupedHistory(event: StrategyHistoryEntity, now: Long): StrategyHistoryEntity? {
        val since = now - HISTORY_DEDUP_MS
        if (dao.countDuplicateStrategyHistory(event.symbol, event.eventType, event.newSummary, since) > 0) return null
        val id = dao.insertHistory(event)
        return if (id > 0L) event.copy(id = id) else null
    }

    private fun terminalStatus(previous: TradeStrategyEntity, currentPrice: Double?, now: Long): StrategyStatus {
        return when {
            currentPrice != null && currentPrice >= previous.target1 -> StrategyStatus.HIT_TARGET
            currentPrice != null && currentPrice <= previous.stopLoss -> StrategyStatus.STOPPED_OUT
            now > previous.validUntil -> StrategyStatus.EXPIRED
            else -> StrategyStatus.INVALIDATED
        }
    }

    private fun eventTypeFor(status: StrategyStatus): String = when (status) {
        StrategyStatus.HIT_TARGET -> StrategyEventType.HIT_TARGET
        StrategyStatus.STOPPED_OUT -> StrategyEventType.STOPPED_OUT
        StrategyStatus.EXPIRED -> StrategyEventType.EXPIRED
        else -> StrategyEventType.INVALIDATED
    }

    private fun terminalReason(status: StrategyStatus): String = when (status) {
        StrategyStatus.HIT_TARGET -> "목표가에 도달했습니다."
        StrategyStatus.STOPPED_OUT -> "손절가에 도달했습니다."
        StrategyStatus.EXPIRED -> "전략 유효 시간이 만료되었습니다."
        else -> "점수 또는 조건 하락으로 첫 화면에서 제거되었습니다."
    }

    private fun pricePlanChanged(previous: TradeStrategyEntity, current: TradeStrategyEntity): Boolean {
        return priceChanged(previous.entryLow, current.entryLow) ||
            priceChanged(previous.entryHigh, current.entryHigh) ||
            priceChanged(previous.stopLoss, current.stopLoss) ||
            priceChanged(previous.target1, current.target1) ||
            priceChanged(previous.target2, current.target2)
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
            expectedReturnPct = expectedReturnPct,
            riskPct = riskPct,
            riskRewardRatio = riskRewardRatio,
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
            expectedReturnPct = expectedReturnPct,
            riskPct = riskPct,
            riskRewardRatio = riskRewardRatio,
            reason = reason,
            invalidationReason = invalidationReason,
            createdAt = createdAt,
            updatedAt = updatedAt,
            validUntil = validUntil,
        )
    }

    private fun TradeStrategyEntity.summary(): String {
        return "rank=$rank score=${score.one()} entry=${entryLow.price()}-${entryHigh.price()} stop=${stopLoss.price()} target=${target1.price()}/${target2.price()} status=$status"
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
