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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cryptotradecoach.data.GitHubSettings
import com.cryptotradecoach.data.ScanDiagnostics
import com.cryptotradecoach.data.TradeStrategy
import com.cryptotradecoach.data.local.StrategyHistoryEntity
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
                    scanDiagnostics = state.scanDiagnostics,
                    isRunning = state.isRunning,
                    lastScanAt = state.lastScanAt,
                    scanIntervalMs = state.scanIntervalMs,
                    maxDisplayCount = state.maxDisplayCount,
                    minimumScore = state.minimumScore,
                    gitHubSettings = state.gitHubSettings,
                    onStart = { startScanner() },
                    onStop = { stopScanner() },
                    onIntervalSelected = viewModel::setScanInterval,
                    onMaxDisplayChanged = viewModel::setMaxDisplayCount,
                    onMinimumScoreChanged = viewModel::setMinimumScore,
                    onGitHubSettingsSaved = viewModel::saveGitHubSettings,
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startScanner() {
        val intent = Intent(this, CoinScannerService::class.java).apply {
            action = CoinScannerService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopScanner() {
        val intent = Intent(this, CoinScannerService::class.java).apply {
            action = CoinScannerService.ACTION_STOP
        }
        startService(intent)
    }
}

@Composable
private fun MainScreen(
    activeStrategies: List<TradeStrategy>,
    historyBySymbol: Map<String, List<StrategyHistoryEntity>>,
    scanDiagnostics: ScanDiagnostics,
    isRunning: Boolean,
    lastScanAt: Long?,
    scanIntervalMs: Long,
    maxDisplayCount: Int,
    minimumScore: Double,
    gitHubSettings: GitHubSettings,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onIntervalSelected: (Long) -> Unit,
    onMaxDisplayChanged: (Int) -> Unit,
    onMinimumScoreChanged: (Double) -> Unit,
    onGitHubSettingsSaved: (GitHubSettings) -> Unit,
) {
    val tabs = listOf("Current", "History", "Settings")
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Crypto Trade Coach",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp),
        )
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) },
                )
            }
        }
        when (selectedTab) {
            0 -> CurrentStrategiesTab(activeStrategies, scanDiagnostics, lastScanAt)
            1 -> StrategyHistoryTab(historyBySymbol)
            2 -> SettingsTab(
                isRunning = isRunning,
                scanIntervalMs = scanIntervalMs,
                maxDisplayCount = maxDisplayCount,
                minimumScore = minimumScore,
                gitHubSettings = gitHubSettings,
                onStart = onStart,
                onStop = onStop,
                onIntervalSelected = onIntervalSelected,
                onMaxDisplayChanged = onMaxDisplayChanged,
                onMinimumScoreChanged = onMinimumScoreChanged,
                onGitHubSettingsSaved = onGitHubSettingsSaved,
            )
        }
    }
}

@Composable
private fun CurrentStrategiesTab(
    activeStrategies: List<TradeStrategy>,
    scanDiagnostics: ScanDiagnostics,
    lastScanAt: Long?,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Last scan: ${lastScanAt?.toTimeText() ?: "-"}") }
        item { DiagnosticsCard(scanDiagnostics) }
        if (activeStrategies.isEmpty()) {
            item { EmptyCard("No ACTIVE strategy. See diagnostics for scan failures or rejected conditions.") }
        } else {
            items(activeStrategies) { strategy ->
                StrategyCard(strategy)
            }
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
            if (scanDiagnostics.rejectionSummary.isEmpty()) {
                Text("Rejections: -")
            } else {
                Text("Rejections:")
                scanDiagnostics.rejectionSummary.forEach { (reason, count) ->
                    Text("$reason: $count")
                }
            }
        }
    }
}

@Composable
private fun StrategyHistoryTab(historyBySymbol: Map<String, List<StrategyHistoryEntity>>) {
    var menuExpanded by remember { mutableStateOf(false) }
    var selectedSymbol by remember(historyBySymbol) { mutableStateOf(historyBySymbol.keys.firstOrNull()) }
    val selectedHistory = selectedSymbol?.let { historyBySymbol[it].orEmpty() }.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (historyBySymbol.isEmpty()) {
                item { EmptyCard("No strategy history.") }
            } else {
                item {
                    Column {
                        OutlinedButton(onClick = { menuExpanded = true }) {
                            Text(selectedSymbol ?: "Select symbol")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            historyBySymbol.keys.forEach { symbol ->
                                DropdownMenuItem(
                                    text = { Text(symbol) },
                                    onClick = {
                                        selectedSymbol = symbol
                                        menuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                items(selectedHistory) { history ->
                    HistoryCard(history)
                }
            }
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
    onStart: () -> Unit,
    onStop: () -> Unit,
    onIntervalSelected: (Long) -> Unit,
    onMaxDisplayChanged: (Int) -> Unit,
    onMinimumScoreChanged: (Double) -> Unit,
    onGitHubSettingsSaved: (GitHubSettings) -> Unit,
) {
    var owner by remember(gitHubSettings) { mutableStateOf(gitHubSettings.owner) }
    var repo by remember(gitHubSettings) { mutableStateOf(gitHubSettings.repo) }
    var branch by remember(gitHubSettings) { mutableStateOf(gitHubSettings.branch) }
    var token by remember(gitHubSettings) { mutableStateOf(gitHubSettings.token) }
    var rulesPath by remember(gitHubSettings) { mutableStateOf(gitHubSettings.rulesPath) }
    var reportPath by remember(gitHubSettings) { mutableStateOf(gitHubSettings.reportPath) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Status: ${if (isRunning) "running" else "stopped"}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = !isRunning) { Text("Start scanner") }
            OutlinedButton(onClick = onStop, enabled = isRunning) { Text("Stop") }
        }
        Text("Scan interval")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScannerStateStore.SUPPORTED_INTERVALS_MS.forEach { interval ->
                FilterChip(
                    selected = scanIntervalMs == interval,
                    onClick = { onIntervalSelected(interval) },
                    label = { Text("${interval / 1000}s") },
                )
            }
        }
        Text("Max displayed symbols: $maxDisplayCount")
        Slider(
            value = maxDisplayCount.toFloat(),
            onValueChange = { onMaxDisplayChanged(it.roundToInt()) },
            valueRange = 1f..10f,
            steps = 8,
        )
        Text("Minimum score: ${minimumScore.roundToInt()}")
        Slider(
            value = minimumScore.toFloat(),
            onValueChange = { onMinimumScoreChanged(it.toDouble()) },
            valueRange = 50f..90f,
            steps = 7,
        )
        Text("GitHub sync", fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = owner,
            onValueChange = { owner = it },
            label = { Text("Owner") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = repo,
            onValueChange = { repo = it },
            label = { Text("Repo") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = branch,
            onValueChange = { branch = it },
            label = { Text("Branch") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = rulesPath,
            onValueChange = { rulesPath = it },
            label = { Text("Rules path") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = reportPath,
            onValueChange = { reportPath = it },
            label = { Text("Report path") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = {
                onGitHubSettingsSaved(
                    GitHubSettings(
                        owner = owner,
                        repo = repo,
                        branch = branch,
                        token = token,
                        rulesPath = rulesPath,
                        reportPath = reportPath,
                    ),
                )
            },
        ) {
            Text("Save GitHub settings")
        }
    }
}

@Composable
private fun StrategyCard(strategy: TradeStrategy) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("#${strategy.rank} ${strategy.symbol} | ${strategy.strategyType}", fontWeight = FontWeight.Bold)
            Text("Score: ${strategy.score.one()} | Expected: ${strategy.expectedReturnPct.percent()} | R/R: ${strategy.riskRewardRatio.one()}")
            Text("Entry: ${strategy.entryLow.price()} - ${strategy.entryHigh.price()}")
            Text("Stop: ${strategy.stopLoss.price()} | Targets: ${strategy.target1.price()} / ${strategy.target2.price()}")
            Text("Valid until: ${strategy.validUntil.toTimeText()} | Updated: ${strategy.updatedAt.toTimeText()}")
            Text(strategy.reason)
        }
    }
}

@Composable
private fun HistoryCard(history: StrategyHistoryEntity) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { },
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${history.symbol} | ${history.eventType}", fontWeight = FontWeight.Bold)
            Text(history.createdAt.toTimeText())
            Text(history.message)
            history.oldSummary?.let { Text("Before: $it") }
            history.newSummary?.let { Text("After: $it") }
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(text = text, modifier = Modifier.padding(12.dp))
    }
}

private fun Double.price(): String = String.format("%,.2f", this)

private fun Double.percent(): String = String.format("%.2f%%", this)

private fun Double.one(): String = String.format("%.1f", this)

private fun Long.toTimeText(): String {
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    return formatter.format(Date(this))
}
