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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
                    isRunning = state.isRunning,
                    lastScanAt = state.lastScanAt,
                    scanIntervalMs = state.scanIntervalMs,
                    maxDisplayCount = state.maxDisplayCount,
                    minimumScore = state.minimumScore,
                    onStart = { startScanner() },
                    onStop = { stopScanner() },
                    onIntervalSelected = viewModel::setScanInterval,
                    onMaxDisplayChanged = viewModel::setMaxDisplayCount,
                    onMinimumScoreChanged = viewModel::setMinimumScore,
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
    isRunning: Boolean,
    lastScanAt: Long?,
    scanIntervalMs: Long,
    maxDisplayCount: Int,
    minimumScore: Double,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onIntervalSelected: (Long) -> Unit,
    onMaxDisplayChanged: (Int) -> Unit,
    onMinimumScoreChanged: (Double) -> Unit,
) {
    val tabs = listOf("현재 전략", "추천 내역", "설정")
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
            0 -> CurrentStrategiesTab(activeStrategies, lastScanAt)
            1 -> StrategyHistoryTab(historyBySymbol)
            2 -> SettingsTab(
                isRunning = isRunning,
                scanIntervalMs = scanIntervalMs,
                maxDisplayCount = maxDisplayCount,
                minimumScore = minimumScore,
                onStart = onStart,
                onStop = onStop,
                onIntervalSelected = onIntervalSelected,
                onMaxDisplayChanged = onMaxDisplayChanged,
                onMinimumScoreChanged = onMinimumScoreChanged,
            )
        }
    }
}

@Composable
private fun CurrentStrategiesTab(
    activeStrategies: List<TradeStrategy>,
    lastScanAt: Long?,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("마지막 스캔: ${lastScanAt?.toTimeText() ?: "-"}")
        }
        if (activeStrategies.isEmpty()) {
            item { EmptyCard("현재 유효한 전략이 없습니다.") }
        } else {
            items(activeStrategies) { strategy ->
                StrategyCard(strategy)
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
                item { EmptyCard("추천 내역이 없습니다.") }
            } else {
                item {
                    Column {
                        OutlinedButton(onClick = { menuExpanded = true }) {
                            Text(selectedSymbol ?: "종목 선택")
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
    onStart: () -> Unit,
    onStop: () -> Unit,
    onIntervalSelected: (Long) -> Unit,
    onMaxDisplayChanged: (Int) -> Unit,
    onMinimumScoreChanged: (Double) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("상태: ${if (isRunning) "실행 중" else "중지됨"}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = !isRunning) { Text("스캐너 시작") }
            OutlinedButton(onClick = onStop, enabled = isRunning) { Text("중지") }
        }
        Text("스캔 주기")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScannerStateStore.SUPPORTED_INTERVALS_MS.forEach { interval ->
                FilterChip(
                    selected = scanIntervalMs == interval,
                    onClick = { onIntervalSelected(interval) },
                    label = { Text("${interval / 1000}초") },
                )
            }
        }
        Text("최대 표시 종목 수: $maxDisplayCount")
        Slider(
            value = maxDisplayCount.toFloat(),
            onValueChange = { onMaxDisplayChanged(it.roundToInt()) },
            valueRange = 1f..10f,
            steps = 8,
        )
        Text("최소 점수 기준: ${minimumScore.roundToInt()}점")
        Slider(
            value = minimumScore.toFloat(),
            onValueChange = { onMinimumScoreChanged(it.toDouble()) },
            valueRange = 50f..90f,
            steps = 7,
        )
    }
}

@Composable
private fun StrategyCard(strategy: TradeStrategy) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("#${strategy.rank} ${strategy.symbol} | ${strategy.strategyType}", fontWeight = FontWeight.Bold)
            Text("점수: ${strategy.score.one()} | 기대수익: ${strategy.expectedReturnPct.percent()} | 손익비: ${strategy.riskRewardRatio.one()}")
            Text("진입: ${strategy.entryLow.price()} - ${strategy.entryHigh.price()}")
            Text("손절: ${strategy.stopLoss.price()} | 목표: ${strategy.target1.price()} / ${strategy.target2.price()}")
            Text("유효시간: ${strategy.validUntil.toTimeText()} | 업데이트: ${strategy.updatedAt.toTimeText()}")
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
            history.oldSummary?.let { Text("이전: $it") }
            history.newSummary?.let { Text("변경: $it") }
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
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    return formatter.format(Date(this))
}
