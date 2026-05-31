from pathlib import Path

repo = Path('.')

# 1) SignalHistoryRepository: record price snapshots and detect missed +5% moves.
p = repo / 'app/src/main/java/com/cryptotradecoach/data/SignalHistoryRepository.kt'
s = p.read_text(encoding='utf-8')
for imp in [
    'import com.cryptotradecoach.data.local.MissedSignalEntity\n',
    'import com.cryptotradecoach.data.local.MissedSignalReason\n',
    'import com.cryptotradecoach.data.local.PriceSnapshotEntity\n',
]:
    if imp not in s:
        s = s.replace('import com.cryptotradecoach.data.local.GuidelineChangeEntity\n', 'import com.cryptotradecoach.data.local.GuidelineChangeEntity\n' + imp, 1)

method_marker = '    private suspend fun ensurePerformanceRow(strategy: TradeStrategy, now: Long) {'
if 'recordPriceSnapshotsAndDetectMissed' not in s:
    method = '''    suspend fun recordPriceSnapshotsAndDetectMissed(
        tickers: List<Ticker>,
        activeStrategies: List<TradeStrategy>,
        now: Long = System.currentTimeMillis(),
    ): List<MissedSignalEntity> {
        if (tickers.isEmpty()) return emptyList()
        val changeRanks = tickers.sortedByDescending { it.signedChangeRate }
            .mapIndexed { index, ticker -> ticker.market to index + 1 }
            .toMap()
        val tradeValueRanks = tickers.sortedByDescending { it.accTradePrice24h }
            .mapIndexed { index, ticker -> ticker.market to index + 1 }
            .toMap()
        val snapshots = tickers.map { ticker ->
            PriceSnapshotEntity(
                market = ticker.market,
                price = ticker.tradePrice,
                signedChangeRate = ticker.signedChangeRate,
                accTradePrice24h = ticker.accTradePrice24h,
                accTradeVolume24h = ticker.accTradeVolume24h,
                rankByChangeRate = changeRanks[ticker.market] ?: Int.MAX_VALUE,
                rankByTradeValue = tradeValueRanks[ticker.market] ?: Int.MAX_VALUE,
                timestamp = now,
            )
        }
        dao.insertSnapshots(snapshots)
        dao.deleteSnapshotsOlderThan(now - SNAPSHOT_RETENTION_MS)
        dao.keepLatestSnapshots(MAX_SNAPSHOT_ROWS)

        val activeBySymbol = activeStrategies.groupBy { it.symbol }
        val out = mutableListOf<MissedSignalEntity>()
        tickers.forEach { ticker ->
            val old = dao.getOldestSnapshotSince(ticker.market, now - PUMP_LOOKBACK_MS) ?: return@forEach
            if (old.price <= 0.0 || ticker.tradePrice <= 0.0) return@forEach
            val change = percentChange(old.price, ticker.tradePrice)
            if (change < MISSED_PUMP_THRESHOLD_PCT) return@forEach
            val preFound = activeBySymbol[ticker.market].orEmpty().any { strategy ->
                strategy.createdAt <= old.timestamp + PRE_FOUND_GRACE_MS
            }
            if (preFound) return@forEach
            if (dao.countRecentMissedSignals(ticker.market, now - MISSED_SIGNAL_DEDUP_MS) > 0) return@forEach
            val rankByChange = changeRanks[ticker.market] ?: Int.MAX_VALUE
            val rankByValue = tradeValueRanks[ticker.market] ?: Int.MAX_VALUE
            val reason = when {
                rankByValue > 35 -> MissedSignalReason.TRADE_VALUE_FILTER_EXCLUDED
                rankByChange > 30 -> MissedSignalReason.CHANGE_RATE_RULE_NOT_MATCHED
                ticker.accTradePrice24h <= 0.0 -> MissedSignalReason.UNKNOWN
                else -> MissedSignalReason.SCORE_TOO_LOW
            }
            val missed = MissedSignalEntity(
                market = ticker.market,
                detectedAt = now,
                currentPrice = ticker.tradePrice,
                previousPrice = old.price,
                changeRate = change,
                rankByChangeRate = rankByChange,
                rankByTradeValue = rankByValue,
                missedReason = reason,
                suggestedStrategy = "PRE_PUMP_ROTATION",
                relatedRuleBefore = "No active strategy before +${MISSED_PUMP_THRESHOLD_PCT.toInt()}% move over ${PUMP_LOOKBACK_MS / 60_000}m",
                suggestedRuleAfter = "Review candidateSelection and prePumpRotation gates for this market.",
            )
            dao.insertMissedSignal(missed)
            out += missed
        }
        return out
    }

'''
    s = s.replace(method_marker, method + method_marker, 1)

constants = {
    'PUMP_LOOKBACK_MS': '        private const val PUMP_LOOKBACK_MS = 30 * 60 * 1000L\n',
    'PRE_FOUND_GRACE_MS': '        private const val PRE_FOUND_GRACE_MS = 5 * 60 * 1000L\n',
    'MISSED_SIGNAL_DEDUP_MS': '        private const val MISSED_SIGNAL_DEDUP_MS = 2 * 60 * 60 * 1000L\n',
    'SNAPSHOT_RETENTION_MS': '        private const val SNAPSHOT_RETENTION_MS = 6 * 60 * 60 * 1000L\n',
    'MAX_SNAPSHOT_ROWS': '        private const val MAX_SNAPSHOT_ROWS = 20_000\n',
    'MISSED_PUMP_THRESHOLD_PCT': '        private const val MISSED_PUMP_THRESHOLD_PCT = 5.0\n',
}
for name, line in constants.items():
    if name not in s:
        s = s.replace('        private const val PERFORMANCE_WINDOW_MS = 24 * 60 * 60 * 1000L\n', '        private const val PERFORMANCE_WINDOW_MS = 24 * 60 * 60 * 1000L\n' + line, 1)
p.write_text(s, encoding='utf-8')

# 2) CoinScannerService: run missed +5% monitor from light scan, throttled to once per minute.
p = repo / 'app/src/main/java/com/cryptotradecoach/service/CoinScannerService.kt'
s = p.read_text(encoding='utf-8')
if 'lastMissedPumpCheckAt' not in s:
    s = s.replace('    private var lastAutoUploadAttemptAt: Long = 0L\n', '    private var lastAutoUploadAttemptAt: Long = 0L\n    private var lastMissedPumpCheckAt: Long = 0L\n', 1)
if 'recordPriceSnapshotsAndDetectMissed' not in s:
    s = s.replace(
        '        val tickers = dataSource.fetchTickers()\n',
        '''        val tickers = dataSource.fetchTickers()
        val now = System.currentTimeMillis()
        if (now - lastMissedPumpCheckAt >= MISSED_PUMP_CHECK_INTERVAL_MS) {
            lastMissedPumpCheckAt = now
            val missed = historyRepository.recordPriceSnapshotsAndDetectMissed(
                tickers = tickers,
                activeStrategies = ScannerStateStore.activeStrategies.value,
                now = now,
            )
            if (missed.isNotEmpty()) {
                ScannerStateStore.setLastError("Missed +5% pump: " + missed.joinToString(",") { it.market })
            }
        }
''',
        1,
    )
if 'MISSED_PUMP_CHECK_INTERVAL_MS' not in s:
    s = s.replace('        private const val LIGHT_SCAN_INTERVAL_MS = 10_000L\n', '        private const val LIGHT_SCAN_INTERVAL_MS = 10_000L\n        private const val MISSED_PUMP_CHECK_INTERVAL_MS = 60_000L\n', 1)
p.write_text(s, encoding='utf-8')

# 3) StrategyReportRepository: include true missed +5% events in liveReview.
p = repo / 'app/src/main/java/com/cryptotradecoach/data/StrategyReportRepository.kt'
s = p.read_text(encoding='utf-8')
if 'import com.cryptotradecoach.data.local.MissedSignalEntity\n' not in s:
    s = s.replace('import com.cryptotradecoach.data.local.StrategyPerformanceEntity\n', 'import com.cryptotradecoach.data.local.MissedSignalEntity\nimport com.cryptotradecoach.data.local.StrategyPerformanceEntity\n', 1)
if 'recentMissedFivePct' not in s:
    s = s.replace(
        '        val logs = dao.getStrategyScanLogsSince(now - REPORT_WINDOW_MS)\n',
        '        val logs = dao.getStrategyScanLogsSince(now - REPORT_WINDOW_MS)\n        val recentMissedFivePct = dao.getRecentMissedSignals(100).filter { now - it.detectedAt <= REPORT_WINDOW_MS }\n',
        1,
    )
    s = s.replace('val liveReview = buildLiveReviewJson(performances, logs, rules, now)', 'val liveReview = buildLiveReviewJson(performances, logs, recentMissedFivePct, rules, now)', 1)
    s = s.replace(
        '''        performances: List<StrategyPerformanceEntity>,
        logs: List<StrategyScanLogEntity>,
        rules: StrategyRules,''',
        '''        performances: List<StrategyPerformanceEntity>,
        logs: List<StrategyScanLogEntity>,
        missedFivePct: List<MissedSignalEntity>,
        rules: StrategyRules,''',
        1,
    )
    s = s.replace('        val missed = logs.filter { it.selectedOrMissed == "MISSED" }.take(20)\n', '        val missed = logs.filter { it.selectedOrMissed == "MISSED" }.take(20)\n        val totalMissed = missed.size + missedFivePct.size\n', 1)
    s = s.replace('            missed.size >= 10 -> "REVIEW_CANDIDATE_POOL"', '            totalMissed >= 10 -> "REVIEW_CANDIDATE_POOL"')
    s = s.replace('.put("missedSignals", missed.size)', '.put("missedSignals", totalMissed)')
    s = s.replace('.put("missedMarkets", missed.joinToString(",") { it.market }.take(500))', '.put("missedMarkets", (missed.map { it.market } + missedFivePct.map { it.market }).distinct().joinToString(",").take(500))')
p.write_text(s, encoding='utf-8')

print('patched missed 5pct monitor')
