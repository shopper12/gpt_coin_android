#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import json
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

import pandas as pd

from binance_btc_backtest import grid, prepare

KST = dt.timezone(dt.timedelta(hours=9))
FUTURES_ENDPOINT = "https://fapi.binance.com/fapi/v1/klines"
SPOT_ENDPOINTS = [
    "https://data-api.binance.vision/api/v3/klines",
    "https://api1.binance.com/api/v3/klines",
    "https://api.binance.com/api/v3/klines",
]


def interval_milliseconds(interval: str) -> int:
    units = {"m": 60_000, "h": 3_600_000, "d": 86_400_000}
    unit = interval[-1]
    if unit not in units:
        raise ValueError(f"unsupported interval: {interval}")
    return int(interval[:-1]) * units[unit]


def fetch_klines(symbol: str, market: str, interval: str, rows: int) -> tuple[pd.DataFrame, str, str, list[str]]:
    candidates: list[tuple[str, str, int]] = []
    if market == "futures":
        candidates.append((FUTURES_ENDPOINT, "futures", 1500))
    candidates.extend((endpoint, "spot_fallback" if market == "futures" else "spot", 1000) for endpoint in SPOT_ENDPOINTS)

    errors: list[str] = []
    for endpoint, source_market, max_limit in candidates:
        try:
            frame = _fetch_from_endpoint(symbol, interval, rows, endpoint, max_limit)
            return frame, source_market, endpoint, errors
        except Exception as exc:
            message = f"{endpoint}: {exc.__class__.__name__}: {str(exc)[:180]}"
            print(f"market data source failed: {message}")
            errors.append(message)
    raise RuntimeError("all Binance kline endpoints failed: " + " | ".join(errors))


def _fetch_from_endpoint(symbol: str, interval: str, rows: int, endpoint: str, max_limit: int) -> pd.DataFrame:
    interval_ms = interval_milliseconds(interval)
    target_rows = max(500, min(int(rows), 12_000))
    remaining = target_rows
    end_time: int | None = None
    collected: list[list[Any]] = []

    while remaining > 0:
        limit = min(max_limit, remaining)
        params = {"symbol": symbol.upper(), "interval": interval, "limit": str(limit)}
        if end_time is not None:
            params["endTime"] = str(end_time)
        url = f"{endpoint}?{urllib.parse.urlencode(params)}"
        request = urllib.request.Request(url, headers={"User-Agent": "UnifiedTradingCoach/1.0"})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                batch = json.load(response)
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")[:220]
            raise RuntimeError(f"HTTP {exc.code} {body}") from exc
        if not isinstance(batch, list) or not batch:
            break
        collected = batch + collected
        earliest = int(batch[0][0])
        end_time = earliest - interval_ms
        remaining -= len(batch)
        if len(batch) < limit:
            break

    by_open_time = {int(row[0]): row for row in collected if isinstance(row, list) and len(row) >= 6}
    ordered = [by_open_time[key] for key in sorted(by_open_time)][-target_rows:]
    if len(ordered) < 500:
        raise RuntimeError(f"insufficient live rows: {len(ordered)}")

    frame = pd.DataFrame(
        {
            "open_time": [int(row[0]) for row in ordered],
            "open": [float(row[1]) for row in ordered],
            "high": [float(row[2]) for row in ordered],
            "low": [float(row[3]) for row in ordered],
            "close": [float(row[4]) for row in ordered],
            "volume": [float(row[5]) for row in ordered],
        }
    )
    frame["open_time"] = pd.to_datetime(frame["open_time"], unit="ms", utc=True)
    return frame.set_index("open_time")


def load_best(path: Path) -> tuple[dict[str, Any], dict[str, Any], str]:
    if path.exists():
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
            params = payload.get("best_params") or {}
            summary = payload.get("best_summary") or {}
            if params:
                return params, summary, "daily_backtest_best"
        except Exception as exc:
            print(f"best report read failed: {exc}")
    return grid()[0], {}, "default_grid_first"


def event_condition(row: pd.Series, params: dict[str, Any]) -> bool:
    return bool(
        row.h4_ema20 > row.h4_ema100
        and row.h4_plus_di > row.h4_minus_di
        and row.h4_adx >= params["h4_adx_min"]
        and row.h1_ema20 > row.h1_ema100
        and row.h1_plus_di > row.h1_minus_di
        and row.h1_adx >= params["h1_adx_min"]
        and row.h1_close > row.h1_don_high_10
        and row.h1_volume > row.h1_vol_sma * params["h1_vol_mult"]
        and 45 <= row.h1_rsi <= 78
    )


def evaluate(df: pd.DataFrame, params: dict[str, Any]) -> dict[str, Any]:
    prepared = prepare(df)
    if prepared.empty:
        raise RuntimeError("prepared monitor frame is empty")
    latest = prepared.iloc[-1]
    context_rows = prepared.tail(int(params.get("window_bars", 12)))
    context_active = any(event_condition(row, params) for _, row in context_rows.iterrows())

    dlb = bool(latest.close > latest.dlb_high)
    ttr = bool(latest.close > latest.psar and (latest.prev_close <= latest.prev_psar or latest.close > latest.fr_high))
    reclaim = bool(latest.prev_close <= latest.prev_ema20 and latest.close > latest.ema20)
    pullback = bool(latest.low <= latest.ema50 * 1.002 and latest.close > latest.ema20)
    base_score = sum((dlb, ttr, reclaim, pullback))

    supertrend = bool(latest.csb_st)
    range_filter = bool(latest.csb_rf)
    choppiness_ok = bool(latest.csb_ci < params["ci_limit"])
    macd_ok = bool(latest.csb_macd)
    confirmation_score = sum((supertrend, range_filter, choppiness_ok, macd_ok))

    above_vwap = bool(latest.close > latest.vwap)
    dmi_ok = bool(latest.plus_di > latest.minus_di)
    adx_ok = bool(latest.adx >= params["entry_adx_min"])
    rsi_ok = bool(params["rsi_min"] <= latest.rsi <= params["rsi_max"])
    volume_ok = bool(latest.volume > latest.vol_sma * params["vol_mult"])
    entry_ready = bool(
        context_active
        and base_score >= params["setup_min"]
        and confirmation_score >= params["csb_min"]
        and above_vwap
        and dmi_ok
        and adx_ok
        and rsi_ok
        and volume_ok
    )

    if entry_ready:
        signal = "LONG_READY"
        summary = "장기·중기 추세 문맥과 5분 진입 조건이 동시에 충족됐습니다."
    elif context_active:
        signal = "LONG_CONTEXT"
        summary = "상위 시간대 상승 문맥은 있으나 현재 5분 진입 조건이 완성되지 않았습니다."
    else:
        signal = "WAIT"
        summary = "상위 시간대 돌파·거래량 문맥이 없어 신규 진입 대기입니다."

    closes = [round(float(value), 2) for value in prepared.close.tail(96).tolist()]
    last_timestamp = prepared.index[-1].to_pydatetime()
    volume_multiple = None
    if pd.notna(latest.vol_sma) and float(latest.vol_sma) > 0:
        volume_multiple = round(float(latest.volume / latest.vol_sma), 3)
    return {
        "signal": signal,
        "summary": summary,
        "current_price": round(float(latest.close), 2),
        "last_candle_utc": last_timestamp.astimezone(dt.timezone.utc).isoformat(),
        "last_candle_kst": last_timestamp.astimezone(KST).isoformat(),
        "recent_closes": closes,
        "components": {
            "context_active": context_active,
            "donchian_breakout": dlb,
            "psar_or_fractal_trigger": ttr,
            "ema20_reclaim": reclaim,
            "ema_pullback": pullback,
            "base_score": int(base_score),
            "base_required": int(params["setup_min"]),
            "supertrend_up": supertrend,
            "range_filter_up": range_filter,
            "choppiness_ok": choppiness_ok,
            "macd_positive": macd_ok,
            "confirmation_score": int(confirmation_score),
            "confirmation_required": int(params["csb_min"]),
            "above_vwap": above_vwap,
            "dmi_positive": dmi_ok,
            "adx_ok": adx_ok,
            "rsi_ok": rsi_ok,
            "volume_ok": volume_ok,
        },
        "metrics": {
            "rsi": round(float(latest.rsi), 2),
            "adx": round(float(latest.adx), 2),
            "vwap": round(float(latest.vwap), 2),
            "ema20": round(float(latest.ema20), 2),
            "ema50": round(float(latest.ema50), 2),
            "volume_multiple": volume_multiple,
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbol", default="BTCUSDT")
    parser.add_argument("--market", choices=["futures", "spot"], default="futures")
    parser.add_argument("--interval", default="5m")
    parser.add_argument("--rows", type=int, default=6000)
    parser.add_argument("--best-report", type=Path, default=Path("reports/binance_btc_best.json"))
    parser.add_argument("--output", type=Path, default=Path("reports/binance_btc_monitor_latest.json"))
    args = parser.parse_args()

    params, backtest_summary, parameter_source = load_best(args.best_report)
    frame, data_market, data_endpoint, source_errors = fetch_klines(args.symbol, args.market, args.interval, args.rows)
    result = evaluate(frame, params)
    now = dt.datetime.now(dt.timezone.utc)
    payload = {
        "schema_version": 2,
        "ok": True,
        "monitoring_mode": "hourly_24x7",
        "source_repository": "shopper12/gpt_coin_android",
        "integrated_from": "shopper12/backtest",
        "generated_at_utc": now.isoformat(),
        "generated_at_kst": now.astimezone(KST).isoformat(),
        "symbol": args.symbol.upper(),
        "requested_market": args.market,
        "market": data_market,
        "data_endpoint": data_endpoint,
        "data_source_errors": source_errors,
        "interval": args.interval,
        "rows_loaded": int(len(frame)),
        "parameter_source": parameter_source,
        "strategy_parameters": params,
        "backtest_summary": backtest_summary,
        **result,
        "guardrail": "Research monitor only. Spot fallback may be used when the futures API is unavailable; a ready signal is not an execution guarantee.",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
