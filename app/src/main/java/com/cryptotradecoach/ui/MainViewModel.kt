package com.cryptotradecoach.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cryptotradecoach.data.Signal
import com.cryptotradecoach.data.SignalHistoryRepository
import com.cryptotradecoach.service.ScannerStateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val isRunning: Boolean = false,
    val topSignals: List<Signal> = emptyList(),
    val historyByMarket: Map<String, List<Signal>> = emptyMap(),
    val lastScanAt: Long? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val historyRepository = SignalHistoryRepository.getInstance(application)

    init {
        viewModelScope.launch {
            ScannerStateStore.loadHistory(historyRepository.getRecentHistoryByMarket())
        }
        viewModelScope.launch {
            ScannerStateStore.isRunning.collect { running ->
                _uiState.value = _uiState.value.copy(isRunning = running)
            }
        }
        viewModelScope.launch {
            ScannerStateStore.topSignals.collect { signals ->
                _uiState.value = _uiState.value.copy(topSignals = signals)
            }
        }
        viewModelScope.launch {
            ScannerStateStore.historyByMarket.collect { history ->
                _uiState.value = _uiState.value.copy(historyByMarket = history)
            }
        }
        viewModelScope.launch {
            ScannerStateStore.lastScanAt.collect { lastScanAt ->
                _uiState.value = _uiState.value.copy(lastScanAt = lastScanAt)
            }
        }
    }
}
