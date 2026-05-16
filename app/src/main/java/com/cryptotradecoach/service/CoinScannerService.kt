package com.cryptotradecoach.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.cryptotradecoach.data.SignalHistoryRepository
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
    private var scanJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notifier = SignalNotificationHelper(this)
        historyRepository = SignalHistoryRepository.getInstance(this)
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
        startForeground(
            NOTIFICATION_ID,
            notifier.foregroundNotification(ScannerStateStore.DEFAULT_SCAN_INTERVAL_MS),
        )
        ScannerStateStore.setRunning(true)

        scanJob = serviceScope.launch {
            while (isActive) {
                runCatching {
                    val maxDisplayCount = ScannerStateStore.maxDisplayCount.first()
                    val minimumScore = ScannerStateStore.minimumScore.first()
                    val candidates = dataSource.fetchMarketCandidates(limit = 80)
                    val scanResult = engine.scan(
                        candidates = candidates,
                        minimumScore = minimumScore,
                        maxResults = maxDisplayCount,
                    )
                    val currentPrices = candidates.associate { it.ticker.market to it.ticker.tradePrice }
                    val persistence = historyRepository.saveStrategyScanResult(
                        scanResult = scanResult,
                        currentPrices = currentPrices,
                    )
                    ScannerStateStore.pushScanResult(
                        activeStrategies = persistence.activeStrategies,
                        historyBySymbol = persistence.historyBySymbol,
                    )
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
                delay(ScannerStateStore.scanIntervalMs.first())
            }
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
    }
}
