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
    KR_ETF_FALLBACK,
    STATIC_MULTI_ASSET,
    download_yahoo,
    load_kr_etfs,
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
DAILY_STATE_PATH = REPORTS / "global_market_daily_state.json"
SCHEMA_VERSION = 1
FAST_INTRADAY_LIMIT = 90
KR_ETF_SHORTLIST_MIN = 80
KR_ETF_INTRADAY_SLOTS = 30
KR_ETF_SIGNAL_SLOTS = 4
MAX_SIGNALS = 12
HISTORY_LIMIT = 600
DAILY_CACHE_MINUTES = 55
MAX_SIGNAL_AGE_MINUTES = 45


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


def discover_liquid_universe(
    us_limit: int,
    kr_limit: int,
    kr_etf_limit: int,
    shortlist_size: int,
) -> list[Instrument]:
    us = load_us_listed()
    kr_stocks = load_kr_listed()
    kr_etfs = load_kr_etfs()
    listed = unique([*us, *kr_stocks, *kr_etfs, *STATIC_MULTI_ASSET])
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
    kr_stock_rows = sorted(
        [row for row in liquidity if row[0].asset_class == "KR_STOCK"],
        key=lambda row: row[1],
        reverse=True,
    )[:kr_limit]
    kr_etf_rows = sorted(
        [row for row in liquidity if row[0].asset_class == "KR_ETF"],
        key=lambda row: row[1],
        reverse=True,
    )[:kr_etf_limit]
    other_rows = sorted([row for row in liquidity if row[0].market not in {"US", "KR"}], key=lambda row: row[1], reverse=True)
    liquid = unique(
        [
            *[item for item, _ in us_rows],
            *[item for item, _ in kr_stock_rows],
            *[item for item, _ in kr_etf_rows],
            *[item for item, _ in other_rows],
            *STATIC_MULTI_ASSET,
            *KR_ETF_FALLBACK,
        ]
    )
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
    kr_etf_ranked = [row for row in ranked if row.get("assetClass") == "KR_ETF"]
    selected_tickers = {
        *[row["ticker"] for row in ranked[:shortlist_size]],
        *[row["ticker"] for row in kr_etf_ranked[:KR_ETF_SHORTLIST_MIN]],
    }
    selected = [liquid_lookup[ticker] for ticker in selected_tickers if ticker in liquid_lookup]
    selected = unique([*selected, *KR_ETF_FALLBACK, *STATIC_MULTI_ASSET, *load_upbit_top(40)])
    save_shortlist(
        selected,
        {
            "usListedDiscovered": len(us),
            "krStockListedDiscovered": len(kr_stocks),
            "krEtfListedDiscovered": len(kr_etfs),
            "liquidityRowsEvaluated": len(liquidity),
            "liquidHistoryEvaluated": len(metrics),
            "usLiquidLimit": us_limit,
            "krLiquidLimit": kr_limit,
            "krEtfLiquidLimit": kr_etf_limit,
            "krEtfShortlistCount": sum(1 for item in selected if item.asset_class == "KR_ETF"),
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


def load_or_refresh_daily_state(instruments: list[Instrument]) -> tuple[list[dict[str, Any]], dict[str, Any], bool]:
    cached = read_json(DAILY_STATE_PATH, {})
    cached_metrics = cached.get("metrics") or []
    cached_regime = cached.get("marketRegime") or {}
    generated_text = str(cached.get("generatedAtKst") or "")
    cache_fresh = False
    if cached_metrics and cached_regime and generated_text:
        try:
            generated_at = dt.datetime.fromisoformat(generated_text)
            age = now_kst() - generated_at.astimezone(KST)
            cache_fresh = dt.timedelta(0) <= age <= dt.timedelta(minutes=DAILY_CACHE_MINUTES)
            requested_kr_etfs = {item.ticker for item in instruments if item.asset_class == "KR_ETF"}
            cached_kr_etfs = {
                str(item.get("ticker") or "")
                for item in cached_metrics
                if item.get("assetClass") == "KR_ETF"
            }
            minimum_kr_etf_coverage = min(10, len(requested_kr_etfs))
            cache_fresh = cache_fresh and len(cached_kr_etfs) >= minimum_kr_etf_coverage
        except (TypeError, ValueError):
            cache_fresh = False
    if cache_fresh:
        return list(cached_metrics), dict(cached_regime), True

    metrics = fetch_daily(instruments)
    regime = market_regime({row["ticker"]: row for row in metrics})
    write_json(
        DAILY_STATE_PATH,
        {
            "schemaVersion": SCHEMA_VERSION,
            "generatedAtKst": now_kst().isoformat(),
            "instrumentCount": len(instruments),
            "metricCount": len(metrics),
            "marketRegime": regime,
            "metrics": metrics,
        },
    )
    return metrics, regime, False


def trigger_freshness(trigger: dict[str, Any]) -> tuple[bool, float | None]:
    timestamp = trigger.get("lastTimestamp")
    if not timestamp:
        return False, None
    try:
        candle_time = pd.Timestamp(timestamp)
        if candle_time.tzinfo is None:
            candle_time = candle_time.tz_localize("UTC")
        else:
            candle_time = candle_time.tz_convert("UTC")
        age_minutes = max(0.0, (pd.Timestamp.now(tz="UTC") - candle_time).total_seconds() / 60.0)
        return age_minutes <= MAX_SIGNAL_AGE_MINUTES, round(age_minutes, 1)
    except (TypeError, ValueError):
        return False, None


def is_strict_daily_candidate(row: dict[str, Any]) -> bool:
    return (
        float(row.get("dailyScore") or 0) >= 74
        and float(row.get("relativeStrengthPercentile") or 0) >= 0.82
        and float(row.get("currentPrice") or 0) > float(row.get("ma200") or 0)
        and float(row.get("ret3m") or -9) > 0
        and float(row.get("ret6m") or -9) > 0
        and float(row.get("ret12m") or -9) > 0
    )


def select_daily_candidates(metrics: list[dict[str, Any]]) -> list[dict[str, Any]]:
    strict = [
        {**row, "dailySignalEligible": True}
        for row in metrics
        if is_strict_daily_candidate(row)
    ]
    strict.sort(
        key=lambda row: (
            float(row.get("dailyScore") or 0),
            float(row.get("relativeStrengthPercentile") or 0),
        ),
        reverse=True,
    )
    strict_tickers = {str(row.get("ticker") or "") for row in strict}
    kr_etf_watch = [
        {**row, "dailySignalEligible": False}
        for row in metrics
        if row.get("assetClass") == "KR_ETF"
        and str(row.get("ticker") or "") not in strict_tickers
        and float(row.get("currentPrice") or 0) > 0
        and float(row.get("avgDollarValue") or 0) > 0
    ]
    kr_etf_watch.sort(
        key=lambda row: (
            float(row.get("dailyScore") or 0),
            float(row.get("ret1m") or -9),
            float(row.get("relativeStrengthPercentile") or 0),
        ),
        reverse=True,
    )
    return [*strict, *kr_etf_watch[:KR_ETF_INTRADAY_SLOTS]]


def can_emit_signal(item: dict[str, Any], trigger: dict[str, Any], fresh: bool) -> bool:
    return bool(item.get("dailySignalEligible", True) and trigger.get("ready") and fresh)


def select_intraday_candidates(candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    kr_etfs = [row for row in candidates if row.get("assetClass") == "KR_ETF"][:KR_ETF_INTRADAY_SLOTS]
    selected: list[dict[str, Any]] = []
    seen: set[str] = set()
    for row in [*kr_etfs, *candidates]:
        ticker = str(row.get("ticker") or "")
        if not ticker or ticker in seen:
            continue
        selected.append(row)
        seen.add(ticker)
        if len(selected) >= FAST_INTRADAY_LIMIT:
            break
    return selected


def select_final_signals(signals: list[dict[str, Any]]) -> list[dict[str, Any]]:
    ranked = sorted(
        signals,
        key=lambda row: (float(row.get("score") or 0), float(row.get("confidence") or 0)),
        reverse=True,
    )
    kr_etfs = [row for row in ranked if row.get("assetClass") == "KR_ETF"][:KR_ETF_SIGNAL_SLOTS]
    reserved_ids = {str(row.get("id") or "") for row in kr_etfs}
    remainder = [row for row in ranked if str(row.get("id") or "") not in reserved_ids]
    selected = [*kr_etfs, *remainder[: max(0, MAX_SIGNALS - len(kr_etfs))]]
    return sorted(
        selected,
        key=lambda row: (float(row.get("score") or 0), float(row.get("confidence") or 0)),
        reverse=True,
    )


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
    active_ids = {str(signal["id"]) for signal in signals}
    for signal in signals:
        by_id[str(signal["id"])] = dict(signal)
    for key, row in list(by_id.items()):
        if (
            row.get("strategyType") == STRATEGY_NAME
            and row.get("status") == "ACTIVE_SIGNAL"
            and key not in active_ids
        ):
            row["status"] = "EXPIRED_SIGNAL"
            row["expiredAtKst"] = now_kst().isoformat()
            by_id[key] = row
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
    metrics, regime, daily_cache_hit = load_or_refresh_daily_state(instruments)
    candidates = select_daily_candidates(metrics)
    intraday_candidates = select_intraday_candidates(candidates)
    intraday = fetch_intraday(intraday_candidates, instruments)
    signals: list[dict[str, Any]] = []
    evaluated: list[dict[str, Any]] = []
    for item in intraday_candidates:
        trigger = intraday_trigger(
            intraday.get(item["ticker"], pd.DataFrame()),
            asset_class=str(item.get("assetClass") or ""),
        )
        evaluated.append(
            {
                "ticker": item["ticker"],
                "name": item["name"],
                "dailyScore": item["dailyScore"],
                "relativeStrengthPercentile": item["relativeStrengthPercentile"],
                "dailySignalEligible": bool(item.get("dailySignalEligible", True)),
                "trigger": trigger,
            }
        )
        fresh, age_minutes = trigger_freshness(trigger)
        trigger["fresh"] = fresh
        trigger["ageMinutes"] = age_minutes
        if can_emit_signal(item, trigger, fresh):
            signals.append(build_signal(item, trigger, regime, generated))
    signals = select_final_signals(signals)
    history = update_history(signals, metrics)
    latest = {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAtKst": generated,
        "strategy": STRATEGY_NAME,
        "monitoringCadence": "GitHub Actions every 15 minutes; Android WorkManager polls every 15 minutes",
        "universe": {
            "shortlistCount": len(instruments),
            "krEtfShortlistCount": sum(1 for item in instruments if item.asset_class == "KR_ETF"),
            "dailyMetricsCount": len(metrics),
            "dailyCandidateCount": len(candidates),
            "krEtfDailyCandidateCount": sum(1 for item in candidates if item.get("assetClass") == "KR_ETF"),
            "strictDailyCandidateCount": sum(1 for item in candidates if item.get("dailySignalEligible")),
            "krEtfSignalEligibleCount": sum(
                1
                for item in candidates
                if item.get("assetClass") == "KR_ETF" and item.get("dailySignalEligible")
            ),
            "intradayEvaluatedCount": len(intraday_candidates),
            "krEtfIntradayEvaluatedCount": sum(1 for item in intraday_candidates if item.get("assetClass") == "KR_ETF"),
            "signalCount": len(signals),
            "krEtfSignalCount": sum(1 for item in signals if item.get("assetClass") == "KR_ETF"),
            "dailyCacheHit": daily_cache_hit,
            "maxSignalAgeMinutes": MAX_SIGNAL_AGE_MINUTES,
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
    parser.add_argument("--kr-etf-limit", type=int, default=180)
    parser.add_argument("--shortlist-size", type=int, default=300)
    args = parser.parse_args()
    REPORTS.mkdir(parents=True, exist_ok=True)
    instruments = (
        discover_liquid_universe(
            max(200, args.us_limit),
            max(200, args.kr_limit),
            max(80, args.kr_etf_limit),
            max(100, args.shortlist_size),
        )
        if args.mode == "full"
        else unique([*load_shortlist(), *KR_ETF_FALLBACK, *STATIC_MULTI_ASSET, *load_upbit_top(40)])
    )
    print(json.dumps(run_scan(instruments), ensure_ascii=False, indent=2, default=str))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
