package com.cryptotradecoach

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cryptotradecoach.data.GitHubSettings
import com.cryptotradecoach.data.ScanDiagnostics
import com.cryptotradecoach.data.TradeStrategy
import com.cryptotradecoach.data.local.StrategyHistoryEntity
import com.cryptotradecoach.data.local.StrategyPerformanceEntity
import com.cryptotradecoach.service.CoinScannerService
import com.cryptotradecoach.service.ScannerStateStore
import com.cryptotradecoach.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
                    onPerformanceRefresh = viewModel::refreshPerformance,
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
    onPerformanceRefresh: () -> Unit,
    onReportUpload: (GitHubSettings) -> Unit,
    onOpenInstallPermissionSettings: () -> Unit,
    onDownloadAndInstallLatestApk: (GitHubSettings) -> Unit,
) {
    val tabs = listOf("Current", "History", "Performance", "Rules", "Settings")
    var selectedTab by remember { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        Text("Crypto Trade Coach", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp))
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
            }
        }
        when (selectedTab) {
            0 -> CurrentStrategiesTab(activeStrategies, scanDiagnostics, lastScanAt, minimumScore)
            1 -> StrategyHistoryTab(historyBySymbol)
            2 -> PerformanceTab(performanceRows, onPerformanceRefresh)
            3 -> RulesTab(currentRulesText, settingsMessage, onRulesRefresh) { onRulesDownload(gitHubSettings) }
            4 -> SettingsTab(
                isRunning = isRunning,
                scanIntervalMs = scanIntervalMs,
                maxDisplayCount = maxDisplayCount,
                minimumScore = minimumScore,
                gitHubSettings = gitHubSettings,
                settingsMessage = settingsMessage,
                onStart = onStart,
                onStop = onStop,
                onIntervalSelected = onIntervalSelected,
                onMaxDisplayChanged = onMaxDisplayChanged,
                onMinimumScoreChanged = onMinimumScoreChanged,
                onGitHubSettingsSaved = onGitHubSettingsSaved,
                onGitHubSettingsTest = onGitHubSettingsTest,
                onRulesDownload = onRulesDownload,
                onReportUpload = onReportUpload,
                onOpenInstallPermissionSettings = onOpenInstallPermissionSettings,
                onDownloadAndInstallLatestApk = onDownloadAndInstallLatestApk,
            )
        }
    }
}

@Composable
private fun CurrentStrategiesTab(activeStrategies: List<TradeStrategy>, scanDiagnostics: ScanDiagnostics, lastScanAt: Long?, minimumScore: Double) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Last scan: ${lastScanAt?.toTimeText() ?: "-"}") }
        item { CurrentStrategySummaryCard(activeStrategies, scanDiagnostics, minimumScore) }
        item { DiagnosticsCard(scanDiagnostics) }
        if (activeStrategies.isEmpty()) {
            item { EmptyCard("No ACTIVE strategy. 점수가 너무 타이트하면 Settings에서 Minimum score를 65 근처로 낮추고, Rejections에서 SCORE_TOO_LOW 비중을 확인하세요.") }
        } else {
            items(activeStrategies) { StrategyCard(it) }
        }
    }
}

@Composable
private fun CurrentStrategySummaryCard(activeStrategies: List<TradeStrategy>, scanDiagnostics: ScanDiagnostics, minimumScore: Double) {
    val btcShort = activeStrategies.firstOrNull { it.strategyType.name == "BTC_SHORT_REGIME" }
    val prePumpCount = activeStrategies.count { it.strategyType.name == "PRE_PUMP_ROTATION" }
    val scoreTooLow = scanDiagnostics.rejectionSummary["SCORE_TOO_LOW"] ?: 0
    val noSignalText = if (scanDiagnostics.candidateCount > 0 && activeStrategies.isEmpty()) {
        "후보 ${scanDiagnostics.candidateCount}개는 평가됐지만 ACTIVE가 없습니다. SCORE_TOO_LOW가 많으면 현재 minimum score ${minimumScore.roundToInt()}가 타이트한 상태입니다."
    } else {
        "스캔 후보가 적으면 Upbit 요청 실패, 캔들 부족, 거래대금 선별 제한을 먼저 봅니다."
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("현재 전략 요약", fontWeight = FontWeight.Bold)
            if (btcShort != null) {
                Text("비트코인: 숏/위험회피 신호 감지 → 진입 ${btcShort.entryLow.price()}~${btcShort.entryHigh.price()}, 손절 ${btcShort.stopLoss.price()}, 목표 ${btcShort.target1.price()}/${btcShort.target2.price()}")
            } else {
                Text("비트코인: 현재 ACTIVE 숏 신호 없음. BTC_SHORT_REGIME은 15분·240분 MA 하방 + 하락 모멘텀 + 매도거래량 조건일 때만 뜹니다.")
            }
            Text("급등 전 알트 탐지: PRE_PUMP_ROTATION ${prePumpCount}개. +10% 이상 급등 후 추격이 아니라, 거래량 점화·좁은 박스 상단·상대강도 개선을 먼저 봅니다.")
            Text("ACTIVE: ${activeStrategies.size}개 | 후보: ${scanDiagnostics.candidateCount}개 | SCORE_TOO_LOW: $scoreTooLow")
            if (activeStrategies.isEmpty()) Text(noSignalText)
        }
    }
}

@Composable
private fun DiagnosticsCard(scanDiagnostics: ScanDiagnostics) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Scan diagnostics", fontWeight = FontWeight.Bold)
            Text("Scanned: ${scanDiagnostics.scannedCount}")
            Text("Candidates: ${scanDiagnostics.candidateCount}")
            Text("Rejected: ${scanDiagnostics.rejectedCount}")
            Text("Last error: ${scanDiagnostics.lastError ?: "-"}")
            if (scanDiagnostics.rejectionSummary.isEmpty()) Text("Rejections: -") else {
                Text("Rejections:")
                scanDiagnostics.rejectionSummary.forEach { (reason, count) -> Text("$reason: $count") }
            }
        }
    }
}

@Composable
private fun StrategyHistoryTab(historyBySymbol: Map<String, List<StrategyHistoryEntity>>) {
    var menuExpanded by remember { mutableStateOf(false) }
    var selectedSymbol by remember(historyBySymbol) { mutableStateOf(historyBySymbol.keys.firstOrNull()) }
    val selectedHistory = selectedSymbol?.let { historyBySymbol[it].orEmpty() }.orEmpty()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (historyBySymbol.isEmpty()) item { EmptyCard("No strategy history.") } else {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("종목별 시간순 전략 변화", fontWeight = FontWeight.Bold)
                        Text("각 줄은 시간 → 이벤트 → 현재 매매전략 순서입니다.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { menuExpanded = true }) { Text(selectedSymbol ?: "Select symbol") }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            historyBySymbol.keys.forEach { symbol ->
                                DropdownMenuItem(text = { Text(symbol) }, onClick = { selectedSymbol = symbol; menuExpanded = false })
                            }
                        }
                    }
                }
                items(selectedHistory) { HistoryCard(it) }
            }
        }
    }
}

@Composable
private fun PerformanceTab(performanceRows: List<StrategyPerformanceEntity>, onPerformanceRefresh: () -> Unit) {
    val rowsByType = performanceRows.groupBy { it.strategyType.name }
    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Text("Strategy performance", fontWeight = FontWeight.Bold); OutlinedButton(onClick = onPerformanceRefresh) { Text("Refresh") } } }
        if (performanceRows.isEmpty()) item { EmptyCard("No performance data yet. Start scanner and wait at least 5 minutes after a strategy appears.") } else {
            rowsByType.forEach { (strategyType, rows) -> item { PerformanceSummaryCard(strategyType, rows) } }
            items(performanceRows.sortedByDescending { it.createdAt }) { PerformanceCard(it) }
        }
    }
}

@Composable
private fun PerformanceSummaryCard(strategyType: String, rows: List<StrategyPerformanceEntity>) {
    val completed = rows.count { it.isComplete }
    val avg5 = rows.mapNotNull { it.return5m }.averageOrNullText()
    val avg15 = rows.mapNotNull { it.return15m }.averageOrNullText()
    val avg30 = rows.mapNotNull { it.return30m }.averageOrNullText()
    val avg60 = rows.mapNotNull { it.return60m }.averageOrNullText()
    val stopRate = if (rows.isEmpty()) 0.0 else rows.count { it.stopHit }.toDouble() / rows.size * 100.0
    val targetRate = if (rows.isEmpty()) 0.0 else rows.count { it.target1Hit || it.target2Hit }.toDouble() / rows.size * 100.0
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(strategyType, fontWeight = FontWeight.Bold)
            Text("Signals: ${rows.size} | Complete: $completed")
            Text("Avg return: 5m $avg5 / 15m $avg15 / 30m $avg30 / 60m $avg60")
            Text("Target hit: ${targetRate.one()}% | Stop hit: ${stopRate.one()}%")
        }
    }
}

@Composable
private fun PerformanceCard(row: StrategyPerformanceEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${row.symbol} | ${row.strategyType} | ${if (row.isComplete) "COMPLETE" else "OPEN"}", fontWeight = FontWeight.Bold)
            Text("Created: ${row.createdAt.toTimeText()} | Updated: ${row.lastUpdatedAt.toTimeText()}")
            Text("Entry: ${row.entryPrice.price()} | Latest: ${row.latestPrice.price()} | Score: ${row.score.one()}")
            Text("Return: 5m ${row.return5m.percentOrDash()} / 15m ${row.return15m.percentOrDash()} / 30m ${row.return30m.percentOrDash()} / 60m ${row.return60m.percentOrDash()}")
            Text("MFE: ${row.mfePct.percent()} | MAE: ${row.maePct.percent()}")
            Text("Target1: ${row.target1Hit} | Target2: ${row.target2Hit} | Stop: ${row.stopHit} | Expired: ${row.expired}")
            Text("Rank by value: ${row.rankByTradeValue}")
        }
    }
}

@Composable
private fun RulesTab(currentRulesText: String, settingsMessage: String?, onRulesRefresh: () -> Unit, onRulesDownload: () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { StrategyManualCard() }
        item { Text("Current rules JSON", fontWeight = FontWeight.Bold) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onRulesRefresh) { Text("Refresh local") }; Button(onClick = onRulesDownload) { Text("Download") } } }
        settingsMessage?.let { item { Text(it) } }
        item { Card(modifier = Modifier.fillMaxWidth()) { Text(currentRulesText.ifBlank { "No rules loaded" }, modifier = Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable
private fun StrategyManualCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("현재 전략 설명", fontWeight = FontWeight.Bold)
            Text("COMPRESSION_BREAKOUT: 15분 박스가 좁아지고 상단에 붙은 상태에서 5분 거래량이 붙으면 돌파 후보로 봅니다.")
            Text("SWEEP_RECLAIM: 단기 저점을 일부러 깨고 다시 회복하면 가짜 이탈 후 반등 후보로 봅니다.")
            Text("TREND_PULLBACK: 큰 추세 위에서 눌림 후 이전 고점을 회복할 때만 봅니다. 현재는 보수적으로 비활성화될 수 있습니다.")
            Text("BEAR_DECOUPLING_BOUNCE: BTC가 약할 때도 거래대금·4시간 거래량이 강한 알트만 예외적으로 봅니다.")
            Text("PRE_PUMP_ROTATION: +10% 급등 전 후보 탐지용입니다. 아직 과열 전인데 거래량 점화, 좁은 박스 상단, 상대강도 개선이 동시에 나오는 종목을 잡습니다.")
            Text("BTC_SHORT_REGIME: KRW-BTC가 15분·240분 평균선 아래에서 하락 모멘텀과 매도 거래량이 붙으면 숏/위험회피 신호로 표시합니다.")
            Text("신호가 너무 안 나오면 먼저 Minimum score를 65로 낮추고, Rejections에서 SCORE_TOO_LOW와 INSUFFICIENT_CANDLE_DATA 비중을 확인하세요.")
        }
    }
}

@Composable
private fun SettingsTab(
    isRunning: Boolean,
    scanIntervalMs: Long,
    maxDisplayCount: Int,
    minimumScore: Double,
    gitHubSettings: GitHubSettings,
    settingsMessage: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onIntervalSelected: (Long) -> Unit,
    onMaxDisplayChanged: (Int) -> Unit,
    onMinimumScoreChanged: (Double) -> Unit,
    onGitHubSettingsSaved: (GitHubSettings) -> Unit,
    onGitHubSettingsTest: (GitHubSettings) -> Unit,
    onRulesDownload: (GitHubSettings) -> Unit,
    onReportUpload: (GitHubSettings) -> Unit,
    onOpenInstallPermissionSettings: () -> Unit,
    onDownloadAndInstallLatestApk: (GitHubSettings) -> Unit,
) {
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
        item { Text("GitHub에 새 APK가 올라간 뒤, 여기서 다운로드와 설치 화면 열기까지 처리합니다. 최종 설치 버튼은 Android 보안상 직접 눌러야 합니다.", style = MaterialTheme.typography.bodySmall) }
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
private fun StrategyCard(strategy: TradeStrategy) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("#${strategy.rank} ${strategy.symbol} | ${strategy.strategyType.name.toKoreanStrategyName()}", fontWeight = FontWeight.Bold)
            Text(strategy.koreanPlanLine())
            Text("Score: ${strategy.score.one()} | Expected: ${strategy.expectedReturnPct.percent()} | R/R: ${strategy.riskRewardRatio.one()}")
            Text("Entry: ${strategy.entryLow.price()} - ${strategy.entryHigh.price()}")
            Text("Stop: ${strategy.stopLoss.price()} | Targets: ${strategy.target1.price()} / ${strategy.target2.price()}")
            Text("Valid until: ${strategy.validUntil.toTimeText()} | Updated: ${strategy.updatedAt.toTimeText()}")
            Text(strategy.reason.toKoreanReasonHint())
        }
    }
}

@Composable
private fun HistoryCard(history: StrategyHistoryEntity) {
    Card(modifier = Modifier.fillMaxWidth().clickable { }) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(history.toTimelineLine(), fontWeight = FontWeight.Bold)
            history.oldSummary?.let { Text("이전: ${it.toCompactPlan()}", style = MaterialTheme.typography.bodySmall) }
            history.newSummary?.let { Text("현재: ${it.toCompactPlan()}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) { Text(text = text, modifier = Modifier.padding(12.dp)) }
}

private fun TradeStrategy.koreanPlanLine(): String {
    val direction = if (strategyType.name == "BTC_SHORT_REGIME") "숏/하락" else "롱/상승"
    return "$direction 전략 | 진입 ${entryLow.price()}~${entryHigh.price()} | 손절 ${stopLoss.price()} | 목표 ${target1.price()}/${target2.price()} | 24h ${changeRate24h.one()}% · 30m ${changeRate30m.one()}% · 5m ${changeRate5m.one()}% · 거래량 ${volumeAcceleration.one()}x"
}

private fun StrategyHistoryEntity.toTimelineLine(): String {
    val current = newSummary?.toCompactPlan().orEmpty()
    val suffix = if (current.isBlank()) message else current
    return "${createdAt.toTimeText()} ${symbol} ${eventType.toKoreanEventName()} → $suffix"
}

private fun String.toCompactPlan(): String {
    return replace("rank=", "순위 ").replace("score=", "점수 ").replace("entry=", "진입 ").replace("stop=", "손절 ").replace("target=", "목표 ").replace("trail=", "추적손절 ").replace("status=", "상태 ")
}

private fun String.toKoreanStrategyName(): String = when (this) {
    "COMPRESSION_BREAKOUT" -> "압축 돌파"
    "SWEEP_RECLAIM" -> "저점 훼손 후 회복"
    "TREND_PULLBACK" -> "추세 눌림 회복"
    "BEAR_DECOUPLING_BOUNCE" -> "약세장 독립강세"
    "PRE_PUMP_ROTATION" -> "급등 전 회전 포착"
    "BTC_SHORT_REGIME" -> "비트코인 숏/위험회피"
    "MOMENTUM_BREAKOUT" -> "모멘텀 돌파"
    "PULLBACK_REBOUND" -> "눌림 반등"
    "VOLUME_EXPANSION" -> "거래량 확장"
    else -> this
}

private fun String.toKoreanEventName(): String = when (this) {
    "NEW_ACTIVE" -> "신규진입"
    "RANK_UP" -> "순위상승"
    "PRICE_PLAN_CHANGED" -> "전략변경"
    "WATCH_ONLY" -> "관찰전환"
    "INVALIDATED" -> "무효화"
    "TARGET1_HIT" -> "1차목표도달"
    "TRAILING_STOP_HIT" -> "추적손절"
    "HIT_TARGET" -> "최종목표도달"
    "STOPPED_OUT" -> "손절"
    "EXPIRED" -> "만료"
    else -> this
}

private fun String.toKoreanReasonHint(): String {
    return replace("PRE_PUMP_ROTATION", "급등 전 회전 포착")
        .replace("BTC_SHORT_REGIME", "비트코인 숏/위험회피")
        .replace("COMPRESSION_BREAKOUT", "압축 돌파")
        .replace("SWEEP_RECLAIM", "저점 훼손 후 회복")
        .replace("TREND_PULLBACK", "추세 눌림 회복")
        .replace("BEAR_DECOUPLING_BOUNCE", "약세장 독립강세")
        .replace("ACTIVE", "활성")
        .replace("WATCH_ONLY", "관찰")
}

private fun Double.price(): String = String.format("%,.2f", this)
private fun Double.percent(): String = String.format("%.2f%%", this)
private fun Double?.percentOrDash(): String = this?.percent() ?: "-"
private fun Double.one(): String = String.format("%.1f", this)
private fun List<Double>.averageOrNullText(): String = if (isEmpty()) "-" else average().percent()
private fun Long.toTimeText(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(this))
