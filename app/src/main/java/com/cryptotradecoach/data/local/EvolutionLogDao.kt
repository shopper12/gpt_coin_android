package com.cryptotradecoach.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

// Provides recent automatic rule-change history for the performance screen.
@Dao
interface EvolutionLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: EvolutionLogEntity)

    @Query("SELECT * FROM evolution_log ORDER BY changedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<EvolutionLogEntity>
}
