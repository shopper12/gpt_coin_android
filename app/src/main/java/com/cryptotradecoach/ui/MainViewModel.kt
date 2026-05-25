package com.cryptotradecoach.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cryptotradecoach.data.AppUpdateRepository
import com.cryptotradecoach.data.GitHubSyncClient
import com.cryptotradecoach.data.GitHubSyncException
import com.cryptotradecoach.data.GitHubSettings
import com.cryptotradecoach.data.SettingsRepository
import com.cryptotradecoach.data.ScanDiagnostics
import com.cryptotradecoach.data.SignalHistoryRepository
import com.cryptotradecoach.data.StrategyReportRepository
import com.cryptotradecoach.data.StrategyRulesRepository
import com.cryptotradecoach.data.TradeStrategy
import com.cryptotradecoach.data.local.StrategyHistoryEntity
import com.cryptotradecoach.data.local.StrategyPerformanceEntity
import com.cryptotradecoach.service.ScannerStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MainUiState(
    val isRunning: Boolean = false,
    val activeStrategies: List<TradeStrategy> = emptyList(),
    val historyBySymbol: Map<String, List<StrategyHistoryEntity>> = emptyMap(),
    val performanceRows: List<StrategyPerformanceEntity> = emptyList(),
    val scanDiagnostics: ScanDiagnostics = ScanDiagnostics(),
    val lastScanAt: Long? = null,
    val scanIntervalMs: Long = ScannerStateStore.DEFAULT_SCAN_INTERVAL_MS,
    val maxDisplayCount: Int = 5,
    val minimumScore: Double = 70.0,
    val gitHubSettings: GitHubSettings = GitHubSettings(),
    val settingsMessage: String? = null,
    val currentRulesText: String = "",
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val historyRepository = SignalHistoryRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)
    private val rulesRepository = StrategyRulesRepository.getInstance(application)
    private val reportRepository = StrategyReportRepository.getInstance(application)
    private val appUpdateRepository = AppUpdateRepository(application.applicationContext)
    private val gitHubSyncClient = GitHubSyncClient()

    init {
        _uiState.value = _uiState.value.copy(
            gitHubSettings = settingsRepository.load(),
            currentRulesText = currentRulesText(),
        )
        viewModelScope.launch {
            ScannerStateStore.loadPersistedState(
                activeStrategies = historyRepository.getActiveStrategies(),
                historyBySymbol = historyRepository.getHistoryBySymbol(),
                context = application,
            )
            refreshPerformance()
        }
        viewModelScope.launch {
            ScannerStateStore.isRunning.collect { running ->
                _uiState.value = _uiState.value.copy(isRunning = running)
            }
        }
        viewModelScope.launch {
            ScannerStateStore.activeStrategies.collect { strategies ->
                _uiState.value = _uiState.value.copy(activeStrategies = strategies)
                refreshPerformance()
            }
        }
        viewModelScope.launch {
            ScannerStateStore.historyBySymbol.collect { history ->
                _uiState.value = _uiState.value.copy(historyBySymbol = history)
            }
        }
        viewModelScope.launch {
            ScannerStateStore.scanDiagnostics.collect { diagnostics ->
                _uiState.value = _uiState.value.copy(scanDiagnostics = diagnostics)
            }
        }
        viewModelScope.launch {
            ScannerStateStore.lastScanAt.collect { lastScanAt ->
                _uiState.value = _uiState.value.copy(lastScanAt = lastScanAt)
                refreshPerformance()
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

    fun saveGitHubSettings(settings: GitHubSettings) {
        val normalized = settings.normalized()
        val saved = settingsRepository.save(normalized)
        _uiState.value = _uiState.value.copy(
            gitHubSettings = normalized,
            settingsMessage = if (saved) "Settings saved" else "Settings save failed",
        )
    }

    fun testGitHubSettings(settings: GitHubSettings) {
        viewModelScope.launch {
            val normalized = settings.normalized()
            saveGitHubSettings(normalized)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val rules = rulesRepository.refreshFromGitHub()
                    _uiState.value = _uiState.value.copy(currentRulesText = currentRulesText(rules))
                    true
                }
            }.fold(
                onSuccess = { it },
                onFailure = { error ->
                    showGitHubMessage(gitHubFailureMessage("GitHub settings test failed", error))
                    return@launch
                },
            )
            showGitHubMessage(if (ok) "GitHub settings OK" else "GitHub settings test failed")
        }
    }

    fun downloadLatestRules(settings: GitHubSettings) {
        viewModelScope.launch {
            val normalized = settings.normalized()
            saveGitHubSettings(normalized)
            val before = withContext(Dispatchers.IO) { rulesRepository.loadLastKnownGood() }
            val after = withContext(Dispatchers.IO) { rulesRepository.refreshFromGitHub() }
            _uiState.value = _uiState.value.copy(currentRulesText = currentRulesText(after))
            showGitHubMessage(
                if (after != before) "Latest rules downloaded and applied" else "Rules already up to date",
            )
        }
    }

    fun refreshCurrentRules() {
        viewModelScope.launch {
            val rulesText = withContext(Dispatchers.IO) { currentRulesText() }
            _uiState.value = _uiState.value.copy(
                currentRulesText = rulesText,
                settingsMessage = "Current rules refreshed",
            )
        }
    }

    fun refreshPerformance() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { historyRepository.getRecentPerformance() }
            _uiState.value = _uiState.value.copy(performanceRows = rows)
        }
    }

    fun uploadLatestReport(settings: GitHubSettings) {
        viewModelScope.launch {
            val normalized = settings.normalized()
            saveGitHubSettings(normalized)
            if (normalized.token.isBlank()) {
                showGitHubMessage("GitHub token is missing")
                return@launch
            }
            val uploaded = withContext(Dispatchers.IO) {
                reportRepository.generateLatestReport(rulesRepository.loadLastKnownGood())
                reportRepository.uploadLatestReport()
            }
            refreshPerformance()
            showGitHubMessage(if (uploaded) "Latest report uploaded" else "Latest report upload failed")
        }
    }

    fun openInstallPermissionSettings() {
        appUpdateRepository.openUnknownAppSourcesSettings()
        _uiState.value = _uiState.value.copy(
            settingsMessage = "설정에서 이 앱의 APK 설치 허용을 켠 뒤 돌아와서 Download and install latest APK를 누르세요.",
        )
    }

    fun downloadAndInstallLatestApk(settings: GitHubSettings) {
        viewModelScope.launch {
            val normalized = settings.normalized()
            saveGitHubSettings(normalized)
            if (normalized.token.isBlank()) {
                showGitHubMessage("GitHub token is missing")
                return@launch
            }
            if (!appUpdateRepository.canRequestPackageInstalls()) {
                _uiState.value = _uiState.value.copy(
                    settingsMessage = "APK 설치 권한이 꺼져 있습니다. Open install permission settings를 먼저 누르세요.",
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(settingsMessage = "최신 APK 다운로드 중...")
            val result = withContext(Dispatchers.IO) {
                runCatching { appUpdateRepository.downloadLatestReleaseApk(normalized) }
            }
            result.fold(
                onSuccess = { apkFile ->
                    _uiState.value = _uiState.value.copy(settingsMessage = "APK 다운로드 완료. 설치 화면을 엽니다.")
                    appUpdateRepository.openApkInstaller(apkFile)
                },
                onFailure = { error ->
                    showGitHubMessage("APK update failed: ${error.message ?: error::class.java.simpleName}")
                },
            )
        }
    }

    private fun showGitHubMessage(message: String) {
        ScannerStateStore.setLastError(
            message.takeIf {
                it == "GitHub token is missing" ||
                    it.contains("HTTP ") ||
                    it.contains("failed", ignoreCase = true)
            },
        )
        _uiState.value = _uiState.value.copy(settingsMessage = message)
    }

    private fun gitHubFailureMessage(prefix: String, error: Throwable): String {
        return if (error is GitHubSyncException) {
            "$prefix at ${error.syncPoint}: HTTP ${error.statusCode}; endpoint=${error.endpoint}; branch=${error.branch}; path=${error.path}"
        } else {
            "$prefix: ${error::class.java.simpleName}"
        }
    }

    private fun currentRulesText(): String = currentRulesText(rulesRepository.loadLastKnownGood())

    private fun currentRulesText(rules: com.cryptotradecoach.data.StrategyRules): String {
        return rules.toJson().toString(2)
    }
}