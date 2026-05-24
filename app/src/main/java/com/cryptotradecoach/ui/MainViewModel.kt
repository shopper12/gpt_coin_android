package com.cryptotradecoach.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cryptotradecoach.data.Signal
import com.cryptotradecoach.data.SignalHistoryRepository
import com.cryptotradecoach.data.local.GuidelineChangeEntity
import com.cryptotradecoach.data.local.MissedSignalEntity
import com.cryptotradecoach.data.local.SignalHistoryEntity
import com.cryptotradecoach.data.local.StrategyReviewEntity
import com.cryptotradecoach.data.remote.StockScannerApi
import com.cryptotradecoach.data.remote.StockScannerSnapshot
import com.cryptotradecoach.service.ScannerStateStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val isRunning: Boolean = false,
    val topSignals: List<Signal> = emptyList(),
    val historyByMarket: Map<String, List<SignalHistoryEntity>> = emptyMap(),
    val missedSignals: List<MissedSignalEntity> = emptyList(),
    val strategyReviews: List<StrategyReviewEntity> = emptyList(),
    val guidelineChanges: List<GuidelineChangeEntity> = emptyList(),
    val lastScanAt: Long? = null,
    val stockSnapshot: StockScannerSnapshot? = null,
    val stockLoading: Boolean = false,
    val stockError: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val historyRepository = SignalHistoryRepository.getInstance(application)
    private val stockScannerApi = StockScannerApi()

    init {
        viewModelScope.launch {
            ScannerStateStore.loadPersistedState(
                historyByMarket = historyRepository.getRecentHistoryByMarket(),
                missedSignals = historyRepository.getRecentMissedSignals(),
                strategyReviews = historyRepository.getRecentStrategyReviews(),
                guidelineChanges = historyRepository.getRecentGuidelineChanges(),
            )
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
            ScannerStateStore.missedSignals.collect { missed ->
                _uiState.value = _uiState.value.copy(missedSignals = missed)
            }
        }
        viewModelScope.launch {
            ScannerStateStore.strategyReviews.collect { reviews ->
                _uiState.value = _uiState.value.copy(strategyReviews = reviews)
            }
        }
        viewModelScope.launch {
            ScannerStateStore.guidelineChanges.collect { changes ->
                _uiState.value = _uiState.value.copy(guidelineChanges = changes)
            }
        }
        viewModelScope.launch {
            ScannerStateStore.lastScanAt.collect { lastScanAt ->
                _uiState.value = _uiState.value.copy(lastScanAt = lastScanAt)
            }
        }
        refreshStockScanner()
    }

    fun refreshStockScanner() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(stockLoading = true, stockError = null)
            runCatching { stockScannerApi.fetchLatest() }
                .onSuccess { snapshot ->
                    _uiState.value = _uiState.value.copy(
                        stockSnapshot = snapshot,
                        stockLoading = false,
                        stockError = null,
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        stockLoading = false,
                        stockError = error.message ?: error::class.java.simpleName,
                    )
                }
        }
    }
}
