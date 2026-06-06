package com.cryptotradecoach.service

import android.content.Context
import com.cryptotradecoach.data.ScanDiagnostics
import com.cryptotradecoach.data.Ticker
import com.cryptotradecoach.data.TradeStrategy
import com.cryptotradecoach.data.local.EvolutionLogEntity
import com.cryptotradecoach.data.local.StrategyHistoryEntity
import com.cryptotradecoach.domain.BacktestResult
import com.cryptotradecoach.domain.BtcRegime
import com.cryptotradecoach.domain.BtcRegimeDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object ScannerStateStore {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _activeStrategies = MutableStateFlow<List<TradeStrategy>>(emptyList())
    val activeStrategies: StateFlow<List<TradeStrategy>> = _activeStrategies

    private val _historyBySymbol = MutableStateFlow<Map<String, List<StrategyHistoryEntity>>>(emptyMap())
    val historyBySymbol: StateFlow<Map<String, List<StrategyHistoryEntity>>> = _historyBySymbol

    private val _lastTickerSnapshot = MutableStateFlow<List<Ticker>>(emptyList())
    val lastTickerSnapshot: StateFlow<List<Ticker>> = _lastTickerSnapshot

    private val _volumeAlerts = MutableStateFlow<List<Ticker>>(emptyList())
    val volumeAlerts: StateFlow<List<Ticker>> = _volumeAlerts

    private val _lastScanAt = MutableStateFlow<Long?>(null)
    val lastScanAt: StateFlow<Long?> = _lastScanAt

    private val _scanDiagnostics = MutableStateFlow(ScanDiagnostics())
    val scanDiagnostics: StateFlow<ScanDiagnostics> = _scanDiagnostics

    private val _scanIntervalMs = MutableStateFlow(DEFAULT_SCAN_INTERVAL_MS)
    val scanIntervalMs: StateFlow<Long> = _scanIntervalMs

    private val _maxDisplayCount = MutableStateFlow(DEFAULT_MAX_DISPLAY_COUNT)
    val maxDisplayCount: StateFlow<Int> = _maxDisplayCount

    private val _minimumScore = MutableStateFlow(DEFAULT_MINIMUM_SCORE)
    val minimumScore: StateFlow<Double> = _minimumScore

    private val _backtestResults = MutableStateFlow<List<BacktestResult>>(emptyList())
    val backtestResults: StateFlow<List<BacktestResult>> = _backtestResults

    private val _ruleChangeLogs = MutableStateFlow<List<EvolutionLogEntity>>(emptyList())
    val evolutionLog: StateFlow<List<EvolutionLogEntity>> = _ruleChangeLogs

    private val _lastEvolvedAt = MutableStateFlow<Long?>(null)
    val lastEvolvedAt: StateFlow<Long?> = _lastEvolvedAt

    private val _currentBtcRegime = MutableStateFlow(BtcRegime.NEUTRAL)
    val currentBtcRegime: StateFlow<BtcRegime> = _currentBtcRegime

    private val _btcChange1h = MutableStateFlow(0.0)
    val btcChange1h: StateFlow<Double> = _btcChange1h

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

    fun updateTickerSnapshot(tickers: List<Ticker>) {
        updateBtcRegimeFromTickers(tickers)
        _lastTickerSnapshot.value = tickers
    }

    fun pushVolumeAlerts(tickers: List<Ticker>) {
        _volumeAlerts.value = tickers
    }

    fun updateBacktestResults(results: List<BacktestResult>) {
        _backtestResults.value = results
    }

    fun updateEvolutionLog(rows: List<EvolutionLogEntity>) {
        _ruleChangeLogs.value = rows
        _lastEvolvedAt.value = rows.maxOfOrNull { it.changedAt }
    }

    fun updateBtcRegime(regime: BtcRegime) {
        _currentBtcRegime.value = regime
    }

    fun updateBtcChange1h(value: Double) {
        _btcChange1h.value = value
    }

    fun pushScanResult(
        activeStrategies: List<TradeStrategy>,
        historyBySymbol: Map<String, List<StrategyHistoryEntity>>,
        diagnostics: ScanDiagnostics = _scanDiagnostics.value.copy(validSignals = activeStrategies, lastError = null),
        context: Context? = null,
    ) {
        markScanAttempt(context)
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

    private fun updateBtcRegimeFromTickers(tickers: List<Ticker>) {
        val btc = tickers.firstOrNull { it.market == "KRW-BTC" } ?: return
        val previous = _lastTickerSnapshot.value.firstOrNull { it.market == "KRW-BTC" }
        val change1h = if (previous != null && previous.tradePrice > 0.0 && btc.tradePrice > 0.0) {
            ((btc.tradePrice - previous.tradePrice) / previous.tradePrice) * 100.0
        } else {
            _btcChange1h.value
        }
        _btcChange1h.value = change1h
        _currentBtcRegime.value = BtcRegimeDetector.detect(
            btcChange24h = btc.signedChangeRate * 100.0,
            btcChange1h = change1h,
        )
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

    const val DEFAULT_SCAN_INTERVAL_MS = 180_000L
    const val DEFAULT_MAX_DISPLAY_COUNT = 3
    const val DEFAULT_MINIMUM_SCORE = 74.0
    val SUPPORTED_INTERVALS_MS = listOf(60_000L, 180_000L, 300_000L, 600_000L)
    private const val PREFS_NAME = "scanner_state"
    private const val KEY_LAST_SCAN_AT = "last_scan_at"
}
