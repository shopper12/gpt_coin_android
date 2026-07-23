#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import sys
import time
from dataclasses import asdict
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from global_market_strategy import (  # noqa: E402
    STRATEGY_NAME,
    Instrument,
    build_signal,
    daily_metrics,
    intraday_trigger,
    is_liquid,
    market_regime,
    rank_relative_strength,
    score_daily,
)
from global_market_universe import (  # noqa: E402
    STATIC_MULTI_ASSET,
    download_yahoo,
    load_kr_listed,
    load_upbit_top,
    load_us_listed,
    unique,
    upbit_history,
)

KST = ZoneInfo("Asia/Seoul")
REPORTS = ROOT / "reports"
SHORTLIST_PATH = REPORTS / "global_market_shortlist.json"
LATEST_PATH = REPORTS / "global_market_signals_latest.json"
HISTORY_PATH = REPORTS / "global_market_recommendation_history.json"
SCHEMA_VERSION = 1
FAST_INTRADAY_LIMIT = 90
MAX_SIGNALS = 12
HISTORY_LIMIT = 600


def now_kst() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc).astimezone(KST)


def read_json(path: Path, default: dict[str, Any]) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else default
    except Exception:
        return default


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")


def load_shortlist() -> list[Instrument]:
    payload = read_json(SHORTLIST_PATH, {})
    output: list[Instrument] = []
    for row in payload.get("instruments") or []:
        try:
            output.append(
                Instrument(
                    ticker=str(row["ticker"]),
                    name=str(row["name"]),
                    market=str(row["market"]),
                    asset_class=str(row["asset_class"]),
                    currency=str(row["currency"]),
                    source=str(row["source"]),
                )
            )
        except Exception:
            continue
    return unique(output or STATIC_MULTI_ASSET)


def save_shortlist(instruments: list[Instrument], discovery: dict[str, Any]) -> None:
    write_json(
        SHORTLIST_PATH,
        {
            "schemaVersion": SCHEMA_VERSION,
            "generatedAtKst": now_kst().isoformat(),
            "strategy": STRATEGY_NAME,
            "discovery": discovery,
            "instrumentCount": len(instruments),
            "instruments": [asdict(item) for item in instruments],
        },
    )


def discover_liquid_universe(us_limit: int, kr_limit: int, shortlist_size: int) -> list[Instrument]:
    us = load_us_listed()
    kr = load_kr_listed()
    listed = unique([*us, *kr, *STATIC_MULTI_ASSET])
    frames = download_yahoo(listed, period="3mo", interval="1d", batch_size=180)
    lookup = {item.ticker: item for item in listed}
    liquidity: list[tuple[Instrument, float]] = []
    for ticker, frame in frames.items():
        if frame.empty or len(frame) < 20 or ticker not in lookup:
            continue
        price = float(frame["close"].iloc[-1])
        value = price * float(frame["volume"].tail(20).mean())
        if price > 0 and value > 0:
            liquidity.append((lookup[ticker], value))
    us_rows = sorted([row for row in liquidity if row[0].market == "US"], key=lambda row: row[1], reverse=True)[:us_limit]
    kr_rows = sorted([row for row in liquidity if row[0].market == "KR"], key=lambda row: row[1], reverse=True)[:kr_limit]
    other_rows = sorted([row for row in liquidity if row[0].market not in {"US", "KR"}], key=lambda row: row[1], reverse=True)
    liquid = unique([*[item for item, _ in us_rows], *[item for item, _ in kr_rows], *[item for item, _ in other_rows], *STATIC_MULTI_ASSET])
    history = download_yahoo(liquid, period="18mo", interval="1d", batch_size=100)
    metrics: list[dict[str, Any]] = []
    liquid_lookup = {item.ticker: item for item in liquid}
    for ticker, frame in history.items():
        item = liquid_lookup.get(ticker)
        metric = daily_metrics(item, frame) if item else None
        if metric and is_liquid(metric):
            metrics.append(metric)
    rank_relative_strength(metrics)
    ranked = sorted(
        metrics,
        key=lambda row: (
            float(row.get("relativeStrengthPercentile") or 0),
            float(row.get("ret6m") or -9),
            math.log10(max(float(row.get("avgDollarValue") or 1), 1)),
        ),
        reverse=True,
    )
    selected_tickers = {row["ticker"] for row in ranked[:shortlist_size]}
    selected = [liquid_lookup[ticker] for ticker in selected_tickers if ticker in liquid_lookup]
    selected = unique([*selected, *STATIC_MULTI_ASSET, *load_upbit_top(40)])
    save_shortlist(
        selected,
        {
            "usListedDiscovered": len(us),
            "krListedDiscovered": len(kr),
            "liquidityRowsEvaluated": len(liquidity),
            "liquidHistoryEvaluated": len(metrics),
            "usLiquidLimit": us_limit,
            "krLiquidLimit": kr_limit,
            "shortlistSizeRequested": shortlist_size,
        },
    )
    return selected


def fetch_daily(instruments: list[Instrument]) -> list[dict[str, Any]]:
    lookup = {item.ticker: item for item in instruments}
    frames = download_yahoo([item for item in instruments if not item.ticker.startswith("KRW-")], period="18mo", interval="1d", batch_size=90)
    metrics: list[dict[str, Any]] = []
    for ticker, frame in frames.items():
        item = lookup.get(ticker)
        metric = daily_metrics(item, frame) if item else None
        if metric and is_liquid(metric):
            metrics.append(metric)
    for item in instruments:
        if not item.ticker.startswith("KRW-"):
            continue
        try:
            metric = daily_metrics(item, upbit_history(item, intraday=False))
            if metric:
                metrics.append(metric)
            time.sleep(0.12)
        except Exception as exc:
            print(f"Upbit daily warning {item.ticker}: {exc}")
    rank_relative_strength(metrics)
    regime = market_regime({row["ticker"]: row for row in metrics})
    for row in metrics:
        row["dailyScore"] = round(score_daily(row, regime), 2)
    return metrics


def fetch_intraday(candidates: list[dict[str, Any]], instruments: list[Instrument]) -> dict[str, pd.DataFrame]:
    lookup = {item.ticker: item for item in instruments}
    selected = candidates[:FAST_INTRADAY_LIMIT]
    yahoo = [lookup[row["ticker"]] for row in selected if row["ticker"] in lookup and not row["ticker"].startswith("KRW-")]
    frames = download_yahoo(yahoo, period="60d", interval="15m", batch_size=18)
    for row in selected:
        ticker = str(row["ticker"])
        if not ticker.startswith("KRW-") or ticker not in lookup:
            continue
        try:
            frames[ticker] = upbit_history(lookup[ticker], intraday=True)
            time.sleep(0.12)
        except Exception as exc:
            print(f"Upbit intraday warning {ticker}: {exc}")
    return frames


def update_history(signals: list[dict[str, Any]], metrics: list[dict[str, Any]]) -> dict[str, Any]:
    current = read_json(
        HISTORY_PATH,
        {"schemaVersion": SCHEMA_VERSION, "coverageStart": "", "coverageEnd": "", "recordCount": 0, "recommendations": []},
    )
    by_id = {str(row.get("id")): dict(row) for row in current.get("recommendations") or [] if row.get("id")}
    for signal in signals:
        by_id[str(signal["id"])] = dict(signal)
    price_by_ticker = {row["ticker"]: row for row in metrics}
    for key, row in list(by_id.items()):
        metric = price_by_ticker.get(row.get("ticker"))
        if not metric:
            continue
        row["currentPrice"] = round(float(metric["currentPrice"]), 6)
        row["previousClose"] = round(float(metric["previousClose"]), 6)
        row["todayChangePct"] = round(float(metric["todayChangePct"]), 3)
        row["recentCloses"] = metric["recentCloses"]
        row["currentPriceDate"] = now_kst().date().isoformat()
        by_id[key] = row
    records = sorted(by_id.values(), key=lambda row: (str(row.get("date") or ""), float(row.get("score") or 0)), reverse=True)[:HISTORY_LIMIT]
    dates = [str(row.get("date")) for row in records if row.get("date")]
    payload = {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAtKst": now_kst().isoformat(),
        "strategy": STRATEGY_NAME,
        "coverageStart": min(dates) if dates else "",
        "coverageEnd": max(dates) if dates else "",
        "recordCount": len(records),
        "recommendations": records,
    }
    write_json(HISTORY_PATH, payload)
    return payload


def run_scan(instruments: list[Instrument]) -> dict[str, Any]:
    generated = now_kst().isoformat()
    metrics = fetch_daily(instruments)
    by_ticker = {row["ticker"]: row for row in metrics}
    regime = market_regime(by_ticker)
    candidates = sorted(
        [
            row
            for row in metrics
            if float(row.get("dailyScore") or 0) >= 74
            and float(row.get("relativeStrengthPercentile") or 0) >= 0.82
            and float(row.get("currentPrice") or 0) > float(row.get("ma200") or 0)
            and float(row.get("ret3m") or -9) > 0
            and float(row.get("ret6m") or -9) > 0
            and float(row.get("ret12m") or -9) > 0
        ],
        key=lambda row: (float(row.get("dailyScore") or 0), float(row.get("relativeStrengthPercentile") or 0)),
        reverse=True,
    )
    intraday = fetch_intraday(candidates, instruments)
    signals: list[dict[str, Any]] = []
    evaluated: list[dict[str, Any]] = []
    for item in candidates[:FAST_INTRADAY_LIMIT]:
        trigger = intraday_trigger(intraday.get(item["ticker"], pd.DataFrame()))
        evaluated.append(
            {
                "ticker": item["ticker"],
                "name": item["name"],
                "dailyScore": item["dailyScore"],
                "relativeStrengthPercentile": item["relativeStrengthPercentile"],
                "trigger": trigger,
            }
        )
        if trigger.get("ready"):
            signals.append(build_signal(item, trigger, regime, generated))
    signals = sorted(signals, key=lambda row: (float(row.get("score") or 0), float(row.get("confidence") or 0)), reverse=True)[:MAX_SIGNALS]
    history = update_history(signals, metrics)
    latest = {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAtKst": generated,
        "strategy": STRATEGY_NAME,
        "monitoringCadence": "GitHub Actions every 15 minutes; Android WorkManager polls every 15 minutes",
        "universe": {
            "shortlistCount": len(instruments),
            "dailyMetricsCount": len(metrics),
            "dailyCandidateCount": len(candidates),
            "intradayEvaluatedCount": min(len(candidates), FAST_INTRADAY_LIMIT),
            "signalCount": len(signals),
        },
        "marketRegime": regime,
        "signals": signals,
        "topEvaluated": evaluated[:30],
        "historyRecordCount": history.get("recordCount", 0),
        "researchBasis": [
            "time-series momentum",
            "cross-sectional momentum",
            "volatility-managed exposure",
            "momentum-crash panic filter",
            "liquidity and exhaustion guards",
            "15-minute breakout/reclaim with ICT-style confirmation",
        ],
        "guardrail": "자동 추천은 주문이 아니다. 실시간 호가, 거래정지, 공시, 슬리피지와 계좌 위험한도를 확인해야 한다.",
    }
    write_json(LATEST_PATH, latest)
    return latest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["fast", "full"], default="fast")
    parser.add_argument("--us-limit", type=int, default=1200)
    parser.add_argument("--kr-limit", type=int, default=800)
    parser.add_argument("--shortlist-size", type=int, default=300)
    args = parser.parse_args()
    REPORTS.mkdir(parents=True, exist_ok=True)
    instruments = (
        discover_liquid_universe(max(200, args.us_limit), max(200, args.kr_limit), max(100, args.shortlist_size))
        if args.mode == "full"
        else unique([*load_shortlist(), *STATIC_MULTI_ASSET, *load_upbit_top(40)])
    )
    print(json.dumps(run_scan(instruments), ensure_ascii=False, indent=2, default=str))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
