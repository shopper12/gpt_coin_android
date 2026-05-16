package com.cryptotradecoach.service

import android.content.Context
import com.cryptotradecoach.data.ScanDiagnostics
import com.cryptotradecoach.data.TradeStrategy
import com.cryptotradecoach.data.local.StrategyHistoryEntity
import com.cryptotradecoach.domain.SignalEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ScannerStateStore {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _activeStrategies = MutableStateFlow<List<TradeStrategy>>(emptyList())
    val activeStrategies: StateFlow<List<TradeStrategy>> = _activeStrategies

    private val _historyBySymbol = MutableStateFlow<Map<String, List<StrategyHistoryEntity>>>(emptyMap())
    val historyBySymbol: StateFlow<Map<String, List<StrategyHistoryEntity>>> = _historyBySymbol

    private val _lastScanAt = MutableStateFlow<Long?>(null)
    val lastScanAt: StateFlow<Long?> = _lastScanAt

    private val _scanDiagnostics = MutableStateFlow(ScanDiagnostics())
    val scanDiagnostics: StateFlow<ScanDiagnostics> = _scanDiagnostics

    private val _scanIntervalMs = MutableStateFlow(DEFAULT_SCAN_INTERVAL_MS)
    val scanIntervalMs: StateFlow<Long> = _scanIntervalMs

    private val _maxDisplayCount = MutableStateFlow(SignalEngine.DEFAULT_MAX_RESULTS)
    val maxDisplayCount: StateFlow<Int> = _maxDisplayCount

    private val _minimumScore = MutableStateFlow(SignalEngine.DEFAULT_MINIMUM_SCORE)
    val minimumScore: StateFlow<Double> = _minimumScore

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun setScanInterval(intervalMs: Long) {
        if (intervalMs in SUPPORTED_INTERVALS_MS) {
            _scanIntervalMs.value = intervalMs
        }
    }

    fun setMaxDisplayCount(count: Int) {
        _maxDisplayCount.value = count.coerceIn(1, 20)
    }

    fun setMinimumScore(score: Double) {
        _minimumScore.value = score.coerceIn(0.0, 100.0)
    }

    fun pushScanResult(
        activeStrategies: List<TradeStrategy>,
        historyBySymbol: Map<String, List<StrategyHistoryEntity>>,
        diagnostics: ScanDiagnostics = _scanDiagnostics.value.copy(validSignals = activeStrategies, lastError = null),
        context: Context? = null,
    ) {
        val scanAt = markScanAttempt(context)
        _activeStrategies.value = activeStrategies
            .sortedWith(compareByDescending<TradeStrategy> { it.score }.thenBy { it.rank })
            .take(_maxDisplayCount.value)
        _historyBySymbol.value = historyBySymbol.toSortedMap()
        _scanDiagnostics.value = diagnostics.copy(validSignals = activeStrategies, lastError = null)
    }

    fun loadPersistedState(
        activeStrategies: List<TradeStrategy>,
        historyBySymbol: Map<String, List<StrategyHistoryEntity>>,
        context: Context? = null,
    ) {
        _activeStrategies.value = activeStrategies
            .sortedWith(compareByDescending<TradeStrategy> { it.score }.thenBy { it.rank })
            .take(_maxDisplayCount.value)
        _historyBySymbol.value = historyBySymbol.toSortedMap()
        context?.loadLastScanAt()?.let { _lastScanAt.value = it }
    }

    fun setLastError(error: String?) {
        _scanDiagnostics.value = _scanDiagnostics.value.copy(lastError = error)
    }

    fun markScanAttempt(context: Context? = null): Long {
        val scanAt = System.currentTimeMillis()
        _lastScanAt.value = scanAt
        context?.persistLastScanAt(scanAt)
        return scanAt
    }

    private fun Context.persistLastScanAt(scanAt: Long) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SCAN_AT, scanAt)
            .apply()
    }

    private fun Context.loadLastScanAt(): Long? {
        val value = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SCAN_AT, 0L)
        return value.takeIf { it > 0L }
    }

    const val DEFAULT_SCAN_INTERVAL_MS = 60_000L
    val SUPPORTED_INTERVALS_MS = listOf(30_000L, 60_000L, 180_000L)
    private const val PREFS_NAME = "scanner_state"
    private const val KEY_LAST_SCAN_AT = "last_scan_at"
}
