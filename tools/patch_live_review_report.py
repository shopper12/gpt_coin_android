from pathlib import Path

p = Path('app/src/main/java/com/cryptotradecoach/data/StrategyReportRepository.kt')
s = p.read_text(encoding='utf-8')

old_return = '''        return StrategyReport(
            generatedAt = now,
            rulesVersion = rules.version,
            summaries = summaries,
            failedSignals = failedSignals,
        ).also { persistLatest(it) }
'''
new_return = '''        val liveReview = buildLiveReviewJson(performances, logs, rules, now)
        return StrategyReport(
            generatedAt = now,
            rulesVersion = rules.version,
            summaries = summaries,
            failedSignals = failedSignals,
        ).also { persistLatest(it, liveReview) }
'''
if old_return in s:
    s = s.replace(old_return, new_return, 1)

old_persist = '''    private fun persistLatest(report: StrategyReport) {
        runCatching {
            latestReportFile.parentFile?.mkdirs()
            latestReportFile.writeText(report.toJson().toString(2))
        }.onFailure {
            Log.w(TAG, "Failed to write latest strategy report.")
        }
    }
'''
new_persist = '''    private fun persistLatest(report: StrategyReport, liveReview: org.json.JSONObject) {
        runCatching {
            latestReportFile.parentFile?.mkdirs()
            val json = report.toJson()
            json.put("liveReview", liveReview)
            latestReportFile.writeText(json.toString(2))
        }.onFailure {
            Log.w(TAG, "Failed to write latest strategy report.")
        }
    }
'''
if old_persist in s:
    s = s.replace(old_persist, new_persist, 1)

marker = '    private fun List<StrategyPerformanceEntity>.toSummary(strategyType: String): StrategyPerformanceSummary {'
if 'private fun buildLiveReviewJson(' not in s:
    fn = '''    private fun buildLiveReviewJson(
        performances: List<StrategyPerformanceEntity>,
        logs: List<StrategyScanLogEntity>,
        rules: StrategyRules,
        now: Long,
    ): org.json.JSONObject {
        val targetHits = performances.count { it.target1Hit || it.target2Hit }
        val stopHits = performances.count { it.stopHit }
        val expired = performances.count { it.expired }
        val missed = logs.filter { it.selectedOrMissed == "MISSED" }.take(20)
        val avgMfe = performances.averageOf { it.mfePct }
        val avgMae = performances.averageOf { it.maePct }
        val stopRate = if (performances.isEmpty()) 0.0 else stopHits.toDouble() / performances.size
        val targetRate = if (performances.isEmpty()) 0.0 else targetHits.toDouble() / performances.size
        val diagnosis = when {
            performances.size < MIN_LIVE_REVIEW_SAMPLE -> "NO_CHANGE_LOW_SAMPLE"
            stopRate >= 0.42 -> "TIGHTEN_HIGH_STOP_RATE"
            targetRate <= 0.18 && avgMfe < 0.45 -> "TIGHTEN_WEAK_MFE"
            missed.size >= 10 -> "REVIEW_CANDIDATE_POOL"
            avgMfe > abs(avgMae) * 1.8 && targetRate >= 0.35 -> "ALLOW_SMALL_EXPANSION"
            else -> "KEEP"
        }
        val suggestion = when (diagnosis) {
            "NO_CHANGE_LOW_SAMPLE" -> "Do not change rules from live data yet."
            "TIGHTEN_HIGH_STOP_RATE" -> "Raise minimumScore by 1~2 and tighten late-entry filters."
            "TIGHTEN_WEAK_MFE" -> "Require stronger volume and tighter range compression."
            "REVIEW_CANDIDATE_POOL" -> "Review candidateSelection, but apply only if backtest agrees."
            "ALLOW_SMALL_EXPANSION" -> "Allow small expansion only, such as +1 maxResults or +5 candle targets."
            else -> "No rule change. Continue collecting live outcomes."
        }
        return org.json.JSONObject()
            .put("reviewedAt", now)
            .put("rulesVersion", rules.version)
            .put("sampleSize", performances.size)
            .put("missedSignals", missed.size)
            .put("targetHitCount", targetHits)
            .put("stopHitCount", stopHits)
            .put("expiredCount", expired)
            .put("targetHitRate", targetRate)
            .put("stopHitRate", stopRate)
            .put("averageMfePercent", avgMfe)
            .put("averageMaePercent", avgMae)
            .put("missedMarkets", missed.joinToString(",") { it.market }.take(500))
            .put("diagnosis", diagnosis)
            .put("ruleChangeSuggestion", suggestion)
    }

'''
    s = s.replace(marker, fn + marker, 1)

if 'MIN_LIVE_REVIEW_SAMPLE' not in s:
    s = s.replace('        private const val REPORT_WINDOW_MS = 24 * 60 * 60 * 1000L\n', '        private const val REPORT_WINDOW_MS = 24 * 60 * 60 * 1000L\n        private const val MIN_LIVE_REVIEW_SAMPLE = 20\n', 1)

p.write_text(s, encoding='utf-8')
print('patched live review report')
