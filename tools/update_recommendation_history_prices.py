#!/usr/bin/env python3
import json
import re
import time
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HISTORY = ROOT / "reports" / "chatgpt_recommendation_history.json"
KST = timezone(timedelta(hours=9))
NON_EXECUTED_STATUSES = {
    "CONDITIONAL",
    "UNTRIGGERED",
    "SOURCE_REVIEW_REQUIRED",
    "WATCH",
}


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
        payload = json.load(response)
    result = payload.get("chart", {}).get("result") or []
    if not result:
        raise RuntimeError(f"Yahoo returned no chart result for {symbol}")
    return result[0]


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


def parse_primary_entry(entry: str, currency: str) -> float | None:
    """Return the first explicit entry price/range midpoint, never a percentage trigger."""
    if not entry or currency not in entry:
        return None
    blocked = ("대비", "돌파", "회복", "이탈", "지지 확인", "시초가", "종가 또는", "원문", "미복구")
    if any(token in entry for token in blocked):
        return None
    currency_pos = entry.find(currency)
    price_text = entry[:currency_pos]
    raw_numbers = re.findall(r"(?<![-+%])\d[\d,]*(?:\.\d+)?", price_text)
    values = []
    for raw in raw_numbers:
        try:
            value = float(raw.replace(",", ""))
        except ValueError:
            continue
        if value > 0:
            values.append(value)
    if not values:
        return None
    if len(values) >= 2 and any(sep in price_text for sep in ("~", "-")):
        return (values[0] + values[1]) / 2.0
    return values[0]


def main() -> None:
    payload = json.loads(HISTORY.read_text(encoding="utf-8"))
    errors = []
    updated = 0
    for row in payload.get("recommendations", []):
        ticker = row.get("ticker", "").strip()
        symbol = row.get("yahooSymbol", ticker).strip()
        if not symbol:
            continue
        status = row.get("status", "ARCHIVED").upper()
        try:
            if row.get("referencePrice") is None and status not in NON_EXECUTED_STATUSES:
                parsed_entry = parse_primary_entry(row.get("entry", ""), row.get("currency", ""))
                if parsed_entry is not None:
                    row["referencePrice"] = round(parsed_entry, 6)
                    row["referencePriceDate"] = row.get("date", "")
                    row["referencePriceMethod"] = "primary_entry_midpoint_from_chat"
                else:
                    value, used_date = date_close(symbol, row["date"])
                    row["referencePrice"] = round(value, 6)
                    row["referencePriceDate"] = used_date
                    row["referencePriceMethod"] = "recommendation_date_or_next_session_close_proxy"

            current, current_date = last_close(symbol)
            row["currentPrice"] = round(current, 6)
            row["currentPriceDate"] = current_date
            row["currentPriceMethod"] = "yahoo_daily_close"
            updated += 1
        except Exception as exc:
            errors.append(f"{ticker}/{symbol}: {exc}")
        time.sleep(0.25)

    payload["generatedAtKst"] = datetime.now(KST).isoformat(timespec="seconds")
    payload["recordCount"] = len(payload.get("recommendations", []))
    payload["priceRefreshErrors"] = errors
    HISTORY.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"records={payload['recordCount']} quotes_updated={updated} errors={len(errors)}")
    for error in errors:
        print(error)


if __name__ == "__main__":
    main()
