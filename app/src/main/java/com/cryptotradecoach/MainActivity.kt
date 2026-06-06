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
    val tabs = listOf("Current", "Search", "Chart", "History", "Performance", "Rules", "Settings")
    var selectedTab by remember { mutableIntStateOf(0) }
    val openChart: (TradeStrategy) -> Unit = { strategy ->
        selectedTab = 2
        onStrategyChart(strategy)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Text("Crypto Trade Coach", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp))
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title -> Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) }) }
        }
        when (selectedTab) {
            0 -> CurrentStrategiesTab(activeStrategies, scanDiagnostics, lastScanAt, minimumScore, chartMessage, openChart)
            1 -> ManualSearchTab(manualStrategy, manualMessage, chartMessage, onManualAnalyze, onManualSave, openChart)
            2 -> ChartTab(strategyChart, selectedChartTimeframe, chartMessage, onChartTimeframeSelected, onClearChart)
            3 -> StrategyHistoryTab(historyBySymbol, openChart)
            4 -> PerformanceTab(performanceRows, backtestResults, evolutionLog, lastEvolvedAt, onPerformanceRefresh, onBacktestRefresh, onEvolutionRefresh)
            5 -> RulesTab(currentRulesText, settingsMessage, onRulesRefresh, { onRulesDownload(gitHubSettings) }, onRulesSave)
            6 -> SettingsTab(isRunning, scanIntervalMs, maxDisplayCount, minimumScore, gitHubSettings, settingsMessage, onStart, onStop, onIntervalSelected, onMaxDisplayChanged, onMinimumScoreChanged, onGitHubSettingsSaved, onGitHubSettingsTest, onRulesDownload, onReportUpload, onOpenInstallPermissionSettings, onDownloadAndInstallLatestApk)
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
private fun ManualSearchTab(manualStrategy: TradeStrategy?, manualMessage: String?, chartMessage: String?, onManualAnalyze: (String) -> Unit, onManualSave: () -> Unit, onStrategyChart: (TradeStrategy) -> Unit) {
    var symbol by rememberSaveable { mutableStateOf("") }
    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("수동 종목 분석", fontWeight = FontWeight.Bold)
                    Text("업비트 KRW 종목을 입력하면 전략과 차트를 계산합니다. 예: XRP 또는 KRW-XRP", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = symbol, onValueChange = { symbol = it }, label = { Text("Symbol") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onManualAnalyze(symbol) }) { Text("Analyze") }
                        OutlinedButton(onClick = onManualSave, enabled = manualStrategy != null) { Text("Save") }
                        OutlinedButton(onClick = { manualStrategy?.let(onStrategyChart) }, enabled = manualStrategy != null) { Text("Chart") }
                    }
                    manualMessage?.let { Text(it) }
                    chartMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        manualStrategy?.let { item { StrategyCard(it, onStrategyChart) } }
    }
}

@Composable
private fun ChartTab(snapshot: StrategyChartSnapshot?, selectedTimeframe: ChartTimeframe, chartMessage: String?, onTimeframeSelected: (ChartTimeframe) -> Unit, onClearChart: () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Strategy chart", fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = onClearChart, enabled = snapshot != null) { Text("Clear") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChartTimeframe.values().forEach { tf -> FilterChip(selected = selectedTimeframe == tf, onClick = { onTimeframeSelected(tf) }, label = { Text(tf.label) }) }
            }
        }
        chartMessage?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
        if (snapshot == null) item { EmptyCard("차트 로딩 중이거나 아직 선택된 전략이 없습니다. 봉 변경 시 이전 차트는 지워지고 새 봉 데이터를 다시 불러옵니다.") } else {
            item { StrategyChartCard(snapshot) }
            item { StrategyCard(snapshot.strategy, onStrategyChart = {}) }
        }
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
    val upColor = Color(0xFF2E7D32)
    val downColor = Color(0xFFC62828)
    val entryColor = Color(0xFF1565C0)
    val stopColor = Color(0xFFC62828)
    val targetColor = Color(0xFF6A1B9A)
    val strategyTimeColor = Color(0xFFEF6C00)
    val outline = MaterialTheme.colorScheme.outline
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
        fun labeledLine(price: Double, color: Color, text: String, width: Float) {
            val yy = y(price)
            drawLine(color, Offset(0f, yy), Offset(size.width, yy), strokeWidth = width)
            val labelX = 8f
            val labelY = (yy - 6f).coerceIn(24f, size.height - 8f)
            drawContext.canvas.nativeCanvas.drawRect(labelX - 4f, labelY - 24f, labelX + labelPaint.measureText(text) + 8f, labelY + 6f, bgPaint)
            drawContext.canvas.nativeCanvas.drawText(text, labelX, labelY, labelPaint)
        }
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
private fun StrategyHistoryTab(historyBySymbol: Map<String, List<StrategyHistoryEntity>>, onStrategyChart: (TradeStrategy) -> Unit) {
    var selectedSymbol by rememberSaveable { mutableStateOf("ALL") }
    val allRows = historyBySymbol.values.flatten().sortedByDescending { it.createdAt }
    val symbols = historyBySymbol.keys.sorted()
    val visibleMap = if (selectedSymbol == "ALL") historyBySymbol.toSortedMap() else historyBySymbol.filterKeys { it == selectedSymbol }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { HistorySummaryCard(allRows) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("종목별 히스토리", fontWeight = FontWeight.Bold)
                OutlinedTextField(value = if (selectedSymbol == "ALL") "" else selectedSymbol, onValueChange = { value -> selectedSymbol = value.trim().uppercase().let { if (it.isBlank()) "ALL" else if (it.startsWith("KRW-")) it else "KRW-$it" } }, label = { Text("Symbol filter") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = selectedSymbol == "ALL", onClick = { selectedSymbol = "ALL" }, label = { Text("ALL") })
                    symbols.take(4).forEach { s -> FilterChip(selected = selectedSymbol == s, onClick = { selectedSymbol = s }, label = { Text(s.removePrefix("KRW-")) }) }
                }
            }
        }
        if (visibleMap.isEmpty()) item { EmptyCard("No matching strategy history.") } else visibleMap.forEach { (symbol, rows) -> item { SymbolHistorySection(symbol, rows, onStrategyChart) } }
    }
}

@Composable
private fun HistorySummaryCard(rows: List<StrategyHistoryEntity>) {
    val stop = rows.count { it.eventType == "STOPPED_OUT" || it.message.contains("Stop", true) }
    val fail = rows.count { it.eventType in setOf("EXPIRED", "INVALIDATED", "WATCH_ONLY") || it.message.contains("expired", true) }
    val profit = rows.count { it.eventType in setOf("TARGET1_HIT", "HIT_TARGET", "TRAILING_STOP_HIT") }
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
        if (restored != null) {
            OutlinedButton(onClick = { onStrategyChart(restored) }, modifier = Modifier.fillMaxWidth()) { Text("이 기록 차트 보기") }
        }
    }
}

@Composable
private fun PerformanceTab(performanceRows: List<StrategyPerformanceEntity>, backtestResults: List<BacktestResult>, evolutionLog: List<EvolutionLogEntity>, lastEvolvedAt: Long?, onPerformanceRefresh: () -> Unit, onBacktestRefresh: () -> Unit, onEvolutionRefresh: () -> Unit) {
    val rowsByType = performanceRows.groupBy { it.strategyType.name }
    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Text("Strategy performance", fontWeight = FontWeight.Bold); OutlinedButton(onClick = { onPerformanceRefresh(); onBacktestRefresh(); onEvolutionRefresh() }) { Text("Refresh") } } }
        item { EvolutionStatusCard(backtestResults, evolutionLog, lastEvolvedAt) }
        if (backtestResults.isNotEmpty()) { item { Text("Backtest ranking", fontWeight = FontWeight.Bold) }; items(backtestResults.sortedByDescending { it.expectancy }) { BacktestDetailCard(it) } }
        if (performanceRows.isEmpty()) item { EmptyCard("No performance data yet.") } else { rowsByType.forEach { (strategyType, rows) -> item { PerformanceSummaryCard(strategyType, rows) } }; items(performanceRows.sortedByDescending { it.createdAt }) { PerformanceCard(it) } }
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
    var editableRulesText by rememberSaveable(currentRulesText) { mutableStateOf(currentRulesText) }
    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { StrategyManualCard() }
        item { Text("Current rules JSON", fontWeight = FontWeight.Bold) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onRulesRefresh) { Text("Refresh") }; Button(onClick = { onRulesSave(editableRulesText) }) { Text("Save local") }; OutlinedButton(onClick = onRulesDownload) { Text("Download") } } }
        settingsMessage?.let { item { Text(it) } }
        item { OutlinedTextField(value = editableRulesText, onValueChange = { editableRulesText = it }, label = { Text("Rules JSON") }, modifier = Modifier.fillMaxWidth(), minLines = 18, singleLine = false, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
    }
}

@Composable
private fun StrategyManualCard() { Card(modifier = Modifier.fillMaxWidth()) { Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Text("현재 전략 설명", fontWeight = FontWeight.Bold); Text("Chart 탭은 1분·5분·15분·1시간·4시간·일봉 차트 위에 진입·손절·목표·전략시점을 표시합니다."); Text("History 탭은 실패/손절/이유/보완과 복원 가능한 차트 보기를 제공합니다.") } } }

@Composable
private fun SettingsTab(isRunning: Boolean, scanIntervalMs: Long, maxDisplayCount: Int, minimumScore: Double, gitHubSettings: GitHubSettings, settingsMessage: String?, onStart: () -> Unit, onStop: () -> Unit, onIntervalSelected: (Long) -> Unit, onMaxDisplayChanged: (Int) -> Unit, onMinimumScoreChanged: (Double) -> Unit, onGitHubSettingsSaved: (GitHubSettings) -> Unit, onGitHubSettingsTest: (GitHubSettings) -> Unit, onRulesDownload: (GitHubSettings) -> Unit, onReportUpload: (GitHubSettings) -> Unit, onOpenInstallPermissionSettings: () -> Unit, onDownloadAndInstallLatestApk: (GitHubSettings) -> Unit) {
    var owner by rememberSaveable(gitHubSettings.owner) { mutableStateOf(gitHubSettings.owner) }
    var repo by rememberSaveable(gitHubSettings.repo) { mutableStateOf(gitHubSettings.repo) }
    var branch by rememberSaveable(gitHubSettings.branch) { mutableStateOf(gitHubSettings.branch) }
    var token by rememberSaveable(gitHubSettings.token) { mutableStateOf(gitHubSettings.token) }
    var rulesPath by rememberSaveable(gitHubSettings.rulesPath) { mutableStateOf(gitHubSettings.rulesPath) }
    var reportPath by rememberSaveable(gitHubSettings.reportPath) { mutableStateOf(gitHubSettings.reportPath) }
    var autoUploadReport by rememberSaveable(gitHubSettings.autoUploadReport) { mutableStateOf(gitHubSettings.autoUploadReport) }
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
private fun StrategyCard(strategy: TradeStrategy, onStrategyChart: (TradeStrategy) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("#${strategy.rank} ${strategy.symbol} | ${strategy.strategyType.name.toKoreanStrategyName()}", fontWeight = FontWeight.Bold)
            Text(strategy.koreanPlanLine())
            Text("Score ${strategy.score.one()} | Expected ${strategy.expectedReturnPct.percent()} | R/R ${strategy.riskRewardRatio.one()}")
            Text("Entry ${strategy.entryLow.price()} - ${strategy.entryHigh.price()}")
            Text("Stop ${strategy.stopLoss.price()} | Targets ${strategy.target1.price()} / ${strategy.target2.price()}")
            Text("Valid until ${strategy.validUntil.toTimeText()}")
            Text(strategy.reason.toKoreanReasonHint(), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { onStrategyChart(strategy) }, modifier = Modifier.fillMaxWidth()) { Text("Open chart with strategy lines") }
        }
    }
}

@Composable
private fun EmptyCard(text: String) { Card(modifier = Modifier.fillMaxWidth()) { Text(text = text, modifier = Modifier.padding(12.dp)) } }

private fun StrategyHistoryEntity.toTradeStrategyOrNull(): TradeStrategy? {
    val summary = newSummary ?: oldSummary ?: return null
    val entry = Regex("entry=([0-9.,]+)-([0-9.,]+)").find(summary) ?: return null
    val stop = Regex("stop=([0-9.,]+)").find(summary) ?: return null
    val target = Regex("target=([0-9.,]+)/([0-9.,]+)").find(summary) ?: return null
    val rank = Regex("rank=([0-9]+)").find(summary)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 999
    val score = Regex("score=([0-9.]+)").find(summary)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
    val strategyTypeName = Regex("strategy=([A-Z_]+)").find(summary)?.groupValues?.getOrNull(1)
    val type = strategyTypeName?.let { runCatching { StrategyType.valueOf(it) }.getOrNull() } ?: StrategyType.WATCH_ONLY
    val status = when (eventType) {
        "STOPPED_OUT" -> StrategyStatus.STOPPED_OUT
        "HIT_TARGET" -> StrategyStatus.HIT_TARGET
        "TARGET1_HIT" -> StrategyStatus.TARGET1_HIT
        "TRAILING_STOP_HIT" -> StrategyStatus.TRAILING_STOP_HIT
        "EXPIRED" -> StrategyStatus.EXPIRED
        else -> StrategyStatus.ACTIVE
    }
    val entryLow = entry.groupValues[1].parsePrice()
    val entryHigh = entry.groupValues[2].parsePrice()
    val stopLoss = stop.groupValues[1].parsePrice()
    val target1 = target.groupValues[1].parsePrice()
    val target2 = target.groupValues[2].parsePrice()
    val entryMid = (entryLow + entryHigh) / 2.0
    if (entryLow <= 0.0 || entryHigh <= 0.0 || stopLoss <= 0.0 || target1 <= 0.0) return null
    return TradeStrategy(
        id = strategyId,
        symbol = symbol,
        strategyType = type,
        status = status,
        score = score,
        rank = rank,
        entryLow = entryLow,
        entryHigh = entryHigh,
        stopLoss = stopLoss,
        target1 = target1,
        target2 = target2,
        trailingStop = stopLoss,
        expectedReturnPct = entryMid.toPctValue(target1),
        riskPct = entryMid.toPctValue(stopLoss),
        riskRewardRatio = if (entryMid > stopLoss) (target1 - entryMid) / (entryMid - stopLoss) else 0.0,
        componentScores = "restored from history",
        rankByChangeRate = 0,
        rankByTradeValue = 0,
        changeRate24h = 0.0,
        changeRate30m = 0.0,
        changeRate5m = 0.0,
        volumeAcceleration = 0.0,
        reason = message,
        invalidationReason = null,
        createdAt = createdAt,
        updatedAt = createdAt,
        validUntil = createdAt + 4 * 60 * 60 * 1000L,
    )
}

private fun TradeStrategy.entryMid(): Double = (entryLow + entryHigh) / 2.0
private fun TradeStrategy.koreanPlanLine(): String { val direction = if (strategyType.name == "BTC_SHORT_REGIME") "숏/하락" else "롱/상승"; return "$direction 전략 | 진입 ${entryLow.price()}~${entryHigh.price()} | 손절 ${stopLoss.price()} | 목표 ${target1.price()}/${target2.price()} | 24h ${changeRate24h.one()}% · 30m ${changeRate30m.one()}% · 5m ${changeRate5m.one()}% · 거래량 ${volumeAcceleration.one()}x" }
private fun StrategyHistoryEntity.historyCategory(): String = when {
    eventType == "STOPPED_OUT" || message.contains("Stop", true) -> "손절"
    eventType in setOf("HIT_TARGET", "TARGET1_HIT", "TRAILING_STOP_HIT") -> "성공"
    eventType in setOf("EXPIRED", "INVALIDATED", "WATCH_ONLY") -> "실패/만료"
    else -> "진행/기록"
}
private fun StrategyHistoryEntity.improvementHint(): String = when {
    eventType == "STOPPED_OUT" || message.contains("Stop", true) -> "진입 추격 여부, 손절폭, 거래량 지속성 확인"
    eventType == "EXPIRED" -> "시간 내 변동성 부족. 기대수익률보다 MFE와 거래대금 조건 재검토"
    eventType == "WATCH_ONLY" -> "관찰 신호가 실제 펌핑했는지 30분 후 성과와 비교"
    eventType in setOf("HIT_TARGET", "TARGET1_HIT") -> "동일 조건 반복 가능. 단 목표 도달 후 재진입 금지"
    else -> "점수 구성요소와 당시 차트 위치를 같이 확인"
}
private fun String.parsePrice(): Double = replace(",", "").toDoubleOrNull() ?: 0.0
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
