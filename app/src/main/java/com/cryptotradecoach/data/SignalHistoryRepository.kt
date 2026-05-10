package com.cryptotradecoach.data

import android.content.Context
import com.cryptotradecoach.data.local.AppDatabase
import com.cryptotradecoach.data.local.toHistoryEntity
import com.cryptotradecoach.data.local.toSignal

class SignalHistoryRepository private constructor(
    private val database: AppDatabase,
) {
    private val dao = database.signalHistoryDao()

    suspend fun saveScanResult(signals: List<Signal>) {
        if (signals.isEmpty()) return
        dao.insertAll(signals.map { it.toHistoryEntity() })
    }

    suspend fun getRecentHistoryByMarket(limitPerMarket: Int = 100): Map<String, List<Signal>> {
        return dao.getMarkets().associateWith { market ->
            dao.getRecentByMarket(market, limitPerMarket).map { it.toSignal() }
        }
    }

    companion object {
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
