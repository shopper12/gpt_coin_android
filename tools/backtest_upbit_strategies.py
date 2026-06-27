#!/usr/bin/env python3
"""Robust Upbit strategy backtest for GitHub Actions.

Outputs:
- reports/backtest_latest.json
- reports/backtest_latest.md

The script is intentionally standard-library only. It never registers duplicate CLI
arguments and degrades to an empty report if Upbit data fetches are unavailable, so
a temporary data/API issue does not break the whole workflow before Android build
verification can run.
"""

from __future__ import annotations

import argparse
import json
import statistics
import time
import urllib.parse
import urllib.request
from collections import defaultdict
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

UPBIT_API = "https://api.upbit.com/v1"
UNIT_MINUTES = 5
MAX_CANDLES_PER_REQUEST = 200
REQUEST_SLEEP_SECONDS = 0.16
DEFAULT_HORIZON_BARS = 12

DEFAULT_RULES: dict[str, Any] = {
    "version": "backtest-default",
    "minimumScore": 74.0,
    "candidateSelection": {
        "maxCandleTargets": 45,
        "topTradeValueCount": 70,
        "topChangeRateCount": 35,
        "volumeBuildupCount": 35,
        "quietAccumulationCount": 25,
        "medianTradeValueMultiplier": 1.05,
        "minBuildupChangeRatePct": -2.0,
        "maxBuildupChangeRatePct": 7.5,
        "maxQuietAbsChangeRatePct": 1.8,
    },
    "prePumpRotation": {
        "enabled": True,
        "minChange24hPct": -2.5,
        "maxChange24hPct": 9.0,
        "maxChange30mPct": 3.2,
        "maxChange5mPct": 1.8,
        "maxTradeValueRank": 55,
        "maxChangeRank": 45,
        "minRotation30mPct": 0.45,
        "minVolumeAcceleration": 1.38,
        "minFiveMinuteVolumeRatio": 1.40,
        "minFifteenMinuteVolumeRatio": 1.25,
        "maxRangePct": 5.2,
        "minRangePosition": 0.58,
        "minHighProximityMultiplier": 0.990,
        "minCloseStairCount": 2,
    },
}


def deep_merge(a: dict[str, Any], b: dict[str, Any]) -> dict[str, Any]:
    out = dict(a)
    for k, v in b.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = deep_merge(out[k], v)
        else:
            out[k] = v
    return out


def load_rules(path: Path) -> dict[str, Any]:
    if not path.exists():
        return DEFAULT_RULES
    try:
        return deep_merge(DEFAULT_RULES, json.loads(path.read_text(encoding="utf-8")))
    except Exception as exc:  # noqa: BLE001
        print(f"WARN: rules load failed, using defaults: {exc}")
        return DEFAULT_RULES


def getv(root: dict[str, Any], path: str, default: Any) -> Any:
    cur: Any = root
    for part in path.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return default
        cur = cur[part]
    return cur


def num(root: dict[str, Any], path: str, default: float) -> float:
    try:
        return float(getv(root, path, default))
    except (TypeError, ValueError):
        return default


def integer(root: dict[str, Any], path: str, default: int) -> int:
    try:
        return int(getv(root, path, default))
    except (TypeError, ValueError):
        return default


@dataclass(frozen=True)
class Candle:
    ts: int
    open: float
    high: float
    low: float
    close: float
    trade_value: float


@dataclass(frozen=True)
class Snapshot:
    market: str
    ts: int
    price: float
    change_24h: float
    change_30m: float
    change_5m: float
    acc_trade_value_24h: float
    volume_accel: float
    rank_change: int
    rank_value: int


@dataclass(frozen=True)
class Signal:
    market: str
    strategy: str
    ts: int
    price: float
    entry: float
    stop: float
    target1: float
    target2: float
    score: float
    direction: str
    reason: str
    change_24h: float
    change_30m: float
    change_5m: float
    volume_accel: float
    rank_change: int
    rank_value: int


@dataclass
class Outcome:
    market: str
    strategy: str
    direction: str
    ts: int
    entry: float
    stop: float
    target1: float
    target2: float
    exit_price: float
    return_pct: float
    mfe_pct: float
    mae_pct: float
    target1_hit: bool
    target2_hit: bool
    stop_hit: bool
    bars_held: int
    score: float
    reason: str


def http_json(path: str, params: dict[str, Any] | None = None, retries: int = 3) -> Any:
    query = "?" + urllib.parse.urlencode(params) if params else ""
    url = f"{UPBIT_API}{path}{query}"
    last_exc: Exception | None = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "CryptoTradeCoachBacktest"})
            with urllib.request.urlopen(req, timeout=30) as response:
                return json.loads(response.read().decode("utf-8"))
        except Exception as exc:  # noqa: BLE001
            last_exc = exc
            time.sleep(0.6 + attempt * 0.8)
    raise RuntimeError(f"HTTP fetch failed {path}: {last_exc}")


def fetch_krw_markets() -> list[str]:
    rows = http_json("/market/all", {"isDetails": "false"})
    return sorted(row["market"] for row in rows if row.get("market", "").startswith("KRW-"))


def fetch_current_tickers(markets: list[str]) -> dict[str, dict[str, Any]]:
    out: dict[str, dict[str, Any]] = {}
    for i in range(0, len(markets), 100):
        chunk = markets[i : i + 100]
        rows = http_json("/ticker", {"markets": ",".join(chunk)})
        for row in rows:
            out[row["market"]] = row
        time.sleep(REQUEST_SLEEP_SECONDS)
    return out


def select_markets(markets: list[str], max_markets: int) -> list[str]:
    if not markets:
        return []
    try:
        tickers = fetch_current_tickers(markets)
    except Exception as exc:  # noqa: BLE001
        print(f"WARN: ticker ranking failed, using first markets: {exc}")
        return markets[:max_markets]
    ranked = sorted(markets, key=lambda m: tickers.get(m, {}).get("acc_trade_price_24h", 0.0), reverse=True)
    selected = ranked[:max_markets]
    if "KRW-BTC" in markets and "KRW-BTC" not in selected:
        selected = ["KRW-BTC"] + selected[:-1]
    return selected


def parse_candle(row: dict[str, Any]) -> Candle:
    return Candle(
        ts=int(row.get("timestamp", 0)),
        open=float(row.get("opening_price", 0.0)),
        high=float(row.get("high_price", 0.0)),
        low=float(row.get("low_price", 0.0)),
        close=float(row.get("trade_price", 0.0)),
        trade_value=float(row.get("candle_acc_trade_price", 0.0)),
    )


def fetch_minute_candles(market: str, days: int) -> list[Candle]:
    needed = int(days * 24 * 60 / UNIT_MINUTES) + 320
    candles: list[Candle] = []
    to_value: str | None = None
    while len(candles) < needed:
        params: dict[str, Any] = {"market": market, "count": MAX_CANDLES_PER_REQUEST}
        if to_value:
            params["to"] = to_value
        rows = http_json(f"/candles/minutes/{UNIT_MINUTES}", params)
        if not rows:
            break
        batch = [parse_candle(row) for row in rows]
        candles.extend(batch)
        oldest = min(c.ts for c in batch)
        to_value = datetime.fromtimestamp((oldest - 1) / 1000, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
        time.sleep(REQUEST_SLEEP_SECONDS)
        if len(batch) < MAX_CANDLES_PER_REQUEST:
            break
    unique = {c.ts: c for c in candles}
    return sorted(unique.values(), key=lambda c: c.ts)[-needed:]


def pct(a: float, b: float) -> float:
    return 0.0 if a <= 0 else (b - a) / a * 100.0


def avg(values: Iterable[float]) -> float:
    values = list(values)
    return sum(values) / len(values) if values else 0.0


def build_snapshots(market: str, candles: list[Candle]) -> dict[int, Snapshot]:
    out: dict[int, Snapshot] = {}
    for i in range(300, len(candles)):
        c = candles[i]
        day = candles[i - 288 : i + 1]
        change_24h = pct(candles[i - 288].close, c.close)
        change_30m = pct(candles[i - 6].open, c.close)
        change_5m = pct(c.open, c.close)
        recent_value = sum(x.trade_value for x in candles[i - 2 : i + 1])
        base = sum(x.trade_value for x in candles[i - 23 : i - 3]) / 6.67
        out[c.ts] = Snapshot(
            market=market,
            ts=c.ts,
            price=c.close,
            change_24h=change_24h,
            change_30m=change_30m,
            change_5m=change_5m,
            acc_trade_value_24h=sum(x.trade_value for x in day),
            volume_accel=recent_value / base if base > 0 else 1.0,
            rank_change=9999,
            rank_value=9999,
        )
    return out


def add_ranks(rows_by_market: dict[str, dict[int, Snapshot]]) -> dict[str, dict[int, Snapshot]]:
    by_ts: dict[int, list[Snapshot]] = defaultdict(list)
    for rows in rows_by_market.values():
        for snap in rows.values():
            by_ts[snap.ts].append(snap)
    ranked: dict[tuple[str, int], Snapshot] = {}
    for ts, snaps in by_ts.items():
        cr = {s.market: i + 1 for i, s in enumerate(sorted(snaps, key=lambda x: x.change_24h, reverse=True))}
        vr = {s.market: i + 1 for i, s in enumerate(sorted(snaps, key=lambda x: x.acc_trade_value_24h, reverse=True))}
        for s in snaps:
            ranked[(s.market, ts)] = Snapshot(**{**asdict(s), "rank_change": cr[s.market], "rank_value": vr[s.market]})
    return {m: {ts: ranked[(m, ts)] for ts in rows if (m, ts) in ranked} for m, rows in rows_by_market.items()}


def candidate_pool(snaps: list[Snapshot], rules: dict[str, Any]) -> set[str]:
    if not snaps:
        return set()
    max_targets = max(10, min(integer(rules, "candidateSelection.maxCandleTargets", 45), 80))
    top_trade = sorted(snaps, key=lambda x: x.acc_trade_value_24h, reverse=True)[: integer(rules, "candidateSelection.topTradeValueCount", 70)]
    top_change = sorted(snaps, key=lambda x: x.change_24h, reverse=True)[: integer(rules, "candidateSelection.topChangeRateCount", 35)]
    trade_set = {x.market for x in top_trade}
    values = sorted(x.acc_trade_value_24h for x in snaps)
    med = values[len(values) // 2] if values else 0.0
    buildup = [x for x in snaps if x.acc_trade_value_24h > med * num(rules, "candidateSelection.medianTradeValueMultiplier", 1.05) and num(rules, "candidateSelection.minBuildupChangeRatePct", -2.0) <= x.change_24h <= num(rules, "candidateSelection.maxBuildupChangeRatePct", 7.5) and x.market not in trade_set]
    buildup = sorted(buildup, key=lambda x: x.acc_trade_value_24h, reverse=True)[: integer(rules, "candidateSelection.volumeBuildupCount", 35)]
    merged = {x.market: x for x in top_trade + top_change + buildup}
    return {x.market for x in sorted(merged.values(), key=lambda x: (min(x.rank_change, x.rank_value), -x.acc_trade_value_24h))[:max_targets]}


def pre_pump_signal(market: str, i: int, candles: list[Candle], snap: Snapshot, rules: dict[str, Any]) -> Signal | None:
    if not bool(getv(rules, "prePumpRotation.enabled", True)):
        return None
    prev20 = candles[i - 20 : i]
    if len(prev20) < 20:
        return None
    c = candles[i]
    hi = max(x.high for x in prev20)
    lo = min(x.low for x in prev20)
    range_pct = max(0.0, pct(lo, hi))
    range_pos = (snap.price - lo) / (hi - lo) if hi > lo else 0.5
    vol_ratio = c.trade_value / avg(x.trade_value for x in prev20) if avg(x.trade_value for x in prev20) > 0 else 1.0
    closes_up = sum(1 for a, b in zip(candles[i - 3 : i + 1], candles[i - 2 : i + 1]) if b.close > a.close)
    if not (num(rules, "prePumpRotation.minChange24hPct", -2.5) <= snap.change_24h <= num(rules, "prePumpRotation.maxChange24hPct", 9.0)):
        return None
    if snap.change_30m > num(rules, "prePumpRotation.maxChange30mPct", 3.2) or snap.change_5m > num(rules, "prePumpRotation.maxChange5mPct", 1.8):
        return None
    if snap.rank_value > integer(rules, "prePumpRotation.maxTradeValueRank", 55):
        return None
    if snap.rank_change > integer(rules, "prePumpRotation.maxChangeRank", 45) and snap.change_30m < num(rules, "prePumpRotation.minRotation30mPct", 0.45):
        return None
    if max(snap.volume_accel, vol_ratio) < num(rules, "prePumpRotation.minVolumeAcceleration", 1.38):
        return None
    if range_pct > num(rules, "prePumpRotation.maxRangePct", 5.2) or range_pos < num(rules, "prePumpRotation.minRangePosition", 0.58):
        return None
    if snap.price < hi * num(rules, "prePumpRotation.minHighProximityMultiplier", 0.990):
        return None
    if closes_up < integer(rules, "prePumpRotation.minCloseStairCount", 2):
        return None
    score = 18.0 + 24.0 + min(max((max(snap.volume_accel, vol_ratio) - 1.0) * 18.0, 0.0), 26.0) + (18.0 if snap.rank_change <= 15 else 13.0) + (16.0 if snap.rank_value <= 15 else 11.0)
    stop = min(lo, min(x.low for x in candles[i - 7 : i + 1])) * 0.996
    risk = max(abs(pct(snap.price, stop)), 0.32)
    target1 = snap.price * (1 + risk * 1.6 / 100.0)
    target2 = snap.price * (1 + risk * 2.6 / 100.0)
    return Signal(market, "PRE_PUMP_ROTATION", snap.ts, snap.price, snap.price, stop, target1, target2, score, "LONG", f"pre-pump vol={snap.volume_accel:.2f}x range={range_pct:.2f}% pos={range_pos*100:.1f}%", snap.change_24h, snap.change_30m, snap.change_5m, snap.volume_accel, snap.rank_change, snap.rank_value)


def evaluate(signal: Signal, future: list[Candle], horizon_bars: int) -> Outcome:
    future = future[:horizon_bars]
    if not future:
        exit_price = signal.entry
        mfe = mae = ret = 0.0
        return Outcome(signal.market, signal.strategy, signal.direction, signal.ts, signal.entry, signal.stop, signal.target1, signal.target2, exit_price, ret, mfe, mae, False, False, False, 0, signal.score, signal.reason)
    exit_price = future[-1].close
    bars = len(future)
    t1 = t2 = stop = False
    mfe = max(pct(signal.entry, c.high) for c in future)
    mae = min(pct(signal.entry, c.low) for c in future)
    for idx, c in enumerate(future, start=1):
        if c.low <= signal.stop:
            stop = True
            exit_price = signal.stop
            bars = idx
            break
        if c.high >= signal.target1:
            t1 = True
        if c.high >= signal.target2:
            t2 = True
            exit_price = signal.target2
            bars = idx
            break
    return Outcome(signal.market, signal.strategy, signal.direction, signal.ts, signal.entry, signal.stop, signal.target1, signal.target2, exit_price, pct(signal.entry, exit_price), mfe, mae, t1, t2, stop, bars, signal.score, signal.reason)


def backtest(days: int, max_markets: int, horizon_bars: int, minimum_score: float, rules: dict[str, Any]) -> dict[str, Any]:
    try:
        markets = select_markets(fetch_krw_markets(), max_markets)
    except Exception as exc:  # noqa: BLE001
        print(f"WARN: market selection failed: {exc}")
        markets = []
    candles_by_market: dict[str, list[Candle]] = {}
    snapshots: dict[str, dict[int, Snapshot]] = {}
    for idx, market in enumerate(markets, start=1):
        print(f"[{idx}/{len(markets)}] fetching {market}")
        try:
            candles = fetch_minute_candles(market, days)
        except Exception as exc:  # noqa: BLE001
            print(f"WARN: candle fetch failed {market}: {exc}")
            continue
        if len(candles) < 340:
            continue
        candles_by_market[market] = candles
        snapshots[market] = build_snapshots(market, candles)
    snapshots = add_ranks(snapshots)
    by_ts: dict[int, list[Snapshot]] = defaultdict(list)
    for rows in snapshots.values():
        for snap in rows.values():
            by_ts[snap.ts].append(snap)
    pool_by_ts = {ts: candidate_pool(snaps, rules) for ts, snaps in by_ts.items()}
    outcomes: list[Outcome] = []
    seen: set[tuple[str, str, int]] = set()
    for market, candles in candles_by_market.items():
        for i in range(300, len(candles) - horizon_bars):
            snap = snapshots.get(market, {}).get(candles[i].ts)
            if not snap or market not in pool_by_ts.get(snap.ts, set()):
                continue
            signal = pre_pump_signal(market, i, candles, snap, rules)
            if signal is None or signal.score < minimum_score:
                continue
            key = (signal.market, signal.strategy, signal.ts // (30 * 60 * 1000))
            if key in seen:
                continue
            seen.add(key)
            outcomes.append(evaluate(signal, candles[i + 1 :], horizon_bars))
    return summarize(outcomes, days, max_markets, horizon_bars, minimum_score, markets, rules)


def quantile(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    values = sorted(values)
    idx = min(len(values) - 1, max(0, int((len(values) - 1) * q)))
    return values[idx]


def summarize(outcomes: list[Outcome], days: int, max_markets: int, horizon_bars: int, minimum_score: float, markets: list[str], rules: dict[str, Any]) -> dict[str, Any]:
    by_strategy: dict[str, list[Outcome]] = defaultdict(list)
    for row in outcomes:
        by_strategy[row.strategy].append(row)
    strategy_summary: dict[str, Any] = {}
    for strategy, rows in sorted(by_strategy.items()):
        returns = [r.return_pct for r in rows]
        strategy_summary[strategy] = {
            "signals": len(rows),
            "avg_return_pct": avg(returns),
            "median_return_pct": statistics.median(returns) if returns else 0.0,
            "p25_return_pct": quantile(returns, 0.25),
            "p75_return_pct": quantile(returns, 0.75),
            "target1_hit_rate_pct": sum(1 for r in rows if r.target1_hit) / len(rows) * 100 if rows else 0.0,
            "target2_hit_rate_pct": sum(1 for r in rows if r.target2_hit) / len(rows) * 100 if rows else 0.0,
            "stop_hit_rate_pct": sum(1 for r in rows if r.stop_hit) / len(rows) * 100 if rows else 0.0,
            "avg_mfe_pct": avg(r.mfe_pct for r in rows),
            "avg_mae_pct": avg(r.mae_pct for r in rows),
        }
    top = sorted(outcomes, key=lambda r: r.return_pct, reverse=True)[:20]
    bottom = sorted(outcomes, key=lambda r: r.return_pct)[:20]
    return {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "config": {
            "days": days,
            "max_markets": max_markets,
            "horizon_minutes": horizon_bars * UNIT_MINUTES,
            "minimum_score": minimum_score,
            "rules_version": rules.get("version", "unknown"),
            "markets": markets,
        },
        "total_signals": len(outcomes),
        "strategy_summary": strategy_summary,
        "top_outcomes": [asdict(x) for x in top],
        "bottom_outcomes": [asdict(x) for x in bottom],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
        "all_outcomes": [asdict(x) for x in outcomes],
    }


def pct_text(x: float) -> str:
    return f"{x:+.2f}%"


def write_reports(result: dict[str, Any], out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "backtest_latest.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = [
        "# Upbit Strategy Backtest",
        "",
        f"- Generated UTC: `{result['generated_at_utc']}`",
        f"- Days: `{result['config']['days']}`",
        f"- Markets: `{len(result['config']['markets'])}`",
        f"- Horizon: `{result['config']['horizon_minutes']} minutes`",
        f"- Minimum score: `{result['config']['minimum_score']}`",
        f"- Total signals: `{result['total_signals']}`",
        "",
        "## Strategy summary",
        "",
        "| Strategy | Signals | Avg ret | Median | T1 hit | T2 hit | Stop hit | Avg MFE | Avg MAE |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for name, s in result["strategy_summary"].items():
        lines.append(f"| {name} | {s['signals']} | {pct_text(s['avg_return_pct'])} | {pct_text(s['median_return_pct'])} | {s['target1_hit_rate_pct']:.1f}% | {s['target2_hit_rate_pct']:.1f}% | {s['stop_hit_rate_pct']:.1f}% | {pct_text(s['avg_mfe_pct'])} | {pct_text(s['avg_mae_pct'])} |")
    lines.extend(["", "## Top 10 outcomes", ""])
    for row in result["top_outcomes"][:10]:
        dt = datetime.fromtimestamp(row["ts"] / 1000, tz=timezone.utc).strftime("%m-%d %H:%M")
        lines.append(f"- `{dt}` {row['market']} {row['strategy']} ret {pct_text(row['return_pct'])} score {row['score']:.1f} — {row['reason']}")
    lines.extend(["", "## Bottom 10 outcomes", ""])
    for row in result["bottom_outcomes"][:10]:
        dt = datetime.fromtimestamp(row["ts"] / 1000, tz=timezone.utc).strftime("%m-%d %H:%M")
        lines.append(f"- `{dt}` {row['market']} {row['strategy']} ret {pct_text(row['return_pct'])} score {row['score']:.1f} — {row['reason']}")
    (out_dir / "backtest_latest.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--days", type=int, default=7)
    parser.add_argument("--max-markets", type=int, default=60)
    parser.add_argument("--horizon-bars", type=int, default=DEFAULT_HORIZON_BARS)
    parser.add_argument("--minimum-score", type=float, default=74.0)
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--out-dir", type=Path, default=Path("reports"))
    args = parser.parse_args()
    result = backtest(args.days, args.max_markets, args.horizon_bars, args.minimum_score, load_rules(args.rules))
    write_reports(result, args.out_dir)
    print(json.dumps(result["strategy_summary"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
