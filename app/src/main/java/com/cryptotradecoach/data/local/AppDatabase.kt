package com.cryptotradecoach.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SignalHistoryEntity::class,
        TradeStrategyEntity::class,
        StrategyHistoryEntity::class,
        StrategyScanLogEntity::class,
        StrategyPerformanceEntity::class,
        PerformanceCheckpointEntity::class,
        PriceSnapshotEntity::class,
        MissedSignalEntity::class,
        WatchOnlyEntity::class,
        StrategyReviewEntity::class,
        GuidelineChangeEntity::class,
        EvolutionLogEntity::class,
    ],
    version = 11,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun signalHistoryDao(): SignalHistoryDao
    abstract fun evolutionLogDao(): EvolutionLogDao
    abstract fun performanceCheckpointDao(): PerformanceCheckpointDao
    abstract fun watchOnlyDao(): WatchOnlyDao
    abstract fun missedSignalDao(): MissedSignalDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "crypto_trade_coach.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}
