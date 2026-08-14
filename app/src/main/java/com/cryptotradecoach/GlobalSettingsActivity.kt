package com.cryptotradecoach

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptotradecoach.service.CoinScannerService
import com.cryptotradecoach.ui.MainViewModel

class GlobalSettingsActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state by viewModel.uiState.collectAsState()
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(onClick = { finish() }) { Text("← 홈") }
                        Text(
                            "전체 설정",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        GlobalSettingsPanel(
                            isRunning = state.isRunning,
                            scanIntervalMs = state.scanIntervalMs,
                            maxDisplayCount = state.maxDisplayCount,
                            minimumScore = state.minimumScore,
                            gitHubSettings = state.gitHubSettings,
                            settingsMessage = state.settingsMessage,
                            onStart = { startScanner() },
                            onStop = { stopScanner() },
                            onIntervalSelected = viewModel::setScanInterval,
                            onMaxDisplayChanged = viewModel::setMaxDisplayCount,
                            onMinimumScoreChanged = viewModel::setMinimumScore,
                            onGitHubSettingsSaved = viewModel::saveGitHubSettings,
                            onGitHubSettingsTest = viewModel::testGitHubSettings,
                            onRulesDownload = viewModel::downloadLatestRules,
                            onReportUpload = viewModel::uploadLatestReport,
                            onOpenInstallPermissionSettings = viewModel::openInstallPermissionSettings,
                            onDownloadAndInstallLatestApk = viewModel::downloadAndInstallLatestApk,
                        )
                    }
                }
            }
        }
    }

    private fun startScanner() {
        val intent = Intent(this, CoinScannerService::class.java).apply {
            action = CoinScannerService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopScanner() {
        startService(Intent(this, CoinScannerService::class.java).apply {
            action = CoinScannerService.ACTION_STOP
        })
    }
}
