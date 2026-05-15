package com.cryptotradecoach.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cryptotradecoach.data.SignalHistoryRepository
import com.cryptotradecoach.data.TradeStrategy
import com.cryptotradecoach.data.local.StrategyHistoryEntity
import com.cryptotradecoach.service.ScannerStateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val isRunning: Boolean = false,
    val activeStrategies: List<TradeStrategy> = emptyList(),
    val historyBySymbol: Map<String, List<StrategyHistoryEntity>> = emptyMap(),
    val lastScanAt: Long? = null,
    val scanIntervalMs: Long = ScannerStateStore.DEFAULT_SCAN_INTERVAL_MS,
    val maxDisplayCount: Int = 5,
    val minimumScore: Double = 70.0,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val historyRepository = SignalHistoryRepository.getInstance(application)

    init {
        viewModelScope.launch {
            ScannerStateStore.loadPersistedState(
                activeStrategies = historyRepository.getActiveStrategies(),
                historyBySymbol = historyRepository.getHistoryBySymbol(),
            )
        }
        viewModelScope.launch {
            ScannerStateStore.isRunning.collect { running ->
                _uiState.value = _uiState.value.copy(isRunning = running)
            }
        }
        viewModelScope.launch {
            ScannerStateStore.activeStrategies.collect { strategies ->
                _uiState.value = _uiState.value.copy(activeStrategies = strategies)
            }
        }
        viewModelScope.launch {
            ScannerStateStore.historyBySymbol.collect { history ->
                _uiState.value = _uiState.value.copy(historyBySymbol = history)
            }
        }
        viewModelScope.launch {
            ScannerStateStore.lastScanAt.collect { lastScanAt ->
                _uiState.value = _uiState.value.copy(lastScanAt = lastScanAt)
            }
        }
        viewModelScope.launch {
            ScannerStateStore.scanIntervalMs.collect { interval ->
                _uiState.value = _uiState.value.copy(scanIntervalMs = interval)
            }
        }
        viewModelScope.launch {
            ScannerStateStore.maxDisplayCount.collect { count ->
                _uiState.value = _uiState.value.copy(maxDisplayCount = count)
            }
        }
        viewModelScope.launch {
            ScannerStateStore.minimumScore.collect { score ->
                _uiState.value = _uiState.value.copy(minimumScore = score)
            }
        }
    }

    fun setScanInterval(intervalMs: Long) {
        ScannerStateStore.setScanInterval(intervalMs)
    }

    fun setMaxDisplayCount(count: Int) {
        ScannerStateStore.setMaxDisplayCount(count)
    }

    fun setMinimumScore(score: Double) {
        ScannerStateStore.setMinimumScore(score)
    }
}
