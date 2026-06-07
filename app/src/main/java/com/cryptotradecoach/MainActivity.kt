package com.cryptotradecoach

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cryptotradecoach.data.Candle
import com.cryptotradecoach.data.GitHubSettings
import com.cryptotradecoach.data.ScanDiagnostics
import com.cryptotradecoach.data.StrategyRules
import com.cryptotradecoach.data.StrategyStatus
import com.cryptotradecoach.data.StrategyType
import com.cryptotradecoach.data.TradeStrategy
import com.cryptotradecoach.data.local.EvolutionLogEntity
import com.cryptotradecoach.data.local.StrategyHistoryEntity
import com.cryptotradecoach.data.local.StrategyPerformanceEntity
import com.cryptotradecoach.domain.BacktestResult
import com.cryptotradecoach.service.CoinScannerService
import com.cryptotradecoach.service.ScannerStateStore
import com.cryptotradecoach.ui.ChartTimeframe
import com.cryptotradecoach.ui.MainViewModel
import com.cryptotradecoach.ui.StrategyChartSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            MaterialTheme {
                val state by viewModel.uiState.collectAsState()
                MainScreen(
                    activeStrategies = state.activeStrategies,
                    historyBySymbol = state.historyBySymbol,
                    performanceRows = state.performanceRows,
                    backtestResults = state.backtestResults,
                    evolutionLog = state.evolutionLog,
                    lastEvolvedAt = state.lastEvolvedAt,
                    manualStrategy = state.manualStrategy,
                    manualMessage = state.manualMessage,
                    selectedChartTimeframe = state.selectedChartTimeframe,
                    strategyChart = state.strategyChart,
                    chartMessage = state.chartMessage,
                    scanDiagnostics = state.scanDiagnostics,
                    isRunning = state.isRunning,
                    lastScanAt = state.lastScanAt,
                    scanIntervalMs = state.scanIntervalMs,
                    maxDisplayCount = state.maxDisplayCount,
                    minimumScore = state.minimumScore,
                    gitHubSettings = state.gitHubSettings,
                    settingsMessage = state.settingsMessage,
                    currentRulesText = state.currentRulesText,
                    onStart = { startScanner() },
                    onStop = { stopScanner() },
                    onIntervalSelected = viewModel::setScanInterval,
                    onMaxDisplayChanged = viewModel::setMaxDisplayCount,
                    onMinimumScoreChanged = viewModel::setMinimumScore,
                    onGitHubSettingsSaved = viewModel::saveGitHubSettings,
                    onGitHubSettingsTest = viewModel::testGitHubSettings,
                    onRulesDownload = viewModel::downloadLatestRules,
                    onRulesRefresh = viewModel::refreshCurrentRules,
                    onRulesSave = viewModel::saveRulesText,
                    onPerformanceRefresh = viewModel::refreshPerformance,
                    onBacktestRefresh = viewModel::refreshBacktest,
                    onEvolutionRefresh = viewModel::refreshEvolutionLog,
                    onManualAnalyze = viewModel::analyzeManualSymbol,
                    onManualSave = viewModel::saveManualStrategy,
                    onStrategyChart = viewModel::loadStrategyChart,
                    onChartTimeframeSelected = viewModel::selectChartTimeframe,
                    onClearChart = viewModel::clearStrategyChart,
                    onReportUpload = viewModel::uploadLatestReport,
                    onOpenInstallPermissionSettings = viewModel::openInstallPermissionSettings,
                    onDownloadAndInstallLatestApk = viewModel::downloadAndInstallLatestApk,
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startScanner() {
        val intent = Intent(this, CoinScannerService::class.java).apply { action = CoinScannerService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopScanner() {
        startService(Intent(this, CoinScannerService::class.java).apply { action = CoinScannerService.ACTION_STOP })
    }
}

@Composable
private fun MainScreen(
    activeStrategies: List<TradeStrategy>,
    historyBySymbol: Map<String, List<StrategyHistoryEntity>>,
    performanceRows: List<StrategyPerformanceEntity>,
    backtestResults: List<BacktestResult>,
    evolutionLog: List<EvolutionLogEntity>,
    lastEvolvedAt: Long?,
    manualStrategy: TradeStrategy?,
    manualMessage: String?,
    selectedChartTimeframe: ChartTimeframe,
    strategyChart: StrategyChartSnapshot?,
    chartMessage: String?,
    scanDiagnostics: ScanDiagnostics,
    isRunning: Boolean,
    lastScanAt: Long?,
    scanIntervalMs: Long,
    maxDisplayCount: Int,
    minimumScore: Double,
    gitHubSettings: GitHubSettings,
    settingsMessage: String?,
    currentRulesText: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onIntervalSelected: (Long) -> Unit,
    onMaxDisplayChanged: (Int) -> Unit,
    onMinimumScoreChanged: (Double) -> Unit,
    onGitHubSettingsSaved: (GitHubSettings) -> Unit,
    onGitHubSettingsTest: (GitHubSettings) -> Unit,
    onRulesDownload: (GitHubSettings) -> Unit,
    onRulesRefresh: () -> Unit,
    onRulesSave: (String) -> Unit,
    onPerformanceRefresh: () -> Unit,
    onBacktestRefresh: () -> Unit,
    onEvolutionRefresh: () -> Unit,
    onManualAnalyze: (String) -> Unit,
    onManualSave: () -> Unit,
    onStrategyChart: (TradeStrategy) -> Unit,
    onChartTimeframeSelected: (ChartTimeframe) -> Unit,
    onClearChart: () -> Unit,
    onReportUpload: (GitHubSettings) -> Unit,
    onOpenInstallPermissionSettings: () -> Unit,
    onDownloadAndInstallLatestApk: (GitHubSettings) -> Unit,
) {
    val tabs = listOf("Current", "Search", "History", "Rules", "Settings")
    var selectedTab by remember { mutableIntStateOf(0) }
    val openChart: (TradeStrategy) -> Unit = { strategy -> selectedTab = 1; onStrategyChart(strategy) }
    Column(modifier = Modifier.fillMaxSize()) {
        Text("Crypto Trade Coach", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp))
        TabRow(selectedTabIndex = selectedTab) { tabs.forEachIndexed { index, title -> Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) }) } }
        when (selectedTab) {
            0 -> CurrentStrategiesTab(activeStrategies, scanDiagnostics, lastScanAt, minimumScore, chartMessage, openChart)
            1 -> ManualSearchTab(manualStrategy, manualMessage, selectedChartTimeframe, strategyChart, chartMessage, onManualAnalyze, onManualSave, openChart, onChartTimeframeSelected, onClearChart)
            2 -> StrategyHistoryTab(historyBySymbol, performanceRows, backtestResults, evolutionLog, lastEvolvedAt, onPerformanceRefresh, onBacktestRefresh, onEvolutionRefresh, openChart)
            3 -> RulesTab(currentRulesText, settingsMessage, onRulesRefresh, { onRulesDownload(gitHubSettings) }, onRulesSave)
            4 -> SettingsTab(isRunning, scanIntervalMs, maxDisplayCount, minimumScore, gitHubSettings, settingsMessage, onStart, onStop, onIntervalSelected, onMaxDisplayChanged, onMinimumScoreChanged, onGitHubSettingsSaved, onGitHubSettingsTest, onRulesDownload, onReportUpload, onOpenInstallPermissionSettings, onDownloadAndInstallLatestApk)
        }
    }
}

@Composable
private fun CurrentStrategiesTab(activeStrategies: List<TradeStrategy>, scanDiagnostics: ScanDiagnostics, lastScanAt: Long?, minimumScore: Double, chartMessage: String?, onStrategyChart: (TradeStrategy) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Last scan: ${lastScanAt?.toTimeText() ?: "-"}") }
        chartMessage?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
        item { CurrentStrategySummaryCard(activeStrategies, scanDiagnostics, minimumScore) }
        item { DiagnosticsCard(scanDiagnostics) }
        if (activeStrategies.isEmpty()) item { EmptyCard("No ACTIVE strategy. Settings에서 Minimum score와 Rejections를 확인하세요.") } else items(activeStrategies) { StrategyCard(it, onStrategyChart) }
    }
}

@Composable
private fun CurrentStrategySummaryCard(activeStrategies: List<TradeStrategy>, scanDiagnostics: ScanDiagnostics, minimumScore: Double) {
    val btcShort = activeStrategies.firstOrNull { it.strategyType.name == "BTC_SHORT_REGIME" }
    val prePumpCount = activeStrategies.count { it.strategyType.name == "PRE_PUMP_ROTATION" }
    val scoreTooLow = scanDiagnostics.rejectionSummary["SCORE_TOO_LOW"] ?: 0
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("현재 전략 요약", fontWeight = FontWeight.Bold)
            Text(if (btcShort != null) "비트코인 숏/위험회피 신호 감지" else "비트코인 ACTIVE 숏 신호 없음")
            Text("PRE_PUMP_ROTATION: ${prePumpCount}개 | ACTIVE: ${activeStrategies.size}개 | 후보: ${scanDiagnostics.candidateCount}개")
            Text("Minimum score ${minimumScore.roundToInt()} | SCORE_TOO_LOW $scoreTooLow")
        }
    }
}

@Composable
private fun DiagnosticsCard(scanDiagnostics: ScanDiagnostics) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Scan diagnostics", fontWeight = FontWeight.Bold)
            Text("Scanned: ${scanDiagnostics.scannedCount} | Candidates: ${scanDiagnostics.candidateCount} | Rejected: ${scanDiagnostics.rejectedCount}")
            Text("Last error: ${scanDiagnostics.lastError ?: "-"}")
            if (scanDiagnostics.rejectionSummary.isEmpty()) Text("Rejections: -") else scanDiagnostics.rejectionSummary.forEach { (reason, count) -> Text("$reason: $count") }
        }
    }
}

@Composable
private fun ManualSearchTab(
    manualStrategy: TradeStrategy?,
    manualMessage: String?,
    selectedTimeframe: ChartTimeframe,
    chartSnapshot: StrategyChartSnapshot?,
    chartMessage: String?,
    onManualAnalyze: (String) -> Unit,
    onManualSave: () -> Unit,
    onStrategyChart: (TradeStrategy) -> Unit,
    onTimeframeSelected: (ChartTimeframe) -> Unit,
    onClearChart: () -> Unit,
) {
    var symbol by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(manualStrategy?.id) { manualStrategy?.let(onStrategyChart) }
    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Search: 종목 분석 + 실시간 차트", fontWeight = FontWeight.Bold)
                    Text("아래 칸에 업비트 KRW 종목을 입력하세요. 예: XRP, KRW-XRP, BTC. 분석 결과와 전략선 차트를 같은 화면에 표시합니다.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = symbol, onValueChange = { symbol = it }, label = { Text("종목 입력: XRP 또는 KRW-XRP") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onManualAnalyze(symbol) }) { Text("Search") }
                        OutlinedButton(onClick = onManualSave, enabled = manualStrategy != null) { Text("Save") }
                        OutlinedButton(onClick = onClearChart, enabled = chartSnapshot != null) { Text("Clear chart") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("BTC", "ETH", "XRP", "SOL").forEach { quick -> FilterChip(selected = symbol.equals(quick, true) || symbol.equals("KRW-$quick", true), onClick = { symbol = quick; onManualAnalyze(quick) }, label = { Text(quick) }) }
                    }
                    manualMessage?.let { Text(it) }
                    chartMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        manualStrategy?.let { item { StrategyCard(it, onStrategyChart) } }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("차트 봉 선택", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ChartTimeframe.values().forEach { tf -> FilterChip(selected = selectedTimeframe == tf, onClick = { onTimeframeSelected(tf) }, label = { Text(tf.label) }) } }
                }
            }
        }
        if (chartSnapshot == null) item { EmptyCard("차트 없음. 위 종목 입력칸에 XRP 또는 KRW-XRP를 입력하고 Search를 누르세요.") } else item { StrategyChartCard(chartSnapshot) }
    }
}

@Composable
private fun StrategyChartCard(snapshot: StrategyChartSnapshot) {
    val strategy = snapshot.strategy
    val current = snapshot.candles.lastOrNull()?.close ?: 0.0
    val baseEntry = strategy.entryMid()
    val firstTime = snapshot.candles.firstOrNull()?.timestamp?.toTimeText() ?: "-"
    val lastTime = snapshot.candles.lastOrNull()?.timestamp?.toTimeText() ?: "-"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${strategy.symbol} · ${strategy.strategyType.name.toKoreanStrategyName()} · ${snapshot.timeframe.label} · ${snapshot.candles.firstOrNull()?.unit ?: "-"}unit", fontWeight = FontWeight.Bold)
            Text("범위 $firstTime ~ $lastTime · 캔들 ${snapshot.candles.size}개", style = MaterialTheme.typography.bodySmall)
            if (snapshot.candles.isEmpty()) Text("차트 데이터 없음") else {
                Box(modifier = Modifier.fillMaxWidth().height(300.dp)) { CandleOverlayChart(snapshot.candles, strategy) }
                Text("전략시점 ${strategy.createdAt.toTimeText()} | 현재 ${current.price()} (${baseEntry.toPct(current)})")
                Text("진입 ${strategy.entryLow.price()}~${strategy.entryHigh.price()} | 손절 ${strategy.stopLoss.price()} (${baseEntry.toPct(strategy.stopLoss)})")
                Text("목표1 ${strategy.target1.price()} (${baseEntry.toPct(strategy.target1)}) | 목표2 ${strategy.target2.price()} (${baseEntry.toPct(strategy.target2)})")
                snapshot.performance?.let { row ->
                    Text("결과: 최신 ${row.latestPrice.price()} | 5m ${row.return5m.percentOrDash()} / 15m ${row.return15m.percentOrDash()} / 30m ${row.return30m.percentOrDash()} / 60m ${row.return60m.percentOrDash()}")
                    Text("MFE ${row.mfePct.percent()} | MAE ${row.maePct.percent()} | Stop ${row.stopHit} | T1 ${row.target1Hit} | T2 ${row.target2Hit}", style = MaterialTheme.typography.bodySmall)
                } ?: Text("아직 성과 추적 결과 없음. 저장/ACTIVE 이후 체크포인트가 쌓이면 표시됩니다.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CandleOverlayChart(candles: List<Candle>, strategy: TradeStrategy) {
    val upColor = Color(0xFF2E7D32); val downColor = Color(0xFFC62828); val entryColor = Color(0xFF1565C0); val stopColor = Color(0xFFC62828); val targetColor = Color(0xFF6A1B9A); val strategyTimeColor = Color(0xFFEF6C00); val outline = MaterialTheme.colorScheme.outline
    Canvas(modifier = Modifier.fillMaxSize()) {
        val visible = candles.takeLast(90)
        if (visible.isEmpty()) return@Canvas
        val prices = visible.flatMap { listOf(it.high, it.low) } + listOf(strategy.entryLow, strategy.entryHigh, strategy.stopLoss, strategy.target1, strategy.target2)
        val minPrice = prices.minOrNull() ?: return@Canvas
        val maxPrice = prices.maxOrNull() ?: return@Canvas
        val range = (maxPrice - minPrice).takeIf { it > 0.0 } ?: return@Canvas
        val labelPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 24f; isAntiAlias = true }
        val bgPaint = Paint().apply { color = android.graphics.Color.argb(170, 0, 0, 0); isAntiAlias = true }
        fun y(price: Double): Float = (size.height - ((price - minPrice) / range).toFloat() * size.height).coerceIn(0f, size.height)
        val candleWidth = size.width / visible.size.toFloat()
        visible.forEachIndexed { index, candle ->
            val x = index * candleWidth + candleWidth / 2f
            val color = if (candle.close >= candle.open) upColor else downColor
            drawLine(color, Offset(x, y(candle.low)), Offset(x, y(candle.high)), strokeWidth = 2f, cap = StrokeCap.Round)
            drawLine(color, Offset(x, y(maxOf(candle.open, candle.close))), Offset(x, y(minOf(candle.open, candle.close))), strokeWidth = max(3f, candleWidth * 0.55f), cap = StrokeCap.Round)
        }
        fun labeledLine(price: Double, color: Color, text: String, width: Float) { val yy = y(price); drawLine(color, Offset(0f, yy), Offset(size.width, yy), strokeWidth = width); val labelX = 8f; val labelY = (yy - 6f).coerceIn(24f, size.height - 8f); drawContext.canvas.nativeCanvas.drawRect(labelX - 4f, labelY - 24f, labelX + labelPaint.measureText(text) + 8f, labelY + 6f, bgPaint); drawContext.canvas.nativeCanvas.drawText(text, labelX, labelY, labelPaint) }
        val base = strategy.entryMid()
        labeledLine(strategy.entryLow, entryColor, "진입L ${strategy.entryLow.price()} ${base.toPct(strategy.entryLow)}", 3f)
        labeledLine(strategy.entryHigh, entryColor, "진입H ${strategy.entryHigh.price()} ${base.toPct(strategy.entryHigh)}", 3f)
        labeledLine(strategy.stopLoss, stopColor, "손절 ${strategy.stopLoss.price()} ${base.toPct(strategy.stopLoss)}", 3f)
        labeledLine(strategy.target1, targetColor, "목표1 ${strategy.target1.price()} ${base.toPct(strategy.target1)}", 2f)
        labeledLine(strategy.target2, targetColor, "목표2 ${strategy.target2.price()} ${base.toPct(strategy.target2)}", 3f)
        val visibleStart = visible.first().timestamp
        val visibleEnd = visible.last().timestamp
        if (strategy.createdAt in visibleStart..visibleEnd) {
            val nearestIndex = visible.indices.minByOrNull { idx -> abs(visible[idx].timestamp - strategy.createdAt) } ?: 0
            val x = nearestIndex * candleWidth + candleWidth / 2f
            drawLine(strategyTimeColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
            val label = "전략 ${strategy.createdAt.toTimeText()}"
            drawContext.canvas.nativeCanvas.drawRect(x + 4f, 6f, x + labelPaint.measureText(label) + 14f, 36f, bgPaint)
            drawContext.canvas.nativeCanvas.drawText(label, x + 8f, 30f, labelPaint)
        } else {
            val label = "전략시점 ${strategy.createdAt.toTimeText()} 차트범위 밖"
            drawContext.canvas.nativeCanvas.drawRect(6f, 6f, labelPaint.measureText(label) + 16f, 36f, bgPaint)
            drawContext.canvas.nativeCanvas.drawText(label, 10f, 30f, labelPaint)
        }
        drawLine(outline, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 1f)
    }
}

@Composable
private fun StrategyHistoryTab(
    historyBySymbol: Map<String, List<StrategyHistoryEntity>>,
    performanceRows: List<StrategyPerformanceEntity>,
    backtestResults: List<BacktestResult>,
    evolutionLog: List<EvolutionLogEntity>,
    lastEvolvedAt: Long?,
    onPerformanceRefresh: () -> Unit,
    onBacktestRefresh: () -> Unit,
    onEvolutionRefresh: () -> Unit,
    onStrategyChart: (TradeStrategy) -> Unit,
) {
    var selectedSymbol by rememberSaveable { mutableStateOf("ALL") }
    var symbolInput by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf("ALL") }
    val allRows = historyBySymbol.values.flatten().sortedByDescending { it.createdAt }
    val symbols = historyBySymbol.keys.sorted()
    val symbolRows = if (selectedSymbol == "ALL") allRows else allRows.filter { it.symbol == selectedSymbol }
    val visibleRows = if (selectedCategory == "ALL") symbolRows else symbolRows.filter { it.historyCategoryKey() == selectedCategory }
    val visibleMap = visibleRows.groupBy { it.symbol }.toSortedMap()
    val rowsByType = performanceRows.groupBy { it.strategyType.name }
    fun applySymbolInput() { selectedSymbol = symbolInput.toHistorySymbolFilter() }
    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Text("History + Performance", fontWeight = FontWeight.Bold); OutlinedButton(onClick = { onPerformanceRefresh(); onBacktestRefresh(); onEvolutionRefresh() }) { Text("Refresh") } } }
        item { HistorySummaryCard(visibleRows.ifEmpty { allRows }) }
        item { EvolutionStatusCard(backtestResults, evolutionLog, lastEvolvedAt) }
        if (backtestResults.isNotEmpty()) { item { Text("Backtest ranking", fontWeight = FontWeight.Bold) }; items(backtestResults.sortedByDescending { it.expectancy }) { BacktestDetailCard(it) } }
        if (performanceRows.isNotEmpty()) { rowsByType.forEach { (strategyType, rows) -> item { PerformanceSummaryCard(strategyType, rows) } }; items(performanceRows.sortedByDescending { it.createdAt }.take(20)) { PerformanceCard(it) } } else item { EmptyCard("No performance data yet. 성과가 없으면 History에 검색/저장 기록만 표시됩니다.") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("History 선택 옵션", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("ALL" to "전체", "PROFIT" to "성공", "STOP" to "손절", "FAIL" to "실패/만료", "OPEN" to "진행").forEach { (key, label) -> FilterChip(selected = selectedCategory == key, onClick = { selectedCategory = key }, label = { Text(label) }) } }
                OutlinedTextField(value = symbolInput, onValueChange = { symbolInput = it }, label = { Text("종목 필터: XRP 또는 KRW-XRP") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { applySymbolInput() }) { Text("Apply") }; OutlinedButton(onClick = { selectedSymbol = "ALL"; symbolInput = "" }) { Text("Clear") }; FilterChip(selected = selectedSymbol == "ALL", onClick = { selectedSymbol = "ALL"; symbolInput = "" }, label = { Text("ALL") }) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { symbols.take(4).forEach { s -> FilterChip(selected = selectedSymbol == s, onClick = { selectedSymbol = s; symbolInput = s.removePrefix("KRW-") }, label = { Text(s.removePrefix("KRW-")) }) } }
                Text("현재 선택: 종목 ${if (selectedSymbol == "ALL") "전체" else selectedSymbol} / 분류 ${selectedCategory.toKoreanHistoryCategoryLabel()}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (visibleMap.isEmpty()) item { EmptyCard("No matching strategy history.") } else visibleMap.forEach { (symbol, rows) -> item { SymbolHistorySection(symbol, rows, onStrategyChart) } }
    }
}

@Composable
private fun HistorySummaryCard(rows: List<StrategyHistoryEntity>) {
    val stop = rows.count { it.historyCategoryKey() == "STOP" }
    val fail = rows.count { it.historyCategoryKey() == "FAIL" }
    val profit = rows.count { it.historyCategoryKey() == "PROFIT" }
    val reasons = rows.groupBy { it.message.ifBlank { it.eventType } }.entries.sortedByDescending { it.value.size }.take(3)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("History 요약", fontWeight = FontWeight.Bold)
            Text("성공/수익: $profit | 손절: $stop | 실패/만료: $fail | 전체: ${rows.size}")
            Text("이유", fontWeight = FontWeight.Bold)
            reasons.forEach { Text("- ${it.key.toKoreanHistoryReason()} (${it.value.size})", style = MaterialTheme.typography.bodySmall) }
            Text("보완: 손절 비중이 높으면 진입조건 완화가 아니라 entryHigh 추격 금지, ATR 손절폭, 거래량 점화 지속시간을 먼저 조정해야 합니다.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SymbolHistorySection(symbol: String, rows: List<StrategyHistoryEntity>, onStrategyChart: (TradeStrategy) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val sortedRows = rows.sortedByDescending { it.createdAt }
            Text("$symbol · ${rows.size}건", fontWeight = FontWeight.Bold)
            sortedRows.take(6).forEach { CompactHistoryLine(it, onStrategyChart) }
            if (rows.size > 6) Text("외 ${rows.size - 6}건", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CompactHistoryLine(history: StrategyHistoryEntity, onStrategyChart: (TradeStrategy) -> Unit) {
    val restored = history.toTradeStrategyOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("${history.createdAt.toTimeText()} ${history.eventType.toKoreanEventName()} · ${history.historyCategory()} · ${history.message.toKoreanHistoryReason()}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        Text((history.newSummary ?: history.oldSummary ?: "-").toCompactPlan().take(120), style = MaterialTheme.typography.bodySmall)
        Text("보완: ${history.improvementHint()}", style = MaterialTheme.typography.bodySmall)
        if (restored != null) OutlinedButton(onClick = { onStrategyChart(restored) }, modifier = Modifier.fillMaxWidth()) { Text("이 기록 Search 차트로 보기") }
    }
}

@Composable
private fun EvolutionStatusCard(backtestResults: List<BacktestResult>, evolutionLog: List<EvolutionLogEntity>, lastEvolvedAt: Long?) {
    var expanded by remember { mutableStateOf(false) }
    val eligibleCount = backtestResults.count { it.sampleSize >= 15 }
    Card(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("자가진화 엔진", fontWeight = FontWeight.Bold)
            Text(lastEvolvedAt?.let { "마지막 조정: ${it.toTimeText()}" } ?: "아직 진화 없음")
            Text("백테스트 전략 수: ${backtestResults.size} | 진화 가능 전략: $eligibleCount | 로그: ${evolutionLog.size}건")
            if (expanded) evolutionLog.take(3).forEach { log -> Text(log.changeLog.lines().take(6).joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun BacktestDetailCard(result: BacktestResult) { Card(modifier = Modifier.fillMaxWidth()) { Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(result.strategyType.toKoreanStrategyName(), fontWeight = FontWeight.Bold); Text("승률 ${(result.winRate * 100.0).one()}% | 기댓값 ${result.expectancy.percent()} | PF ${result.profitFactor.one()} | ${result.sampleSize}건"); Text("60m ${result.avgReturn60m.percent()} | 240m ${result.avgReturn240m.percent()} | MFE ${result.avgMfe.percent()} | MAE ${result.avgMae.percent()}", style = MaterialTheme.typography.bodySmall) } } }

@Composable
private fun PerformanceSummaryCard(strategyType: String, rows: List<StrategyPerformanceEntity>) { Card(modifier = Modifier.fillMaxWidth()) { Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { val completed = rows.count { it.isComplete }; Text(strategyType.toKoreanStrategyName(), fontWeight = FontWeight.Bold); Text("Signals: ${rows.size} | Complete: $completed"); Text("Avg 5m ${rows.mapNotNull { it.return5m }.averageOrNullText()} / 15m ${rows.mapNotNull { it.return15m }.averageOrNullText()} / 30m ${rows.mapNotNull { it.return30m }.averageOrNullText()} / 60m ${rows.mapNotNull { it.return60m }.averageOrNullText()}") } } }

@Composable
private fun PerformanceCard(row: StrategyPerformanceEntity) { Card(modifier = Modifier.fillMaxWidth()) { Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("${row.symbol} | ${row.strategyType.name.toKoreanStrategyName()} | ${if (row.isComplete) "COMPLETE" else "LOCKED"}", fontWeight = FontWeight.Bold); Text("Entry ${row.entryPrice.price()} | Latest ${row.latestPrice.price()} | Score ${row.score.one()}"); Text("Return 5m ${row.return5m.percentOrDash()} / 15m ${row.return15m.percentOrDash()} / 30m ${row.return30m.percentOrDash()} / 60m ${row.return60m.percentOrDash()}"); Text("MFE ${row.mfePct.percent()} | MAE ${row.maePct.percent()} | Stop ${row.stopHit} | T1 ${row.target1Hit} | T2 ${row.target2Hit}", style = MaterialTheme.typography.bodySmall) } } }

@Composable
private fun RulesTab(currentRulesText: String, settingsMessage: String?, onRulesRefresh: () -> Unit, onRulesDownload: () -> Unit, onRulesSave: (String) -> Unit) {
    val parsed = remember(currentRulesText) { runCatching { StrategyRules.fromJson(currentRulesText) }.getOrDefault(StrategyRules.DEFAULT) }
    var version by rememberSaveable(currentRulesText) { mutableStateOf(parsed.version) }
    var minimumScore by rememberSaveable(currentRulesText) { mutableStateOf(parsed.minimumScore.toString()) }
    var maxResults by rememberSaveable(currentRulesText) { mutableStateOf(parsed.maxResults.toString()) }
    var validForMinutes by rememberSaveable(currentRulesText) { mutableStateOf(parsed.validForMinutes.toString()) }
    var entryBandPct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.entryBandPct.toString()) }
    var maxCandleTargets by rememberSaveable(currentRulesText) { mutableStateOf(parsed.candidateSelection.maxCandleTargets.toString()) }
    var topTradeValueCount by rememberSaveable(currentRulesText) { mutableStateOf(parsed.candidateSelection.topTradeValueCount.toString()) }
    var topChangeRateCount by rememberSaveable(currentRulesText) { mutableStateOf(parsed.candidateSelection.topChangeRateCount.toString()) }
    var volumeBuildupCount by rememberSaveable(currentRulesText) { mutableStateOf(parsed.candidateSelection.volumeBuildupCount.toString()) }
    var quietAccumulationCount by rememberSaveable(currentRulesText) { mutableStateOf(parsed.candidateSelection.quietAccumulationCount.toString()) }
    var medianTradeValueMultiplier by rememberSaveable(currentRulesText) { mutableStateOf(parsed.candidateSelection.medianTradeValueMultiplier.toString()) }
    var minBuildupChangeRatePct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.candidateSelection.minBuildupChangeRatePct.toString()) }
    var maxBuildupChangeRatePct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.candidateSelection.maxBuildupChangeRatePct.toString()) }
    var maxQuietAbsChangeRatePct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.candidateSelection.maxQuietAbsChangeRatePct.toString()) }
    var compressionEnabled by rememberSaveable(currentRulesText) { mutableStateOf(parsed.compressionBreakout.enabled) }
    var rangeCompressionRatio by rememberSaveable(currentRulesText) { mutableStateOf(parsed.compressionBreakout.rangeCompressionRatio.toString()) }
    var maxDistanceTo15mHighPct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.compressionBreakout.maxDistanceTo15mHighPct.toString()) }
    var compressionMinVolumeAcceleration by rememberSaveable(currentRulesText) { mutableStateOf(parsed.compressionBreakout.minVolumeAcceleration.toString()) }
    var compressionMinFiveMinuteVolumeRatio by rememberSaveable(currentRulesText) { mutableStateOf(parsed.compressionBreakout.minFiveMinuteVolumeRatio.toString()) }
    var sweepEnabled by rememberSaveable(currentRulesText) { mutableStateOf(parsed.sweepReclaim.enabled) }
    var fiveMinuteLookback by rememberSaveable(currentRulesText) { mutableStateOf(parsed.sweepReclaim.fiveMinuteLookback.toString()) }
    var fifteenMinuteLookback by rememberSaveable(currentRulesText) { mutableStateOf(parsed.sweepReclaim.fifteenMinuteLookback.toString()) }
    var requireVolumeAboveAverage by rememberSaveable(currentRulesText) { mutableStateOf(parsed.sweepReclaim.requireVolumeAboveAverage) }
    var trendEnabled by rememberSaveable(currentRulesText) { mutableStateOf(parsed.trendPullback.enabled) }
    var higherTimeframeMaPeriod by rememberSaveable(currentRulesText) { mutableStateOf(parsed.trendPullback.higherTimeframeMaPeriod.toString()) }
    var fifteenMinuteMaPeriod by rememberSaveable(currentRulesText) { mutableStateOf(parsed.trendPullback.fifteenMinuteMaPeriod.toString()) }
    var min15mMaMultiplier by rememberSaveable(currentRulesText) { mutableStateOf(parsed.trendPullback.min15mMaMultiplier.toString()) }
    var minPriorLowMultiplier by rememberSaveable(currentRulesText) { mutableStateOf(parsed.trendPullback.minPriorLowMultiplier.toString()) }
    var pullbackLookback by rememberSaveable(currentRulesText) { mutableStateOf(parsed.trendPullback.pullbackLookback.toString()) }
    var reclaimLookback by rememberSaveable(currentRulesText) { mutableStateOf(parsed.trendPullback.reclaimLookback.toString()) }
    var bearEnabled by rememberSaveable(currentRulesText) { mutableStateOf(parsed.bearDecouplingBounce.enabled) }
    var btcWeakBelowPct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.bearDecouplingBounce.btcWeakBelowPct.toString()) }
    var altStrongAbovePct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.bearDecouplingBounce.altStrongAbovePct.toString()) }
    var bearMaxTradeValueRank by rememberSaveable(currentRulesText) { mutableStateOf(parsed.bearDecouplingBounce.maxTradeValueRank.toString()) }
    var minFourHourVolumeMultiple by rememberSaveable(currentRulesText) { mutableStateOf(parsed.bearDecouplingBounce.minFourHourVolumeMultiple.toString()) }
    var maxPreviousFourHourVolumeMultiple by rememberSaveable(currentRulesText) { mutableStateOf(parsed.bearDecouplingBounce.maxPreviousFourHourVolumeMultiple.toString()) }
    var maxPriceOver240mMa20Pct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.bearDecouplingBounce.maxPriceOver240mMa20Pct.toString()) }
    var maxBearishUpperWickPct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.bearDecouplingBounce.maxBearishUpperWickPct.toString()) }
    var decouplingScoreCap by rememberSaveable(currentRulesText) { mutableStateOf(parsed.bearDecouplingBounce.decouplingScoreCap.toString()) }
    var wickPenalty by rememberSaveable(currentRulesText) { mutableStateOf(parsed.bearDecouplingBounce.wickPenalty.toString()) }
    var prePumpEnabled by rememberSaveable(currentRulesText) { mutableStateOf(parsed.prePumpRotation.enabled) }
    var minChange24hPct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.prePumpRotation.minChange24hPct.toString()) }
    var maxChange24hPct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.prePumpRotation.maxChange24hPct.toString()) }
    var maxChange30mPct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.prePumpRotation.maxChange30mPct.toString()) }
    var maxChange5mPct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.prePumpRotation.maxChange5mPct.toString()) }
    var prePumpMaxTradeValueRank by rememberSaveable(currentRulesText) { mutableStateOf(parsed.prePumpRotation.maxTradeValueRank.toString()) }
    var maxChangeRank by rememberSaveable(currentRulesText) { mutableStateOf(parsed.prePumpRotation.maxChangeRank.toString()) }
    var minRotation30mPct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.prePumpRotation.minRotation30mPct.toString()) }
    var prePumpMinVolumeAcceleration by rememberSaveable(currentRulesText) { mutableStateOf(parsed.prePumpRotation.minVolumeAcceleration.toString()) }
    var minFiveMinuteVolumeRatio by rememberSaveable(currentRulesText) { mutableStateOf(parsed.prePumpRotation.minFiveMinuteVolumeRatio.toString()) }
    var minFifteenMinuteVolumeRatio by rememberSaveable(currentRulesText) { mutableStateOf(parsed.prePumpRotation.minFifteenMinuteVolumeRatio.toString()) }
    var maxRangePct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.prePumpRotation.maxRangePct.toString()) }
    var minRangePosition by rememberSaveable(currentRulesText) { mutableStateOf(parsed.prePumpRotation.minRangePosition.toString()) }
    var minHighProximityMultiplier by rememberSaveable(currentRulesText) { mutableStateOf(parsed.prePumpRotation.minHighProximityMultiplier.toString()) }
    var minCloseStairCount by rememberSaveable(currentRulesText) { mutableStateOf(parsed.prePumpRotation.minCloseStairCount.toString()) }
    var overheat24hBasePct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.scoring.overheat24hBasePct.toString()) }
    var overheat24hWeight by rememberSaveable(currentRulesText) { mutableStateOf(parsed.scoring.overheat24hWeight.toString()) }
    var overheat30mBasePct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.scoring.overheat30mBasePct.toString()) }
    var overheat30mWeight by rememberSaveable(currentRulesText) { mutableStateOf(parsed.scoring.overheat30mWeight.toString()) }
    var overheat5mBasePct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.scoring.overheat5mBasePct.toString()) }
    var overheat5mWeight by rememberSaveable(currentRulesText) { mutableStateOf(parsed.scoring.overheat5mWeight.toString()) }
    var overheatMax by rememberSaveable(currentRulesText) { mutableStateOf(parsed.scoring.overheatMax.toString()) }
    var hardBlockBtc24hBelowPct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.scoring.hardBlockBtc24hBelowPct.toString()) }
    var hardBlock30mPumpPct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.scoring.hardBlock30mPumpPct.toString()) }
    var hardBlock5mPumpPct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.scoring.hardBlock5mPumpPct.toString()) }
    var hardBlockRedUpperWickPct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.scoring.hardBlockRedUpperWickPct.toString()) }
    var defaultStopMultiplier by rememberSaveable(currentRulesText) { mutableStateOf(parsed.risk.defaultStopMultiplier.toString()) }
    var minimumRiskPct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.risk.minimumRiskPct.toString()) }
    var minimumExpectedReturnPct by rememberSaveable(currentRulesText) { mutableStateOf(parsed.risk.minimumExpectedReturnPct.toString()) }

    fun editedRules(): StrategyRules = parsed.copy(
        version = version.ifBlank { parsed.version },
        minimumScore = minimumScore.doubleOr(parsed.minimumScore),
        maxResults = maxResults.intOr(parsed.maxResults),
        validForMinutes = validForMinutes.intOr(parsed.validForMinutes),
        entryBandPct = entryBandPct.doubleOr(parsed.entryBandPct),
        candidateSelection = parsed.candidateSelection.copy(maxCandleTargets = maxCandleTargets.intOr(parsed.candidateSelection.maxCandleTargets), topTradeValueCount = topTradeValueCount.intOr(parsed.candidateSelection.topTradeValueCount), topChangeRateCount = topChangeRateCount.intOr(parsed.candidateSelection.topChangeRateCount), volumeBuildupCount = volumeBuildupCount.intOr(parsed.candidateSelection.volumeBuildupCount), quietAccumulationCount = quietAccumulationCount.intOr(parsed.candidateSelection.quietAccumulationCount), medianTradeValueMultiplier = medianTradeValueMultiplier.doubleOr(parsed.candidateSelection.medianTradeValueMultiplier), minBuildupChangeRatePct = minBuildupChangeRatePct.doubleOr(parsed.candidateSelection.minBuildupChangeRatePct), maxBuildupChangeRatePct = maxBuildupChangeRatePct.doubleOr(parsed.candidateSelection.maxBuildupChangeRatePct), maxQuietAbsChangeRatePct = maxQuietAbsChangeRatePct.doubleOr(parsed.candidateSelection.maxQuietAbsChangeRatePct)),
        compressionBreakout = parsed.compressionBreakout.copy(enabled = compressionEnabled, rangeCompressionRatio = rangeCompressionRatio.doubleOr(parsed.compressionBreakout.rangeCompressionRatio), maxDistanceTo15mHighPct = maxDistanceTo15mHighPct.doubleOr(parsed.compressionBreakout.maxDistanceTo15mHighPct), minVolumeAcceleration = compressionMinVolumeAcceleration.doubleOr(parsed.compressionBreakout.minVolumeAcceleration), minFiveMinuteVolumeRatio = compressionMinFiveMinuteVolumeRatio.doubleOr(parsed.compressionBreakout.minFiveMinuteVolumeRatio)),
        sweepReclaim = parsed.sweepReclaim.copy(enabled = sweepEnabled, fiveMinuteLookback = fiveMinuteLookback.intOr(parsed.sweepReclaim.fiveMinuteLookback), fifteenMinuteLookback = fifteenMinuteLookback.intOr(parsed.sweepReclaim.fifteenMinuteLookback), requireVolumeAboveAverage = requireVolumeAboveAverage),
        trendPullback = parsed.trendPullback.copy(enabled = trendEnabled, higherTimeframeMaPeriod = higherTimeframeMaPeriod.intOr(parsed.trendPullback.higherTimeframeMaPeriod), fifteenMinuteMaPeriod = fifteenMinuteMaPeriod.intOr(parsed.trendPullback.fifteenMinuteMaPeriod), min15mMaMultiplier = min15mMaMultiplier.doubleOr(parsed.trendPullback.min15mMaMultiplier), minPriorLowMultiplier = minPriorLowMultiplier.doubleOr(parsed.trendPullback.minPriorLowMultiplier), pullbackLookback = pullbackLookback.intOr(parsed.trendPullback.pullbackLookback), reclaimLookback = reclaimLookback.intOr(parsed.trendPullback.reclaimLookback)),
        bearDecouplingBounce = parsed.bearDecouplingBounce.copy(enabled = bearEnabled, btcWeakBelowPct = btcWeakBelowPct.doubleOr(parsed.bearDecouplingBounce.btcWeakBelowPct), altStrongAbovePct = altStrongAbovePct.doubleOr(parsed.bearDecouplingBounce.altStrongAbovePct), maxTradeValueRank = bearMaxTradeValueRank.intOr(parsed.bearDecouplingBounce.maxTradeValueRank), minFourHourVolumeMultiple = minFourHourVolumeMultiple.doubleOr(parsed.bearDecouplingBounce.minFourHourVolumeMultiple), maxPreviousFourHourVolumeMultiple = maxPreviousFourHourVolumeMultiple.doubleOr(parsed.bearDecouplingBounce.maxPreviousFourHourVolumeMultiple), maxPriceOver240mMa20Pct = maxPriceOver240mMa20Pct.doubleOr(parsed.bearDecouplingBounce.maxPriceOver240mMa20Pct), maxBearishUpperWickPct = maxBearishUpperWickPct.doubleOr(parsed.bearDecouplingBounce.maxBearishUpperWickPct), decouplingScoreCap = decouplingScoreCap.doubleOr(parsed.bearDecouplingBounce.decouplingScoreCap), wickPenalty = wickPenalty.doubleOr(parsed.bearDecouplingBounce.wickPenalty)),
        prePumpRotation = parsed.prePumpRotation.copy(enabled = prePumpEnabled, minChange24hPct = minChange24hPct.doubleOr(parsed.prePumpRotation.minChange24hPct), maxChange24hPct = maxChange24hPct.doubleOr(parsed.prePumpRotation.maxChange24hPct), maxChange30mPct = maxChange30mPct.doubleOr(parsed.prePumpRotation.maxChange30mPct), maxChange5mPct = maxChange5mPct.doubleOr(parsed.prePumpRotation.maxChange5mPct), maxTradeValueRank = prePumpMaxTradeValueRank.intOr(parsed.prePumpRotation.maxTradeValueRank), maxChangeRank = maxChangeRank.intOr(parsed.prePumpRotation.maxChangeRank), minRotation30mPct = minRotation30mPct.doubleOr(parsed.prePumpRotation.minRotation30mPct), minVolumeAcceleration = prePumpMinVolumeAcceleration.doubleOr(parsed.prePumpRotation.minVolumeAcceleration), minFiveMinuteVolumeRatio = minFiveMinuteVolumeRatio.doubleOr(parsed.prePumpRotation.minFiveMinuteVolumeRatio), minFifteenMinuteVolumeRatio = minFifteenMinuteVolumeRatio.doubleOr(parsed.prePumpRotation.minFifteenMinuteVolumeRatio), maxRangePct = maxRangePct.doubleOr(parsed.prePumpRotation.maxRangePct), minRangePosition = minRangePosition.doubleOr(parsed.prePumpRotation.minRangePosition), minHighProximityMultiplier = minHighProximityMultiplier.doubleOr(parsed.prePumpRotation.minHighProximityMultiplier), minCloseStairCount = minCloseStairCount.intOr(parsed.prePumpRotation.minCloseStairCount)),
        scoring = parsed.scoring.copy(overheat24hBasePct = overheat24hBasePct.doubleOr(parsed.scoring.overheat24hBasePct), overheat24hWeight = overheat24hWeight.doubleOr(parsed.scoring.overheat24hWeight), overheat30mBasePct = overheat30mBasePct.doubleOr(parsed.scoring.overheat30mBasePct), overheat30mWeight = overheat30mWeight.doubleOr(parsed.scoring.overheat30mWeight), overheat5mBasePct = overheat5mBasePct.doubleOr(parsed.scoring.overheat5mBasePct), overheat5mWeight = overheat5mWeight.doubleOr(parsed.scoring.overheat5mWeight), overheatMax = overheatMax.doubleOr(parsed.scoring.overheatMax), hardBlockBtc24hBelowPct = hardBlockBtc24hBelowPct.doubleOr(parsed.scoring.hardBlockBtc24hBelowPct), hardBlock30mPumpPct = hardBlock30mPumpPct.doubleOr(parsed.scoring.hardBlock30mPumpPct), hardBlock5mPumpPct = hardBlock5mPumpPct.doubleOr(parsed.scoring.hardBlock5mPumpPct), hardBlockRedUpperWickPct = hardBlockRedUpperWickPct.doubleOr(parsed.scoring.hardBlockRedUpperWickPct)),
        risk = parsed.risk.copy(defaultStopMultiplier = defaultStopMultiplier.doubleOr(parsed.risk.defaultStopMultiplier), minimumRiskPct = minimumRiskPct.doubleOr(parsed.risk.minimumRiskPct), minimumExpectedReturnPct = minimumExpectedReturnPct.doubleOr(parsed.risk.minimumExpectedReturnPct)),
    )

    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { StrategyManualCard() }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onRulesRefresh) { Text("Refresh") }; Button(onClick = { onRulesSave(editedRules().toJson().toString(2)) }) { Text("Save local") }; OutlinedButton(onClick = onRulesDownload) { Text("Download") } } }
        settingsMessage?.let { item { Text(it) } }
        item { RuleSection("기본") { RuleTextField("버전", version, { version = it }); RuleTextField("최소 점수", minimumScore, { minimumScore = it }); RuleTextField("최대 결과 수", maxResults, { maxResults = it }); RuleTextField("유효 시간(분)", validForMinutes, { validForMinutes = it }); RuleTextField("진입 밴드 %", entryBandPct, { entryBandPct = it }) } }
        item { RuleSection("후보 선정") { RuleTextField("캔들 후보 수", maxCandleTargets, { maxCandleTargets = it }); RuleTextField("거래대금 상위 수", topTradeValueCount, { topTradeValueCount = it }); RuleTextField("등락률 상위 수", topChangeRateCount, { topChangeRateCount = it }); RuleTextField("거래량 빌드업 수", volumeBuildupCount, { volumeBuildupCount = it }); RuleTextField("조용한 매집 수", quietAccumulationCount, { quietAccumulationCount = it }); RuleTextField("중앙 거래대금 배수", medianTradeValueMultiplier, { medianTradeValueMultiplier = it }); RuleTextField("빌드업 최소 등락률 %", minBuildupChangeRatePct, { minBuildupChangeRatePct = it }); RuleTextField("빌드업 최대 등락률 %", maxBuildupChangeRatePct, { maxBuildupChangeRatePct = it }); RuleTextField("조용한 매집 최대 절대등락 %", maxQuietAbsChangeRatePct, { maxQuietAbsChangeRatePct = it }) } }
        item { RuleSection("압축 돌파") { RuleSwitchField("사용", compressionEnabled, { compressionEnabled = it }); RuleTextField("압축 비율", rangeCompressionRatio, { rangeCompressionRatio = it }); RuleTextField("15분 고점 근접 %", maxDistanceTo15mHighPct, { maxDistanceTo15mHighPct = it }); RuleTextField("최소 거래량 가속", compressionMinVolumeAcceleration, { compressionMinVolumeAcceleration = it }); RuleTextField("최소 5분 거래량 비율", compressionMinFiveMinuteVolumeRatio, { compressionMinFiveMinuteVolumeRatio = it }) } }
        item { RuleSection("저점 훼손 후 회복") { RuleSwitchField("사용", sweepEnabled, { sweepEnabled = it }); RuleTextField("5분 룩백", fiveMinuteLookback, { fiveMinuteLookback = it }); RuleTextField("15분 룩백", fifteenMinuteLookback, { fifteenMinuteLookback = it }); RuleSwitchField("평균 이상 거래량 요구", requireVolumeAboveAverage, { requireVolumeAboveAverage = it }) } }
        item { RuleSection("추세 눌림") { RuleSwitchField("사용", trendEnabled, { trendEnabled = it }); RuleTextField("상위 MA 기간", higherTimeframeMaPeriod, { higherTimeframeMaPeriod = it }); RuleTextField("15분 MA 기간", fifteenMinuteMaPeriod, { fifteenMinuteMaPeriod = it }); RuleTextField("15분 MA 배수", min15mMaMultiplier, { min15mMaMultiplier = it }); RuleTextField("이전 저점 배수", minPriorLowMultiplier, { minPriorLowMultiplier = it }); RuleTextField("눌림 룩백", pullbackLookback, { pullbackLookback = it }); RuleTextField("회복 룩백", reclaimLookback, { reclaimLookback = it }) } }
        item { RuleSection("약세장 독립강세") { RuleSwitchField("사용", bearEnabled, { bearEnabled = it }); RuleTextField("BTC 약세 기준 %", btcWeakBelowPct, { btcWeakBelowPct = it }); RuleTextField("알트 강세 기준 %", altStrongAbovePct, { altStrongAbovePct = it }); RuleTextField("최대 거래대금 순위", bearMaxTradeValueRank, { bearMaxTradeValueRank = it }); RuleTextField("최소 4시간 거래량 배수", minFourHourVolumeMultiple, { minFourHourVolumeMultiple = it }); RuleTextField("이전 4시간 거래량 최대", maxPreviousFourHourVolumeMultiple, { maxPreviousFourHourVolumeMultiple = it }); RuleTextField("240m MA20 과열 %", maxPriceOver240mMa20Pct, { maxPriceOver240mMa20Pct = it }); RuleTextField("상단꼬리 최대 %", maxBearishUpperWickPct, { maxBearishUpperWickPct = it }); RuleTextField("디커플링 점수 상한", decouplingScoreCap, { decouplingScoreCap = it }); RuleTextField("꼬리 패널티", wickPenalty, { wickPenalty = it }) } }
        item { RuleSection("급등 전 회전") { RuleSwitchField("사용", prePumpEnabled, { prePumpEnabled = it }); RuleTextField("24h 최소 등락 %", minChange24hPct, { minChange24hPct = it }); RuleTextField("24h 최대 등락 %", maxChange24hPct, { maxChange24hPct = it }); RuleTextField("30m 최대 등락 %", maxChange30mPct, { maxChange30mPct = it }); RuleTextField("5m 최대 등락 %", maxChange5mPct, { maxChange5mPct = it }); RuleTextField("최대 거래대금 순위", prePumpMaxTradeValueRank, { prePumpMaxTradeValueRank = it }); RuleTextField("최대 등락률 순위", maxChangeRank, { maxChangeRank = it }); RuleTextField("30m 최소 회전 %", minRotation30mPct, { minRotation30mPct = it }); RuleTextField("최소 거래량 가속", prePumpMinVolumeAcceleration, { prePumpMinVolumeAcceleration = it }); RuleTextField("최소 5분 거래량 비율", minFiveMinuteVolumeRatio, { minFiveMinuteVolumeRatio = it }); RuleTextField("최소 15분 거래량 비율", minFifteenMinuteVolumeRatio, { minFifteenMinuteVolumeRatio = it }); RuleTextField("최대 박스폭 %", maxRangePct, { maxRangePct = it }); RuleTextField("최소 박스 위치", minRangePosition, { minRangePosition = it }); RuleTextField("고점 근접 배수", minHighProximityMultiplier, { minHighProximityMultiplier = it }); RuleTextField("종가 계단 수", minCloseStairCount, { minCloseStairCount = it }) } }
        item { RuleSection("과열/차단 점수") { RuleTextField("24h 과열 기준 %", overheat24hBasePct, { overheat24hBasePct = it }); RuleTextField("24h 과열 가중치", overheat24hWeight, { overheat24hWeight = it }); RuleTextField("30m 과열 기준 %", overheat30mBasePct, { overheat30mBasePct = it }); RuleTextField("30m 과열 가중치", overheat30mWeight, { overheat30mWeight = it }); RuleTextField("5m 과열 기준 %", overheat5mBasePct, { overheat5mBasePct = it }); RuleTextField("5m 과열 가중치", overheat5mWeight, { overheat5mWeight = it }); RuleTextField("과열 최대 감점", overheatMax, { overheatMax = it }); RuleTextField("BTC 24h 하락 차단 %", hardBlockBtc24hBelowPct, { hardBlockBtc24hBelowPct = it }); RuleTextField("30m 펌핑 차단 %", hardBlock30mPumpPct, { hardBlock30mPumpPct = it }); RuleTextField("5m 펌핑 차단 %", hardBlock5mPumpPct, { hardBlock5mPumpPct = it }); RuleTextField("음봉 상단꼬리 차단 %", hardBlockRedUpperWickPct, { hardBlockRedUpperWickPct = it }) } }
        item { RuleSection("리스크") { RuleTextField("기본 손절 배수", defaultStopMultiplier, { defaultStopMultiplier = it }); RuleTextField("최소 손절폭 %", minimumRiskPct, { minimumRiskPct = it }); RuleTextField("최소 기대수익 %", minimumExpectedReturnPct, { minimumExpectedReturnPct = it }) } }
    }
}

@Composable
private fun RuleSection(title: String, content: @Composable Column.() -> Unit) { Card(modifier = Modifier.fillMaxWidth()) { Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, fontWeight = FontWeight.Bold); content() } } }
@Composable
private fun RuleTextField(label: String, value: String, onValueChange: (String) -> Unit) { OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
@Composable
private fun RuleSwitchField(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("$label: ${if (checked) "ON" else "OFF"}"); Switch(checked = checked, onCheckedChange = onCheckedChange) } }

@Composable
private fun StrategyManualCard() { Card(modifier = Modifier.fillMaxWidth()) { Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text("현재 전략 설명", fontWeight = FontWeight.Bold); Text("Search 탭에서 종목 입력, 전략 분석, 실시간 차트를 한 번에 봅니다."); Text("History 탭은 기존 Performance 기능까지 포함해서 실패/손절/이유/성과/백테스트를 같이 봅니다."); Text("Rules 탭은 JSON을 직접 만지는 대신 각 조건을 입력칸으로 수정합니다.") } } }

@Composable
private fun SettingsTab(isRunning: Boolean, scanIntervalMs: Long, maxDisplayCount: Int, minimumScore: Double, gitHubSettings: GitHubSettings, settingsMessage: String?, onStart: () -> Unit, onStop: () -> Unit, onIntervalSelected: (Long) -> Unit, onMaxDisplayChanged: (Int) -> Unit, onMinimumScoreChanged: (Double) -> Unit, onGitHubSettingsSaved: (GitHubSettings) -> Unit, onGitHubSettingsTest: (GitHubSettings) -> Unit, onRulesDownload: (GitHubSettings) -> Unit, onReportUpload: (GitHubSettings) -> Unit, onOpenInstallPermissionSettings: () -> Unit, onDownloadAndInstallLatestApk: (GitHubSettings) -> Unit) {
    var owner by rememberSaveable(gitHubSettings.owner) { mutableStateOf(gitHubSettings.owner) }; var repo by rememberSaveable(gitHubSettings.repo) { mutableStateOf(gitHubSettings.repo) }; var branch by rememberSaveable(gitHubSettings.branch) { mutableStateOf(gitHubSettings.branch) }; var token by rememberSaveable(gitHubSettings.token) { mutableStateOf(gitHubSettings.token) }; var rulesPath by rememberSaveable(gitHubSettings.rulesPath) { mutableStateOf(gitHubSettings.rulesPath) }; var reportPath by rememberSaveable(gitHubSettings.reportPath) { mutableStateOf(gitHubSettings.reportPath) }; var autoUploadReport by rememberSaveable(gitHubSettings.autoUploadReport) { mutableStateOf(gitHubSettings.autoUploadReport) }
    fun currentSettings() = GitHubSettings(owner = owner, repo = repo, branch = branch, rulesPath = rulesPath, reportPath = reportPath, token = token, autoUploadReport = autoUploadReport)
    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Status: ${if (isRunning) "running" else "stopped"}") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = onStart, enabled = !isRunning) { Text("Start scanner") }; OutlinedButton(onClick = onStop, enabled = isRunning) { Text("Stop") } } }
        item { Text("Scan interval") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ScannerStateStore.SUPPORTED_INTERVALS_MS.forEach { interval -> FilterChip(selected = scanIntervalMs == interval, onClick = { onIntervalSelected(interval) }, label = { Text("${interval / 1000}s") }) } } }
        item { Text("Max displayed symbols: $maxDisplayCount") }
        item { Slider(value = maxDisplayCount.toFloat(), onValueChange = { onMaxDisplayChanged(it.roundToInt()) }, valueRange = 1f..10f, steps = 8) }
        item { Text("Minimum score: ${minimumScore.roundToInt()}") }
        item { Slider(value = minimumScore.toFloat(), onValueChange = { onMinimumScoreChanged(it.toDouble()) }, valueRange = 50f..90f, steps = 7) }
        item { Text("App update", fontWeight = FontWeight.Bold) }
        item { Text("패키지 충돌은 기존 APK와 새 APK 서명키가 다를 때 발생합니다. 같은 release key를 유지해야 삭제 없이 업데이트됩니다.", style = MaterialTheme.typography.bodySmall) }
        item { OutlinedButton(onClick = onOpenInstallPermissionSettings, modifier = Modifier.fillMaxWidth()) { Text("Open install permission settings") } }
        item { Button(onClick = { onDownloadAndInstallLatestApk(currentSettings()) }, modifier = Modifier.fillMaxWidth()) { Text("Download and install latest APK") } }
        item { Text("GitHub sync", fontWeight = FontWeight.Bold) }
        item { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text("Auto upload report"); Text("Upload at most every 10 minutes", style = MaterialTheme.typography.bodySmall) }; Switch(checked = autoUploadReport, onCheckedChange = { autoUploadReport = it }) } }
        item { OutlinedTextField(value = owner, onValueChange = { owner = it }, label = { Text("GitHub owner") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { OutlinedTextField(value = repo, onValueChange = { repo = it }, label = { Text("GitHub repository") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { OutlinedTextField(value = branch, onValueChange = { branch = it }, label = { Text("GitHub branch") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { OutlinedTextField(value = rulesPath, onValueChange = { rulesPath = it }, label = { Text("Rules JSON path") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { OutlinedTextField(value = reportPath, onValueChange = { reportPath = it }, label = { Text("Report JSON path") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("GitHub token") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation()) }
        item { Button(onClick = { onGitHubSettingsSaved(currentSettings()) }, modifier = Modifier.fillMaxWidth()) { Text("Save settings") } }
        item { OutlinedButton(onClick = { onGitHubSettingsTest(currentSettings()) }, modifier = Modifier.fillMaxWidth()) { Text("Test GitHub settings") } }
        item { OutlinedButton(onClick = { onRulesDownload(currentSettings()) }, modifier = Modifier.fillMaxWidth()) { Text("Download latest rules") } }
        item { OutlinedButton(onClick = { onReportUpload(currentSettings()) }, modifier = Modifier.fillMaxWidth()) { Text("Upload latest report") } }
        settingsMessage?.let { item { Text(it) } }
    }
}

@Composable
private fun StrategyCard(strategy: TradeStrategy, onStrategyChart: (TradeStrategy) -> Unit) { Card(modifier = Modifier.fillMaxWidth()) { Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("#${strategy.rank} ${strategy.symbol} | ${strategy.strategyType.name.toKoreanStrategyName()}", fontWeight = FontWeight.Bold); Text(strategy.koreanPlanLine()); Text("Score ${strategy.score.one()} | Expected ${strategy.expectedReturnPct.percent()} | R/R ${strategy.riskRewardRatio.one()}"); Text("Entry ${strategy.entryLow.price()} - ${strategy.entryHigh.price()}"); Text("Stop ${strategy.stopLoss.price()} | Targets ${strategy.target1.price()} / ${strategy.target2.price()}"); Text("Valid until ${strategy.validUntil.toTimeText()}"); Text(strategy.reason.toKoreanReasonHint(), style = MaterialTheme.typography.bodySmall); OutlinedButton(onClick = { onStrategyChart(strategy) }, modifier = Modifier.fillMaxWidth()) { Text("Search 차트에서 보기") } } } }
@Composable private fun EmptyCard(text: String) { Card(modifier = Modifier.fillMaxWidth()) { Text(text = text, modifier = Modifier.padding(12.dp)) } }

private fun StrategyHistoryEntity.toTradeStrategyOrNull(): TradeStrategy? {
    val summary = newSummary ?: oldSummary ?: return null
    val entry = Regex("entry=([0-9.,]+)-([0-9.,]+)").find(summary) ?: return null
    val stop = Regex("stop=([0-9.,]+)").find(summary) ?: return null
    val target = Regex("target=([0-9.,]+)/([0-9.,]+)").find(summary) ?: return null
    val rank = Regex("rank=([0-9]+)").find(summary)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 999
    val score = Regex("score=([0-9.]+)").find(summary)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
    val strategyTypeName = Regex("strategy=([A-Z_]+)").find(summary)?.groupValues?.getOrNull(1)
    val type = strategyTypeName?.let { runCatching { StrategyType.valueOf(it) }.getOrNull() } ?: StrategyType.WATCH_ONLY
    val status = when (eventType) { "STOPPED_OUT" -> StrategyStatus.STOPPED_OUT; "HIT_TARGET" -> StrategyStatus.HIT_TARGET; "TARGET1_HIT" -> StrategyStatus.TARGET1_HIT; "TRAILING_STOP_HIT" -> StrategyStatus.TRAILING_STOP_HIT; "EXPIRED" -> StrategyStatus.EXPIRED; else -> StrategyStatus.ACTIVE }
    val entryLow = entry.groupValues[1].parsePrice(); val entryHigh = entry.groupValues[2].parsePrice(); val stopLoss = stop.groupValues[1].parsePrice(); val target1 = target.groupValues[1].parsePrice(); val target2 = target.groupValues[2].parsePrice(); val entryMid = (entryLow + entryHigh) / 2.0
    if (entryLow <= 0.0 || entryHigh <= 0.0 || stopLoss <= 0.0 || target1 <= 0.0) return null
    return TradeStrategy(id = strategyId, symbol = symbol, strategyType = type, status = status, score = score, rank = rank, entryLow = entryLow, entryHigh = entryHigh, stopLoss = stopLoss, target1 = target1, target2 = target2, trailingStop = stopLoss, expectedReturnPct = entryMid.toPctValue(target1), riskPct = entryMid.toPctValue(stopLoss), riskRewardRatio = if (entryMid > stopLoss) (target1 - entryMid) / (entryMid - stopLoss) else 0.0, componentScores = "restored from history", rankByChangeRate = 0, rankByTradeValue = 0, changeRate24h = 0.0, changeRate30m = 0.0, changeRate5m = 0.0, volumeAcceleration = 0.0, reason = message, invalidationReason = null, createdAt = createdAt, updatedAt = createdAt, validUntil = createdAt + 4 * 60 * 60 * 1000L)
}

private fun TradeStrategy.entryMid(): Double = (entryLow + entryHigh) / 2.0
private fun TradeStrategy.koreanPlanLine(): String { val direction = if (strategyType.name == "BTC_SHORT_REGIME") "숏/하락" else "롱/상승"; return "$direction 전략 | 진입 ${entryLow.price()}~${entryHigh.price()} | 손절 ${stopLoss.price()} | 목표 ${target1.price()}/${target2.price()} | 24h ${changeRate24h.one()}% · 30m ${changeRate30m.one()}% · 5m ${changeRate5m.one()}% · 거래량 ${volumeAcceleration.one()}x" }
private fun StrategyHistoryEntity.historyCategoryKey(): String = when { eventType == "STOPPED_OUT" || message.contains("Stop", true) -> "STOP"; eventType in setOf("HIT_TARGET", "TARGET1_HIT", "TRAILING_STOP_HIT") -> "PROFIT"; eventType in setOf("EXPIRED", "INVALIDATED", "WATCH_ONLY") -> "FAIL"; else -> "OPEN" }
private fun StrategyHistoryEntity.historyCategory(): String = historyCategoryKey().toKoreanHistoryCategoryLabel()
private fun String.toKoreanHistoryCategoryLabel(): String = when (this) { "ALL" -> "전체"; "PROFIT" -> "성공"; "STOP" -> "손절"; "FAIL" -> "실패/만료"; "OPEN" -> "진행"; else -> this }
private fun String.toHistorySymbolFilter(): String { val upper = trim().uppercase().replace("/", "-"); return when { upper.isBlank() || upper == "ALL" -> "ALL"; upper.startsWith("KRW-") -> upper; else -> "KRW-$upper" } }
private fun StrategyHistoryEntity.improvementHint(): String = when { eventType == "STOPPED_OUT" || message.contains("Stop", true) -> "진입 추격 여부, 손절폭, 거래량 지속성 확인"; eventType == "EXPIRED" -> "시간 내 변동성 부족. 기대수익률보다 MFE와 거래대금 조건 재검토"; eventType == "WATCH_ONLY" -> "관찰 신호가 실제 펌핑했는지 30분 후 성과와 비교"; eventType in setOf("HIT_TARGET", "TARGET1_HIT") -> "동일 조건 반복 가능. 단 목표 도달 후 재진입 금지"; else -> "점수 구성요소와 당시 차트 위치를 같이 확인" }
private fun String.parsePrice(): Double = replace(",", "").toDoubleOrNull() ?: 0.0
private fun String.doubleOr(default: Double): Double = toDoubleOrNull() ?: default
private fun String.intOr(default: Int): Int = toIntOrNull() ?: default
private fun Double.toPctValue(price: Double): Double = if (this <= 0.0) 0.0 else ((price - this) / this) * 100.0
private fun Double.toPct(price: Double): String = if (this <= 0.0) "-" else String.format(Locale.US, "%+.2f%%", ((price - this) / this) * 100.0)
private fun String.toCompactPlan(): String = replace("rank=", "#").replace("score=", "점수 ").replace("entry=", "진입 ").replace("stop=", "손절 ").replace("target=", "목표 ").replace("trail=", "트레일 ").replace("status=", "").replace("strategy=", "")
private fun String.toKoreanStrategyName(): String = when (this) { "COMPRESSION_BREAKOUT" -> "압축 돌파"; "SWEEP_RECLAIM" -> "저점 훼손 후 회복"; "TREND_PULLBACK" -> "추세 눌림 회복"; "BEAR_DECOUPLING_BOUNCE" -> "약세장 독립강세"; "PRE_PUMP_ROTATION" -> "급등 전 회전 포착"; "BTC_SHORT_REGIME" -> "비트코인 숏/위험회피"; "WATCH_ONLY" -> "관찰"; else -> this }
private fun String.toKoreanEventName(): String = when (this) { "NEW_ACTIVE" -> "신규"; "MANUAL_SEARCH" -> "검색"; "RANK_UP" -> "순위"; "PRICE_PLAN_CHANGED" -> "변경"; "WATCH_ONLY" -> "관찰"; "INVALIDATED" -> "무효"; "TARGET1_HIT" -> "1차"; "TRAILING_STOP_HIT" -> "트레일"; "HIT_TARGET" -> "목표"; "STOPPED_OUT" -> "손절"; "EXPIRED" -> "만료"; else -> this }
private fun String.toKoreanHistoryReason(): String = replace("Stop loss was reached.", "손절 도달").replace("Target1 was reached.", "1차 목표 도달").replace("Target2 was reached.", "2차 목표 도달").replace("Strategy valid window expired.", "유효시간 만료").replace("New ACTIVE strategy was created.", "신규 전략 생성")
private fun String.toKoreanReasonHint(): String = replace("PRE_PUMP_ROTATION", "급등 전 회전 포착").replace("BTC_SHORT_REGIME", "비트코인 숏/위험회피").replace("COMPRESSION_BREAKOUT", "압축 돌파").replace("SWEEP_RECLAIM", "저점 훼손 후 회복").replace("TREND_PULLBACK", "추세 눌림 회복").replace("BEAR_DECOUPLING_BOUNCE", "약세장 독립강세").replace("ACTIVE", "활성").replace("WATCH_ONLY", "관찰")
private fun Double.price(): String = String.format(Locale.US, "%,.2f", this)
private fun Double.percent(): String = String.format(Locale.US, "%.2f%%", this)
private fun Double?.percentOrDash(): String = this?.percent() ?: "-"
private fun Double.one(): String = String.format(Locale.US, "%.1f", this)
private fun List<Double>.averageOrNullText(): String = if (isEmpty()) "-" else average().percent()
private fun Long.toTimeText(): String = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(this))
