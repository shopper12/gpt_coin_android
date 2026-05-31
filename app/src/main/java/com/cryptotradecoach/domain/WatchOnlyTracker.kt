package com.cryptotradecoach.domain

import com.cryptotradecoach.data.StrategyScanResult
import com.cryptotradecoach.data.StrategyStatus
import com.cryptotradecoach.data.Ticker
import com.cryptotradecoach.data.local.AppDatabase
import com.cryptotradecoach.data.local.MissedSignalEntity
import com.cryptotradecoach.data.local.MissedSignalReason
import com.cryptotradecoach.data.local.WatchOnlyEntity

/** Records WATCH_ONLY candidates and marks them when they later pump. */
class WatchOnlyTracker(
    private val db: AppDatabase,
) {
    suspend fun record(scanResult: StrategyScanResult, now: Long = System.currentTimeMillis()) {
        val dao = db.watchOnlyDao()
        scanResult.scanLogs
            .filter { it.strategyStatus == StrategyStatus.WATCH_ONLY }
            .filter { it.currentPrice > 0.0 }
            .forEach { log ->
                val duplicates = dao.countRecentForMarket(log.market, now - DEDUP_WINDOW_MS)
                if (duplicates > 0) return@forEach
                dao.insert(
                    WatchOnlyEntity(
                        market = log.market,
                        strategyType = log.strategyType,
                        createdAt = now,
                        checkAfterAt = now + CHECK_AFTER_MS,
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

    suspend fun resolve(tickers: List<Ticker>, now: Long = System.currentTimeMillis()): List<WatchOnlyEntity> {
        val prices = tickers.associate { it.market to it.tradePrice }
        val due = db.watchOnlyDao().getDue(now)
        val pumped = mutableListOf<WatchOnlyEntity>()
        due.forEach { item ->
            val currentPrice = prices[item.market] ?: return@forEach
            if (currentPrice <= 0.0 || item.entryPrice <= 0.0) return@forEach
            val returnPct = percentChange(item.entryPrice, currentPrice)
            val didPump = returnPct >= PUMP_RETURN_PCT
            db.watchOnlyDao().markChecked(
                id = item.id,
                checkedAt = now,
                checkedPrice = currentPrice,
                returnPct = returnPct,
                pumped = didPump,
            )
            if (didPump) {
                val resolved = item.copy(
                    checked = true,
                    checkedAt = now,
                    checkedPrice = currentPrice,
                    returnPct = returnPct,
                    pumped = true,
                )
                pumped += resolved
                db.signalHistoryDao().insertMissedSignal(
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
                        relatedRuleBefore = "WATCH_ONLY score=${item.score}; reason=${item.missedReason}; rankChange=${item.rankByChangeRate}; rankValue=${item.rankByTradeValue}",
                        suggestedRuleAfter = "WATCH_ONLY pumped after 30m; lower threshold or promote similar setup to ACTIVE when liquidity/volume confirms.",
                    ),
                )
            }
        }
        return pumped
    }

    private fun percentChange(from: Double, to: Double): Double {
        if (from <= 0.0) return 0.0
        return (to - from) / from * 100.0
    }

    companion object {
        private const val CHECK_AFTER_MS = 30L * 60L * 1000L
        private const val DEDUP_WINDOW_MS = 30L * 60L * 1000L
        private const val PUMP_RETURN_PCT = 5.0
    }
}
