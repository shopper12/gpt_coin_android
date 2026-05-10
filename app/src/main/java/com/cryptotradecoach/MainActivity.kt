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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cryptotradecoach.data.Signal
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
                    lastScanAt = state.lastScanAt,
                    onStart = { startScanner() },
                    onStop = { stopScanner() },
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
    historyByMarket: Map<String, List<Signal>>,
    lastScanAt: Long?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    var historyMenuExpanded by remember { mutableStateOf(false) }
    var selectedMarket by remember { mutableStateOf<String?>(null) }
    val selectedHistory = selectedMarket?.let { historyByMarket[it].orEmpty() }.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Crypto Trade Coach", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Status: ${if (isRunning) "auto scan running" else "stopped"}")
        Text("Last scan: ${lastScanAt?.toTimeText() ?: "-"}")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = !isRunning) { Text("Start auto scan") }
            Button(onClick = onStop, enabled = isRunning) { Text("Stop") }
        }

        Text("Top 5 valid strategies", style = MaterialTheme.typography.titleMedium)
        if (topSignals.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (isRunning) "Waiting for the next scan result." else "Start auto scan to load current strategies.",
                    modifier = Modifier.padding(12.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(topSignals) { signal ->
                    SignalRow(signal)
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text("Recommendation history by market", style = MaterialTheme.typography.titleMedium)
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

        if (selectedMarket != null) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(selectedHistory) { signal ->
                    HistoryRow(signal)
                }
            }
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
private fun HistoryRow(signal: Signal) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${signal.timestamp.toTimeText()} | ${signal.strategyName}", fontWeight = FontWeight.Bold)
            Text("Entry: ${signal.entryPrice.toDisplay()} | Stop: ${signal.stopLossPrice.toDisplay()} | Target: ${signal.targetPrice.toDisplay()}")
            Text("Score: ${signal.score.toDisplay()}")
        }
    }
}

private fun Double.toDisplay(): String = String.format("%,.2f", this)

private fun Long.toTimeText(): String {
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    return formatter.format(Date(this))
}
