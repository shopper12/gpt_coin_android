#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path

import pandas as pd

from binance_btc_backtest import (
    download_klines,
    grid,
    load_ohlcv,
    run_combo,
    write_markdown_report,
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbol", default="BTCUSDT")
    parser.add_argument("--market", choices=["futures", "spot"], default="futures")
    parser.add_argument("--interval", default="5m")
    parser.add_argument("--lookback-days", type=int, default=180)
    parser.add_argument("--end", default="")
    parser.add_argument("--data", type=Path, default=Path("runtime_data/binance_klines.csv"))
    parser.add_argument("--output-dir", type=Path, default=Path("reports"))
    args = parser.parse_args()

    end_date = dt.date.fromisoformat(args.end) if args.end else dt.datetime.now(dt.timezone.utc).date()
    lookback_days = max(90, min(args.lookback_days, 365))
    start_date = end_date - dt.timedelta(days=lookback_days)
    start = start_date.isoformat()
    end = end_date.isoformat()

    rows_downloaded = download_klines(
        args.symbol.upper(),
        args.market,
        args.interval,
        start,
        end,
        args.data,
    )
    if rows_downloaded <= 0:
        raise SystemExit("No Binance archive rows downloaded.")

    frame = load_ohlcv(args.data)
    frame = frame[(frame.index >= pd.Timestamp(start, tz="UTC")) & (frame.index <= pd.Timestamp(end, tz="UTC"))]
    if len(frame) < 5_000:
        raise SystemExit(f"Insufficient rows for rolling backtest: {len(frame)}")

    result_rows: list[dict] = []
    evaluated: list[tuple[dict, dict, pd.DataFrame, bool]] = []
    for params in grid():
        trades, summary = run_combo(frame, params)
        accepted = bool(
            summary.get("trades", 0) >= 30
            and summary.get("profit_factor", 0) > 1.0
            and summary.get("max_drawdown_pct", 999) < 15.0
        )
        result_rows.append({**params, **summary, "accepted": accepted})
        evaluated.append((params, summary, trades, accepted))

    valid = [item for item in evaluated if item[1].get("trades", 0) > 0]
    if not valid:
        raise SystemExit("No strategy combination generated a completed trade.")
    guarded = [item for item in valid if item[3]]
    pool = guarded or valid
    best_params, best_summary, best_trades, accepted = max(
        pool,
        key=lambda item: (
            float(item[1].get("net_pnl", -1e18)),
            float(item[1].get("profit_factor", 0.0)),
            int(item[1].get("trades", 0)),
        ),
    )

    output_dir = args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(result_rows).sort_values(
        ["accepted", "net_pnl"], ascending=[False, False]
    ).to_csv(output_dir / "binance_btc_results.csv", index=False)
    best_trades.to_csv(output_dir / "binance_btc_best_trades.csv", index=False)

    generated_at = dt.datetime.now(dt.timezone.utc)
    payload = {
        "schema_version": 2,
        "source": "gpt_coin_android/tools/run_binance_btc_daily_backtest.py",
        "integrated_from": "shopper12/backtest",
        "selection_metric": "max_net_pnl_with_pf_dd_guards_else_best_available",
        "generated_at_utc": generated_at.isoformat(),
        "symbol": args.symbol.upper(),
        "market": args.market,
        "interval": args.interval,
        "start": start,
        "end": end,
        "lookback_days": lookback_days,
        "rows_tested": int(len(frame)),
        "parameter_sets_tested": len(result_rows),
        "accepted": accepted,
        "guard": {
            "min_trades": 30,
            "min_profit_factor_exclusive": 1.0,
            "max_drawdown_pct_exclusive": 15.0,
            "guarded_candidate_count": len(guarded),
        },
        "best_params": best_params,
        "best_summary": best_summary,
    }
    (output_dir / "binance_btc_best.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    write_markdown_report(output_dir / "binance_btc_backtest_latest.md", payload)
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
