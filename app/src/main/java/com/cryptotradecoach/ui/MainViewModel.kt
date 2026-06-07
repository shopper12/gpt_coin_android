package com.cryptotradecoach.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cryptotradecoach.data.AppUpdateRepository
import com.cryptotradecoach.data.Candle
import com.cryptotradecoach.data.GitHubSyncClient
import com.cryptotradecoach.data.GitHubSyncException
import com.cryptotradecoach.data.GitHubSettings
import com.cryptotradecoach.data.ScanDiagnostics
import com.cryptotradecoach.data.SettingsRepository
import com.cryptotradecoach.data.SignalHistoryRepository
import com.cryptotradecoach.data.StrategyReportRepository
import com.cryptotradecoach.data.StrategyRules
import com.cryptotradecoach.data.StrategyRulesRepository
import com.cryptotradecoach.data.StrategyScanLog
import com.cryptotradecoach.data.StrategyScanResult
import com.cryptotradecoach.data.StrategyStatus
import com.cryptotradecoach.data.StrategyType
import com.cryptotradecoach.data.TradeStrategy
import com.cryptotradecoach.data.UpbitMarketDataSource
import com.cryptotradecoach.data.local.AppDatabase
import com.cryptotradecoach.data.local.EvolutionLogEntity
import com.cryptotradecoach.data.local.StrategyHistoryEntity
import com.cryptotradecoach.data.local.StrategyPerformanceEntity
import com.cryptotradecoach.domain.BacktestEngine
import com.cryptotradecoach.domain.BacktestResult
import com.cryptotradecoach.domain.BtcRegime
import com.cryptotradecoach.domain.SignalEngine
import com.cryptotradecoach.service.ScannerStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

enum class ChartTimeframe(val label: String, val upbitMinuteUnit: Int?, val count: Int) {
    ONE_MINUTE("1분", 1, 120),
    FIVE_MINUTE("5분", 5, 120),
    FIFTEEN_MINUTE("15분", 15, 120),
    ONE_HOUR("1시간", 60, 120),
    FOUR_HOUR("4시간", 240, 120),
    ONE_DAY("일봉", null, 120),
}

data class StrategyChartSnapshot(
    val strategy: TradeStrategy,
    val timeframe: ChartTimeframe,
    val candles: List<Candle>,
    val performance: StrategyPerformanceEntity?,
    val message: String,
    val loadedAt: Long = System.currentTimeMillis(),
)

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
    val selectedChartTimeframe: ChartTimeframe = ChartTimeframe.FIVE_MINUTE,
    val strategyChart: StrategyChartSnapshot? = null,
    val chartMessage: String? = null,
    val scanDiagnostics: ScanDiagnostics = ScanDiagnostics(),
    val lastScanAt: Long? = null,
    val scanIntervalMs: Long = ScannerStateStore.DEFAULT_SCAN_INTERVAL_MS,
    val maxDisplayCount: Int = ScannerStateStore.DEFAULT_MAX_DISPLAY_COUNT,
    val minimumScore: Double = ScannerStateStore.DEFAULT_MINIMUM_SCORE,
    val currentBtcRegime: BtcRegime = BtcRegime.NEUTRAL,
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
        viewModelScope.launch { ScannerStateStore.currentBtcRegime.collect { _uiState.value = _uiState.value.copy(currentBtcRegime = it) } }
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
            val symbol = normalizeMarket(rawSymbol)
            if (symbol.isBlank() || symbol == "KRW-") {
                _uiState.value = _uiState.value.copy(manualMessage = "종목을 입력하세요. 예: XRP 또는 KRW-XRP")
                return@launch
            }
            _uiState.value = _uiState.value.copy(manualMessage = "분석 중: $symbol", chartMessage = null)

            val result = withTimeoutOrNull(MANUAL_ANALYSIS_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val rules = rulesRepository.loadLastKnownGood()
                        val candidate = manualDataSource.fetchManualMarketCandidateFast(symbol, rules)
                            ?: return@runCatching ManualAnalyzeResult(null, manualDataSource.lastError ?: "후보 생성 실패: $symbol")
                        val scanResult = manualEngine.scan(candidates = listOf(candidate), rules = rules, maxResults = 1)
                        val active = scanResult.activeStrategies.firstOrNull()?.copy(rank = 1)
                        val log = scanResult.scanLogs.firstOrNull()
                        val strategy = active ?: log?.toWatchOnlyStrategy(now = System.currentTimeMillis())
                        val message = when {
                            active != null -> "ACTIVE 분석 완료: ${active.symbol} score=${active.score.one()}"
                            log != null -> "관찰 기록: ${log.market} score=${log.score.one()} status=${log.strategyStatus} reason=${log.missedReason ?: log.strategyStatus.name}"
                            else -> "ACTIVE 전략 조건이 없습니다. 관찰 로그도 생성되지 않았습니다."
                        }
                        historyRepository.recordManualSearch(symbol, strategy, message)
                        ManualAnalyzeResult(strategy, message)
                    }
                }
            }

            if (result == null) {
                val message = "수동 분석 시간 초과: $symbol. 네트워크/업비트 응답 지연입니다. 잠시 후 다시 시도하세요."
                val latestHistory = withContext(Dispatchers.IO) {
                    historyRepository.recordManualSearch(symbol, null, message)
                    historyRepository.getHistoryBySymbol()
                }
                ScannerStateStore.pushScanResult(historyRepository.getActiveStrategies(), latestHistory, _uiState.value.scanDiagnostics, getApplication())
                _uiState.value = _uiState.value.copy(manualMessage = message, historyBySymbol = latestHistory)
                return@launch
            }

            result.fold(
                onSuccess = { analyzed ->
                    val latestHistory = withContext(Dispatchers.IO) { historyRepository.getHistoryBySymbol() }
                    ScannerStateStore.pushScanResult(historyRepository.getActiveStrategies(), latestHistory, _uiState.value.scanDiagnostics, getApplication())
                    _uiState.value = _uiState.value.copy(manualStrategy = analyzed.strategy, manualMessage = analyzed.message, historyBySymbol = latestHistory)
                },
                onFailure = { error ->
                    val message = "수동 분석 실패: ${error.message ?: error::class.java.simpleName}"
                    val latestHistory = withContext(Dispatchers.IO) {
                        historyRepository.recordManualSearch(symbol, null, message)
                        historyRepository.getHistoryBySymbol()
                    }
                    ScannerStateStore.pushScanResult(historyRepository.getActiveStrategies(), latestHistory, _uiState.value.scanDiagnostics, getApplication())
                    _uiState.value = _uiState.value.copy(manualMessage = message, historyBySymbol = latestHistory)
                },
            )
        }
    }

    fun saveManualStrategy() {
        val strategy = _uiState.value.manualStrategy ?: run {
            _uiState.value = _uiState.value.copy(manualMessage = "저장할 분석 결과가 없습니다.")
            return
        }
        viewModelScope.launch {
            val latestHistory = withContext(Dispatchers.IO) {
                if (strategy.status == StrategyStatus.ACTIVE) {
                    historyRepository.saveStrategyScanResult(StrategyScanResult(activeStrategies = listOf(strategy), scanLogs = emptyList()), mapOf(strategy.symbol to strategy.entryHigh))
                    historyRepository.recordManualSearch(strategy.symbol, strategy, "ACTIVE 저장 완료: ${strategy.symbol}")
                } else {
                    historyRepository.recordManualSearch(strategy.symbol, strategy, "관찰 기록 저장 완료: ${strategy.symbol}. ACTIVE가 아니므로 성과 추적에는 넣지 않았습니다.")
                }
                historyRepository.getHistoryBySymbol()
            }
            ScannerStateStore.pushScanResult(historyRepository.getActiveStrategies(), latestHistory, _uiState.value.scanDiagnostics, getApplication())
            refreshPerformance()
            _uiState.value = _uiState.value.copy(
                manualMessage = if (strategy.status == StrategyStatus.ACTIVE) "ACTIVE 저장 완료: ${strategy.symbol}" else "관찰 기록 저장 완료: ${strategy.symbol}",
                historyBySymbol = latestHistory,
            )
        }
    }

    fun loadStrategyChart(strategy: TradeStrategy) { loadStrategyChart(strategy, _uiState.value.selectedChartTimeframe) }
    fun selectChartTimeframe(timeframe: ChartTimeframe) { val currentStrategy = _uiState.value.strategyChart?.strategy ?: _uiState.value.manualStrategy; _uiState.value = _uiState.value.copy(selectedChartTimeframe = timeframe); currentStrategy?.let { loadStrategyChart(it, timeframe) } }

    fun loadStrategyChart(strategy: TradeStrategy, timeframe: ChartTimeframe) {
        viewModelScope.launch {
            val loadingMessage = "차트 불러오는 중: ${strategy.symbol} · ${timeframe.label}"
            _uiState.value = _uiState.value.copy(selectedChartTimeframe = timeframe, strategyChart = null, chartMessage = loadingMessage)
            val result = withTimeoutOrNull(CHART_LOAD_TIMEOUT_MS) { withContext(Dispatchers.IO) { runCatching { loadCandles(strategy, timeframe) } } }
            if (result == null) { _uiState.value = _uiState.value.copy(chartMessage = "차트 로딩 시간 초과: ${strategy.symbol}"); return@launch }
            result.fold(
                onSuccess = { candles ->
                    val performance = withContext(Dispatchers.IO) { historyRepository.getRecentPerformance(windowMs = 7L * 24L * 60L * 60L * 1000L, limit = 1000).firstOrNull { it.strategyId == strategy.id } }
                    _uiState.value = _uiState.value.copy(strategyChart = StrategyChartSnapshot(strategy = strategy, timeframe = timeframe, candles = candles, performance = performance, message = "차트 로딩 완료: ${strategy.symbol} · ${timeframe.label} · ${candles.size} candles"), chartMessage = null)
                },
                onFailure = { error -> _uiState.value = _uiState.value.copy(strategyChart = null, chartMessage = "차트 로딩 실패: ${error.message ?: error::class.java.simpleName}") },
            )
        }
    }

    fun clearStrategyChart() { _uiState.value = _uiState.value.copy(strategyChart = null, chartMessage = null) }

    private fun loadCandles(strategy: TradeStrategy, timeframe: ChartTimeframe): List<Candle> {
        return if (timeframe.upbitMinuteUnit != null) manualDataSource.fetchMinuteCandles(strategy.symbol, timeframe.upbitMinuteUnit, timeframe.count) else fetchDayCandles(strategy.symbol, timeframe.count)
    }

    private fun fetchDayCandles(market: String, count: Int): List<Candle> {
        val base = "https" + "://" + "api.upbit.com"
        val connection = (URL("$base/v1/candles/days?market=$market&count=$count").openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 4_000; readTimeout = 4_000 }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val arr = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            return (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Candle(market = market, unit = 1440, timestamp = obj.optLong("timestamp", 0L), open = obj.optDouble("opening_price", 0.0), high = obj.optDouble("high_price", 0.0), low = obj.optDouble("low_price", 0.0), close = obj.optDouble("trade_price", 0.0), volume = obj.optDouble("candle_acc_trade_volume", 0.0), tradePrice = obj.optDouble("candle_acc_trade_price", 0.0))
            }.sortedBy { it.timestamp }
        } finally { connection.disconnect() }
    }

    fun refreshPerformance() { viewModelScope.launch { _uiState.value = _uiState.value.copy(performanceRows = withContext(Dispatchers.IO) { historyRepository.getRecentPerformance(limit = 500) }) } }
    fun refreshBacktest() { viewModelScope.launch { val results = withContext(Dispatchers.IO) { BacktestEngine(db).runAll() }; ScannerStateStore.updateBacktestResults(results) } }
    fun refreshEvolutionLog() { viewModelScope.launch { _uiState.value = _uiState.value.copy(evolutionLog = withContext(Dispatchers.IO) { db.evolutionLogDao().getRecent() }) } }

    fun refreshCurrentRules() { _uiState.value = _uiState.value.copy(currentRulesText = currentRulesText(), settingsMessage = "현재 로컬 규칙을 다시 불러왔습니다.") }
    fun saveRulesText(text: String) { runCatching { rulesRepository.persistLocal(StrategyRules.fromJson(text)); _uiState.value = _uiState.value.copy(currentRulesText = text, settingsMessage = "규칙 저장 완료") }.onFailure { _uiState.value = _uiState.value.copy(settingsMessage = "규칙 저장 실패: ${it.message}") } }
    fun downloadLatestRules(settings: GitHubSettings = settingsRepository.load()) { viewModelScope.launch { val result = withContext(Dispatchers.IO) { rulesRepository.refreshFromGitHub(settings) }; _uiState.value = _uiState.value.copy(currentRulesText = result.toJson().toString(2), settingsMessage = "GitHub 규칙 다운로드 완료") } }
    fun saveGitHubSettings(settings: GitHubSettings) { settingsRepository.save(settings); _uiState.value = _uiState.value.copy(gitHubSettings = settingsRepository.load(), settingsMessage = "GitHub 설정 저장 완료") }
    fun testGitHubSettings(settings: GitHubSettings) { viewModelScope.launch { _uiState.value = _uiState.value.copy(settingsMessage = "GitHub 연결 테스트 중..."); val message = withContext(Dispatchers.IO) { runCatching { gitHubSyncClient.fetchText(settings.normalized()); "GitHub 연결 성공" }.getOrElse { error -> when (error) { is GitHubSyncException -> "GitHub 연결 실패: ${error.message}" else -> "GitHub 연결 실패: ${error.message ?: error::class.java.simpleName}" } } }; _uiState.value = _uiState.value.copy(settingsMessage = message) } }
    fun uploadLatestReport(settings: GitHubSettings) { viewModelScope.launch { _uiState.value = _uiState.value.copy(settingsMessage = "리포트 업로드 중..."); val ok = withContext(Dispatchers.IO) { reportRepository.uploadLatestReport() }; _uiState.value = _uiState.value.copy(settingsMessage = if (ok) "리포트 업로드 완료" else "리포트 업로드 실패") } }
    fun openInstallPermissionSettings() { appUpdateRepository.openInstallPermissionSettings() }
    fun downloadAndInstallLatestApk(settings: GitHubSettings) { viewModelScope.launch { _uiState.value = _uiState.value.copy(settingsMessage = "APK 다운로드 확인 중..."); val message = withContext(Dispatchers.IO) { appUpdateRepository.downloadAndInstallLatest(settings.normalized()) }; _uiState.value = _uiState.value.copy(settingsMessage = message) } }

    private fun StrategyScanLog.toWatchOnlyStrategy(now: Long): TradeStrategy {
        val price = currentPrice.takeIf { it > 0.0 } ?: entryPrice
        val stop = stopLossPrice.takeIf { it > 0.0 } ?: price * 0.986
        val t1 = target1.takeIf { it > 0.0 } ?: price * 1.012
        val t2 = target2.takeIf { it > 0.0 } ?: price * 1.024
        val riskPct = abs(percentChange(price, stop)).coerceAtLeast(0.32)
        val expectedReturn = abs(percentChange(price, t2)).coerceAtLeast(0.90)
        return TradeStrategy(
            id = "$market-MANUAL_WATCH",
            symbol = market,
            strategyType = strategyType.takeIf { it != StrategyType.BTC_SHORT_REGIME || market == "KRW-BTC" } ?: StrategyType.WATCH_ONLY,
            status = StrategyStatus.WATCH_ONLY,
            score = score,
            rank = 1,
            entryLow = price * 0.999,
            entryHigh = price * 1.001,
            stopLoss = stop,
            target1 = t1,
            target2 = t2,
            trailingStop = trailingStop.takeIf { it > 0.0 } ?: price * 0.995,
            expectedReturnPct = expectedReturn,
            riskPct = riskPct,
            riskRewardRatio = expectedReturn / riskPct,
            componentScores = componentScores,
            rankByChangeRate = rankByChangeRate,
            rankByTradeValue = rankByTradeValue,
            changeRate24h = changeRate24h,
            changeRate30m = changeRate30m,
            changeRate5m = changeRate5m,
            volumeAcceleration = volumeAcceleration,
            reason = "MANUAL WATCH_ONLY; reason=${missedReason ?: strategyStatus.name}; score=${score.one()}; $componentScores",
            invalidationReason = missedReason,
            createdAt = now,
            updatedAt = now,
            validUntil = now + 240L * 60L * 1000L,
        )
    }

    private fun normalizeMarket(raw: String): String {
        val upper = raw.trim().uppercase().replace("/", "-")
        return when {
            upper.isBlank() -> ""
            upper.startsWith("KRW-") -> upper
            else -> "KRW-$upper"
        }
    }

    private fun percentChange(from: Double, to: Double): Double {
        if (from <= 0.0) return 0.0
        return ((to - from) / from) * 100.0
    }

    private fun Double.one(): String = String.format(java.util.Locale.US, "%.1f", this)

    private fun currentRulesText(): String = rulesRepository.loadLastKnownGood().toJson().toString(2)

    companion object {
        private const val MANUAL_ANALYSIS_TIMEOUT_MS = 9_000L
        private const val CHART_LOAD_TIMEOUT_MS = 8_000L
    }
}

private data class ManualAnalyzeResult(val strategy: TradeStrategy?, val message: String)
