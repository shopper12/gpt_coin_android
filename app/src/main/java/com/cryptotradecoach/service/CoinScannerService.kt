package com.cryptotradecoach.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.cryptotradecoach.data.Signal
import com.cryptotradecoach.data.SignalHistoryRepository
import com.cryptotradecoach.data.UpbitMarketDataSource
import com.cryptotradecoach.data.local.MissedSignalEntity
import com.cryptotradecoach.domain.SignalEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CoinScannerService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataSource = UpbitMarketDataSource()
    private val engine = SignalEngine()
    private lateinit var notifier: SignalNotificationHelper
    private lateinit var historyRepository: SignalHistoryRepository
    private var scanJob: Job? = null
    private val lastNotifiedByMarket = mutableMapOf<String, Long>()
    private val lastStrategyByMarket = mutableMapOf<String, String>()
    private val lastStrategyChangeNotifiedByMarket = mutableMapOf<String, Long>()
    private val lastMissedSignalNotifiedByMarket = mutableMapOf<String, Long>()

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
        startForeground(NOTIFICATION_ID, notifier.foregroundNotification())
        ScannerStateStore.setRunning(true)

        scanJob = serviceScope.launch {
            while (isActive) {
                runCatching {
                    val tickers = dataSource.fetchTickers()
                    val signals = engine.scan(tickers)
                    val persistence = historyRepository.saveScanResult(tickers, signals)
                    ScannerStateStore.pushScanResult(
                        validSignals = signals,
                        persistedHistoryByMarket = persistence.historyByMarket,
                        missedSignals = persistence.missedSignals,
                        strategyReviews = persistence.strategyReviews,
                        guidelineChanges = persistence.guidelineChanges,
                    )

                    val topSignals = signals.take(5)
                    notifyStrategyChanges(topSignals)
                    filterDuplicateSignals(topSignals).forEachIndexed { index, signal ->
                        notifier.notifySignal(signal, SIGNAL_NOTIFICATION_BASE + index)
                    }
                    notifyMissedSignals(persistence.newlyMissedSignals)
                }
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    private fun notifyStrategyChanges(signals: List<Signal>) {
        val now = System.currentTimeMillis()
        signals.forEachIndexed { index, signal ->
            val previous = lastStrategyByMarket[signal.market]
            if (previous != null && previous != signal.strategyName) {
                val lastNotified = lastStrategyChangeNotifiedByMarket[signal.market] ?: 0L
                if ((now - lastNotified) >= STRATEGY_CHANGE_COOLDOWN_MS) {
                    notifier.notifyStrategyChanged(
                        signal = signal,
                        previousStrategy = previous,
                        id = STRATEGY_CHANGE_NOTIFICATION_BASE + index,
                    )
                    lastStrategyChangeNotifiedByMarket[signal.market] = now
                }
            }
            lastStrategyByMarket[signal.market] = signal.strategyName
        }
    }

    private fun notifyMissedSignals(missedSignals: List<MissedSignalEntity>) {
        val now = System.currentTimeMillis()
        missedSignals.forEachIndexed { index, missed ->
            val lastNotified = lastMissedSignalNotifiedByMarket[missed.market] ?: 0L
            if ((now - lastNotified) >= MISSED_SIGNAL_COOLDOWN_MS) {
                notifier.notifyMissedSignal(missed, MISSED_SIGNAL_NOTIFICATION_BASE + index)
                lastMissedSignalNotifiedByMarket[missed.market] = now
            }
        }
    }

    private fun filterDuplicateSignals(signals: List<Signal>): List<Signal> {
        val now = System.currentTimeMillis()
        return signals.filter { signal ->
            val last = lastNotifiedByMarket[signal.market] ?: 0L
            val allowed = (now - last) >= DUPLICATE_COOLDOWN_MS
            if (allowed) lastNotifiedByMarket[signal.market] = now
            allowed
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
        private const val SCAN_INTERVAL_MS = 30_000L
        private const val DUPLICATE_COOLDOWN_MS = 10 * 60 * 1000L
        private const val STRATEGY_CHANGE_COOLDOWN_MS = 5 * 60 * 1000L
        private const val MISSED_SIGNAL_COOLDOWN_MS = 30 * 60 * 1000L
        private const val NOTIFICATION_ID = 101
        private const val SIGNAL_NOTIFICATION_BASE = 500
        private const val STRATEGY_CHANGE_NOTIFICATION_BASE = 800
        private const val MISSED_SIGNAL_NOTIFICATION_BASE = 1_100
    }
}
