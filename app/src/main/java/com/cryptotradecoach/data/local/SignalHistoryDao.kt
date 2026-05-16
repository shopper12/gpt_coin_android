package com.cryptotradecoach.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.cryptotradecoach.data.StrategyStatus

@Dao
interface SignalHistoryDao {
    @Query(
        """
        SELECT * FROM active_strategies
        WHERE status = 'ACTIVE'
        ORDER BY score DESC, rank ASC, updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun getActiveStrategies(limit: Int = 5): List<TradeStrategyEntity>

    @Upsert
    suspend fun upsertStrategy(strategy: TradeStrategyEntity)

    @Query(
        """
        UPDATE active_strategies
        SET status = :status,
            invalidationReason = :reason,
            updatedAt = :updatedAt
        WHERE id = :strategyId
        """,
    )
    suspend fun invalidateStrategy(
        strategyId: String,
        status: StrategyStatus = StrategyStatus.INVALIDATED,
        reason: String,
        updatedAt: Long,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHistory(item: StrategyHistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertScanLogs(items: List<StrategyScanLogEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPerformance(item: StrategyPerformanceEntity): Long

    @Query("SELECT * FROM strategy_performance WHERE strategyId = :strategyId LIMIT 1")
    suspend fun getPerformanceByStrategyId(strategyId: String): StrategyPerformanceEntity?

    @Query(
        """
        SELECT * FROM strategy_performance
        WHERE isComplete = 0
        ORDER BY createdAt ASC
        LIMIT :limit
        """,
    )
    suspend fun getOpenPerformance(limit: Int = 500): List<StrategyPerformanceEntity>

    @Query(
        """
        SELECT * FROM strategy_performance
        WHERE createdAt >= :since
        ORDER BY createdAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getPerformanceSince(since: Long, limit: Int = 2000): List<StrategyPerformanceEntity>

    @Query(
        """
        UPDATE strategy_performance
        SET lastUpdatedAt = :lastUpdatedAt,
            latestPrice = :latestPrice,
            priceAfter5m = :priceAfter5m,
            priceAfter15m = :priceAfter15m,
            priceAfter30m = :priceAfter30m,
            priceAfter60m = :priceAfter60m,
            return5m = :return5m,
            return15m = :return15m,
            return30m = :return30m,
            return60m = :return60m,
            mfePct = :mfePct,
            maePct = :maePct,
            target1Hit = :target1Hit,
            target2Hit = :target2Hit,
            stopHit = :stopHit,
            expired = :expired,
            isComplete = :isComplete
        WHERE id = :id
        """,
    )
    suspend fun updatePerformance(
        id: Long,
        lastUpdatedAt: Long,
        latestPrice: Double,
        priceAfter5m: Double?,
        priceAfter15m: Double?,
        priceAfter30m: Double?,
        priceAfter60m: Double?,
        return5m: Double?,
        return15m: Double?,
        return30m: Double?,
        return60m: Double?,
        mfePct: Double,
        maePct: Double,
        target1Hit: Boolean,
        target2Hit: Boolean,
        stopHit: Boolean,
        expired: Boolean,
        isComplete: Boolean,
    )

    @Query(
        """
        SELECT * FROM strategy_scan_logs
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentStrategyScanLogs(limit: Int = 500): List<StrategyScanLogEntity>

    @Query(
        """
        SELECT * FROM strategy_scan_logs
        WHERE timestamp >= :since
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getStrategyScanLogsSince(since: Long, limit: Int = 1000): List<StrategyScanLogEntity>

    @Query(
        """
        SELECT * FROM strategy_history
        WHERE symbol = :symbol
        ORDER BY createdAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getHistoryBySymbol(symbol: String, limit: Int = 200): List<StrategyHistoryEntity>

    @Query(
        """
        SELECT * FROM strategy_history
        ORDER BY createdAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getAllHistory(limit: Int = 500): List<StrategyHistoryEntity>

    @Query(
        """
        SELECT * FROM active_strategies
        WHERE symbol = :symbol
        ORDER BY updatedAt DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestStrategyBySymbol(symbol: String): TradeStrategyEntity?

    @Query(
        """
        SELECT * FROM active_strategies
        WHERE status = 'ACTIVE'
        ORDER BY updatedAt DESC
        """,
    )
    suspend fun getAllCurrentlyActiveStrategies(): List<TradeStrategyEntity>

    @Query(
        """
        SELECT COUNT(*) FROM strategy_history
        WHERE symbol = :symbol
        AND eventType = :eventType
        AND newSummary = :newSummary
        AND createdAt >= :since
        """,
    )
    suspend fun countDuplicateStrategyHistory(
        symbol: String,
        eventType: String,
        newSummary: String?,
        since: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSignalHistory(item: SignalHistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSnapshots(items: List<PriceSnapshotEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissedSignal(item: MissedSignalEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStrategyReview(item: StrategyReviewEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGuidelineChange(item: GuidelineChangeEntity)

    @Query("SELECT DISTINCT market FROM signal_history ORDER BY market ASC")
    suspend fun getHistoryMarkets(): List<String>

    @Query(
        """
        SELECT * FROM signal_history
        WHERE market = :market
        ORDER BY createdAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentHistoryByMarket(market: String, limit: Int = 100): List<SignalHistoryEntity>

    @Query(
        """
        SELECT * FROM signal_history
        ORDER BY createdAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentHistory(limit: Int = 300): List<SignalHistoryEntity>

    @Query(
        """
        SELECT * FROM signal_history
        WHERE market = :market
        ORDER BY createdAt DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestHistoryForMarket(market: String): SignalHistoryEntity?

    @Query(
        """
        SELECT COUNT(*) FROM signal_history
        WHERE market = :market AND eventType = :eventType AND createdAt >= :since
        """,
    )
    suspend fun countMarketEventSince(market: String, eventType: String, since: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM signal_history
        WHERE market = :market
        AND eventType IN ('INITIAL_SIGNAL', 'STRATEGY_CHANGED', 'PRICE_MOVED_5_PERCENT')
        AND createdAt >= :since
        """,
    )
    suspend fun countRecentActiveSignalEvents(market: String, since: Long): Int

    @Query(
        """
        SELECT * FROM signal_history
        WHERE eventType IN ('INITIAL_SIGNAL', 'STRATEGY_CHANGED', 'PRICE_MOVED_5_PERCENT')
        AND createdAt <= :expireBefore
        AND market NOT IN (
            SELECT market FROM signal_history
            WHERE eventType IN ('STOP_HIT', 'TARGET_HIT', 'EXPIRED', 'INVALIDATED')
            AND createdAt >= :terminalSince
        )
        ORDER BY createdAt ASC
        LIMIT :limit
        """,
    )
    suspend fun getExpiredCandidates(expireBefore: Long, terminalSince: Long, limit: Int = 100): List<SignalHistoryEntity>

    @Query(
        """
        SELECT * FROM price_snapshots
        WHERE market = :market AND timestamp < :before
        ORDER BY timestamp DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun getPreviousSnapshot(market: String, before: Long): PriceSnapshotEntity?

    @Query(
        """
        SELECT * FROM price_snapshots
        WHERE market = :market AND timestamp >= :since
        ORDER BY timestamp ASC, id ASC
        LIMIT 1
        """,
    )
    suspend fun getOldestSnapshotSince(market: String, since: Long): PriceSnapshotEntity?

    @Query(
        """
        SELECT COUNT(*) FROM missed_signals
        WHERE market = :market AND detectedAt >= :since
        """,
    )
    suspend fun countRecentMissedSignals(market: String, since: Long): Int

    @Query(
        """
        SELECT * FROM missed_signals
        ORDER BY detectedAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentMissedSignals(limit: Int = 100): List<MissedSignalEntity>

    @Query(
        """
        SELECT * FROM strategy_reviews
        ORDER BY reviewedAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentStrategyReviews(limit: Int = 50): List<StrategyReviewEntity>

    @Query(
        """
        SELECT * FROM guideline_changes
        ORDER BY changedAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentGuidelineChanges(limit: Int = 50): List<GuidelineChangeEntity>

    @Query(
        """
        SELECT COUNT(*) FROM guideline_changes
        WHERE affectedStrategyName = :strategyName
        AND afterRule = :afterRule
        AND changedAt >= :since
        """,
    )
    suspend fun countSimilarGuidelineChanges(strategyName: String, afterRule: String, since: Long): Int

    @Query("DELETE FROM price_snapshots WHERE timestamp < :cutoff")
    suspend fun deleteSnapshotsOlderThan(cutoff: Long)

    @Query(
        """
        DELETE FROM price_snapshots
        WHERE id NOT IN (
            SELECT id FROM price_snapshots ORDER BY timestamp DESC, id DESC LIMIT :limit
        )
        """,
    )
    suspend fun keepLatestSnapshots(limit: Int)
}