package com.cryptotradecoach.data

import com.cryptotradecoach.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

// Tracks active trade recommendations after creation and keeps realized performance updated.
class SignalMonitor(
    private val upbitApi: UpbitMarketDataSource,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
) {
    private val monitored = ConcurrentHashMap<String, MonitoredSignal>()

    data class MonitoredSignal(
        val strategy: TradeStrategy,
        val startedAt: Long = System.currentTimeMillis(),
        val priceSnapshots: MutableList<PriceSnapshot> = mutableListOf(),
        var stopHit: Boolean = false,
        var target1Hit: Boolean = false,
        var target2Hit: Boolean = false,
        var exitReason: String = "MONITORING",
    )

    data class PriceSnapshot(
        val elapsedMs: Long,
        val price: Double,
        val volume: Double,
    )

    fun startMonitoring(strategy: TradeStrategy) {
        if (monitored.containsKey(strategy.id)) return
        val entry = MonitoredSignal(strategy)
        monitored[strategy.id] = entry
        scope.launch {
            var elapsed = 0L
            while (elapsed <= MAX_DURATION_MS && !entry.stopHit && !entry.target2Hit) {
                delay(TICK_INTERVAL_MS)
                elapsed = System.currentTimeMillis() - entry.startedAt
                val ticker = runCatching {
                    upbitApi.fetchTickers().firstOrNull { it.market == strategy.symbol }
                }.getOrNull() ?: continue
                val price = ticker.tradePrice
                entry.priceSnapshots.add(PriceSnapshot(elapsed, price, ticker.accTradeVolume24h))
                if (price <= strategy.stopLoss) {
                    entry.stopHit = true
                    entry.exitReason = "STOP_HIT"
                }
                if (price >= strategy.target1) {
                    entry.target1Hit = true
                }
                if (price >= strategy.target2) {
                    entry.target2Hit = true
                    entry.exitReason = "TARGET2_HIT"
                }
                if (CHECKPOINTS.any { checkpoint -> elapsed in checkpoint..(checkpoint + TICK_INTERVAL_MS) }) {
                    flushToDb(entry, final = false)
                }
            }
            if (entry.exitReason == "MONITORING") {
                entry.exitReason = "EXPIRED_240M"
            }
            flushToDb(entry, final = true)
            monitored.remove(strategy.id)
        }
    }

    fun stopMonitoring(signalId: String, reason: String = "MANUAL") {
        val entry = monitored.remove(signalId) ?: return
        entry.exitReason = reason
        scope.launch { flushToDb(entry, final = true) }
    }

    private suspend fun flushToDb(entry: MonitoredSignal, final: Boolean) {
        val snapshots = entry.priceSnapshots
        if (snapshots.isEmpty()) return
        val dao = db.signalHistoryDao()
        val row = dao.getPerformanceByStrategyId(entry.strategy.id) ?: return
        val entryPrice = row.entryPrice.takeIf { it > 0.0 } ?: entry.strategy.entryHigh
        val latest = snapshots.last().price
        fun priceAt(targetMs: Long): Double? {
            return snapshots
                .filter { it.elapsedMs <= targetMs + TICK_INTERVAL_MS }
                .maxByOrNull { it.elapsedMs }
                ?.price
        }
        fun returnPct(price: Double?): Double? = price?.let { percentChange(entryPrice, it) }
        val latestReturn = percentChange(entryPrice, latest)
        val mfe = max(row.mfePct, snapshots.maxOfOrNull { percentChange(entryPrice, it.price) } ?: latestReturn)
        val mae = min(row.maePct, snapshots.minOfOrNull { percentChange(entryPrice, it.price) } ?: latestReturn)
        val priceAfter5m = row.priceAfter5m ?: priceAt(5L * 60L * 1000L)
        val priceAfter15m = row.priceAfter15m ?: priceAt(15L * 60L * 1000L)
        val priceAfter30m = row.priceAfter30m ?: priceAt(30L * 60L * 1000L)
        val priceAfter60m = row.priceAfter60m ?: priceAt(60L * 60L * 1000L)
        dao.updatePerformance(
            id = row.id,
            lastUpdatedAt = System.currentTimeMillis(),
            latestPrice = latest,
            priceAfter5m = priceAfter5m,
            priceAfter15m = priceAfter15m,
            priceAfter30m = priceAfter30m,
            priceAfter60m = priceAfter60m,
            return5m = returnPct(priceAfter5m),
            return15m = returnPct(priceAfter15m),
            return30m = returnPct(priceAfter30m),
            return60m = returnPct(priceAfter60m),
            mfePct = mfe,
            maePct = mae,
            target1Hit = row.target1Hit || entry.target1Hit,
            target2Hit = row.target2Hit || entry.target2Hit,
            stopHit = row.stopHit || entry.stopHit,
            expired = row.expired || final,
            isComplete = row.isComplete || final || entry.stopHit || entry.target2Hit,
        )
    }

    private fun percentChange(from: Double, to: Double): Double {
        if (from <= 0.0) return 0.0
        return (to - from) / from * 100.0
    }

    companion object {
        private const val TICK_INTERVAL_MS = 30L * 1000L
        private const val MAX_DURATION_MS = 240L * 60L * 1000L
        private val CHECKPOINTS = listOf(
            5L * 60L * 1000L,
            15L * 60L * 1000L,
            30L * 60L * 1000L,
            60L * 60L * 1000L,
            120L * 60L * 1000L,
            240L * 60L * 1000L,
        )
    }
}
