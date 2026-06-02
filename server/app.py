import os
import time
import math
import threading
from dataclasses import dataclass, asdict
from typing import Dict, List, Optional, Tuple

import requests
from flask import Flask, jsonify

app = Flask(__name__)

UPBIT = "https://api.upbit.com/v1"
SCAN_INTERVAL_SEC = int(os.getenv("SCAN_INTERVAL_SEC", "60"))
MAX_CANDLE_TARGETS = int(os.getenv("MAX_CANDLE_TARGETS", "45"))
MIN_SCORE = float(os.getenv("MIN_SCORE", "74"))
MAX_RESULTS = int(os.getenv("MAX_RESULTS", "3"))
STRATEGY_TTL_SEC = int(os.getenv("STRATEGY_TTL_SEC", str(4 * 60 * 60)))
TICKER_CACHE_SEC = int(os.getenv("TICKER_CACHE_SEC", "12"))
CANDLE_CACHE_SEC = int(os.getenv("CANDLE_CACHE_SEC", "55"))

session = requests.Session()
lock = threading.Lock()
last_scan_at = 0.0
last_error: Optional[str] = None
last_payload: Dict = {
    "ok": True,
    "mode": "render_low_bandwidth",
    "activeStrategies": [],
    "diagnostics": {"status": "booting"},
}
active_by_symbol: Dict[str, Dict] = {}
ticker_cache: Tuple[float, List[Dict]] = (0.0, [])
candle_cache: Dict[Tuple[str, int], Tuple[float, List[Dict]]] = {}


def get_json(path: str, params: Optional[Dict] = None):
    r = session.get(f"{UPBIT}{path}", params=params, timeout=8)
    r.raise_for_status()
    return r.json()


def krw_markets() -> List[str]:
    rows = get_json("/market/all", {"isDetails": "false"})
    return [r["market"] for r in rows if r.get("market", "").startswith("KRW-")]


def tickers() -> List[Dict]:
    global ticker_cache
    now = time.time()
    if now - ticker_cache[0] < TICKER_CACHE_SEC and ticker_cache[1]:
        return ticker_cache[1]
    markets = krw_markets()
    out: List[Dict] = []
    for i in range(0, len(markets), 100):
        chunk = markets[i:i + 100]
        out.extend(get_json("/ticker", {"markets": ",".join(chunk)}))
    ticker_cache = (now, out)
    return out


def candles(market: str, unit: int, count: int) -> List[Dict]:
    key = (market, unit)
    now = time.time()
    cached = candle_cache.get(key)
    if cached and now - cached[0] < CANDLE_CACHE_SEC:
        return cached[1]
    rows = get_json(f"/candles/minutes/{unit}", {"market": market, "count": count})
    rows = sorted(rows, key=lambda x: x.get("timestamp", 0))
    candle_cache[key] = (now, rows)
    return rows


def pct(a: float, b: float) -> float:
    return 0.0 if a <= 0 else (b - a) / a * 100.0


def avg_trade_price(rows: List[Dict]) -> float:
    return sum(float(r.get("candle_acc_trade_price", 0.0)) for r in rows) / len(rows) if rows else 0.0


def select_targets(rows: List[Dict]) -> List[Dict]:
    by_value = sorted(rows, key=lambda x: float(x.get("acc_trade_price_24h", 0.0)), reverse=True)[:70]
    by_change = sorted(rows, key=lambda x: float(x.get("signed_change_rate", 0.0)), reverse=True)[:35]
    median = sorted(float(x.get("acc_trade_price_24h", 0.0)) for x in rows)
    med = median[len(median) // 2] if median else 0.0
    value_markets = {x["market"] for x in by_value}
    buildup = [x for x in rows if float(x.get("acc_trade_price_24h", 0.0)) > med * 1.05 and -2.0 <= float(x.get("signed_change_rate", 0.0)) * 100 <= 7.5 and x["market"] not in value_markets]
    buildup = sorted(buildup, key=lambda x: float(x.get("acc_trade_price_24h", 0.0)), reverse=True)[:35]
    merged = []
    seen = set()
    for x in by_value + by_change + buildup:
        if x["market"] not in seen:
            merged.append(x)
            seen.add(x["market"])
    return merged[:MAX_CANDLE_TARGETS]


def build_strategy(ticker: Dict, change_rank: int, value_rank: int, now_ms: int) -> Optional[Dict]:
    market = ticker["market"]
    price = float(ticker.get("trade_price", 0.0))
    if price <= 0:
        return None
    five = candles(market, 5, 60)
    fifteen = candles(market, 15, 50)
    if len(five) < 25 or len(fifteen) < 25:
        return None
    prev_high = max(float(x.get("high_price", 0.0)) for x in five[-21:-1])
    prev_low = min(float(x.get("low_price", 0.0)) for x in five[-21:-1])
    range_pct = max(0.0, pct(prev_low, prev_high))
    range_pos = 0.5 if prev_high <= prev_low else max(0.0, min(1.0, (price - prev_low) / (prev_high - prev_low)))
    avg5 = avg_trade_price(five[-21:-1])
    avg15 = avg_trade_price(fifteen[-21:-1])
    five_ratio = float(five[-1].get("candle_acc_trade_price", 0.0)) / avg5 if avg5 > 0 else 1.0
    fifteen_ratio = float(fifteen[-1].get("candle_acc_trade_price", 0.0)) / avg15 if avg15 > 0 else 1.0
    change24 = float(ticker.get("signed_change_rate", 0.0)) * 100.0
    change30 = pct(float(five[-6].get("opening_price", price)), price)
    change5 = pct(float(five[-1].get("opening_price", price)), price)
    recent = sum(float(x.get("candle_acc_trade_price", 0.0)) for x in five[-3:])
    previous = sum(float(x.get("candle_acc_trade_price", 0.0)) for x in five[-23:-3]) / 6.67
    volume_accel = recent / previous if previous > 0 else 1.0

    if not (-2.5 <= change24 <= 9.0 and change30 <= 3.2 and change5 <= 1.8):
        return None
    if not (value_rank <= 55 and change_rank <= 45):
        return None
    if not (volume_accel >= 1.38 or five_ratio >= 1.40 or fifteen_ratio >= 1.25):
        return None
    if not (range_pct <= 5.2 and range_pos >= 0.58 and price >= prev_high * 0.990):
        return None

    structure = 24.0
    volume = max(0.0, min(26.0, (max(volume_accel, five_ratio, fifteen_ratio) - 1.0) * 18.0))
    rotation = 18.0 if change_rank <= 15 else 13.0
    liquidity = 16.0 if value_rank <= 15 else 11.0
    score = max(0.0, min(94.0, 18.0 + structure + volume + rotation + liquidity))
    if score < MIN_SCORE:
        return None

    stop = min(prev_low, min(float(x.get("low_price", prev_low)) for x in five[-8:])) * 0.996
    risk_pct = max(0.32, abs(pct(price, stop)))
    target1 = price * (1.0 + risk_pct * 1.6 / 100.0)
    target2 = price * (1.0 + risk_pct * 2.6 / 100.0)
    return {
        "id": f"{market}-PRE_PUMP_RENDER",
        "symbol": market,
        "strategyType": "PRE_PUMP_ROTATION",
        "status": "ACTIVE",
        "score": round(score, 2),
        "rank": 999,
        "entryLow": price * 0.999,
        "entryHigh": price * 1.001,
        "stopLoss": stop,
        "target1": target1,
        "target2": target2,
        "trailingStop": price * (1.0 - max(risk_pct * 0.55, 0.32) / 100.0),
        "expectedReturnPct": round(abs(pct(price, target2)), 3),
        "riskPct": round(risk_pct, 3),
        "riskRewardRatio": round(abs(pct(price, target2)) / risk_pct, 3),
        "componentScores": f"render=true;structure={structure};volume={volume:.1f};rotation={rotation};liquidity={liquidity};rangePct={range_pct:.1f};rangePos={range_pos*100:.1f};volAccel={volume_accel:.2f}",
        "rankByChangeRate": change_rank,
        "rankByTradeValue": value_rank,
        "changeRate24h": round(change24, 3),
        "changeRate30m": round(change30, 3),
        "changeRate5m": round(change5, 3),
        "volumeAcceleration": round(volume_accel, 3),
        "reason": "Render server scanned Upbit and returned low-bandwidth locked strategy candidate.",
        "invalidationReason": None,
        "createdAt": now_ms,
        "updatedAt": now_ms,
        "validUntil": now_ms + STRATEGY_TTL_SEC * 1000,
    }


def terminal_status(strategy: Dict, latest_price: float, now_ms: int) -> Optional[str]:
    if latest_price >= float(strategy["target2"]):
        return "HIT_TARGET"
    if latest_price <= float(strategy["stopLoss"]):
        return "STOPPED_OUT"
    if latest_price <= float(strategy["trailingStop"]) and latest_price > float(strategy["stopLoss"]):
        return "TRAILING_STOP_HIT"
    if now_ms > int(strategy["validUntil"]):
        return "EXPIRED"
    return None


def scan_once() -> Dict:
    global active_by_symbol, last_error
    now_ms = int(time.time() * 1000)
    rows = tickers()
    price_by_market = {x["market"]: float(x.get("trade_price", 0.0)) for x in rows}

    # Keep existing strategies locked until terminal outcome.
    locked: Dict[str, Dict] = {}
    for symbol, st in list(active_by_symbol.items()):
        latest = price_by_market.get(symbol, float(st.get("entryHigh", 0.0)))
        status = terminal_status(st, latest, now_ms)
        if status is None:
            st["updatedAt"] = now_ms
            st["latestPrice"] = latest
            locked[symbol] = st
    active_by_symbol = locked

    change_rank = {x["market"]: i + 1 for i, x in enumerate(sorted(rows, key=lambda r: float(r.get("signed_change_rate", 0.0)), reverse=True))}
    value_rank = {x["market"]: i + 1 for i, x in enumerate(sorted(rows, key=lambda r: float(r.get("acc_trade_price_24h", 0.0)), reverse=True))}

    candidates = []
    for t in select_targets(rows):
        if t["market"] in active_by_symbol:
            continue
        try:
            strategy = build_strategy(t, change_rank[t["market"]], value_rank[t["market"]], now_ms)
            if strategy:
                candidates.append(strategy)
        except Exception as e:
            last_error = f"candidate {t.get('market')} failed: {type(e).__name__} {e}"
    candidates = sorted(candidates, key=lambda x: (-float(x["score"]), float(x["riskPct"])))[:MAX_RESULTS]
    for idx, st in enumerate(candidates, start=1):
        st["rank"] = idx
        active_by_symbol[st["symbol"]] = st

    active = sorted(active_by_symbol.values(), key=lambda x: (-float(x["score"]), int(x["rank"])))[:MAX_RESULTS]
    return {
        "ok": True,
        "mode": "render_low_bandwidth",
        "generatedAt": now_ms,
        "activeStrategies": active,
        "diagnostics": {
            "scannedTickers": len(rows),
            "candleTargets": MAX_CANDLE_TARGETS,
            "activeLocked": len(active_by_symbol),
            "lastError": last_error,
            "nextScanSec": SCAN_INTERVAL_SEC,
        },
    }


def worker():
    global last_scan_at, last_payload, last_error
    while True:
        try:
            payload = scan_once()
            with lock:
                last_payload = payload
                last_scan_at = time.time()
        except Exception as e:
            last_error = f"scan failed: {type(e).__name__} {e}"
            with lock:
                last_payload = {**last_payload, "ok": False, "error": last_error}
        time.sleep(SCAN_INTERVAL_SEC)


@app.route("/health")
def health():
    return jsonify({"ok": True, "service": "gpt-coin-scanner", "lastScanAt": last_scan_at})


@app.route("/api/latest-strategy")
def latest_strategy():
    with lock:
        return jsonify(last_payload)


@app.route("/api/scan-now")
def scan_now():
    payload = scan_once()
    with lock:
        global last_payload, last_scan_at
        last_payload = payload
        last_scan_at = time.time()
    return jsonify(payload)


if os.getenv("DISABLE_BACKGROUND_WORKER", "0") != "1":
    threading.Thread(target=worker, daemon=True).start()
