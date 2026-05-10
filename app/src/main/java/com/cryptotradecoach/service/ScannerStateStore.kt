package com.cryptotradecoach.service

import com.cryptotradecoach.data.Signal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ScannerStateStore {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _topSignals = MutableStateFlow<List<Signal>>(emptyList())
    val topSignals: StateFlow<List<Signal>> = _topSignals

    private val _historyByMarket = MutableStateFlow<Map<String, List<Signal>>>(emptyMap())
    val historyByMarket: StateFlow<Map<String, List<Signal>>> = _historyByMarket

    private val _lastScanAt = MutableStateFlow<Long?>(null)
    val lastScanAt: StateFlow<Long?> = _lastScanAt

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun pushScanResult(
        validSignals: List<Signal>,
        persistedHistoryByMarket: Map<String, List<Signal>>? = null,
    ) {
        _lastScanAt.value = System.currentTimeMillis()
        _topSignals.value = validSignals.take(5)
        if (persistedHistoryByMarket != null) {
            _historyByMarket.value = persistedHistoryByMarket.toSortedMap()
            return
        }
        if (validSignals.isEmpty()) return

        val updated = _historyByMarket.value.toMutableMap()
        validSignals.forEach { signal ->
            val history = updated[signal.market].orEmpty()
            updated[signal.market] = (listOf(signal) + history)
                .distinctBy { "${it.market}-${it.strategyName}-${it.timestamp}" }
                .take(100)
        }
        _historyByMarket.value = updated.toSortedMap()
    }

    fun loadHistory(historyByMarket: Map<String, List<Signal>>) {
        _historyByMarket.value = historyByMarket.toSortedMap()
    }
}
