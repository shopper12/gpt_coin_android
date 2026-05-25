#!/usr/bin/env python3
# Triggered patcher: moves PRE_PUMP_ROTATION thresholds from SignalEngine constants to StrategyRules.
from pathlib import Path

path = Path('app/src/main/java/com/cryptotradecoach/domain/SignalEngine.kt')
text = path.read_text(encoding='utf-8')
replacements = [
    (
        '        val closesUp = five.takeLast(4).zipWithNext().count { it.second.close > it.first.close }\n        val notAlreadyPumped = changeRate24h in -4.0..8.5 && changeRate30m < 3.2 && changeRate5m < 1.8\n',
        '        val closesUp = five.takeLast(4).zipWithNext().count { it.second.close > it.first.close }\n        val r = rules.prePumpRotation\n        val notAlreadyPumped = changeRate24h in r.minChange24hPct..r.maxChange24hPct &&\n            changeRate30m < r.maxChange30mPct &&\n            changeRate5m < r.maxChange5mPct\n',
    ),
    ('        val liquidityOk = rankByTradeValue <= 25\n', '        val liquidityOk = rankByTradeValue <= r.maxTradeValueRank\n'),
    ('        val rotationOk = rankByChangeRate <= 35 || changeRate30m > 0.7\n', '        val rotationOk = rankByChangeRate <= r.maxChangeRank || changeRate30m > r.minRotation30mPct\n'),
    (
        '        val volumeIgnition = volumeAcceleration >= 1.45 || fiveVolumeRatio >= 1.6 || fifteenVolumeRatio >= 1.35\n',
        '        val volumeIgnition = volumeAcceleration >= r.minVolumeAcceleration ||\n            fiveVolumeRatio >= r.minFiveMinuteVolumeRatio ||\n            fifteenVolumeRatio >= r.minFifteenMinuteVolumeRatio\n',
    ),
    (
        '        val structureOk = rangePct <= 4.2 && rangePos >= 0.55 && price >= prev20High * 0.992\n',
        '        val structureOk = rangePct <= r.maxRangePct &&\n            rangePos >= r.minRangePosition &&\n            price >= prev20High * r.minHighProximityMultiplier\n',
    ),
    (
        '        val active = notAlreadyPumped && liquidityOk && rotationOk && volumeIgnition && structureOk && closesUp >= 2\n',
        '        val active = r.enabled && notAlreadyPumped && liquidityOk && rotationOk && volumeIgnition && structureOk && closesUp >= r.minCloseStairCount\n',
    ),
    ('                if (!liquidityOk) "TRADE_VALUE_RANK_OVER_25" else null,\n', '                if (!liquidityOk) "TRADE_VALUE_RANK_OVER_${r.maxTradeValueRank}" else null,\n'),
    ('                if (closesUp < 2) "NO_5M_CLOSE_STAIR" else null,\n', '                if (closesUp < r.minCloseStairCount) "NO_5M_CLOSE_STAIR" else null,\n'),
]
for old, new in replacements:
    if old in text:
        text = text.replace(old, new, 1)
    elif new not in text:
        raise SystemExit(f'missing pattern: {old[:80]}')
path.write_text(text, encoding='utf-8')