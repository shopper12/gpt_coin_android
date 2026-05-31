package com.cryptotradecoach.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Persists and resolves WATCH_ONLY candidates that later pump. */
@Dao
interface WatchOnlyDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: WatchOnlyEntity): Long

    @Query(
        """
        SELECT * FROM watch_only_candidates
        WHERE checked = 0 AND checkAfterAt <= :now
        ORDER BY checkAfterAt ASC
        LIMIT :limit
        """,
    )
    suspend fun getDue(now: Long, limit: Int = 300): List<WatchOnlyEntity>

    @Query(
        """
        UPDATE watch_only_candidates
        SET checked = 1,
            checkedAt = :checkedAt,
            checkedPrice = :checkedPrice,
            returnPct = :returnPct,
            pumped = :pumped
        WHERE id = :id
        """,
    )
    suspend fun markChecked(
        id: Long,
        checkedAt: Long,
        checkedPrice: Double,
        returnPct: Double,
        pumped: Boolean,
    )

    @Query(
        """
        SELECT * FROM watch_only_candidates
        WHERE pumped = 1
        ORDER BY checkedAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentPumped(limit: Int = 100): List<WatchOnlyEntity>

    @Query(
        """
        SELECT COUNT(*) FROM watch_only_candidates
        WHERE market = :market AND createdAt >= :since
        """,
    )
    suspend fun countRecentForMarket(market: String, since: Long): Int
}
