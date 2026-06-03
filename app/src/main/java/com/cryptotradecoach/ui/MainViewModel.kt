package com.cryptotradecoach.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cryptotradecoach.data.AppUpdateRepository
import com.cryptotradecoach.data.GitHubSyncClient
import com.cryptotradecoach.data.GitHubSyncException
import com.cryptotradecoach.data.GitHubSettings
import com.cryptotradecoach.data.ScanDiagnostics
import com.cryptotradecoach.data.SettingsRepository
import com.cryptotradecoach.data.SignalHistoryRepository
import com.cryptotradecoach.data.StrategyReportRepository
import com.cryptotradecoach.data.StrategyRules
import com.cryptotradecoach.data.StrategyRulesRepository
import com.cryptotradecoach.data.StrategyScanResult
import com.cryptotradecoach.data.TradeStrategy
import com.cryptotradecoach.data.UpbitMarketDataSource
import com.cryptotradecoach.data.local.AppDatabase
import com.cryptotradecoach.data.local.EvolutionLogEntity
import com.cryptotradecoach.data.local.StrategyHistoryEntity
import com.cryptotradecoach.data.local.StrategyPerformanceEntity
import com.cryptotradecoach.domain.BacktestEngine
import com.cryptotradecoach.domain.BacktestResult
import com.cryptotradecoach.domain.SignalEngine
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
    val backtestResults: List<BacktestResult> = emptyList(),
    val evolutionLog: List<EvolutionLogEntity> = emptyList(),
    val lastEvolvedAt: Long? = null,
    val manualStrategy: TradeStrategy? = null,
    val manualMessage: String? = null,
    val scanDiagnostics: ScanDiagnostics = ScanDiagnostics(),
    val lastScanAt: Long? = null,
    val scanIntervalMs: Long = ScannerStateStore.DEFAULT_SCAN_INTERVAL_MS,
    val maxDisplayCount: Int = ScannerStateStore.DEFAULT_MAX_DISPLAY_COUNT,
    val minimumScore: Double = ScannerStateStore.DEFAULT_MINIMUM_SCORE,
    val gitHubSettings: GitHubSettings = GitHubSettings(),
    val settingsMessage: String? = null,
    val currentRulesText: String = "",
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val db = AppDatabase.getInstance(application)
    private val historyRepository = SignalHistoryRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)
    private val rulesRepository = StrategyRulesRepository.getInstance(application)
    private val reportRepository = StrategyReportRepository.getInstance(application)
    private val appUpdateRepository = AppUpdateRepository(application.applicationContext)
    private val manualDataSource = UpbitMarketDataSource()
    private val manualEngine = SignalEngine()
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
            refreshBacktest()
            refreshEvolutionLog()
        }
        viewModelScope.launch { ScannerStateStore.isRunning.collect { _uiState.value = _uiState.value.copy(isRunning = it) } }
        viewModelScope.launch { ScannerStateStore.activeStrategies.collect { _uiState.value = _uiState.value.copy(activeStrategies = it); refreshPerformance() } }
        viewModelScope.launch { ScannerStateStore.historyBySymbol.collect { _uiState.value = _uiState.value.copy(historyBySymbol = it) } }
        viewModelScope.launch { ScannerStateStore.scanDiagnostics.collect { _uiState.value = _uiState.value.copy(scanDiagnostics = it) } }
        viewModelScope.launch { ScannerStateStore.lastScanAt.collect { _uiState.value = _uiState.value.copy(lastScanAt = it); refreshPerformance() } }
        viewModelScope.launch { ScannerStateStore.scanIntervalMs.collect { _uiState.value = _uiState.value.copy(scanIntervalMs = it) } }
        viewModelScope.launch { ScannerStateStore.maxDisplayCount.collect { _uiState.value = _uiState.value.copy(maxDisplayCount = it) } }
        viewModelScope.launch { ScannerStateStore.minimumScore.collect { _uiState.value = _uiState.value.copy(minimumScore = it) } }
        viewModelScope.launch { ScannerStateStore.backtestResults.collect { _uiState.value = _uiState.value.copy(backtestResults = it) } }
        viewModelScope.launch {
            ScannerStateStore.evolutionLog.collect { rows ->
                _uiState.value = _uiState.value.copy(evolutionLog = rows, lastEvolvedAt = rows.maxOfOrNull { it.changedAt })
            }
        }
    }

    fun setScanInterval(intervalMs: Long) { ScannerStateStore.setScanInterval(intervalMs) }
    fun setMaxDisplayCount(count: Int) { ScannerStateStore.setMaxDisplayCount(count) }
    fun setMinimumScore(score: Double) { ScannerStateStore.setMinimumScore(score) }

    fun analyzeManualSymbol(rawSymbol: String) {
        viewModelScope.launch {
            val symbol = rawSymbol.trim().uppercase()
            if (symbol.isBlank()) {
                _uiState.value = _uiState.value.copy(manualMessage = "종목을 입력하세요.", manualStrategy = null)
                return@launch
            }
            _uiState.value = _uiState.value.copy(manualMessage = "분석 중: $symbol", manualStrategy = null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rules = rulesRepository.refreshFromGitHub()
                    val candidate = manualDataSource.fetchManualMarketCandidate(symbol)
                        ?: return@runCatching ManualAnalyzeResult(null, manualDataSource.lastError ?: "후보 생성 실패")
                    val scanResult = manualEngine.scan(
                        candidates = listOf(candidate),
                        rules = rules,
                        maxResults = 1,
                    )
                    val strategy = scanResult.activeStrategies.firstOrNull()?.copy(rank = 1)
                    val message = strategy?.let { "분석 완료: ${it.symbol}" }
                        ?: scanResult.scanLogs.firstOrNull()?.let { log -> "ACTIVE 전략 조건 없음: ${log.market} score=${String.format(java.util.Locale.US, "%.1f", log.score)} reason=${log.missedReason ?: log.strategyStatus.name}" }
                        ?: "ACTIVE 전략 조건이 없습니다. 관찰만 권장합니다."
                    historyRepository.recordManualSearch(symbol, strategy, message)
                    ManualAnalyzeResult(strategy, message)
                }
            }
            result.fold(
                onSuccess = { analyzed ->
                    val latestHistory = withContext(Dispatchers.IO) { historyRepository.getHistoryBySymbol() }
                    ScannerStateStore.pushScanResult(historyRepository.getActiveStrategies(), latestHistory, _uiState.value.scanDiagnostics, getApplication())
                    _uiState.value = _uiState.value.copy(manualStrategy = analyzed.strategy, manualMessage = analyzed.message, historyBySymbol = latestHistory)
                },
                onFailure = { error ->
                    val message = "수동 분석 실패: ${error.message ?: error::class.java.simpleName}"
                    withContext(Dispatchers.IO) { historyRepository.recordManualSearch(symbol, null, message) }
                    _uiState.value = _uiState.value.copy(manualStrategy = null, manualMessage = message)
                },
            )
        }
    }

    fun saveManualStrategy() {
        val strategy = _uiState.value.manualStrategy ?: run {
            _uiState.value = _uiState.value.copy(manualMessage = "저장할 전략이 없습니다.")
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                historyRepository.saveStrategyScanResult(StrategyScanResult(activeStrategies = listOf(strategy), scanLogs = emptyList()), mapOf(strategy.symbol to strategy.entryHigh))
            }
            ScannerStateStore.pushScanResult(historyRepository.getActiveStrategies(), historyRepository.getHistoryBySymbol(), _uiState.value.scanDiagnostics, getApplication())
            refreshPerformance()
            _uiState.value = _uiState.value.copy(manualMessage = "저장 완료: ${strategy.symbol}")
        }
    }

    fun saveGitHubSettings(settings: GitHubSettings) {
        val normalized = settings.normalized()
        val saved = settingsRepository.save(normalized)
        _uiState.value = _uiState.value.copy(gitHubSettings = normalized, settingsMessage = if (saved) "Settings saved" else "Settings save failed")
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
                onFailure = { error -> showGitHubMessage(gitHubFailureMessage("GitHub settings test failed", error)); return@launch },
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
            showGitHubMessage(if (after != before) "Latest rules downloaded and applied" else "Rules already up to date")
        }
    }

    fun refreshCurrentRules() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(currentRulesText = withContext(Dispatchers.IO) { currentRulesText() }, settingsMessage = "Current rules refreshed")
        }
    }

    fun saveRulesText(rawJson: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { val rules = StrategyRules.fromJson(rawJson); rulesRepository.persistLocal(rules); rules.toJson().toString(2) } }
            result.fold(
                onSuccess = { _uiState.value = _uiState.value.copy(currentRulesText = it, settingsMessage = "Rules saved locally. 다음 스캔부터 적용됩니다.") },
                onFailure = { _uiState.value = _uiState.value.copy(settingsMessage = "Rules save failed: ${it.message ?: it::class.java.simpleName}") },
            )
        }
    }

    fun refreshPerformance() { viewModelScope.launch { _uiState.value = _uiState.value.copy(performanceRows = withContext(Dispatchers.IO) { historyRepository.getRecentPerformance() }) } }
    fun refreshBacktest() { viewModelScope.launch { val results = withContext(Dispatchers.IO) { BacktestEngine(db).runAll() }; ScannerStateStore.updateBacktestResults(results); _uiState.value = _uiState.value.copy(backtestResults = results) } }
    fun refreshEvolutionLog() { viewModelScope.launch { val rows = withContext(Dispatchers.IO) { db.evolutionLogDao().getRecent() }; ScannerStateStore.updateEvolutionLog(rows); _uiState.value = _uiState.value.copy(evolutionLog = rows, lastEvolvedAt = rows.maxOfOrNull { it.changedAt }) } }

    fun uploadLatestReport(settings: GitHubSettings) {
        viewModelScope.launch {
            val normalized = settings.normalized()
            saveGitHubSettings(normalized)
            if (normalized.token.isBlank()) {
                showGitHubMessage("GitHub token is missing")
                return@launch
            }
            val uploaded = withContext(Dispatchers.IO) { reportRepository.generateLatestReport(rulesRepository.loadLastKnownGood()); reportRepository.uploadLatestReport() }
            refreshPerformance()
            refreshBacktest()
            showGitHubMessage(if (uploaded) "Latest report uploaded" else "Latest report upload failed")
        }
    }

    fun openInstallPermissionSettings() {
        appUpdateRepository.openUnknownAppSourcesSettings()
        _uiState.value = _uiState.value.copy(settingsMessage = "설정에서 이 앱의 APK 설치 허용을 켠 뒤 돌아와서 Download and install latest APK를 누르세요.")
    }

    fun downloadAndInstallLatestApk(settings: GitHubSettings) {
        viewModelScope.launch {
            val normalized = settings.normalized()
            saveGitHubSettings(normalized)
            if (!appUpdateRepository.canRequestPackageInstalls()) {
                _uiState.value = _uiState.value.copy(settingsMessage = "APK 설치 권한이 꺼져 있습니다. Open install permission settings를 먼저 누르세요.")
                return@launch
            }
            _uiState.value = _uiState.value.copy(settingsMessage = "최신 APK 다운로드 중...")
            val result = withContext(Dispatchers.IO) { runCatching { appUpdateRepository.downloadLatestReleaseApk(normalized) } }
            result.fold(
                onSuccess = { apkFile -> _uiState.value = _uiState.value.copy(settingsMessage = "APK 다운로드 완료. 설치 화면을 엽니다: ${apkFile.name}"); appUpdateRepository.openApkInstaller(apkFile) },
                onFailure = { error -> showGitHubMessage("APK update failed: ${error.message ?: error::class.java.simpleName}") },
            )
        }
    }

    private fun showGitHubMessage(message: String) {
        ScannerStateStore.setLastError(message.takeIf { it == "GitHub token is missing" || it.contains("HTTP ") || it.contains("failed", ignoreCase = true) })
        _uiState.value = _uiState.value.copy(settingsMessage = message)
    }

    private fun gitHubFailureMessage(prefix: String, error: Throwable): String {
        return if (error is GitHubSyncException) "$prefix at ${error.syncPoint}: HTTP ${error.statusCode}; endpoint=${error.endpoint}; branch=${error.branch}; path=${error.path}" else "$prefix: ${error::class.java.simpleName}"
    }

    private fun currentRulesText(): String = currentRulesText(rulesRepository.loadLastKnownGood())
    private fun currentRulesText(rules: StrategyRules): String = rules.toJson().toString(2)
    private data class ManualAnalyzeResult(val strategy: TradeStrategy?, val message: String)
}
