#!/usr/bin/env python3
"""Lightweight Upbit walk-forward backtest for strategy-rules.json.

This is intentionally conservative. It does not try to mirror the full Android
SignalEngine line-for-line; it tests whether rule changes improve broad forward
returns on historical candles without look-ahead.
"""

from __future__ import annotations

import argparse
import time
from dataclasses import dataclass
from typing import Any

from common import AgentError, http_json, load_json, save_json, now_ms

UPBIT = "https://api.upbit.com/v1"


@dataclass(frozen=True)
class Candle:
    ts: int
    open: float
    high: float
    low: float
    close: float
    trade_value: float


def fetch_krw_markets(limit: int) -> list[str]:
    rows = http_json(f"{UPBIT}/market/all?isDetails=false")
    markets = [row["market"] for row in rows if row.get("market", "").startswith("KRW-")]
    markets = [m for m in markets if m != "KRW-BTC"]
    return markets[:limit]


def fetch_candles(market: str, unit: int, count: int) -> list[Candle]:
    rows = http_json(f"{UPBIT}/candles/minutes/{unit}?market={market}&count={count}")
    candles = [
        Candle(
            ts=int(row.get("timestamp", 0)),
            open=float(row.get("opening_price", 0.0)),
            high=float(row.get("high_price", 0.0)),
            low=float(row.get("low_price", 0.0)),
            close=float(row.get("trade_price", 0.0)),
            trade_value=float(row.get("candle_acc_trade_price", 0.0)),
        )
        for row in rows
    ]
    return sorted(candles, key=lambda x: x.ts)


def pct(a: float, b: float) -> float:
    return 0.0 if a <= 0 else ((b - a) / a) * 100.0


def avg(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def simulate_market(market: str, rules: dict[str, Any], count: int) -> list[dict[str, Any]]:
    five = fetch_candles(market, 5, count)
    if len(five) < 80:
        return []
    out: list[dict[str, Any]] = []
    compression = rules["compressionBreakout"]
    sweep = rules["sweepReclaim"]
    risk = rules["risk"]
    minimum_score = float(rules.get("minimumScore", 60.0))

    # Walk forward. At index i, only candles <= i are visible. Outcome uses future candles.
    for i in range(50, len(five) - 12):
        visible = five[: i + 1]
        price = visible[-1].close
        recent = visible[-20:]
        previous = visible[-40:-20]
        if len(previous) < 20:
            continue
        recent_high = max(c.high for c in recent)
        recent_low = min(c.low for c in recent)
        previous_high = max(c.high for c in previous)
        previous_low = min(c.low for c in previous)
        recent_range = pct(recent_low, recent_high)
        previous_range = pct(previous_low, previous_high)
        compressed = previous_range > 0 and recent_range <= previous_range * float(compression["rangeCompressionRatio"])
        distance_high = ((recent_high - price) / recent_high) * 100 if recent_high > 0 else 99.0
        near_high = 0 <= distance_high <= float(compression["maxDistanceTo15mHighPct"])
        current_value = visible[-1].trade_value
        avg_value = avg([c.trade_value for c in visible[-21:-1]])
        value_ratio = current_value / avg_value if avg_value > 0 else 0.0
        volume_ok = value_ratio >= float(compression["minFiveMinuteVolumeRatio"])
        score = 18.0 if compressed else max(0.0, 12.0 - recent_range * 2.0)
        if near_high:
            score += max(8.0, 18.0 - distance_high * 3.0)
        score += min(20.0, max(0.0, (value_ratio - 1.0) * 18.0))
        if compressed and near_high and volume_ok and score >= minimum_score:
            future = five[i + 1 : i + 13]
            if not future:
                continue
            entry = price
            high_after = max(c.high for c in future)
            low_after = min(c.low for c in future)
            close_after_30m = future[min(5, len(future) - 1)].close
            stop = recent_low * 0.997
            risk_pct = max(abs(pct(entry, stop)), float(risk.get("minimumRiskPct", 0.1)))
            target1 = entry * (1.0 + risk_pct * 1.5 / 100.0)
            out.append(
                {
                    "market": market,
                    "strategyType": "COMPRESSION_BREAKOUT",
                    "timestamp": visible[-1].ts,
                    "score": score,
                    "entry": entry,
                    "return30m": pct(entry, close_after_30m),
                    "mfe": pct(entry, high_after),
                    "mae": pct(entry, low_after),
                    "targetHit": high_after >= target1,
                    "stopHit": low_after <= stop,
                }
            )

        # Sweep & reclaim approximation on 5m.
        lookback = int(sweep["fiveMinuteLookback"])
        if len(visible) > lookback + 3:
            prior = visible[-lookback - 2 : -2]
            prior_low = min(c.low for c in prior)
            sweep_candle = min(visible[-5:-1], key=lambda c: c.low)
            reclaimed = sweep_candle.low < prior_low and visible[-1].close > prior_low
            vol_ok = True
            if sweep.get("requireVolumeAboveAverage", True):
                vol_ok = visible[-1].trade_value >= avg([c.trade_value for c in visible[-21:-1]])
            if reclaimed and vol_ok and minimum_score <= 70.0:
                future = five[i + 1 : i + 13]
                if not future:
                    continue
                entry = price
                high_after = max(c.high for c in future)
                low_after = min(c.low for c in future)
                close_after_30m = future[min(5, len(future) - 1)].close
                stop = sweep_candle.low * 0.997
                risk_pct = max(abs(pct(entry, stop)), float(risk.get("minimumRiskPct", 0.1)))
                target1 = entry * (1.0 + risk_pct * 1.5 / 100.0)
                out.append(
                    {
                        "market": market,
                        "strategyType": "SWEEP_RECLAIM",
                        "timestamp": visible[-1].ts,
                        "score": 60.0,
                        "entry": entry,
                        "return30m": pct(entry, close_after_30m),
                        "mfe": pct(entry, high_after),
                        "mae": pct(entry, low_after),
                        "targetHit": high_after >= target1,
                        "stopHit": low_after <= stop,
                    }
                )
    return out


def summarize(trades: list[dict[str, Any]]) -> dict[str, Any]:
    if not trades:
        return {
            "sampleSize": 0,
            "avgReturn30m": 0.0,
            "targetHitRate": 0.0,
            "stopHitRate": 0.0,
            "avgMfe": 0.0,
            "avgMae": 0.0,
            "mfeMaeRatio": 0.0,
            "byStrategy": [],
        }
    by_strategy = []
    for strategy in sorted({t["strategyType"] for t in trades}):
        rows = [t for t in trades if t["strategyType"] == strategy]
        by_strategy.append(_summary_rows(strategy, rows))
    all_summary = _summary_rows("ALL", trades)
    all_summary["byStrategy"] = by_strategy
    return all_summary


def _summary_rows(strategy: str, rows: list[dict[str, Any]]) -> dict[str, Any]:
    avg_mfe = avg([float(t["mfe"]) for t in rows])
    avg_mae = avg([float(t["mae"]) for t in rows])
    return {
        "strategyType": strategy,
        "sampleSize": len(rows),
        "avgReturn30m": avg([float(t["return30m"]) for t in rows]),
        "targetHitRate": sum(1 for t in rows if t["targetHit"]) / len(rows),
        "stopHitRate": sum(1 for t in rows if t["stopHit"]) / len(rows),
        "avgMfe": avg_mfe,
        "avgMae": avg_mae,
        "mfeMaeRatio": avg_mfe / abs(avg_mae) if avg_mae else 0.0,
    }


def run_backtest(rules_path: str, markets: int, candles: int, sleep_sec: float) -> dict[str, Any]:
    rules = load_json(rules_path)
    selected_markets = fetch_krw_markets(markets)
    trades: list[dict[str, Any]] = []
    failures: list[str] = []
    for market in selected_markets:
        try:
            trades.extend(simulate_market(market, rules, candles))
            if sleep_sec > 0:
                time.sleep(sleep_sec)
        except AgentError as e:
            failures.append(f"{market}: {e}")
    return {
        "generatedAt": now_ms(),
        "rulesPath": rules_path,
        "marketsTested": selected_markets,
        "tradeCount": len(trades),
        "summary": summarize(trades),
        "failures": failures[:20],
        "sampleTrades": trades[:50],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rules", default="rules/strategy-rules.json")
    parser.add_argument("--markets", type=int, default=20)
    parser.add_argument("--candles", type=int, default=160)
    parser.add_argument("--sleep", type=float, default=0.12)
    parser.add_argument("--write-result", default="reports/backtest-latest.json")
    args = parser.parse_args()
    result = run_backtest(args.rules, args.markets, args.candles, args.sleep)
    save_json(args.write_result, result)
    print(f"Backtest trades: {result['tradeCount']}")
    print(result["summary"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
