package com.cryptotradecoach.service

import com.cryptotradecoach.data.Signal
import com.cryptotradecoach.data.local.GuidelineChangeEntity
import com.cryptotradecoach.data.local.MissedSignalEntity
import com.cryptotradecoach.data.local.SignalHistoryEntity
import com.cryptotradecoach.data.local.StrategyReviewEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ScannerStateStore {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _topSignals = MutableStateFlow<List<Signal>>(emptyList())
    val topSignals: StateFlow<List<Signal>> = _topSignals

    private val _historyByMarket = MutableStateFlow<Map<String, List<SignalHistoryEntity>>>(emptyMap())
    val historyByMarket: StateFlow<Map<String, List<SignalHistoryEntity>>> = _historyByMarket

    private val _missedSignals = MutableStateFlow<List<MissedSignalEntity>>(emptyList())
    val missedSignals: StateFlow<List<MissedSignalEntity>> = _missedSignals

    private val _strategyReviews = MutableStateFlow<List<StrategyReviewEntity>>(emptyList())
    val strategyReviews: StateFlow<List<StrategyReviewEntity>> = _strategyReviews

    private val _guidelineChanges = MutableStateFlow<List<GuidelineChangeEntity>>(emptyList())
    val guidelineChanges: StateFlow<List<GuidelineChangeEntity>> = _guidelineChanges

    private val _lastScanAt = MutableStateFlow<Long?>(null)
    val lastScanAt: StateFlow<Long?> = _lastScanAt

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun pushScanResult(
        validSignals: List<Signal>,
        persistedHistoryByMarket: Map<String, List<SignalHistoryEntity>>? = null,
        missedSignals: List<MissedSignalEntity> = _missedSignals.value,
        strategyReviews: List<StrategyReviewEntity> = _strategyReviews.value,
        guidelineChanges: List<GuidelineChangeEntity> = _guidelineChanges.value,
    ) {
        _lastScanAt.value = System.currentTimeMillis()
        _topSignals.value = validSignals.take(5)
        _missedSignals.value = missedSignals
        _strategyReviews.value = strategyReviews
        _guidelineChanges.value = guidelineChanges
        if (persistedHistoryByMarket != null) {
            _historyByMarket.value = persistedHistoryByMarket.toSortedMap()
            return
        }
    }

    fun loadPersistedState(
        historyByMarket: Map<String, List<SignalHistoryEntity>>,
        missedSignals: List<MissedSignalEntity>,
        strategyReviews: List<StrategyReviewEntity>,
        guidelineChanges: List<GuidelineChangeEntity>,
    ) {
        _historyByMarket.value = historyByMarket.toSortedMap()
        _missedSignals.value = missedSignals
        _strategyReviews.value = strategyReviews
        _guidelineChanges.value = guidelineChanges
    }
}
