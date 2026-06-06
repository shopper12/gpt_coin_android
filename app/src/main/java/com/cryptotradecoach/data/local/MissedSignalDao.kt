package com.cryptotradecoach.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface MissedSignalDao {
    @Query(
        """
        SELECT * FROM missed_signals
        WHERE isTracking = 1 AND detectedAt >= :since
        ORDER BY detectedAt DESC, id DESC
        """,
    )
    suspend fun getTrackingMissedSignals(since: Long): List<MissedSignalEntity>

    @Upsert
    suspend fun upsert(item: MissedSignalEntity)
}
