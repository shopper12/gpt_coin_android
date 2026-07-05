#!/usr/bin/env python3
"""Binance BTC backtest and optimization pipeline.

This folds the standalone `shopper12/backtest` research workflow into the
Android app repository so crypto research artifacts are produced from the same
repo as the app.

Outputs:
  - runtime_data/binance_klines.csv
  - reports/binance_btc_results.csv
  - reports/binance_btc_best.json
  - reports/binance_btc_best_trades.csv
  - reports/binance_btc_backtest_latest.md
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import io
import json
import math
import urllib.error
import urllib.request
import zipfile
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

BINANCE_MONTHLY_SPOT = "https://data.binance.vision/data/spot/monthly/klines"
BINANCE_MONTHLY_FUTURES = "https://data.binance.vision/data/futures/um/monthly/klines"
DEFAULT_START_EQUITY = 10_000.0
COMMISSION_RATE = 0.0006
ENTRY_SLIPPAGE_LONG = 1.0002
EXIT_SLIPPAGE_LONG = 0.9998


def parse_utc(value: str) -> dt.datetime:
    parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
    return parsed.astimezone(dt.timezone.utc)


def fetch_bytes(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(request, timeout=90) as response:
        return response.read()


def read_zip_csv_rows(payload: bytes) -> list[list[str]]:
    with zipfile.ZipFile(io.BytesIO(payload)) as zipped:
        csv_names = [name for name in zipped.namelist() if name.endswith(".csv")]
        if not csv_names:
            return []
        with zipped.open(csv_names[0]) as handle:
            text = io.TextIOWrapper(handle, encoding="utf-8")
            return list(csv.reader(text))


def month_iter(start: dt.datetime, end: dt.datetime):
    current = dt.datetime(start.year, start.month, 1, tzinfo=dt.timezone.utc)
    last = dt.datetime(end.year, end.month, 1, tzinfo=dt.timezone.utc)
    while current <= last:
        yield current.year, current.month
        if current.month == 12:
            current = dt.datetime(current.year + 1, 1, 1, tzinfo=dt.timezone.utc)
        else:
            current = dt.datetime(current.year, current.month + 1, 1, tzinfo=dt.timezone.utc)


def archive_prefix(market: str) -> str:
    return BINANCE_MONTHLY_FUTURES if market == "futures" else BINANCE_MONTHLY_SPOT


def fetch_month(symbol: str, interval: str, year: int, month: int, market: str) -> list[list[str]]:
    ym = f"{year}-{month:02d}"
    filename = f"{symbol}-{interval}-{ym}.zip"
    url = f"{archive_prefix(market)}/{symbol}/{interval}/{filename}"
    try:
        rows = read_zip_csv_rows(fetch_bytes(url))
        print(f"archive {ym}: {len(rows)} rows")
        return rows
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            print(f"archive {ym}: missing")
            return []
        raise


def normalize_rows(rows: list[list[Any]], start_ms: int, end_ms: int) -> list[list[Any]]:
    output: list[list[Any]] = []
    seen: set[int] = set()
    for row in rows:
        if not row:
            continue
        try:
            open_time = int(float(row[0]))
        except Exception:
            continue
        if open_time < start_ms or open_time > end_ms or open_time in seen:
            continue
        seen.add(open_time)
        output.append(row[:12])
    return sorted(output, key=lambda r: int(float(r[0])))


def download_klines(symbol: str, market: str, interval: str, start: str, end: str, output: Path) -> int:
    start_dt = parse_utc(start)
    end_dt = parse_utc(end)
    start_ms = int(start_dt.timestamp() * 1000)
    end_ms = int(end_dt.timestamp() * 1000)

    rows: list[list[str]] = []
    for year, month in month_iter(start_dt, end_dt):
        rows.extend(fetch_month(symbol.upper(), interval, year, month, market))
    rows = normalize_rows(rows, start_ms, end_ms)

    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow([
            "open_time",
            "open",
            "high",
            "low",
            "close",
            "volume",
            "close_time",
            "quote_volume",
            "trades",
            "taker_buy_volume",
            "taker_buy_quote_volume",
            "ignore",
        ])
        writer.writerows(rows)
    print(f"Downloaded {len(rows)} rows to {output}")
    return len(rows)


def ema(series: pd.Series, length: int) -> pd.Series:
    return series.ewm(span=length, adjust=False).mean()


def atr(df: pd.DataFrame, length: int) -> pd.Series:
    previous = df["close"].shift(1)
    true_range = pd.concat(
        [
            df["high"] - df["low"],
            (df["high"] - previous).abs(),
            (df["low"] - previous).abs(),
        ],
        axis=1,
    ).max(axis=1)
    return true_range.ewm(alpha=1 / length, adjust=False).mean()


def dmi_adx(df: pd.DataFrame, length: int) -> tuple[pd.Series, pd.Series, pd.Series]:
    up = df["high"].diff()
    down = -df["low"].diff()
    plus_dm = np.where((up > down) & (up > 0), up, 0.0)
    minus_dm = np.where((down > up) & (down > 0), down, 0.0)
    previous = df["close"].shift(1)
    true_range = pd.concat(
        [
            df["high"] - df["low"],
            (df["high"] - previous).abs(),
            (df["low"] - previous).abs(),
        ],
        axis=1,
    ).max(axis=1)
    atr_value = true_range.ewm(alpha=1 / length, adjust=False).mean()
    plus_di = 100 * pd.Series(plus_dm, index=df.index).ewm(alpha=1 / length, adjust=False).mean() / atr_value
    minus_di = 100 * pd.Series(minus_dm, index=df.index).ewm(alpha=1 / length, adjust=False).mean() / atr_value
    dx = 100 * (plus_di - minus_di).abs() / (plus_di + minus_di)
    adx = dx.ewm(alpha=1 / length, adjust=False).mean()
    return plus_di, minus_di, adx


def rsi(series: pd.Series, length: int = 14) -> pd.Series:
    delta = series.diff()
    up = delta.clip(lower=0).ewm(alpha=1.0 / length, adjust=False).mean()
    down = (-delta.clip(upper=0)).ewm(alpha=1.0 / length, adjust=False).mean()
    return 100.0 - 100.0 / (1.0 + up / down)


def session_vwap(df: pd.DataFrame) -> pd.Series:
    typical_price = (df["high"] + df["low"] + df["close"]) / 3.0
    day = df.index.floor("D")
    price_volume = (typical_price * df["volume"]).groupby(day).cumsum()
    volume = df["volume"].groupby(day).cumsum()
    return price_volume / volume.replace(0, np.nan)


def load_ohlcv(path: Path) -> pd.DataFrame:
    raw = pd.read_csv(path)
    raw.columns = [str(column).strip().lower().replace(" ", "_") for column in raw.columns]
    if "open_time" not in raw.columns:
        raw = pd.read_csv(path, header=None).iloc[:, :6]
        raw.columns = ["open_time", "open", "high", "low", "close", "volume"]
    if np.issubdtype(raw["open_time"].dtype, np.number):
        raw["open_time"] = pd.to_datetime(raw["open_time"], unit="ms", utc=True)
    else:
        raw["open_time"] = pd.to_datetime(raw["open_time"], utc=True, errors="coerce")
    for column in ["open", "high", "low", "close", "volume"]:
        raw[column] = pd.to_numeric(raw[column], errors="coerce")
    df = raw.dropna(subset=["open_time", "open", "high", "low", "close"])
    df = df.sort_values("open_time").drop_duplicates("open_time").set_index("open_time")
    return df[["open", "high", "low", "close", "volume"]]


def resample_ohlcv(df: pd.DataFrame, rule: str) -> pd.DataFrame:
    return df.resample(rule).agg({"open": "first", "high": "max", "low": "min", "close": "last", "volume": "sum"}).dropna()


def psar(df: pd.DataFrame, step: float, max_step: float) -> pd.Series:
    high, low, close = df.high.to_numpy(), df.low.to_numpy(), df.close.to_numpy()
    out = np.full(len(df), np.nan)
    if len(df) < 3:
        return pd.Series(out, index=df.index)
    bull = close[1] >= close[0]
    acceleration = step
    extreme = high[1] if bull else low[1]
    out[1] = low[0] if bull else high[0]
    for i in range(2, len(df)):
        value = out[i - 1] + acceleration * (extreme - out[i - 1])
        if bull:
            value = min(value, low[i - 1], low[i - 2])
            if low[i] < value:
                bull, value, extreme, acceleration = False, extreme, low[i], step
            elif high[i] > extreme:
                extreme = high[i]
                acceleration = min(acceleration + step, max_step)
        else:
            value = max(value, high[i - 1], high[i - 2])
            if high[i] > value:
                bull, value, extreme, acceleration = True, extreme, high[i], step
            elif low[i] < extreme:
                extreme = low[i]
                acceleration = min(acceleration + step, max_step)
        out[i] = value
    return pd.Series(out, index=df.index)


def supertrend_up(df: pd.DataFrame, length: int = 10, multiplier: float = 3.0) -> pd.Series:
    atr_value = df["atr"] if "atr" in df else (df["high"] - df["low"]).rolling(length).mean()
    hl2 = (df["high"] + df["low"]) / 2.0
    upper = hl2 + multiplier * atr_value
    lower = hl2 - multiplier * atr_value
    trend = np.ones(len(df), dtype=int)
    final_upper = upper.copy()
    final_lower = lower.copy()
    close = df["close"]
    for i in range(1, len(df)):
        final_upper.iloc[i] = upper.iloc[i] if close.iloc[i - 1] > final_upper.iloc[i - 1] else min(upper.iloc[i], final_upper.iloc[i - 1])
        final_lower.iloc[i] = lower.iloc[i] if close.iloc[i - 1] < final_lower.iloc[i - 1] else max(lower.iloc[i], final_lower.iloc[i - 1])
        if trend[i - 1] == -1 and close.iloc[i] > final_upper.iloc[i - 1]:
            trend[i] = 1
        elif trend[i - 1] == 1 and close.iloc[i] < final_lower.iloc[i - 1]:
            trend[i] = -1
        else:
            trend[i] = trend[i - 1]
    return pd.Series(trend == 1, index=df.index)


def range_filter_up(df: pd.DataFrame, period: int = 100, multiplier: float = 3.0) -> pd.Series:
    source = df["close"]
    avg_range = (source - source.shift(1)).abs().ewm(span=period, adjust=False).mean()
    smooth = avg_range.ewm(span=period * 2 - 1, adjust=False).mean() * multiplier
    filt = source.copy()
    for i in range(1, len(df)):
        previous = filt.iloc[i - 1]
        if source.iloc[i] > previous:
            filt.iloc[i] = previous if source.iloc[i] - smooth.iloc[i] < previous else source.iloc[i] - smooth.iloc[i]
        else:
            filt.iloc[i] = previous if source.iloc[i] + smooth.iloc[i] > previous else source.iloc[i] + smooth.iloc[i]
    return source > filt


def choppiness(df: pd.DataFrame, length: int = 14) -> pd.Series:
    previous = df["close"].shift(1)
    true_range = pd.concat(
        [
            df["high"] - df["low"],
            (df["high"] - previous).abs(),
            (df["low"] - previous).abs(),
        ],
        axis=1,
    ).max(axis=1)
    rolling_range = df["high"].rolling(length).max() - df["low"].rolling(length).min()
    return 100.0 * np.log10(true_range.rolling(length).sum() / rolling_range.replace(0, np.nan)) / math.log10(length)


def prepare(df: pd.DataFrame) -> pd.DataFrame:
    x = df.copy()
    x["ema20"] = ema(x["close"], 20)
    x["ema50"] = ema(x["close"], 50)
    x["atr"] = atr(x, 14)
    x["plus_di"], x["minus_di"], x["adx"] = dmi_adx(x, 14)
    x["rsi"] = rsi(x["close"], 14)
    x["vwap"] = session_vwap(x)
    x["vol_sma"] = x["volume"].rolling(20).mean()

    h1 = resample_ohlcv(df, "1h")
    h1["ema20"] = ema(h1["close"], 20)
    h1["ema100"] = ema(h1["close"], 100)
    h1["plus_di"], h1["minus_di"], h1["adx"] = dmi_adx(h1, 14)
    h1["rsi"] = rsi(h1["close"], 14)
    h1["vol_sma"] = h1["volume"].rolling(20).mean()
    h1["don_high_10"] = h1["high"].shift(1).rolling(10).max()
    for column in ["ema20", "ema100", "plus_di", "minus_di", "adx", "rsi", "vol_sma", "volume", "don_high_10", "close"]:
        x[f"h1_{column}"] = h1[column].reindex(x.index, method="ffill")

    h4 = resample_ohlcv(df, "4h")
    h4["ema20"] = ema(h4["close"], 20)
    h4["ema100"] = ema(h4["close"], 100)
    h4["plus_di"], h4["minus_di"], h4["adx"] = dmi_adx(h4, 14)
    for column in ["ema20", "ema100", "plus_di", "minus_di", "adx"]:
        x[f"h4_{column}"] = h4[column].reindex(x.index, method="ffill")

    x["dlb_high"] = x.high.shift(1).rolling(20).max()
    x["psar"] = psar(x, 0.02, 0.2)
    x["fr_high"] = x.high.shift(2).rolling(5).max()
    x["prev_close"] = x.close.shift(1)
    x["prev_psar"] = x.psar.shift(1)
    x["prev_ema20"] = x.ema20.shift(1)
    x["csb_st"] = supertrend_up(x, 10, 3.0)
    x["csb_rf"] = range_filter_up(x, 100, 3.0)
    x["csb_ci"] = choppiness(x, 14)
    macd = x.close.ewm(span=12, adjust=False).mean() - x.close.ewm(span=26, adjust=False).mean()
    x["csb_macd"] = macd > macd.ewm(span=9, adjust=False).mean()
    return x.dropna()


def run_combo(df: pd.DataFrame, params: dict[str, Any]) -> tuple[pd.DataFrame, dict[str, Any]]:
    x = prepare(df)
    equity = DEFAULT_START_EQUITY
    peak = DEFAULT_START_EQUITY
    max_drawdown = 0.0
    position: dict[str, Any] | None = None
    cooldown = 0
    context = 0
    trades: list[dict[str, Any]] = []

    for timestamp, row in x.iterrows():
        event = (
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
        context = params["window_bars"] if event else max(0, context - 1)
        cooldown = max(0, cooldown - 1)

        if position is None:
            if cooldown or context <= 0:
                continue
            dlb = row.close > row.dlb_high
            ttr = row.close > row.psar and (row.prev_close <= row.prev_psar or row.close > row.fr_high)
            reclaim = row.prev_close <= row.prev_ema20 and row.close > row.ema20
            pullback = row.low <= row.ema50 * 1.002 and row.close > row.ema20
            base_score = int(dlb) + int(ttr) + int(reclaim) + int(pullback)
            csb_score = int(row.csb_st) + int(row.csb_rf) + int(row.csb_ci < params["ci_limit"]) + int(row.csb_macd)
            entry_ok = (
                base_score >= params["setup_min"]
                and csb_score >= params["csb_min"]
                and row.close > row.vwap
                and row.plus_di > row.minus_di
                and row.adx >= params["entry_adx_min"]
                and params["rsi_min"] <= row.rsi <= params["rsi_max"]
                and row.volume > row.vol_sma * params["vol_mult"]
            )
            if not entry_ok:
                continue
            risk = params["atr_stop"] * row.atr
            if risk <= 0:
                continue
            quantity = (equity * params["risk_pct"] / 100.0) / risk
            entry = row.close * ENTRY_SLIPPAGE_LONG
            equity -= entry * quantity * COMMISSION_RATE
            position = {
                "entry_time": timestamp,
                "entry": entry,
                "quantity": quantity,
                "risk": risk,
                "trail": entry - params["atr_trail"] * row.atr,
                "partial": 0.0,
                "tp1_done": False,
                "bars": 0,
            }
            context = 0
            continue

        position["bars"] += 1
        quantity = position["quantity"]
        position["trail"] = max(position["trail"], row.high - params["atr_trail"] * row.atr)
        tp1 = position["entry"] + params["tp1_r"] * position["risk"]
        tp2 = position["entry"] + params["tp2_r"] * position["risk"]
        if not position["tp1_done"] and row.high >= tp1:
            half = quantity * 0.5
            partial = (tp1 - position["entry"]) * half - tp1 * half * COMMISSION_RATE
            equity += partial
            position["partial"] += partial
            position["quantity"] -= half
            quantity = position["quantity"]
            position["tp1_done"] = True

        exit_price: float | None = None
        reason: str | None = None
        if row.low <= position["trail"]:
            exit_price, reason = position["trail"] * EXIT_SLIPPAGE_LONG, "trail_stop"
        elif row.high >= tp2:
            exit_price, reason = tp2 * EXIT_SLIPPAGE_LONG, "tp2"
        elif position["bars"] >= params["max_bars"] or row.close < row.ema50 or (params["psar_exit"] and row.close < row.psar):
            exit_price, reason = row.close * EXIT_SLIPPAGE_LONG, "time_or_trend_exit"
        if exit_price is None:
            continue

        exit_pnl = (exit_price - position["entry"]) * quantity - exit_price * quantity * COMMISSION_RATE
        pnl = position["partial"] + exit_pnl
        equity += exit_pnl
        peak = max(peak, equity)
        max_drawdown = max(max_drawdown, peak - equity)
        trades.append(
            {
                "side": "long",
                "entry_time": str(position["entry_time"]),
                "exit_time": str(timestamp),
                "entry": position["entry"],
                "exit": exit_price,
                "pnl": pnl,
                "equity_after": equity,
                "exit_reason": reason,
                "bars": position["bars"],
            }
        )
        position = None
        cooldown = params["cooldown"]

    trades_df = pd.DataFrame(trades)
    if trades_df.empty:
        return trades_df, {"trades": 0, "net_pnl": -1e18, "score": -1e18}
    wins = trades_df[trades_df.pnl > 0]
    losses = trades_df[trades_df.pnl < 0]
    gross_profit = float(wins.pnl.sum())
    gross_loss = float(losses.pnl.sum())
    profit_factor = gross_profit / abs(gross_loss) if gross_loss < 0 else 999.0
    net = equity - DEFAULT_START_EQUITY
    return trades_df, {
        "trades": int(len(trades_df)),
        "wins": int(len(wins)),
        "losses": int(len(losses)),
        "net_pnl": net,
        "net_pnl_pct": net / DEFAULT_START_EQUITY * 100.0,
        "win_rate_pct": float(len(wins) / len(trades_df) * 100.0),
        "profit_factor": profit_factor,
        "max_drawdown": max_drawdown,
        "max_drawdown_pct": max_drawdown / DEFAULT_START_EQUITY * 100.0,
        "score": net,
    }


def grid() -> list[dict[str, Any]]:
    base = {
        "psar_step": 0.02,
        "psar_max": 0.2,
        "h4_adx_min": 5.0,
        "h1_adx_min": 20.0,
        "h1_vol_mult": 1.5,
        "entry_adx_min": 12.0,
        "window_bars": 12,
        "max_bars": 24,
        "tp1_r": 1.0,
        "cooldown": 6,
        "rsi_min": 40.0,
        "rsi_max": 76.0,
        "dlb_len": 20,
        "atr_stop": 2.0,
        "atr_trail": 3.0,
        "vol_mult": 1.0,
        "tp2_r": 2.5,
        "setup_min": 1,
        "psar_exit": False,
    }
    return [
        {**base, "csb_min": 0, "ci_limit": 55.0, "risk_pct": 1.5},
        {**base, "csb_min": 1, "ci_limit": 55.0, "risk_pct": 1.5},
        {**base, "csb_min": 2, "ci_limit": 55.0, "risk_pct": 1.5},
        {**base, "csb_min": 1, "ci_limit": 50.0, "risk_pct": 2.0},
        {**base, "csb_min": 2, "ci_limit": 50.0, "risk_pct": 2.0},
        {**base, "csb_min": 3, "ci_limit": 55.0, "risk_pct": 2.0},
    ]


def write_markdown_report(path: Path, payload: dict[str, Any]) -> None:
    summary = payload.get("best_summary") or {}
    params = payload.get("best_params") or {}
    lines = [
        "# Binance BTC Backtest Latest",
        "",
        f"- Symbol: `{payload.get('symbol')}`",
        f"- Market: `{payload.get('market')}`",
        f"- Interval: `{payload.get('interval')}`",
        f"- Period: `{payload.get('start')}` → `{payload.get('end')}`",
        f"- Rows tested: `{payload.get('rows_tested')}`",
        "",
        "## Best summary",
        "",
        f"- Trades: `{summary.get('trades')}`",
        f"- Net PnL: `{summary.get('net_pnl')}`",
        f"- Net PnL %: `{summary.get('net_pnl_pct')}`",
        f"- Win rate %: `{summary.get('win_rate_pct')}`",
        f"- Profit factor: `{summary.get('profit_factor')}`",
        f"- Max drawdown %: `{summary.get('max_drawdown_pct')}`",
        "",
        "## Best params",
        "",
        "```json",
        json.dumps(params, ensure_ascii=False, indent=2),
        "```",
        "",
        "## Guardrail",
        "",
        "This is a bar-based research backtest. It is not a tick-accurate execution simulator and must not be treated as a live trading guarantee.",
    ]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def run_optimization(data_path: Path, output_dir: Path, symbol: str, market: str, interval: str, start: str, end: str) -> dict[str, Any]:
    df = load_ohlcv(data_path)
    if start:
        df = df[df.index >= pd.Timestamp(start, tz="UTC")]
    if end:
        df = df[df.index <= pd.Timestamp(end, tz="UTC")]

    rows: list[dict[str, Any]] = []
    best_summary: dict[str, Any] | None = None
    best_params: dict[str, Any] | None = None
    best_trades = pd.DataFrame()

    for params in grid():
        trades, summary = run_combo(df, params)
        rows.append({**params, **summary})
        ok = summary.get("trades", 0) >= 30 and summary.get("profit_factor", 0) > 1.0 and summary.get("max_drawdown_pct", 999) < 15.0
        if ok and (best_summary is None or summary["net_pnl"] > best_summary["net_pnl"]):
            best_summary = summary
            best_params = params
            best_trades = trades

    output_dir.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(rows).sort_values("net_pnl", ascending=False).to_csv(output_dir / "binance_btc_results.csv", index=False)
    best_trades.to_csv(output_dir / "binance_btc_best_trades.csv", index=False)
    payload = {
        "source": "gpt_coin_android/tools/binance_btc_backtest.py",
        "integrated_from": "shopper12/backtest",
        "selection_metric": "max_net_pnl_usdt_with_pf_dd_guards",
        "symbol": symbol,
        "market": market,
        "interval": interval,
        "start": start,
        "end": end,
        "best_params": best_params,
        "best_summary": best_summary,
        "rows_tested": len(rows),
    }
    (output_dir / "binance_btc_best.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    write_markdown_report(output_dir / "binance_btc_backtest_latest.md", payload)
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return payload


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbol", default="BTCUSDT")
    parser.add_argument("--market", choices=["futures", "spot"], default="futures")
    parser.add_argument("--interval", default="5m")
    parser.add_argument("--start", default="2025-06-22")
    parser.add_argument("--end", default="")
    parser.add_argument("--data", type=Path, default=Path("runtime_data/binance_klines.csv"))
    parser.add_argument("--output-dir", type=Path, default=Path("reports"))
    parser.add_argument("--skip-download", action="store_true")
    args = parser.parse_args()

    end = args.end or dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d")
    if not args.skip_download:
        rows = download_klines(args.symbol, args.market, args.interval, args.start, end, args.data)
        if rows <= 0:
            raise SystemExit("No Binance kline rows downloaded.")
    if not args.data.exists():
        raise SystemExit(f"Missing data file: {args.data}")
    run_optimization(args.data, args.output_dir, args.symbol.upper(), args.market, args.interval, args.start, end)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
