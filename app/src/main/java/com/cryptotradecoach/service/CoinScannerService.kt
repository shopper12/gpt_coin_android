package com.cryptotradecoach.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.cryptotradecoach.data.AppUpdateRepository
import com.cryptotradecoach.data.ScanDiagnostics
import com.cryptotradecoach.data.SettingsRepository
import com.cryptotradecoach.data.SignalHistoryRepository
import com.cryptotradecoach.data.SignalMonitor
import com.cryptotradecoach.data.StrategyReportRepository
import com.cryptotradecoach.data.StrategyRulesRepository
import com.cryptotradecoach.data.UpbitMarketDataSource
import com.cryptotradecoach.data.local.AppDatabase
import com.cryptotradecoach.data.local.StrategyEventType
import com.cryptotradecoach.domain.BacktestEngine
import com.cryptotradecoach.domain.SignalEngine
import com.cryptotradecoach.domain.StrategyEvolver
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
    private lateinit var db: AppDatabase
    private lateinit var notifier: SignalNotificationHelper
    private lateinit var historyRepository: SignalHistoryRepository
    private lateinit var rulesRepository: StrategyRulesRepository
    private lateinit var reportRepository: StrategyReportRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var appUpdateRepository: AppUpdateRepository
    private lateinit var signalMonitor: SignalMonitor
    private lateinit var backtestEngine: BacktestEngine
    private lateinit var evolver: StrategyEvolver
    private var scanJob: Job? = null
    private var lastAutoUploadAttemptAt: Long = 0L
    private var lastBacktestAttemptAt: Long = 0L
    private var lastAppUpdateCheckAt: Long = 0L
    private var lastNotifiedVersionCode: Int = 0

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getInstance(this)
        notifier = SignalNotificationHelper(this)
        historyRepository = SignalHistoryRepository.getInstance(this)
        rulesRepository = StrategyRulesRepository.getInstance(this)
        reportRepository = StrategyReportRepository.getInstance(this)
        settingsRepository = SettingsRepository.getInstance(this)
        appUpdateRepository = AppUpdateRepository(this)
        signalMonitor = SignalMonitor(dataSource, db, serviceScope)
        backtestEngine = BacktestEngine(db)
        evolver = StrategyEvolver(rulesRepository, db)
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
            launch {
                while (isActive) {
                    runCatching { performLightScan() }
                        .onFailure { error ->
                            Log.w("CryptoScanner", "Light scan failed", error)
                            ScannerStateStore.setLastError("Light scan failed: ${error.message ?: error::class.java.simpleName}")
                        }
                    delay(LIGHT_SCAN_INTERVAL_MS)
                }
            }
            launch {
                while (isActive) {
                    ScannerStateStore.markScanAttempt(this@CoinScannerService)
                    runCatching { performDeepScan() }
                        .onFailure { error ->
                            Log.e("CryptoScanner", "Scan failed", error)
                            ScannerStateStore.setLastError(error.message ?: error::class.java.simpleName)
                        }
                    delay(ScannerStateStore.scanIntervalMs.first())
                }
            }
        }
    }

    private suspend fun performLightScan() {
        val tickers = dataSource.fetchTickers()
        val previousByMarket = ScannerStateStore.lastTickerSnapshot.value.associateBy { it.market }
        val medianTradeValue = tickers.map { it.accTradePrice24h }.sorted()
            .let { values -> if (values.isEmpty()) 0.0 else values[values.size / 2] }

        val alerts = if (previousByMarket.isNotEmpty()) {
            tickers.mapNotNull { ticker ->
                val previous = previousByMarket[ticker.market] ?: return@mapNotNull null
                val volumeChange = if (previous.accTradeVolume24h > 0.0) {
                    ticker.accTradeVolume24h / previous.accTradeVolume24h
                } else {
                    1.0
                }
                val valueChange = if (previous.accTradePrice24h > 0.0) {
                    ticker.accTradePrice24h / previous.accTradePrice24h
                } else {
                    1.0
                }
                val volumeOrValueMoved = volumeChange >= 1.015 || valueChange >= 1.012
                val priceNotExtended = ticker.signedChangeRate in -0.02..0.06
                val liquidityOk = ticker.accTradePrice24h > medianTradeValue * 0.3
                if (volumeOrValueMoved && priceNotExtended && liquidityOk) {
                    ticker to maxOf(volumeChange, valueChange)
                } else {
                    null
                }
            }.sortedByDescending { it.second }.take(5).map { it.first }
        } else {
            emptyList()
        }

        ScannerStateStore.updateTickerSnapshot(tickers)
        ScannerStateStore.pushVolumeAlerts(alerts)
    }

    private suspend fun performDeepScan() {
        val maxDisplayCount = ScannerStateStore.maxDisplayCount.first()
        val minimumScore = ScannerStateStore.minimumScore.first()
        val previousActiveIds = ScannerStateStore.activeStrategies.value.map { it.id }.toSet()
        val rules = rulesRepository.refreshFromGitHub()
        val candidateLimit = maxOf(maxDisplayCount, rules.candidateSelection.maxCandleTargets)
        val candidates = dataSource.fetchMarketCandidates(candidateLimit, rules)
        val scanResult = engine.scan(
            candidates = candidates,
            rules = rules,
            minimumScore = minimumScore,
            maxResults = maxDisplayCount,
        )
        val currentPrices = candidates.associate { it.ticker.market to it.ticker.tradePrice }
        val persistence = historyRepository.saveStrategyScanResult(
            scanResult = scanResult,
            currentPrices = currentPrices,
        )
        val nextActiveIds = persistence.activeStrategies.map { it.id }.toSet()
        persistence.activeStrategies.forEach { strategy ->
            if (strategy.id !in previousActiveIds) signalMonitor.startMonitoring(strategy)
        }
        previousActiveIds.forEach { id ->
            if (id !in nextActiveIds) signalMonitor.stopMonitoring(id, "SIGNAL_ENDED")
        }
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
        maybeRunBacktestAndEvolution()
        maybeAutoUploadLatestReport()
        maybeNotifyAppUpdate()
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
    }

    private suspend fun maybeRunBacktestAndEvolution(now: Long = System.currentTimeMillis()) {
        if (now - lastBacktestAttemptAt < BACKTEST_INTERVAL_MS) return
        lastBacktestAttemptAt = now
        val results = backtestEngine.runAll(now)
        ScannerStateStore.updateBacktestResults(results)
        evolver.maybeEvolve(results, now)
        ScannerStateStore.updateEvolutionLog(db.evolutionLogDao().getRecent())
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

    private fun maybeNotifyAppUpdate(now: Long = System.currentTimeMillis()) {
        if (now - lastAppUpdateCheckAt < APP_UPDATE_CHECK_INTERVAL_MS) return
        lastAppUpdateCheckAt = now
        val settings = settingsRepository.load().normalized()
        val info = appUpdateRepository.checkLatestRelease(settings) ?: return
        if (info.hasUpdate && info.versionCode != lastNotifiedVersionCode) {
            lastNotifiedVersionCode = info.versionCode
            notifier.notifyAppUpdateAvailable(info)
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
        private const val LIGHT_SCAN_INTERVAL_MS = 10_000L
        private const val AUTO_REPORT_UPLOAD_INTERVAL_MS = 10 * 60 * 1000L
        private const val BACKTEST_INTERVAL_MS = 6L * 60L * 60L * 1000L
        private const val APP_UPDATE_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
    }
}
