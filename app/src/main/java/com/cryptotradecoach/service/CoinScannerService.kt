package com.cryptotradecoach.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.cryptotradecoach.data.AppUpdateRepository
import com.cryptotradecoach.data.Candle
import com.cryptotradecoach.data.MarketCandidate
import com.cryptotradecoach.data.ScanDiagnostics
import com.cryptotradecoach.data.SettingsRepository
import com.cryptotradecoach.data.SignalHistoryRepository
import com.cryptotradecoach.data.SignalMonitor
import com.cryptotradecoach.data.StrategyReportRepository
import com.cryptotradecoach.data.StrategyRules
import com.cryptotradecoach.data.StrategyRulesRepository
import com.cryptotradecoach.data.StrategyScanLog
import com.cryptotradecoach.data.StrategyScanResult
import com.cryptotradecoach.data.StrategyStatus
import com.cryptotradecoach.data.StrategyType
import com.cryptotradecoach.data.Ticker
import com.cryptotradecoach.data.TradeStrategy
import com.cryptotradecoach.data.UpbitMarketDataSource
import com.cryptotradecoach.data.local.AppDatabase
import com.cryptotradecoach.data.local.MissedSignalEntity
import com.cryptotradecoach.data.local.MissedSignalReason
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
    private val pumpBaselines = mutableMapOf<String, PumpBaseline>()

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
        val now = System.currentTimeMillis()
        val tickers = dataSource.fetchTickers()
        val previousByMarket = ScannerStateStore.lastTickerSnapshot.value.associateBy { it.market }
        val medianTradeValue = tickers.map { it.accTradePrice24h }.sorted()
            .let { values -> if (values.isEmpty()) 0.0 else values[values.size / 2] }
        val changeRanks = tickers.sortedByDescending { it.signedChangeRate }.mapIndexed { index, ticker -> ticker.market to index + 1 }.toMap()
        val tradeValueRanks = tickers.sortedByDescending { it.accTradePrice24h }.mapIndexed { index, ticker -> ticker.market to index + 1 }.toMap()

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

        recordMissedPumpCandidates(tickers, now, changeRanks, tradeValueRanks)
        ScannerStateStore.updateTickerSnapshot(tickers)
        ScannerStateStore.pushVolumeAlerts(alerts)
    }

    private suspend fun recordMissedPumpCandidates(
        tickers: List<Ticker>,
        now: Long,
        changeRanks: Map<String, Int>,
        tradeValueRanks: Map<String, Int>,
    ) {
        if (tickers.isEmpty()) return
        val rules = rulesRepository.loadLastKnownGood()
        val missedThreshold = missedPumpReturnThreshold(rules)
        val activeSymbols = ScannerStateStore.activeStrategies.value.map { it.symbol }.toSet()
        tickers.forEach { ticker ->
            val price = ticker.tradePrice
            if (price <= 0.0) return@forEach
            val rankByChange = changeRanks[ticker.market] ?: Int.MAX_VALUE
            val rankByValue = tradeValueRanks[ticker.market] ?: Int.MAX_VALUE
            val baseline = pumpBaselines[ticker.market]
            if (baseline == null) {
                pumpBaselines[ticker.market] = PumpBaseline(price, now, rankByChange, rankByValue)
                return@forEach
            }
            val elapsed = now - baseline.timestamp
            val movedPct = percentChange(baseline.price, price)
            if (elapsed > MISSED_PUMP_BASELINE_RESET_MS || movedPct < -2.0) {
                pumpBaselines[ticker.market] = PumpBaseline(price, now, rankByChange, rankByValue)
                return@forEach
            }
            if (elapsed < MISSED_PUMP_MIN_WINDOW_MS || movedPct < missedThreshold) return@forEach
            if (ticker.market in activeSymbols) {
                pumpBaselines[ticker.market] = PumpBaseline(price, now, rankByChange, rankByValue)
                return@forEach
            }
            if (db.signalHistoryDao().countRecentMissedSignals(ticker.market, now - MISSED_PUMP_DUPLICATE_WINDOW_MS) > 0) return@forEach

            val reason = classifyMissedPumpReason(
                baseline = baseline,
                rankByChange = rankByChange,
                rankByValue = rankByValue,
                rulesMaxChangeRank = rules.prePumpRotation.maxChangeRank,
                rulesMaxValueRank = rules.prePumpRotation.maxTradeValueRank,
            )
            db.signalHistoryDao().insertMissedSignal(
                MissedSignalEntity(
                    market = ticker.market,
                    detectedAt = now,
                    currentPrice = price,
                    previousPrice = baseline.price,
                    changeRate = movedPct,
                    rankByChangeRate = rankByChange,
                    rankByTradeValue = rankByValue,
                    missedReason = reason,
                    suggestedStrategy = "PRE_PUMP_ROTATION",
                    relatedRuleBefore = "maxTradeValueRank=${rules.prePumpRotation.maxTradeValueRank}; maxChangeRank=${rules.prePumpRotation.maxChangeRank}; minVolumeAcceleration=${rules.prePumpRotation.minVolumeAcceleration}; minFiveMinuteVolumeRatio=${rules.prePumpRotation.minFiveMinuteVolumeRatio}; maxRangePct=${rules.prePumpRotation.maxRangePct}; minimumScore=${rules.minimumScore}; missedPumpThreshold=$missedThreshold",
                    suggestedRuleAfter = "Widen candidate universe, lower pre-pump ignition thresholds, prioritize ACTIVE setups over WATCH_ONLY, and force-watch recurring missed markets.",
                ),
            )
            ScannerStateStore.setLastError("Missed pump recorded: ${ticker.market} +${String.format(java.util.Locale.US, "%.1f", movedPct)}% reason=$reason threshold=${String.format(java.util.Locale.US, "%.1f", missedThreshold)}%")
            pumpBaselines[ticker.market] = PumpBaseline(price, now, rankByChange, rankByValue)
        }
    }

    private fun classifyMissedPumpReason(
        baseline: PumpBaseline,
        rankByChange: Int,
        rankByValue: Int,
        rulesMaxChangeRank: Int,
        rulesMaxValueRank: Int,
    ): String {
        return when {
            baseline.rankByTradeValue > rulesMaxValueRank && rankByValue > rulesMaxValueRank -> MissedSignalReason.TRADE_VALUE_FILTER_EXCLUDED
            baseline.rankByChangeRate > rulesMaxChangeRank && rankByChange > rulesMaxChangeRank -> MissedSignalReason.CHANGE_RATE_RULE_NOT_MATCHED
            baseline.rankByTradeValue > rulesMaxValueRank && rankByValue <= rulesMaxValueRank -> "LATE_LIQUIDITY_ROTATION"
            baseline.rankByChangeRate > rulesMaxChangeRank && rankByChange <= rulesMaxChangeRank -> "LATE_CHANGE_RANK_ROTATION"
            rankByValue <= rulesMaxValueRank && rankByChange <= rulesMaxChangeRank -> MissedSignalReason.SCORE_TOO_LOW
            rankByValue <= rulesMaxValueRank + 15 -> MissedSignalReason.VOLUME_SCORE_TOO_LOW
            else -> MissedSignalReason.UNKNOWN
        }
    }

    private suspend fun performDeepScan() {
        val now = System.currentTimeMillis()
        val maxDisplayCount = ScannerStateStore.maxDisplayCount.first()
        val minimumScore = ScannerStateStore.minimumScore.first()
        val previousActiveIds = ScannerStateStore.activeStrategies.value.map { it.id }.toSet()
        val rules = rulesRepository.refreshFromGitHub()
        val candidateLimit = maxOf(maxDisplayCount, rules.candidateSelection.maxCandleTargets)
        val candidates = dataSource.fetchMarketCandidates(candidateLimit, rules)
        val baseScanResult = engine.scan(
            candidates = candidates,
            rules = rules,
            minimumScore = minimumScore,
            maxResults = maxDisplayCount,
        )
        val scanResult = rescueMissedPrePumpCandidates(
            base = baseScanResult,
            candidates = candidates,
            rules = rules,
            minimumScore = minimumScore,
            maxResults = maxDisplayCount,
            now = now,
            rescueMarkets = adaptiveRescueMarkets(now),
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

    private fun rescueMissedPrePumpCandidates(
        base: StrategyScanResult,
        candidates: List<MarketCandidate>,
        rules: StrategyRules,
        minimumScore: Double,
        maxResults: Int,
        now: Long,
        rescueMarkets: Set<String>,
    ): StrategyScanResult {
        val selectedSymbols = base.activeStrategies.map { it.symbol }.toSet()
        val rescue = candidates
            .filter { it.ticker.market !in selectedSymbols }
            .mapNotNull { buildRescuePrePumpStrategy(it, rules, minimumScore, now, rescueMarkets) }
        if (rescue.isEmpty()) return base
        val combined = (base.activeStrategies + rescue)
            .distinctBy { it.symbol }
            .sortedWith(compareByDescending<TradeStrategy> { it.score }.thenBy { it.riskPct })
            .take(maxResults.coerceIn(1, 20))
            .mapIndexed { index, strategy -> strategy.copy(rank = index + 1) }
        val rescueLogs = rescue.map { strategy -> strategy.toRescueScanLog(now, selected = combined.any { it.id == strategy.id }) }
        val summary = base.rejectionSummary.toMutableMap()
        summary["RESCUED_PRE_PUMP"] = rescue.size
        return base.copy(
            activeStrategies = combined,
            validSignals = combined,
            scanLogs = base.scanLogs + rescueLogs,
            rejectionSummary = summary.toSortedMap(),
            lastError = base.lastError,
        )
    }

    private fun buildRescuePrePumpStrategy(
        candidate: MarketCandidate,
        rules: StrategyRules,
        minimumScore: Double,
        now: Long,
        rescueMarkets: Set<String>,
    ): TradeStrategy? {
        val ticker = candidate.ticker
        val price = ticker.tradePrice
        val five = candidate.fiveMinuteCandles.sortedBy { it.timestamp }
        val fifteen = candidate.fifteenMinuteCandles.sortedBy { it.timestamp }
        if (price <= 0.0 || five.size < 25 || fifteen.size < 25) return null
        val r = rules.prePumpRotation
        val prev20High = five.dropLast(1).takeLast(20).maxOfOrNull { it.high } ?: return null
        val prev20Low = five.dropLast(1).takeLast(20).minOfOrNull { it.low } ?: return null
        val rangePct = percentChange(prev20Low, prev20High).coerceAtLeast(0.0)
        val rangePos = if (prev20High > prev20Low) ((price - prev20Low) / (prev20High - prev20Low)).coerceIn(0.0, 1.0) else 0.5
        val avg5Value = five.dropLast(1).takeLast(20).averageTradePrice()
        val avg15Value = fifteen.dropLast(1).takeLast(20).averageTradePrice()
        val fiveVolumeRatio = if (avg5Value > 0.0) five.last().tradePrice / avg5Value else 1.0
        val fifteenVolumeRatio = if (avg15Value > 0.0) fifteen.last().tradePrice / avg15Value else 1.0
        val change24h = ticker.signedChangeRate * 100.0
        val notAlreadyPumped = change24h in (r.minChange24hPct - 1.0)..(r.maxChange24hPct + 1.5) &&
            candidate.changeRate30m <= r.maxChange30mPct + 0.8 &&
            candidate.changeRate5m <= r.maxChange5mPct + 0.5
        val rankOk = candidate.rankByTradeValue <= r.maxTradeValueRank + 15 || ticker.market in rescueMarkets
        val rotationOk = candidate.rankByChangeRate <= r.maxChangeRank + 20 || candidate.changeRate30m >= r.minRotation30mPct * 0.5
        val volumeOk = candidate.volumeAcceleration >= r.minVolumeAcceleration * 0.88 ||
            fiveVolumeRatio >= r.minFiveMinuteVolumeRatio * 0.88 ||
            fifteenVolumeRatio >= r.minFifteenMinuteVolumeRatio * 0.88
        val structureOk = rangePct <= r.maxRangePct * 1.25 &&
            rangePos >= (r.minRangePosition - 0.12).coerceAtLeast(0.25) &&
            price >= prev20High * (r.minHighProximityMultiplier - 0.010)
        if (!(notAlreadyPumped && rankOk && rotationOk && volumeOk && structureOk)) return null
        val structureScore = 21.0
        val volumeScore = ((maxOf(candidate.volumeAcceleration, fiveVolumeRatio, fifteenVolumeRatio) - 1.0) * 16.0).coerceIn(0.0, 24.0)
        val rotationScore = when {
            candidate.rankByChangeRate <= 15 -> 18.0
            candidate.rankByChangeRate <= r.maxChangeRank + 20 -> 13.0
            candidate.changeRate30m >= r.minRotation30mPct * 0.5 -> 10.0
            else -> 0.0
        }
        val liquidityScore = when {
            candidate.rankByTradeValue <= 15 -> 15.0
            candidate.rankByTradeValue <= r.maxTradeValueRank + 15 -> 10.0
            ticker.market in rescueMarkets -> 8.0
            else -> 0.0
        }
        val score = (18.0 + structureScore + volumeScore + rotationScore + liquidityScore).coerceIn(0.0, 92.0)
        if (score < minimumScore.coerceAtMost(68.0)) return null
        val stop = minOf(prev20Low, five.takeLast(8).minOfOrNull { it.low } ?: prev20Low) * 0.996
        val riskPct = kotlin.math.abs(percentChange(price, stop)).coerceAtLeast(rules.risk.minimumRiskPct)
        val target1 = price * (1.0 + riskPct * 1.5 / 100.0)
        val target2 = price * (1.0 + riskPct * 2.4 / 100.0)
        val expectedReturn = kotlin.math.abs(percentChange(price, target2)).coerceAtLeast(rules.risk.minimumExpectedReturnPct)
        return TradeStrategy(
            id = "${ticker.market}-PRE_PUMP_RESCUE",
            symbol = ticker.market,
            strategyType = StrategyType.PRE_PUMP_ROTATION,
            status = StrategyStatus.ACTIVE,
            score = score,
            rank = Int.MAX_VALUE,
            entryLow = price * (1.0 - rules.entryBandPct / 100.0),
            entryHigh = price * (1.0 + rules.entryBandPct / 100.0),
            stopLoss = stop,
            target1 = target1,
            target2 = target2,
            trailingStop = price * (1.0 - maxOf(riskPct * 0.55, rules.risk.minimumRiskPct) / 100.0),
            expectedReturnPct = expectedReturn,
            riskPct = riskPct,
            riskRewardRatio = expectedReturn / riskPct,
            componentScores = "rescue=true;structureScore=${structureScore.one()};volumeScore=${volumeScore.one()};rotationScore=${rotationScore.one()};liquidityScore=${liquidityScore.one()};rangePct=${rangePct.one()};rangePos=${(rangePos * 100.0).one()};5mVolRatio=${fiveVolumeRatio.one()};15mVolRatio=${fifteenVolumeRatio.one()};adaptiveRescue=${ticker.market in rescueMarkets}",
            rankByChangeRate = candidate.rankByChangeRate,
            rankByTradeValue = candidate.rankByTradeValue,
            changeRate24h = change24h,
            changeRate30m = candidate.changeRate30m,
            changeRate5m = candidate.changeRate5m,
            volumeAcceleration = candidate.volumeAcceleration,
            reason = "PRE_PUMP_RESCUE ACTIVE; adaptive rescue universe; rangePct=${rangePct.one()}%; rangePos=${(rangePos * 100.0).one()}%; volAccel=${candidate.volumeAcceleration.one()}x; 5mVolRatio=${fiveVolumeRatio.one()}x; 15mVolRatio=${fifteenVolumeRatio.one()}x; rankChange=${candidate.rankByChangeRate}; rankValue=${candidate.rankByTradeValue}",
            invalidationReason = null,
            createdAt = now,
            updatedAt = now,
            validUntil = now + rules.validForMinutes * 60 * 1000L,
        )
    }

    private fun TradeStrategy.toRescueScanLog(now: Long, selected: Boolean): StrategyScanLog {
        return StrategyScanLog(
            market = symbol,
            strategyType = strategyType,
            timestamp = now,
            currentPrice = entryHigh,
            entryPrice = entryHigh,
            stopLossPrice = stopLoss,
            target1 = target1,
            target2 = target2,
            trailingStop = trailingStop,
            score = score,
            componentScores = componentScores,
            rankByChangeRate = rankByChangeRate,
            rankByTradeValue = rankByTradeValue,
            changeRate24h = changeRate24h,
            changeRate30m = changeRate30m,
            changeRate5m = changeRate5m,
            volumeAcceleration = volumeAcceleration,
            selectedOrMissed = if (selected) "SELECTED" else "MISSED",
            missedReason = if (selected) null else "RESCUE_NOT_TOP_RANKED",
            topNAtScan = rank,
            strategyStatus = status,
        )
    }

    private suspend fun maybeRunBacktestAndEvolution(now: Long = System.currentTimeMillis()) {
        val recentCompleted = db.signalHistoryDao().getPerformanceSince(now - BACKTEST_SAMPLE_LOOKBACK_MS, 5000).count { it.isComplete }
        val interval = if (recentCompleted < BACKTEST_MIN_SAMPLE_TARGET) BACKTEST_FAST_INTERVAL_MS else BACKTEST_STABLE_INTERVAL_MS
        if (now - lastBacktestAttemptAt < interval) return
        lastBacktestAttemptAt = now
        val results = backtestEngine.runAll(now)
        ScannerStateStore.updateBacktestResults(results)
        evolver.maybeEvolve(results, now)
        ScannerStateStore.updateEvolutionLog(db.evolutionLogDao().getRecent())
    }

    private suspend fun adaptiveRescueMarkets(now: Long): Set<String> {
        val recent = db.signalHistoryDao().getRecentMissedSignals(80)
            .filter { it.detectedAt >= now - RESCUE_LOOKBACK_MS }
            .sortedWith(compareByDescending<MissedSignalEntity> { it.changeRate }.thenBy { it.rankByTradeValue })
            .map { it.market }
            .distinct()
            .take(12)
            .toSet()
        return if (recent.size >= 3) recent else FALLBACK_RESCUE_MARKETS
    }

    private fun missedPumpReturnThreshold(rules: StrategyRules): Double {
        val activeCount = ScannerStateStore.activeStrategies.value.size
        val scoreAdjustment = when {
            rules.minimumScore >= 75.0 -> -0.4
            rules.minimumScore <= 62.0 -> 0.4
            else -> 0.0
        }
        val signalAdjustment = when {
            activeCount == 0 -> -0.3
            activeCount >= 5 -> 0.3
            else -> 0.0
        }
        return (BASE_MISSED_PUMP_RETURN_PCT + scoreAdjustment + signalAdjustment).coerceIn(3.6, 5.4)
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

    private fun percentChange(from: Double, to: Double): Double {
        if (from <= 0.0) return 0.0
        return (to - from) / from * 100.0
    }

    private fun List<Candle>.averageTradePrice(): Double {
        return if (isEmpty()) 0.0 else sumOf { it.tradePrice } / size
    }

    private fun Double.one(): String = String.format(java.util.Locale.US, "%.1f", this)

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

    private data class PumpBaseline(
        val price: Double,
        val timestamp: Long,
        val rankByChangeRate: Int,
        val rankByTradeValue: Int,
    )

    companion object {
        const val ACTION_START = "com.cryptotradecoach.action.START"
        const val ACTION_STOP = "com.cryptotradecoach.action.STOP"
        private const val NOTIFICATION_ID = 101
        private const val STRATEGY_EVENT_NOTIFICATION_BASE = 500
        private const val LIGHT_SCAN_INTERVAL_MS = 10_000L
        private const val AUTO_REPORT_UPLOAD_INTERVAL_MS = 10 * 60 * 1000L
        private const val BACKTEST_FAST_INTERVAL_MS = 30L * 60L * 1000L
        private const val BACKTEST_STABLE_INTERVAL_MS = 6L * 60L * 60L * 1000L
        private const val BACKTEST_SAMPLE_LOOKBACK_MS = 14L * 24L * 60L * 60L * 1000L
        private const val BACKTEST_MIN_SAMPLE_TARGET = 15
        private const val APP_UPDATE_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
        private const val MISSED_PUMP_MIN_WINDOW_MS = 3L * 60L * 1000L
        private const val MISSED_PUMP_BASELINE_RESET_MS = 45L * 60L * 1000L
        private const val MISSED_PUMP_DUPLICATE_WINDOW_MS = 6L * 60L * 60L * 1000L
        private const val BASE_MISSED_PUMP_RETURN_PCT = 4.5
        private const val RESCUE_LOOKBACK_MS = 3L * 24L * 60L * 60L * 1000L
        private val FALLBACK_RESCUE_MARKETS = setOf(
            "KRW-XLM",
            "KRW-XRP",
            "KRW-ADA",
            "KRW-DOGE",
            "KRW-SOL",
            "KRW-LINK",
            "KRW-AVAX",
            "KRW-DOT",
            "KRW-SUI",
            "KRW-APT",
            "KRW-ONDO",
            "KRW-HBAR",
            "KRW-STX",
        )
    }
}
