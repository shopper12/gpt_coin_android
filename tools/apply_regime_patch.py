from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"missing pattern: {label}")
    return text.replace(old, new, 1)


signal_path = ROOT / "app/src/main/java/com/cryptotradecoach/domain/SignalEngine.kt"
signal = signal_path.read_text()

signal = replace_once(
    signal,
    "        maxResults: Int = rules.maxResults,\n    ): StrategyScanResult {\n        val tickers = candidates.map { it.ticker }",
    "        maxResults: Int = rules.maxResults,\n        btcRegime: BtcRegime = BtcRegime.NEUTRAL,\n    ): StrategyScanResult {\n        val tickers = candidates.map { it.ticker }",
    "candidate scan signature",
)

signal = replace_once(
    signal,
    "        val candidateBtcChange = candidates.firstOrNull { it.btcChangeRate24h != 0.0 }?.btcChangeRate24h\n        return scan(",
    "        val candidateBtcChange = candidates.firstOrNull { it.btcChangeRate24h != 0.0 }?.btcChangeRate24h\n        val effectiveRegime = if (btcRegime == BtcRegime.NEUTRAL && candidateBtcChange != null) {\n            BtcRegimeDetector.detect(candidateBtcChange)\n        } else {\n            btcRegime\n        }\n        return scan(",
    "candidate effective regime",
)

signal = replace_once(
    signal,
    "            btcChangeRateOverride = candidateBtcChange,\n        )\n    }\n\n    fun scan(",
    "            btcChangeRateOverride = candidateBtcChange,\n            btcRegime = effectiveRegime,\n        )\n    }\n\n    fun scan(",
    "candidate pass regime",
)

signal = replace_once(
    signal,
    "        btcChangeRateOverride: Double? = null,\n    ): StrategyScanResult {",
    "        btcChangeRateOverride: Double? = null,\n        btcRegime: BtcRegime = BtcRegime.NEUTRAL,\n    ): StrategyScanResult {",
    "ticker scan signature",
)

signal = replace_once(
    signal,
    "        val btcChangeRate24h = btcChangeRateOverride\n            ?: tickers.firstOrNull { it.market == \"KRW-BTC\" }?.signedChangeRate?.times(100.0)\n            ?: 0.0\n        val evaluations = tickers.mapNotNull { ticker ->",
    "        val btcChangeRate24h = btcChangeRateOverride\n            ?: tickers.firstOrNull { it.market == \"KRW-BTC\" }?.signedChangeRate?.times(100.0)\n            ?: 0.0\n        val effectiveRegime = if (btcRegime == BtcRegime.NEUTRAL && btcChangeRate24h != 0.0) {\n            BtcRegimeDetector.detect(btcChangeRate24h)\n        } else {\n            btcRegime\n        }\n        val effectiveMinScore = (minimumScore + BtcRegimeDetector.minimumScoreDelta(effectiveRegime)).coerceAtMost(90.0)\n        val evaluations = tickers.mapNotNull { ticker ->",
    "ticker effective regime",
)

signal = replace_once(
    signal,
    "                btcChangeRate24h = btcChangeRate24h,\n                rules = rules,",
    "                btcChangeRate24h = btcChangeRate24h,\n                btcRegime = effectiveRegime,\n                rules = rules,",
    "pass regime evaluate",
)

signal = replace_once(
    signal,
    "strategy.status == StrategyStatus.ACTIVE && strategy.score >= minimumScore",
    "strategy.status == StrategyStatus.ACTIVE && strategy.score >= effectiveMinScore",
    "effective score active",
)

signal = replace_once(
    signal,
    "evaluation.strategy.score < minimumScore -> \"SCORE_TOO_LOW\"",
    "evaluation.strategy.score < effectiveMinScore -> \"SCORE_TOO_LOW_REGIME_${effectiveRegime.name}\"",
    "effective score reject",
)

signal = replace_once(
    signal,
    "            lastError = null,\n        )\n    }\n\n    private fun evaluateTicker(",
    "            lastError = if (effectiveRegime.isRisky()) {\n                \"BTC ${effectiveRegime.name}: SignalEngine regime gate active; effectiveMinScore=${effectiveMinScore.one()}\"\n            } else {\n                null\n            },\n        )\n    }\n\n    private fun evaluateTicker(",
    "regime last error",
)

signal = replace_once(
    signal,
    "        btcChangeRate24h: Double,\n        rules: StrategyRules,",
    "        btcChangeRate24h: Double,\n        btcRegime: BtcRegime,\n        rules: StrategyRules,",
    "evaluate signature",
)

signal = replace_once(
    signal,
    "        val overheatPenalty = overheatPenalty(changeRate24h, changeRate30m, changeRate5m, rules)",
    "        val overheatPenalty = overheatPenalty(changeRate24h, changeRate30m, changeRate5m, rules, btcRegime)",
    "overheat call",
)

signal = replace_once(
    signal,
    "        val best = setups.maxByOrNull { it.rawScore - it.penaltySensitiveMultiplier * (overheatPenalty + liquidityPenalty) } ?: return null",
    "        val filteredSetups = setups.map { setup ->\n            if (BtcRegimeDetector.isStrategyAllowed(setup.strategyType.name, btcRegime)) {\n                setup\n            } else {\n                setup.copy(\n                    active = false,\n                    failed = setup.failed + \"REGIME_BLOCKED_${btcRegime.name}\",\n                    diagnostics = setup.diagnostics + \"btcRegime=$btcRegime\",\n                )\n            }\n        }\n        val best = filteredSetups.maxWithOrNull(\n            compareBy<StrategySetup> { if (it.active) 1 else 0 }\n                .thenBy { it.rawScore - it.penaltySensitiveMultiplier * (overheatPenalty + liquidityPenalty) },\n        ) ?: return null",
    "regime setup filter",
)

signal = replace_once(
    signal,
    "        val stopLoss = if (isShort) {\n            max(best.stopLoss.takeIf { it > 0.0 } ?: price * 1.012, price * 1.001)\n        } else {\n            min(best.stopLoss.takeIf { it > 0.0 } ?: price * rules.risk.defaultStopMultiplier, price * 0.999)\n        }\n        val riskPct = abs(percentChange(price, stopLoss)).coerceAtLeast(rules.risk.minimumRiskPct)\n        val target1 = if (isShort) price * (1.0 - riskPct * 1.5 / 100.0) else price * (1.0 + riskPct * 1.5 / 100.0)\n        val target2 = if (isShort) price * (1.0 - riskPct * 2.4 / 100.0) else price * (1.0 + riskPct * 2.4 / 100.0)",
    "        val rawStopLoss = if (isShort) {\n            max(best.stopLoss.takeIf { it > 0.0 } ?: price * 1.012, price * 1.001)\n        } else {\n            min(best.stopLoss.takeIf { it > 0.0 } ?: price * rules.risk.defaultStopMultiplier, price * 0.999)\n        }\n        val stopLoss = if (!isShort && btcRegime.isRisky()) {\n            val tightMultiplier = when (btcRegime) {\n                BtcRegime.BEAR -> 0.985\n                BtcRegime.CRASH -> 0.990\n                else -> rawStopLoss / price\n            }\n            max(rawStopLoss, price * tightMultiplier)\n        } else {\n            rawStopLoss\n        }\n        val riskPct = abs(percentChange(price, stopLoss)).coerceAtLeast(rules.risk.minimumRiskPct)\n        val target1Multiplier = when {\n            btcRegime == BtcRegime.CRASH && !isShort -> 1.2\n            btcRegime == BtcRegime.BEAR && !isShort -> 1.3\n            else -> 1.5\n        }\n        val target2Multiplier = when {\n            btcRegime == BtcRegime.CRASH && !isShort -> 1.8\n            btcRegime == BtcRegime.BEAR && !isShort -> 2.0\n            else -> 2.4\n        }\n        val target1 = if (isShort) price * (1.0 - riskPct * target1Multiplier / 100.0) else price * (1.0 + riskPct * target1Multiplier / 100.0)\n        val target2 = if (isShort) price * (1.0 - riskPct * target2Multiplier / 100.0) else price * (1.0 + riskPct * target2Multiplier / 100.0)",
    "tight stop targets",
)

signal = replace_once(
    signal,
    "            listOf(\n                \"trendScore=${best.trendScore.one()}\",",
    "            listOf(\n                \"btcRegime=$btcRegime\",\n                \"trendScore=${best.trendScore.one()}\",",
    "component regime",
)

signal = replace_once(
    signal,
    "        val missedReason = when {\n            status == StrategyStatus.ACTIVE -> null",
    "        val regimeBlocked = best.failed.firstOrNull { it.startsWith(\"REGIME_BLOCKED_\") }\n        val missedReason = when {\n            status == StrategyStatus.ACTIVE -> null\n            regimeBlocked != null -> regimeBlocked",
    "regime missed reason",
)

signal = replace_once(
    signal,
    "        val notAlreadyPumped = changeRate24h in r.minChange24hPct..r.maxChange24hPct &&\n            changeRate30m < r.maxChange30mPct &&\n            changeRate5m < r.maxChange5mPct\n        val liquidityOk = rankByTradeValue <= r.maxTradeValueRank",
    "        val notAlreadyPumped = changeRate24h in r.minChange24hPct..r.maxChange24hPct &&\n            changeRate30m < r.maxChange30mPct &&\n            changeRate5m < r.maxChange5mPct &&\n            changeRate24h > -8.0\n        val recentDropThenBounce = recentDropThenBounce(five)\n        val notDeadCat = !recentDropThenBounce\n        val liquidityOk = rankByTradeValue <= r.maxTradeValueRank",
    "prepump deadcat vars",
)

signal = replace_once(
    signal,
    "        val active = r.enabled && notAlreadyPumped && liquidityOk && rotationOk && volumeIgnition && structureOk && closeStairOk",
    "        val active = r.enabled && notAlreadyPumped && liquidityOk && rotationOk && volumeIgnition && structureOk && closeStairOk && notDeadCat",
    "prepump active deadcat",
)

signal = replace_once(
    signal,
    "                \"prePump=notYet10pct\",",
    "                \"prePump=notYetExtendedOrDeadCat\",",
    "prepump passed label",
)

signal = replace_once(
    signal,
    "                \"rankValue=$rankByTradeValue\",\n            ),",
    "                \"rankValue=$rankByTradeValue\",\n                \"deadCat=$recentDropThenBounce\",\n            ),",
    "prepump deadcat passed",
)

signal = replace_once(
    signal,
    "                if (!notAlreadyPumped) \"ALREADY_PUMPED_OR_TOO_WEAK\" else null,\n                if (!liquidityOk) \"TRADE_VALUE_RANK_OVER_${r.maxTradeValueRank}\" else null,",
    "                if (!notAlreadyPumped) \"ALREADY_PUMPED_OR_TOO_WEAK\" else null,\n                if (!notDeadCat) \"DEAD_CAT_BOUNCE_SUSPECTED\" else null,\n                if (!liquidityOk) \"TRADE_VALUE_RANK_OVER_${r.maxTradeValueRank}\" else null,",
    "prepump deadcat failed",
)

signal = replace_once(
    signal,
    "    private fun overheatPenalty(change24h: Double, change30m: Double, change5m: Double, rules: StrategyRules): Double {\n        var penalty = 0.0\n        if (change24h > rules.scoring.overheat24hBasePct) {\n            penalty += (change24h - rules.scoring.overheat24hBasePct) * rules.scoring.overheat24hWeight\n        }\n        if (change30m > rules.scoring.overheat30mBasePct) {\n            penalty += (change30m - rules.scoring.overheat30mBasePct) * rules.scoring.overheat30mWeight\n        }\n        if (change5m > rules.scoring.overheat5mBasePct) {\n            penalty += (change5m - rules.scoring.overheat5mBasePct) * rules.scoring.overheat5mWeight\n        }\n        return penalty.coerceIn(0.0, rules.scoring.overheatMax)\n    }",
    "    private fun overheatPenalty(change24h: Double, change30m: Double, change5m: Double, rules: StrategyRules, btcRegime: BtcRegime): Double {\n        var penalty = 0.0\n        if (change24h > rules.scoring.overheat24hBasePct) {\n            penalty += (change24h - rules.scoring.overheat24hBasePct) * rules.scoring.overheat24hWeight\n        }\n        if (change30m > rules.scoring.overheat30mBasePct) {\n            penalty += (change30m - rules.scoring.overheat30mBasePct) * rules.scoring.overheat30mWeight\n        }\n        if (change5m > rules.scoring.overheat5mBasePct) {\n            penalty += (change5m - rules.scoring.overheat5mBasePct) * rules.scoring.overheat5mWeight\n        }\n        if (btcRegime.isRisky()) {\n            if (change30m > 1.0 && change24h < -5.0) penalty += 18.0\n            penalty += when (btcRegime) {\n                BtcRegime.BEAR -> 8.0\n                BtcRegime.CRASH -> 20.0\n                else -> 0.0\n            }\n        }\n        return penalty.coerceIn(0.0, rules.scoring.overheatMax + 20.0)\n    }\n\n    private fun recentDropThenBounce(five: List<Candle>): Boolean {\n        val recent = five.takeLast(12)\n        if (recent.size < 12) return false\n        val drops = recent.take(8).count { it.close < it.open }\n        val lastClose = recent.last().close\n        val bottomPrice = recent.dropLast(2).minOfOrNull { it.low } ?: lastClose\n        val bounceFromBottom = percentChange(bottomPrice, lastClose)\n        return drops >= 5 && bounceFromBottom > 2.0\n    }",
    "overheat deadcat",
)

signal_path.write_text(signal)

main_path = ROOT / "app/src/main/java/com/cryptotradecoach/ui/MainViewModel.kt"
main = main_path.read_text()
main = replace_once(
    main,
    "val scanResult = manualEngine.scan(candidates = listOf(candidate), rules = rules, maxResults = 1)",
    "val scanResult = manualEngine.scan(candidates = listOf(candidate), rules = rules, maxResults = 1, btcRegime = ScannerStateStore.currentBtcRegime.value)",
    "manual scan regime",
)
main_path.write_text(main)

service_path = ROOT / "app/src/main/java/com/cryptotradecoach/service/CoinScannerService.kt"
service = service_path.read_text()
service = replace_once(
    service,
    "            minimumScore = minimumScore,\n            maxResults = maxDisplayCount,\n        )",
    "            minimumScore = minimumScore,\n            maxResults = maxDisplayCount,\n            btcRegime = ScannerStateStore.currentBtcRegime.value,\n        )",
    "deep scan regime",
)
service_path.write_text(service)

print("regime patch applied")
