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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.cryptotradecoach.data.Signal
import com.cryptotradecoach.data.local.GuidelineChangeEntity
import com.cryptotradecoach.data.local.MissedSignalEntity
import com.cryptotradecoach.data.local.SignalHistoryEntity
import com.cryptotradecoach.data.local.StrategyReviewEntity
import com.cryptotradecoach.data.remote.KrShortStock
import com.cryptotradecoach.data.remote.StockScannerSnapshot
import com.cryptotradecoach.service.CoinScannerService
import com.cryptotradecoach.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                    isRunning = state.isRunning,
                    topSignals = state.topSignals,
                    historyByMarket = state.historyByMarket,
                    missedSignals = state.missedSignals,
                    strategyReviews = state.strategyReviews,
                    guidelineChanges = state.guidelineChanges,
                    lastScanAt = state.lastScanAt,
                    stockSnapshot = state.stockSnapshot,
                    stockLoading = state.stockLoading,
                    stockError = state.stockError,
                    onStart = { startScanner() },
                    onStop = { stopScanner() },
                    onRefreshStocks = { viewModel.refreshStockScanner() },
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
    isRunning: Boolean,
    topSignals: List<Signal>,
    historyByMarket: Map<String, List<SignalHistoryEntity>>,
    missedSignals: List<MissedSignalEntity>,
    strategyReviews: List<StrategyReviewEntity>,
    guidelineChanges: List<GuidelineChangeEntity>,
    lastScanAt: Long?,
    stockSnapshot: StockScannerSnapshot?,
    stockLoading: Boolean,
    stockError: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefreshStocks: () -> Unit,
) {
    val tabs = listOf("Current", "Stocks", "History", "Missed", "Review", "Guidelines")
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
            0 -> CurrentTab(isRunning, topSignals, lastScanAt, onStart, onStop)
            1 -> StocksTab(stockSnapshot, stockLoading, stockError, onRefreshStocks)
            2 -> HistoryTab(historyByMarket)
            3 -> MissedTab(missedSignals)
            4 -> ReviewTab(strategyReviews)
            5 -> GuidelinesTab(guidelineChanges)
        }
    }
}

@Composable
private fun StocksTab(
    snapshot: StockScannerSnapshot?,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRefresh, enabled = !loading) { Text(if (loading) "Loading" else "Refresh") }
        }
        if (error != null) {
            EmptyCard("Stock scanner API error: $error")
        }
        if (snapshot == null) {
            EmptyCard(if (loading) "Loading latest stock scan." else "No stock scan loaded yet.")
            return@Column
        }
        Text("Scan: ${snapshot.createdAtKst} / mode=${snapshot.mode}")
        Text("Quote quality: ${snapshot.quoteOk}/${snapshot.total} (${(snapshot.quoteOkRate * 100).toDisplay()}%)")
        Text("KR short candidates", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (snapshot.krShortStocks.isEmpty()) {
                item { EmptyCard("No KR short candidates.") }
            } else {
                items(snapshot.krShortStocks) { stock -> StockRow(stock) }
            }
        }
    }
}

@Composable
private fun StockRow(stock: KrShortStock) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${stock.name}(${stock.code}) | ${stock.sector}/${stock.strategyType}", fontWeight = FontWeight.Bold)
            Text("Score: ${stock.score.toDisplay()} | Risk: ${stock.riskPct.toDisplay()}%")
            Text("Now: ${stock.currentPrice.toPrice()} (${stock.priceBasis}, ${stock.quoteSource})")
            Text("Time: ${stock.priceTimestamp}")
            Text("Entry: ${stock.entry.toPrice()} | Stop: ${stock.stopLoss.toPrice()} | Target: ${stock.target1.toPrice()} → ${stock.target2.toPrice()}")
            Text("Reason: ${stock.reason}")
            Text("Invalidation: ${stock.failureCondition}")
        }
    }
}

@Composable
private fun CurrentTab(
    isRunning: Boolean,
    topSignals: List<Signal>,
    lastScanAt: Long?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Status: ${if (isRunning) "auto scan running" else "stopped"}")
        Text("Last scan: ${lastScanAt?.toTimeText() ?: "-"}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = !isRunning) { Text("Start auto scan") }
            Button(onClick = onStop, enabled = isRunning) { Text("Stop") }
        }
        Text("Current valid strategies Top 5", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (topSignals.isEmpty()) {
                item { EmptyCard(if (isRunning) "Waiting for the next scan result." else "Start auto scan to load current strategies.") }
            } else {
                items(topSignals) { signal -> SignalRow(signal) }
            }
        }
    }
}

@Composable
private fun HistoryTab(historyByMarket: Map<String, List<SignalHistoryEntity>>) {
    var historyMenuExpanded by remember { mutableStateOf(false) }
    var selectedMarket by remember(historyByMarket) { mutableStateOf(historyByMarket.keys.firstOrNull()) }
    val selectedHistory = selectedMarket?.let { historyByMarket[it].orEmpty() }.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { historyMenuExpanded = true },
                enabled = historyByMarket.isNotEmpty(),
            ) {
                Text(selectedMarket ?: "Select market")
            }
            DropdownMenu(
                expanded = historyMenuExpanded,
                onDismissRequest = { historyMenuExpanded = false },
            ) {
                historyByMarket.keys.forEach { market ->
                    DropdownMenuItem(
                        text = { Text(market) },
                        onClick = {
                            selectedMarket = market
                            historyMenuExpanded = false
                        },
                    )
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectedHistory.isEmpty()) {
                item { EmptyCard("No strategy history yet.") }
            } else {
                items(selectedHistory) { history -> HistoryRow(history) }
            }
        }
    }
}

@Composable
private fun MissedTab(missedSignals: List<MissedSignalEntity>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (missedSignals.isEmpty()) {
            item { EmptyCard("No missed signals detected yet.") }
        } else {
            items(missedSignals) { missed -> MissedRow(missed) }
        }
    }
}

@Composable
private fun ReviewTab(strategyReviews: List<StrategyReviewEntity>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (strategyReviews.isEmpty()) {
            item { EmptyCard("No strategy review report yet.") }
        } else {
            items(strategyReviews) { review -> ReviewRow(review) }
        }
    }
}

@Composable
private fun GuidelinesTab(guidelineChanges: List<GuidelineChangeEntity>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (guidelineChanges.isEmpty()) {
            item { EmptyCard("No guideline change suggestions yet.") }
        } else {
            items(guidelineChanges) { change -> GuidelineRow(change) }
        }
    }
}

@Composable
private fun SignalRow(signal: Signal) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${signal.market} | ${signal.strategyName}", fontWeight = FontWeight.Bold)
            Text("Score: ${signal.score.toDisplay()}")
            Text("Entry: ${signal.entryPrice.toDisplay()} | Stop: ${signal.stopLossPrice.toDisplay()} | Target: ${signal.targetPrice.toDisplay()}")
            Text("Reason: ${signal.reason}")
        }
    }
}

@Composable
private fun HistoryRow(history: SignalHistoryEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${history.eventType} | ${history.strategyName}", fontWeight = FontWeight.Bold)
            Text("${history.createdAt.toTimeText()} | Price: ${history.currentPrice.toDisplay()} | Score: ${history.score.toDisplay()}")
            Text("Entry: ${history.entryPrice.toDisplay()} | Stop: ${history.stopLossPrice.toDisplay()} | Target: ${history.targetPrice.toDisplay()}")
            Text("MFE: ${history.mfePercent.toPercent()} | MAE: ${history.maePercent.toPercent()}")
            Text(history.reason)
        }
    }
}

@Composable
private fun MissedRow(missed: MissedSignalEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${missed.market} | ${missed.detectedAt.toTimeText()}", fontWeight = FontWeight.Bold)
            Text("Change: ${missed.changeRate.toPercent()} | ${missed.previousPrice.toDisplay()} -> ${missed.currentPrice.toDisplay()}")
            Text("Ranks: change ${missed.rankByChangeRate}, trade value ${missed.rankByTradeValue}")
            Text("Reason: ${missed.missedReason}")
            Text("Suggested: ${missed.suggestedStrategy}")
        }
    }
}

@Composable
private fun ReviewRow(review: StrategyReviewEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${review.strategyName} | ${review.reviewedAt.toTimeText()}", fontWeight = FontWeight.Bold)
            Text("Target: ${review.targetHitCount} | Stop: ${review.stopHitCount} | Expired: ${review.expiredCount}")
            Text("Active: ${review.totalActiveSignals} | Missed: ${review.totalMissedSignals}")
            Text("MFE avg: ${review.averageMfePercent.toPercent()} | MAE avg: ${review.averageMaePercent.toPercent()}")
            Text("Diagnosis: ${review.diagnosis}")
            Text("Suggestion: ${review.ruleChangeSuggestion}")
        }
    }
}

@Composable
private fun GuidelineRow(change: GuidelineChangeEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${change.affectedStrategyName} | Applied: ${change.applied}", fontWeight = FontWeight.Bold)
            Text("Before: ${change.beforeRule}")
            Text("After: ${change.afterRule}")
            Text("Reason: ${change.reason}")
            Text("Evidence: ${change.evidenceMarkets}")
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(text = text, modifier = Modifier.padding(12.dp))
    }
}

private fun Double.toDisplay(): String = String.format("%,.2f", this)

private fun Double.toPercent(): String = String.format("%.2f%%", this)

private fun Double.toPrice(): String = String.format("%,.0f", this)

private fun Long.toTimeText(): String {
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    return formatter.format(Date(this))
}
