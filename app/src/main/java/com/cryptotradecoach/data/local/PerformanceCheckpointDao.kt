package com.cryptotradecoach.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PerformanceCheckpointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PerformanceCheckpointEntity)

    @Query(
        """
        SELECT * FROM performance_checkpoints
        WHERE createdAt >= :since
        ORDER BY createdAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getSince(since: Long, limit: Int = 10000): List<PerformanceCheckpointEntity>

    @Query(
        """
        SELECT * FROM performance_checkpoints
        WHERE strategyId = :strategyId
        ORDER BY elapsedMinutes ASC
        """,
    )
    suspend fun getByStrategyId(strategyId: String): List<PerformanceCheckpointEntity>
}
