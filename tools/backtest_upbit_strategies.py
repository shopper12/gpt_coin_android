#!/usr/bin/env python3
"""Backtest Crypto Trade Coach strategy logic on historical Upbit candles.

This script intentionally uses only the Python standard library so it can run in
GitHub Actions without dependency installation. It focuses on the currently most
important rules:
- PRE_PUMP_ROTATION: catch candidates before a +10% style move is already obvious.
- BTC_SHORT_REGIME: flag KRW-BTC short / risk-off structure.
- RANGE_BOTTOM_BOUNCE and PREV_CLOSE_RECLAIM are research-only candidates using
  the newly parsed daily range fields.

It writes:
- reports/backtest_latest.json
- reports/backtest_latest.md
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
import time
import urllib.parse
import urllib.request
from collections import defaultdict
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

UPBIT_API = "https://api.upbit.com/v1"
UNIT_MINUTES = 5
MAX_CANDLES_PER_REQUEST = 200
REQUEST_SLEEP_SECONDS = 0.13
DEFAULT_HORIZON_BARS = 12  # 12 * 5m = 60m

DEFAULT_RULES = {
    "version": "backtest-default",
    "candidateSelection": {
        "maxCandleTargets": 35,
        "topTradeValueCount": 40,
        "topChangeRateCount": 15,
        "volumeBuildupCount": 15,
        "quietAccumulationCount": 10,
        "medianTradeValueMultiplier": 1.5,
        "minBuildupChangeRatePct": -2.0,
        "maxBuildupChangeRatePct": 5.0,
        "maxQuietAbsChangeRatePct": 1.5,
    },
    "prePumpRotation": {
        "enabled": True,
        "minChange24hPct": -4.0,
        "maxChange24hPct": 8.5,
        "maxChange30mPct": 3.2,
        "maxChange5mPct": 1.8,
        "maxTradeValueRank": 25,
        "maxChangeRank": 35,
        "minRotation30mPct": 0.7,
        "minVolumeAcceleration": 1.45,
        "minFiveMinuteVolumeRatio": 1.6,
        "minFifteenMinuteVolumeRatio": 1.35,
        "maxRangePct": 4.2,
        "minRangePosition": 0.55,
        "minHighProximityMultiplier": 0.992,
        "minCloseStairCount": 2,
    },
}

def _merge(a, b):
    out = dict(a)
    for k, v in b.items():
        out[k] = _merge(out[k], v) if isinstance(v, dict) and isinstance(out.get(k), dict) else v
    return out

def load_rules(path: Path) -> dict:
    return DEFAULT_RULES if not path.exists() else _merge(DEFAULT_RULES, json.loads(path.read_text(encoding="utf-8")))

def rv(rules: dict, key: str, default):
    cur = rules
    for part in key.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return default
        cur = cur[part]
    return cur

def rf(rules: dict, key: str, default: float) -> float:
    try:
        return float(rv(rules, key, default))
    except (TypeError, ValueError):
        return default

def ri(rules: dict, key: str, default: int) -> int:
    try:
        return int(rv(rules, key, default))
    except (TypeError, ValueError):
        return default



@dataclass(frozen=True)
class Candle:
    ts: int
    open: float
    high: float
    low: float
    close: float
    volume: float
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
    high_24h: float
    low_24h: float
    prev_close: float
    volume_accel: float
    rank_change: int = 9999
    rank_value: int = 9999


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


def http_json(path: str, params: dict[str, Any] | None = None) -> Any:
    query = ""
    if params:
        query = "?" + urllib.parse.urlencode(params)
    url = f"{UPBIT_API}{path}{query}"
    request = urllib.request.Request(url, headers={"User-Agent": "CryptoTradeCoachBacktest"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


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
    tickers = fetch_current_tickers(markets)
    ranked = sorted(
        markets,
        key=lambda m: tickers.get(m, {}).get("acc_trade_price_24h", 0.0),
        reverse=True,
    )
    selected = ranked[:max_markets]
    if "KRW-BTC" not in selected and "KRW-BTC" in markets:
        selected = ["KRW-BTC"] + selected[:-1]
    return selected


def parse_candle(row: dict[str, Any]) -> Candle:
    return Candle(
        ts=int(row.get("timestamp", 0)),
        open=float(row.get("opening_price", 0.0)),
        high=float(row.get("high_price", 0.0)),
        low=float(row.get("low_price", 0.0)),
        close=float(row.get("trade_price", 0.0)),
        volume=float(row.get("candle_acc_trade_volume", 0.0)),
        trade_value=float(row.get("candle_acc_trade_price", 0.0)),
    )


def fetch_minute_candles(market: str, days: int, unit: int = UNIT_MINUTES) -> list[Candle]:
    needed = int(days * 24 * 60 / unit) + 300
    candles: list[Candle] = []
    to_value: str | None = None
    while len(candles) < needed:
        params: dict[str, Any] = {"market": market, "count": MAX_CANDLES_PER_REQUEST}
        if to_value:
            params["to"] = to_value
        rows = http_json(f"/candles/minutes/{unit}", params)
        if not rows:
            break
        batch = [parse_candle(row) for row in rows]
        candles.extend(batch)
        oldest = min(c.ts for c in batch)
        # Upbit accepts UTC datetime strings without a timezone suffix for pagination.
        to_value = datetime.fromtimestamp((oldest - 1) / 1000, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
        time.sleep(REQUEST_SLEEP_SECONDS)
        if len(batch) < MAX_CANDLES_PER_REQUEST:
            break
    unique = {c.ts: c for c in candles}
    return sorted(unique.values(), key=lambda c: c.ts)[-needed:]


def percent_change(from_price: float, to_price: float) -> float:
    if from_price <= 0:
        return 0.0
    return (to_price - from_price) / from_price * 100.0


def avg(values: Iterable[float]) -> float:
    values = list(values)
    return sum(values) / len(values) if values else 0.0


def aggregate_15m(candles: list[Candle]) -> list[Candle]:
    groups: dict[int, list[Candle]] = defaultdict(list)
    bucket_ms = 15 * 60 * 1000
    for c in candles:
        groups[c.ts // bucket_ms * bucket_ms].append(c)
    out: list[Candle] = []
    for ts, rows in sorted(groups.items()):
        rows = sorted(rows, key=lambda c: c.ts)
        if len(rows) < 2:
            continue
        out.append(
            Candle(
                ts=ts,
                open=rows[0].open,
                high=max(r.high for r in rows),
                low=min(r.low for r in rows),
                close=rows[-1].close,
                volume=sum(r.volume for r in rows),
                trade_value=sum(r.trade_value for r in rows),
            )
        )
    return out


def aggregate_240m(candles: list[Candle]) -> list[Candle]:
    groups: dict[int, list[Candle]] = defaultdict(list)
    bucket_ms = 240 * 60 * 1000
    for c in candles:
        groups[c.ts // bucket_ms * bucket_ms].append(c)
    out: list[Candle] = []
    for ts, rows in sorted(groups.items()):
        rows = sorted(rows, key=lambda c: c.ts)
        if len(rows) < 12:
            continue
        out.append(
            Candle(
                ts=ts,
                open=rows[0].open,
                high=max(r.high for r in rows),
                low=min(r.low for r in rows),
                close=rows[-1].close,
                volume=sum(r.volume for r in rows),
                trade_value=sum(r.trade_value for r in rows),
            )
        )
    return out


def build_snapshots(market: str, candles: list[Candle]) -> dict[int, Snapshot]:
    out: dict[int, Snapshot] = {}
    for i in range(60, len(candles)):
        c = candles[i]
        day_window = candles[max(0, i - 288) : i + 1]
        if len(day_window) < 60:
            continue
        prev_24h = candles[i - 288].close if i >= 288 else day_window[0].open
        change_24h = percent_change(prev_24h, c.close)
        change_30m = percent_change(candles[i - 6].open, c.close) if i >= 6 else 0.0
        change_5m = percent_change(c.open, c.close)
        recent_trade_value = sum(x.trade_value for x in candles[max(0, i - 2) : i + 1])
        base_window = candles[max(0, i - 23) : max(0, i - 3)]
        base_trade_value = sum(x.trade_value for x in base_window) / 6.67 if base_window else 0.0
        volume_accel = recent_trade_value / base_trade_value if base_trade_value > 0 else 1.0
        out[c.ts] = Snapshot(
            market=market,
            ts=c.ts,
            price=c.close,
            change_24h=change_24h,
            change_30m=change_30m,
            change_5m=change_5m,
            acc_trade_value_24h=sum(x.trade_value for x in day_window),
            high_24h=max(x.high for x in day_window),
            low_24h=min(x.low for x in day_window),
            prev_close=prev_24h,
            volume_accel=volume_accel,
        )
    return out


def add_ranks(snapshots_by_market: dict[str, dict[int, Snapshot]]) -> dict[str, dict[int, Snapshot]]:
    by_ts: dict[int, list[Snapshot]] = defaultdict(list)
    for rows in snapshots_by_market.values():
        for snap in rows.values():
            by_ts[snap.ts].append(snap)
    ranked_lookup: dict[tuple[str, int], Snapshot] = {}
    for ts, snaps in by_ts.items():
        change_rank = {s.market: i + 1 for i, s in enumerate(sorted(snaps, key=lambda x: x.change_24h, reverse=True))}
        value_rank = {s.market: i + 1 for i, s in enumerate(sorted(snaps, key=lambda x: x.acc_trade_value_24h, reverse=True))}
        for s in snaps:
            ranked_lookup[(s.market, ts)] = Snapshot(
                **{**asdict(s), "rank_change": change_rank[s.market], "rank_value": value_rank[s.market]}
            )
    return {
        market: {ts: ranked_lookup[(market, ts)] for ts in rows.keys() if (market, ts) in ranked_lookup}
        for market, rows in snapshots_by_market.items()
    }



def candidate_markets_at_ts(snaps: list[Snapshot], rules: dict) -> set[str]:
    if not snaps:
        return set()
    max_targets = max(10, min(ri(rules, "candidateSelection.maxCandleTargets", 35), 80))
    top_trade = sorted(snaps, key=lambda x: x.acc_trade_value_24h, reverse=True)[:ri(rules, "candidateSelection.topTradeValueCount", 40)]
    top_change = sorted(snaps, key=lambda x: x.change_24h, reverse=True)[:ri(rules, "candidateSelection.topChangeRateCount", 15)]
    trade_set = {x.market for x in top_trade}
    values = sorted(x.acc_trade_value_24h for x in snaps)
    median_value = values[len(values) // 2] if values else 0.0
    buildup = [x for x in snaps if x.acc_trade_value_24h > median_value * rf(rules, "candidateSelection.medianTradeValueMultiplier", 1.5) and rf(rules, "candidateSelection.minBuildupChangeRatePct", -2.0) <= x.change_24h <= rf(rules, "candidateSelection.maxBuildupChangeRatePct", 5.0) and x.market not in trade_set]
    buildup = sorted(buildup, key=lambda x: x.acc_trade_value_24h, reverse=True)[:ri(rules, "candidateSelection.volumeBuildupCount", 15)]
    quiet = [x for x in snaps if abs(x.change_24h) < rf(rules, "candidateSelection.maxQuietAbsChangeRatePct", 1.5) and x.market not in trade_set and x.market not in {y.market for y in buildup}]
    quiet = sorted(quiet, key=lambda x: x.acc_trade_value_24h, reverse=True)[:ri(rules, "candidateSelection.quietAccumulationCount", 10)]
    cr = {x.market: n + 1 for n, x in enumerate(top_change)}
    vr = {x.market: n + 1 for n, x in enumerate(top_trade)}
    merged = {x.market: x for x in top_trade + top_change + buildup + quiet}
    ranked = sorted(merged.values(), key=lambda x: (min(cr.get(x.market, 9999), vr.get(x.market, 9999)), -x.acc_trade_value_24h))
    return {x.market for x in ranked[:max_targets]}


def risk_targets_long(entry: float, stop: float) -> tuple[float, float, float]:
    stop = min(stop if stop > 0 else entry * 0.985, entry * 0.999)
    risk_pct = max(abs(percent_change(entry, stop)), 0.35)
    return stop, entry * (1 + risk_pct * 1.5 / 100.0), entry * (1 + risk_pct * 2.4 / 100.0)


def risk_targets_short(entry: float, stop: float) -> tuple[float, float, float]:
    stop = max(stop if stop > 0 else entry * 1.012, entry * 1.001)
    risk_pct = max(abs(percent_change(entry, stop)), 0.35)
    return stop, entry * (1 - risk_pct * 1.5 / 100.0), entry * (1 - risk_pct * 2.4 / 100.0)


def pre_pump_signal(market: str, i: int, five: list[Candle], fifteen: list[Candle], snap: Snapshot, rules: dict) -> Signal | None:
    c = five[i]
    prev20 = five[max(0, i - 20) : i]
    if len(prev20) < 20 or len(fifteen) < 25:
        return None
    prev20_high = max(x.high for x in prev20)
    prev20_low = min(x.low for x in prev20)
    range_pct = max(percent_change(prev20_low, prev20_high), 0.0)
    range_pos = (snap.price - prev20_low) / (prev20_high - prev20_low) if prev20_high > prev20_low else 0.5
    five_vol_ratio = c.trade_value / avg(x.trade_value for x in prev20) if avg(x.trade_value for x in prev20) > 0 else 1.0
    fifteen_vol_ratio = fifteen[-1].trade_value / avg(x.trade_value for x in fifteen[-21:-1]) if avg(x.trade_value for x in fifteen[-21:-1]) > 0 else 1.0
    recent4 = five[max(0, i - 3) : i + 1]
    closes_up = sum(1 for a, b in zip(recent4, recent4[1:]) if b.close > a.close)

    if not rv(rules, "prePumpRotation.enabled", True):
        return None
    not_already_pumped = rf(rules, "prePumpRotation.minChange24hPct", -4.0) <= snap.change_24h <= rf(rules, "prePumpRotation.maxChange24hPct", 8.5) and snap.change_30m < rf(rules, "prePumpRotation.maxChange30mPct", 3.2) and snap.change_5m < rf(rules, "prePumpRotation.maxChange5mPct", 1.8)
    liquidity_ok = snap.rank_value <= ri(rules, "prePumpRotation.maxTradeValueRank", 25)
    rotation_ok = snap.rank_change <= ri(rules, "prePumpRotation.maxChangeRank", 35) or snap.change_30m > rf(rules, "prePumpRotation.minRotation30mPct", 0.7)
    volume_ignition = snap.volume_accel >= rf(rules, "prePumpRotation.minVolumeAcceleration", 1.45) or five_vol_ratio >= rf(rules, "prePumpRotation.minFiveMinuteVolumeRatio", 1.6) or fifteen_vol_ratio >= rf(rules, "prePumpRotation.minFifteenMinuteVolumeRatio", 1.35)
    structure_ok = range_pct <= rf(rules, "prePumpRotation.maxRangePct", 4.2) and range_pos >= rf(rules, "prePumpRotation.minRangePosition", 0.55) and snap.price >= prev20_high * rf(rules, "prePumpRotation.minHighProximityMultiplier", 0.992)
    active = not_already_pumped and liquidity_ok and rotation_ok and volume_ignition and structure_ok and closes_up >= ri(rules, "prePumpRotation.minCloseStairCount", 2)
    if not active:
        return None

    structure_score = 22.0 if structure_ok else (12.0 if range_pos >= 0.5 else 0.0)
    volume_score = min(max((max(snap.volume_accel, five_vol_ratio, fifteen_vol_ratio) - 1.0) * 18.0, 0.0), 24.0)
    rotation_score = 20.0 if snap.rank_change <= 10 else 15.0 if snap.rank_change <= 25 else 12.0 if snap.change_30m > rf(rules, "prePumpRotation.minRotation30mPct", 0.7) else 0.0
    liquidity_score = 16.0 if snap.rank_value <= 10 else 10.0 if snap.rank_value <= ri(rules, "prePumpRotation.maxTradeValueRank", 25) else 0.0
    score = structure_score + volume_score + rotation_score + liquidity_score + 14.0
    raw_stop = min(prev20_low, min(x.low for x in five[max(0, i - 7) : i + 1])) * 0.997
    stop, target1, target2 = risk_targets_long(snap.price, raw_stop)
    return Signal(
        market=market,
        strategy="PRE_PUMP_ROTATION",
        ts=snap.ts,
        price=snap.price,
        entry=snap.price,
        stop=stop,
        target1=target1,
        target2=target2,
        score=score,
        direction="LONG",
        reason=f"pre-pump vol={snap.volume_accel:.2f}x range={range_pct:.2f}% pos={range_pos*100:.1f}% ranks={snap.rank_change}/{snap.rank_value}",
        change_24h=snap.change_24h,
        change_30m=snap.change_30m,
        change_5m=snap.change_5m,
        volume_accel=snap.volume_accel,
        rank_change=snap.rank_change,
        rank_value=snap.rank_value,
    )


def range_bottom_signal(market: str, i: int, five: list[Candle], snap: Snapshot) -> Signal | None:
    if snap.high_24h <= snap.low_24h or snap.rank_value > 30:
        return None
    pos = (snap.price - snap.low_24h) / (snap.high_24h - snap.low_24h + 0.001)
    if not (0.03 <= pos < 0.20 and snap.volume_accel > 1.35 and snap.change_30m > -0.2):
        return None
    score = 48 + min(snap.volume_accel * 8, 24) + max(0, 20 - pos * 60)
    stop, target1, target2 = risk_targets_long(snap.price, snap.low_24h * 0.992)
    return Signal(
        market=market,
        strategy="RANGE_BOTTOM_BOUNCE_RESEARCH",
        ts=snap.ts,
        price=snap.price,
        entry=snap.price,
        stop=stop,
        target1=target1,
        target2=target2,
        score=score,
        direction="LONG",
        reason=f"24h low-zone pos={pos*100:.1f}% vol={snap.volume_accel:.2f}x",
        change_24h=snap.change_24h,
        change_30m=snap.change_30m,
        change_5m=snap.change_5m,
        volume_accel=snap.volume_accel,
        rank_change=snap.rank_change,
        rank_value=snap.rank_value,
    )


def prev_close_signal(market: str, i: int, five: list[Candle], snap: Snapshot) -> Signal | None:
    if snap.prev_close <= 0 or snap.rank_value > 30:
        return None
    reclaimed = snap.price > snap.prev_close and 0.5 <= snap.change_24h <= 4.0 and snap.volume_accel > 1.25
    if not reclaimed:
        return None
    recent_low = min(x.low for x in five[max(0, i - 12) : i + 1])
    score = 45 + min(snap.volume_accel * 8, 20) + (8 if snap.rank_change <= 25 else 0) + min(snap.change_30m * 4, 12)
    stop, target1, target2 = risk_targets_long(snap.price, min(recent_low, snap.prev_close) * 0.995)
    return Signal(
        market=market,
        strategy="PREV_CLOSE_RECLAIM_RESEARCH",
        ts=snap.ts,
        price=snap.price,
        entry=snap.price,
        stop=stop,
        target1=target1,
        target2=target2,
        score=score,
        direction="LONG",
        reason=f"prev close reclaim change24={snap.change_24h:.2f}% vol={snap.volume_accel:.2f}x",
        change_24h=snap.change_24h,
        change_30m=snap.change_30m,
        change_5m=snap.change_5m,
        volume_accel=snap.volume_accel,
        rank_change=snap.rank_change,
        rank_value=snap.rank_value,
    )


def btc_short_signal(i: int, five: list[Candle], fifteen: list[Candle], four_hour: list[Candle], snap: Snapshot) -> Signal | None:
    if len(four_hour) < 22 or len(fifteen) < 25 or i < 16:
        return None
    price = snap.price
    ma20_15m = avg(x.close for x in fifteen[-20:])
    ma20_240m = avg(x.close for x in four_hour[-20:])
    last5 = five[i]
    prev5 = five[i - 1]
    lower_high = max(x.high for x in five[i - 7 : i + 1]) < max(x.high for x in five[i - 15 : i - 7])
    below_short_ma = price < ma20_15m * 0.998
    below_four_hour_ma = price < ma20_240m * 0.995
    downside_momentum = snap.change_30m <= -0.55 or snap.change_5m <= -0.22
    sell_volume = snap.volume_accel >= 1.35 and last5.close < last5.open
    failed_bounce = prev5.close > prev5.open and last5.close < prev5.open
    active = below_short_ma and below_four_hour_ma and downside_momentum and (sell_volume or failed_bounce or lower_high)
    if not active:
        return None
    trend_score = 22.0 if below_four_hour_ma else 0.0
    momentum_score = min(max(abs(min(snap.change_30m, 0.0)) * 18.0 + abs(min(snap.change_5m, 0.0)) * 28.0, 0.0), 24.0)
    volume_score = min(max((snap.volume_accel - 1.0) * 18.0, 0.0), 18.0)
    failure_score = sum([lower_high, failed_bounce, below_short_ma]) * 8.0
    score = 16.0 + trend_score + momentum_score + volume_score + failure_score
    raw_stop = max(x.high for x in five[i - 11 : i + 1]) * 1.003
    stop, target1, target2 = risk_targets_short(price, raw_stop)
    return Signal(
        market="KRW-BTC",
        strategy="BTC_SHORT_REGIME",
        ts=snap.ts,
        price=price,
        entry=price,
        stop=stop,
        target1=target1,
        target2=target2,
        score=score,
        direction="SHORT",
        reason=f"btc short below15m={below_short_ma} below4h={below_four_hour_ma} vol={snap.volume_accel:.2f}x",
        change_24h=snap.change_24h,
        change_30m=snap.change_30m,
        change_5m=snap.change_5m,
        volume_accel=snap.volume_accel,
        rank_change=snap.rank_change,
        rank_value=snap.rank_value,
    )


def evaluate_signal(signal: Signal, future: list[Candle], horizon_bars: int) -> Outcome:
    future = future[:horizon_bars]
    if not future:
        return Outcome(
            market=signal.market,
            strategy=signal.strategy,
            direction=signal.direction,
            ts=signal.ts,
            entry=signal.entry,
            stop=signal.stop,
            target1=signal.target1,
            target2=signal.target2,
            exit_price=signal.entry,
            return_pct=0.0,
            mfe_pct=0.0,
            mae_pct=0.0,
            target1_hit=False,
            target2_hit=False,
            stop_hit=False,
            bars_held=0,
            score=signal.score,
            reason=signal.reason,
        )
    target1_hit = False
    target2_hit = False
    stop_hit = False
    exit_price = future[-1].close
    bars_held = len(future)
    if signal.direction == "LONG":
        mfe_pct = max(percent_change(signal.entry, c.high) for c in future)
        mae_pct = min(percent_change(signal.entry, c.low) for c in future)
        for idx, c in enumerate(future, start=1):
            if c.low <= signal.stop:
                stop_hit = True
                exit_price = signal.stop
                bars_held = idx
                break
            if c.high >= signal.target1:
                target1_hit = True
            if c.high >= signal.target2:
                target2_hit = True
                exit_price = signal.target2
                bars_held = idx
                break
        return_pct = percent_change(signal.entry, exit_price)
    else:
        mfe_pct = max(percent_change(signal.entry, signal.entry + (signal.entry - c.low)) for c in future)
        mae_pct = min(percent_change(signal.entry, signal.entry + (signal.entry - c.high)) for c in future)
        for idx, c in enumerate(future, start=1):
            if c.high >= signal.stop:
                stop_hit = True
                exit_price = signal.stop
                bars_held = idx
                break
            if c.low <= signal.target1:
                target1_hit = True
            if c.low <= signal.target2:
                target2_hit = True
                exit_price = signal.target2
                bars_held = idx
                break
        return_pct = percent_change(signal.entry, signal.entry + (signal.entry - exit_price))
    return Outcome(
        market=signal.market,
        strategy=signal.strategy,
        direction=signal.direction,
        ts=signal.ts,
        entry=signal.entry,
        stop=signal.stop,
        target1=signal.target1,
        target2=signal.target2,
        exit_price=exit_price,
        return_pct=return_pct,
        mfe_pct=mfe_pct,
        mae_pct=mae_pct,
        target1_hit=target1_hit,
        target2_hit=target2_hit,
        stop_hit=stop_hit,
        bars_held=bars_held,
        score=signal.score,
        reason=signal.reason,
    )


def backtest(days: int, max_markets: int, horizon_bars: int, minimum_score: float, rules: dict) -> dict[str, Any]:
    markets = select_markets(fetch_krw_markets(), max_markets=max_markets)
    candles_by_market: dict[str, list[Candle]] = {}
    snapshots_by_market: dict[str, dict[int, Snapshot]] = {}
    for idx, market in enumerate(markets, start=1):
        print(f"[{idx}/{len(markets)}] fetching {market}")
        try:
            candles = fetch_minute_candles(market, days=days, unit=UNIT_MINUTES)
        except Exception as exc:  # noqa: BLE001 - report and continue research run
            print(f"WARN fetch failed {market}: {exc}")
            continue
        if len(candles) < 340:
            continue
        candles_by_market[market] = candles
        snapshots_by_market[market] = build_snapshots(market, candles)
    snapshots_by_market = add_ranks(snapshots_by_market)
    snaps_by_ts: dict[int, list[Snapshot]] = defaultdict(list)
    for rows in snapshots_by_market.values():
        for snap in rows.values():
            snaps_by_ts[snap.ts].append(snap)
    candidate_pool_by_ts = {ts: candidate_markets_at_ts(snaps, rules) for ts, snaps in snaps_by_ts.items()}

    outcomes: list[Outcome] = []
    signals_seen: set[tuple[str, str, int]] = set()
    for market, candles in candles_by_market.items():
        fifteen_all = aggregate_15m(candles)
        four_all = aggregate_240m(candles)
        fifteen_by_ts = {c.ts: pos for pos, c in enumerate(fifteen_all)}
        four_by_ts = {c.ts: pos for pos, c in enumerate(four_all)}
        for i in range(300, len(candles) - horizon_bars):
            snap = snapshots_by_market.get(market, {}).get(candles[i].ts)
            if not snap or market not in candidate_pool_by_ts.get(snap.ts, set()):
                continue
            fifteen_pos = max((pos for ts, pos in fifteen_by_ts.items() if ts <= candles[i].ts), default=-1)
            if fifteen_pos < 24:
                continue
            fifteen = fifteen_all[: fifteen_pos + 1]
            candidates: list[Signal | None] = [
                pre_pump_signal(market, i, candles, fifteen, snap, rules),
                range_bottom_signal(market, i, candles, snap),
                prev_close_signal(market, i, candles, snap),
            ]
            if market == "KRW-BTC":
                four_pos = max((pos for ts, pos in four_by_ts.items() if ts <= candles[i].ts), default=-1)
                four = four_all[: four_pos + 1] if four_pos >= 0 else []
                candidates.append(btc_short_signal(i, candles, fifteen, four, snap))
            for signal in candidates:
                if signal is None or signal.score < minimum_score:
                    continue
                # De-duplicate same strategy/market for 30 minutes to avoid counting every candle of one setup.
                dedup_key = (signal.market, signal.strategy, signal.ts // (30 * 60 * 1000))
                if dedup_key in signals_seen:
                    continue
                signals_seen.add(dedup_key)
                outcomes.append(evaluate_signal(signal, candles[i + 1 :], horizon_bars=horizon_bars))
    return summarize(outcomes, days, max_markets, horizon_bars, minimum_score, markets, rules)


def quantile(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    sorted_values = sorted(values)
    index = min(len(sorted_values) - 1, max(0, int((len(sorted_values) - 1) * q)))
    return sorted_values[index]


def summarize(outcomes: list[Outcome], days: int, max_markets: int, horizon_bars: int, minimum_score: float, markets: list[str], rules: dict) -> dict[str, Any]:
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
    }


def pct(x: float) -> str:
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
        lines.append(
            f"| {name} | {s['signals']} | {pct(s['avg_return_pct'])} | {pct(s['median_return_pct'])} | "
            f"{s['target1_hit_rate_pct']:.1f}% | {s['target2_hit_rate_pct']:.1f}% | {s['stop_hit_rate_pct']:.1f}% | "
            f"{pct(s['avg_mfe_pct'])} | {pct(s['avg_mae_pct'])} |"
        )
    lines.extend(["", "## Top 10 outcomes", ""])
    for row in result["top_outcomes"][:10]:
        dt = datetime.fromtimestamp(row["ts"] / 1000, tz=timezone.utc).strftime("%m-%d %H:%M")
        lines.append(f"- `{dt}` {row['market']} {row['strategy']} ret {pct(row['return_pct'])} score {row['score']:.1f} — {row['reason']}")
    lines.extend(["", "## Bottom 10 outcomes", ""])
    for row in result["bottom_outcomes"][:10]:
        dt = datetime.fromtimestamp(row["ts"] / 1000, tz=timezone.utc).strftime("%m-%d %H:%M")
        lines.append(f"- `{dt}` {row['market']} {row['strategy']} ret {pct(row['return_pct'])} score {row['score']:.1f} — {row['reason']}")
    (out_dir / "backtest_latest.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--days", type=int, default=7)
    parser.add_argument("--max-markets", type=int, default=60)
    parser.add_argument("--horizon-bars", type=int, default=DEFAULT_HORIZON_BARS)
    parser.add_argument("--minimum-score", type=float, default=65.0)
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--out-dir", type=Path, default=Path("reports"))
    args = parser.parse_args()
    result = backtest(
        days=args.days,
        max_markets=args.max_markets,
        horizon_bars=args.horizon_bars,
        minimum_score=args.minimum_score,
        rules=load_rules(args.rules),
    )
    write_reports(result, args.out_dir)
    print(json.dumps(result["strategy_summary"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
