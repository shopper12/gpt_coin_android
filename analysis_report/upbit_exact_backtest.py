"""
Exact Upbit backtest for CryptoTradeCoach recommendation export.

Run from project root after exporting CSV files:
python tools/upbit_exact_backtest.py exports/recommendation-export/extracted_folder

This script:
- reads signal_history.csv and missed_signals.csv
- fetches Upbit 1-minute candles after each recommendation/detection timestamp
- evaluates entry/target/stop paths
- compares selected recommendations against missed candidates at the same scan time

Requires:
pip install pandas requests tqdm
"""
import sys, time, math, json
from pathlib import Path
from datetime import datetime, timezone, timedelta
import requests
import pandas as pd
from tqdm import tqdm

BASE_URL = "https://api.upbit.com/v1/candles/minutes/1"

def ms_to_to_param(ms: int, minutes_after: int = 360) -> str:
    # Upbit 'to' is exclusive end time. Use UTC string.
    dt = datetime.fromtimestamp(ms / 1000, tz=timezone.utc) + timedelta(minutes=minutes_after + 2)
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")

def fetch_1m_candles(market: str, start_ms: int, minutes_after: int = 360) -> pd.DataFrame:
    # Upbit max count 200. Fetch in chunks backward from end.
    end_dt = datetime.fromtimestamp(start_ms / 1000, tz=timezone.utc) + timedelta(minutes=minutes_after + 2)
    all_rows = []
    remaining = minutes_after + 5
    to = end_dt.strftime("%Y-%m-%dT%H:%M:%SZ")
    while remaining > 0:
        count = min(200, remaining)
        r = requests.get(BASE_URL, params={"market": market, "to": to, "count": count}, timeout=10)
        if r.status_code != 200:
            raise RuntimeError(f"Upbit API error {r.status_code}: {r.text[:200]}")
        rows = r.json()
        if not rows:
            break
        all_rows.extend(rows)
        oldest = min(pd.to_datetime(x["candle_date_time_utc"]).to_pydatetime().replace(tzinfo=timezone.utc) for x in rows)
        to = oldest.strftime("%Y-%m-%dT%H:%M:%SZ")
        remaining -= count
        time.sleep(0.12)
    if not all_rows:
        return pd.DataFrame()
    df = pd.DataFrame(all_rows)
    df["ts"] = pd.to_datetime(df["candle_date_time_utc"], utc=True)
    start_dt = datetime.fromtimestamp(start_ms / 1000, tz=timezone.utc)
    end_limit = start_dt + timedelta(minutes=minutes_after)
    df = df[(df["ts"] >= start_dt) & (df["ts"] <= end_limit)].sort_values("ts")
    return df

def evaluate_path(candles: pd.DataFrame, entry: float, target: float, stop: float):
    if candles.empty:
        return {"outcome": "NO_DATA"}
    entered = True
    for _, row in candles.iterrows():
        high = float(row["high_price"])
        low = float(row["low_price"])
        # Conservative: if same candle touches both stop and target, stop first.
        if low <= stop:
            return {"outcome": "STOP_FIRST", "exit_time": row["ts"], "pnl_pct": (stop/entry-1)*100}
        if high >= target:
            return {"outcome": "TARGET_FIRST", "exit_time": row["ts"], "pnl_pct": (target/entry-1)*100}
    close = float(candles.iloc[-1]["trade_price"])
    return {
        "outcome": "TIME_EXIT",
        "exit_time": candles.iloc[-1]["ts"],
        "pnl_pct": (close/entry-1)*100,
        "mfe_pct": (candles["high_price"].max()/entry-1)*100,
        "mae_pct": (candles["low_price"].min()/entry-1)*100,
    }

def main(folder):
    folder = Path(folder)
    sig = pd.read_csv(folder / "signal_history.csv")
    inits = sig[sig["eventType"] == "INITIAL_SIGNAL"].copy()
    results = []
    for _, row in tqdm(inits.iterrows(), total=len(inits)):
        candles = fetch_1m_candles(row["market"], int(row["createdAt"]), 360)
        ev = evaluate_path(candles, float(row["entryPrice"]), float(row["targetPrice"]), float(row["stopLossPrice"]))
        ev.update({
            "market": row["market"],
            "strategyName": row["strategyName"],
            "createdAt": row["createdAt"],
            "entryPrice": row["entryPrice"],
            "targetPrice": row["targetPrice"],
            "stopLossPrice": row["stopLossPrice"],
            "score": row["score"],
        })
        results.append(ev)
    out = pd.DataFrame(results)
    out.to_csv(folder / "upbit_exact_backtest_selected.csv", index=False, encoding="utf-8-sig")
    print(out.groupby(["strategyName","outcome"]).size())
    print("Saved", folder / "upbit_exact_backtest_selected.csv")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python tools/upbit_exact_backtest.py <csv-export-folder>")
        sys.exit(1)
    main(sys.argv[1])
