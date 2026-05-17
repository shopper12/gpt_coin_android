package com.cryptotradecoach.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.cryptotradecoach.data.ScanDiagnostics
import com.cryptotradecoach.data.SettingsRepository
import com.cryptotradecoach.data.SignalHistoryRepository
import com.cryptotradecoach.data.StrategyReportRepository
import com.cryptotradecoach.data.StrategyRulesRepository
import com.cryptotradecoach.data.UpbitMarketDataSource
import com.cryptotradecoach.data.local.StrategyEventType
import com.cryptotradecoach.domain.SignalEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CoinScannerService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataSource = UpbitMarketDataSource()
    private val engine = SignalEngine()
    private lateinit var notifier: SignalNotificationHelper
    private lateinit var historyRepository: SignalHistoryRepository
    private lateinit var rulesRepository: StrategyRulesRepository
    private lateinit var reportRepository: StrategyReportRepository
    private lateinit var settingsRepository: SettingsRepository
    private var scanJob: Job? = null
    private var lastAutoUploadAttemptAt: Long = 0L

    override fun onCreate() {
        super.onCreate()
        notifier = SignalNotificationHelper(this)
        historyRepository = SignalHistoryRepository.getInstance(this)
        rulesRepository = StrategyRulesRepository.getInstance(this)
        reportRepository = StrategyReportRepository.getInstance(this)
        settingsRepository = SettingsRepository.getInstance(this)
        notifier.ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopScanner()
            else -> startScanner()
        }
        return START_STICKY
    }

    private fun startScanner() {
        if (scanJob?.isActive == true) return
        val notification = notifier.foregroundNotification(ScannerStateStore.DEFAULT_SCAN_INTERVAL_MS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        ScannerStateStore.setRunning(true)

        scanJob = serviceScope.launch {
            while (isActive) {
                ScannerStateStore.markScanAttempt(this@CoinScannerService)
                runCatching {
                    val maxDisplayCount = ScannerStateStore.maxDisplayCount.first()
                    val minimumScore = ScannerStateStore.minimumScore.first()
                    val rules = rulesRepository.refreshFromGitHub()
                    val tickers = dataSource.fetchTickers()
                    val candleTargets = dataSource.selectCandleTargets(tickers)
                    val candleData = dataSource.fetchCandleData(candleTargets)
                    val scanResult = engine.scan(
                        tickers = candleTargets,
                        candleData = candleData,
                        rules = rules,
                        minimumScore = minimumScore,
                        maxResults = maxDisplayCount,
                    )
                    val currentPrices = candleTargets.associate { it.market to it.tradePrice }
                    val persistence = historyRepository.saveStrategyScanResult(
                        scanResult = scanResult,
                        currentPrices = currentPrices,
                    )
                    ScannerStateStore.pushScanResult(
                        activeStrategies = persistence.activeStrategies,
                        historyBySymbol = persistence.historyBySymbol,
                        diagnostics = ScanDiagnostics(
                            validSignals = scanResult.validSignals,
                            scannedCount = scanResult.scannedCount,
                            candidateCount = scanResult.candidateCount,
                            rejectedCount = scanResult.rejectedCount,
                            rejectionSummary = scanResult.rejectionSummary,
                            lastError = scanResult.lastError ?: dataSource.lastError,
                        ),
                        context = this@CoinScannerService,
                    )
                    reportRepository.generateLatestReport(rules = rules)
                    maybeAutoUploadLatestReport()
                    persistence.newEvents
                        .filter { event ->
                            event.eventType != StrategyEventType.NEW_ACTIVE ||
                                event.newSummary.orEmpty().contains("rank=1") ||
                                event.newSummary.orEmpty().contains("rank=2") ||
                                event.newSummary.orEmpty().contains("rank=3")
                        }
                        .forEachIndexed { index, event ->
                            notifier.notifyStrategyEvent(event, STRATEGY_EVENT_NOTIFICATION_BASE + index)
                        }
                }.onFailure { error ->
                    Log.e("CryptoScanner", "Scan failed", error)
                    ScannerStateStore.setLastError(error.message ?: error::class.java.simpleName)
                }
                delay(ScannerStateStore.scanIntervalMs.first())
            }
        }
    }

    private fun maybeAutoUploadLatestReport(now: Long = System.currentTimeMillis()) {
        val settings = settingsRepository.load().normalized()
        if (!settings.autoUploadReport) return
        if (settings.token.isBlank()) {
            ScannerStateStore.setLastError("Auto report upload skipped: GitHub token is missing")
            return
        }
        val lastSuccessfulUploadAt = settingsRepository.loadLastAutoReportUploadAt()
        val lastUploadGateAt = maxOf(lastSuccessfulUploadAt, lastAutoUploadAttemptAt)
        if (now - lastUploadGateAt < AUTO_REPORT_UPLOAD_INTERVAL_MS) return

        lastAutoUploadAttemptAt = now
        val uploaded = reportRepository.uploadLatestReport()
        if (uploaded) {
            settingsRepository.markAutoReportUploaded(now)
            ScannerStateStore.setLastError(null)
            Log.i("CryptoScanner", "Auto uploaded latest strategy report.")
        } else {
            Log.w("CryptoScanner", "Auto report upload failed.")
        }
    }

    private fun stopScanner() {
        scanJob?.cancel()
        scanJob = null
        ScannerStateStore.setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scanJob?.cancel()
        serviceScope.cancel()
        ScannerStateStore.setRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.cryptotradecoach.action.START"
        const val ACTION_STOP = "com.cryptotradecoach.action.STOP"
        private const val NOTIFICATION_ID = 101
        private const val STRATEGY_EVENT_NOTIFICATION_BASE = 500
        private const val AUTO_REPORT_UPLOAD_INTERVAL_MS = 10 * 60 * 1000L
    }
}