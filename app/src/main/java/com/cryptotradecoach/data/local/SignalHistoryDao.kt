package com.cryptotradecoach.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SignalHistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<SignalHistoryEntity>)

    @Query("SELECT DISTINCT market FROM strategy_history ORDER BY market ASC")
    suspend fun getMarkets(): List<String>

    @Query(
        """
        SELECT * FROM strategy_history
        WHERE market = :market
        ORDER BY timestamp DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentByMarket(market: String, limit: Int = 100): List<SignalHistoryEntity>
}
