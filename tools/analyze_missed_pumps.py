#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import time
import urllib.parse
import urllib.request
from collections import defaultdict
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

UPBIT_API = "https://api.upbit.com/v1"
REQUEST_SLEEP_SECONDS = 0.13
MAX_CANDLES_PER_REQUEST = 200
UNIT_MINUTES = 5


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
class MissedPump:
    market: str
    ts: int
    price: float
    future_return_pct: float
    max_future_return_pct: float
    prior_30m_change_pct: float
    five_min_change_pct: float
    volume_accel: float
    trade_value_rank: int
    change_rank: int
    reason: str


def http_json(path: str, params: dict[str, Any] | None = None) -> Any:
    query = ""
    if params:
        query = "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(
        f"{UPBIT_API}{path}{query}",
        headers={"User-Agent": "CryptoTradeCoachMissedPumpAnalyzer"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def fetch_krw_markets() -> list[str]:
    rows = http_json("/market/all", {"isDetails": "false"})
    return sorted(row["market"] for row in rows if row.get("market", "").startswith("KRW-"))


def fetch_current_tickers(markets: list[str]) -> dict[str, dict[str, Any]]:
    out: dict[str, dict[str, Any]] = {}
    for i in range(0, len(markets), 100):
        rows = http_json("/ticker", {"markets": ",".join(markets[i : i + 100])})
        for row in rows:
            out[row["market"]] = row
        time.sleep(REQUEST_SLEEP_SECONDS)
    return out


def select_markets(markets: list[str], max_markets: int) -> list[str]:
    tickers = fetch_current_tickers(markets)
    return sorted(markets, key=lambda m: tickers.get(m, {}).get("acc_trade_price_24h", 0.0), reverse=True)[:max_markets]


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
    needed = int(days * 24 * 60 / unit) + 320
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
        to_value = datetime.fromtimestamp((oldest - 1) / 1000, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
        time.sleep(REQUEST_SLEEP_SECONDS)
        if len(batch) < MAX_CANDLES_PER_REQUEST:
            break
    unique = {c.ts: c for c in candles}
    return sorted(unique.values(), key=lambda c: c.ts)[-needed:]


def pct(a: float, b: float) -> float:
    if a <= 0:
        return 0.0
    return (b - a) / a * 100.0


def avg(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def detect_market_events(market: str, candles: list[Candle], horizon_bars: int, pump_threshold_pct: float) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for i in range(300, len(candles) - horizon_bars):
        now = candles[i]
        future = candles[i + 1 : i + 1 + horizon_bars]
        max_high = max(c.high for c in future)
        future_return = pct(now.close, future[-1].close)
        max_future = pct(now.close, max_high)
        if max_future < pump_threshold_pct:
            continue
        prior_30m = pct(candles[i - 6].open, now.close) if i >= 6 else 0.0
        five_change = pct(now.open, now.close)
        recent_value = sum(c.trade_value for c in candles[i - 2 : i + 1])
        base_value = sum(c.trade_value for c in candles[i - 23 : i - 3]) / 6.67 if i >= 23 else 0.0
        volume_accel = recent_value / base_value if base_value > 0 else 1.0
        day_window = candles[i - 288 : i + 1] if i >= 288 else candles[: i + 1]
        acc_value = sum(c.trade_value for c in day_window)
        change_24h = pct(candles[i - 288].close, now.close) if i >= 288 else 0.0
        events.append(
            {
                "market": market,
                "ts": now.ts,
                "price": now.close,
                "future_return_pct": future_return,
                "max_future_return_pct": max_future,
                "prior_30m_change_pct": prior_30m,
                "five_min_change_pct": five_change,
                "volume_accel": volume_accel,
                "acc_trade_value_24h": acc_value,
                "change_24h": change_24h,
            }
        )
    return events


def add_cross_section_ranks(events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_ts: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for event in events:
        by_ts[int(event["ts"])].append(event)
    for rows in by_ts.values():
        value_rank = {row["market"]: i + 1 for i, row in enumerate(sorted(rows, key=lambda x: x["acc_trade_value_24h"], reverse=True))}
        change_rank = {row["market"]: i + 1 for i, row in enumerate(sorted(rows, key=lambda x: x["change_24h"], reverse=True))}
        for row in rows:
            row["trade_value_rank"] = value_rank[row["market"]]
            row["change_rank"] = change_rank[row["market"]]
    return events


def load_signal_keys(backtest_path: Path) -> set[tuple[str, int]]:
    if not backtest_path.exists():
        return set()
    report = json.loads(backtest_path.read_text(encoding="utf-8"))
    keys: set[tuple[str, int]] = set()
    for section in ("top_outcomes", "bottom_outcomes", "all_outcomes"):
        for row in report.get(section, []) or []:
            market = row.get("market")
            ts = row.get("ts")
            if market and ts:
                keys.add((market, int(ts) // (30 * 60 * 1000)))
    return keys


def classify_miss(row: dict[str, Any]) -> str:
    if row["trade_value_rank"] > 35:
        return "CANDIDATE_POOL_TOO_NARROW"
    if row["volume_accel"] < 1.25:
        return "VOLUME_IGNITION_TOO_LATE_OR_LOW"
    if row["prior_30m_change_pct"] > 3.2 or row["five_min_change_pct"] > 1.8:
        return "ALREADY_MOVING_BEFORE_SIGNAL"
    if row["change_rank"] > 45:
        return "RELATIVE_STRENGTH_RANK_TOO_LOW"
    return "SCORING_OR_STRUCTURE_FILTER_TOO_STRICT"


def analyze(days: int, max_markets: int, horizon_bars: int, pump_threshold_pct: float, backtest_path: Path) -> dict[str, Any]:
    markets = select_markets(fetch_krw_markets(), max_markets)
    all_events: list[dict[str, Any]] = []
    for idx, market in enumerate(markets, start=1):
        print(f"[{idx}/{len(markets)}] missed-pump scan {market}")
        try:
            candles = fetch_minute_candles(market, days=days)
        except Exception as exc:
            print(f"WARN: {market} fetch failed: {exc}")
            continue
        if len(candles) < 340:
            continue
        all_events.extend(detect_market_events(market, candles, horizon_bars, pump_threshold_pct))
    all_events = add_cross_section_ranks(all_events)
    signal_keys = load_signal_keys(backtest_path)
    missed: list[MissedPump] = []
    for row in all_events:
        key = (row["market"], int(row["ts"]) // (30 * 60 * 1000))
        if key in signal_keys:
            continue
        missed.append(
            MissedPump(
                market=row["market"],
                ts=int(row["ts"]),
                price=float(row["price"]),
                future_return_pct=float(row["future_return_pct"]),
                max_future_return_pct=float(row["max_future_return_pct"]),
                prior_30m_change_pct=float(row["prior_30m_change_pct"]),
                five_min_change_pct=float(row["five_min_change_pct"]),
                volume_accel=float(row["volume_accel"]),
                trade_value_rank=int(row["trade_value_rank"]),
                change_rank=int(row["change_rank"]),
                reason=classify_miss(row),
            )
        )
    missed = sorted(missed, key=lambda x: x.max_future_return_pct, reverse=True)
    reason_counts: dict[str, int] = defaultdict(int)
    for row in missed:
        reason_counts[row.reason] += 1
    return {
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "config": {
            "days": days,
            "max_markets": max_markets,
            "horizon_minutes": horizon_bars * UNIT_MINUTES,
            "pump_threshold_pct": pump_threshold_pct,
        },
        "total_pump_events": len(all_events),
        "missed_pump_count": len(missed),
        "reason_counts": dict(sorted(reason_counts.items(), key=lambda item: item[1], reverse=True)),
        "top_missed_pumps": [asdict(row) for row in missed[:50]],
    }


def write_reports(result: dict[str, Any], out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "missed_pumps_latest.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = [
        "# Missed Pump Analysis",
        "",
        f"- Generated UTC: `{result['generated_at_utc']}`",
        f"- Pump events: `{result['total_pump_events']}`",
        f"- Missed events: `{result['missed_pump_count']}`",
        "",
        "## Reason counts",
        "",
    ]
    for reason, count in result["reason_counts"].items():
        lines.append(f"- `{reason}`: {count}")
    lines.extend(["", "## Top missed pumps", ""])
    for row in result["top_missed_pumps"][:20]:
        ts = datetime.fromtimestamp(row["ts"] / 1000, tz=timezone.utc).strftime("%m-%d %H:%M")
        lines.append(
            f"- `{ts}` {row['market']} max+{row['max_future_return_pct']:.2f}% "
            f"vol={row['volume_accel']:.2f}x ranks={row['change_rank']}/{row['trade_value_rank']} reason={row['reason']}"
        )
    (out_dir / "missed_pumps_latest.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--days", type=int, default=7)
    parser.add_argument("--max-markets", type=int, default=60)
    parser.add_argument("--horizon-bars", type=int, default=12)
    parser.add_argument("--pump-threshold-pct", type=float, default=8.0)
    parser.add_argument("--backtest", type=Path, default=Path("reports/backtest_latest.json"))
    parser.add_argument("--out-dir", type=Path, default=Path("reports"))
    args = parser.parse_args()
    result = analyze(args.days, args.max_markets, args.horizon_bars, args.pump_threshold_pct, args.backtest)
    write_reports(result, args.out_dir)
    print(json.dumps(result["reason_counts"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
