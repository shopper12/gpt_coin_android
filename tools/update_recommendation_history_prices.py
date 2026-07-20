#!/usr/bin/env python3
import json
import time
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HISTORY = ROOT / "reports" / "chatgpt_recommendation_history.json"
KST = timezone(timedelta(hours=9))


def yahoo_chart(symbol: str, period1: int | None = None, period2: int | None = None) -> dict:
    params = {"interval": "1d", "events": "history"}
    if period1 is None:
        params["range"] = "5d"
    else:
        params["period1"] = str(period1)
        params["period2"] = str(period2)
    url = f"https://query1.finance.yahoo.com/v8/finance/chart/{urllib.parse.quote(symbol)}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=20) as response:
        return json.load(response)["chart"]["result"][0]


def last_close(symbol: str) -> tuple[float, str]:
    result = yahoo_chart(symbol)
    closes = result["indicators"]["quote"][0]["close"]
    timestamps = result["timestamp"]
    for ts, close in reversed(list(zip(timestamps, closes))):
        if close is not None:
            date = datetime.fromtimestamp(ts, tz=timezone.utc).astimezone(KST).date().isoformat()
            return float(close), date
    raise RuntimeError(f"No current close for {symbol}")


def date_close(symbol: str, date_text: str) -> tuple[float, str]:
    start = datetime.fromisoformat(date_text).replace(tzinfo=KST)
    end = start + timedelta(days=7)
    result = yahoo_chart(symbol, int(start.timestamp()), int(end.timestamp()))
    closes = result["indicators"]["quote"][0]["close"]
    timestamps = result["timestamp"]
    for ts, close in zip(timestamps, closes):
        if close is not None:
            date = datetime.fromtimestamp(ts, tz=timezone.utc).astimezone(KST).date().isoformat()
            return float(close), date
    raise RuntimeError(f"No historical close for {symbol} near {date_text}")


def main() -> None:
    payload = json.loads(HISTORY.read_text(encoding="utf-8"))
    errors = []
    for row in payload.get("recommendations", []):
        symbol = row.get("ticker", "").strip()
        if not symbol:
            continue
        try:
            if row.get("referencePrice") is None:
                value, used_date = date_close(symbol, row["date"])
                row["referencePrice"] = round(value, 6)
                row["referencePriceDate"] = used_date
                row["referencePriceMethod"] = "recommendation_date_or_next_session_close_proxy"
                if row.get("status") == "PRICE_BACKFILL_REQUIRED":
                    row["status"] = "PROXY_REFERENCE_PRICE"
            current, current_date = last_close(symbol)
            row["currentPrice"] = round(current, 6)
            row["currentPriceDate"] = current_date
            row["currentPriceMethod"] = "yahoo_daily_close"
        except Exception as exc:
            errors.append(f"{symbol}: {exc}")
        time.sleep(0.3)

    payload["generatedAtKst"] = datetime.now(KST).isoformat(timespec="seconds")
    payload["priceRefreshErrors"] = errors
    HISTORY.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"updated={len(payload.get('recommendations', []))} errors={len(errors)}")
    for error in errors:
        print(error)


if __name__ == "__main__":
    main()
