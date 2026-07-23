from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any

import numpy as np
import pandas as pd

STRATEGY_NAME = "EVIDENCE_WEIGHTED_GLOBAL_TREND_MOMENTUM"


@dataclass(frozen=True)
class Instrument:
    ticker: str
    name: str
    market: str
    asset_class: str
    currency: str
    source: str


def _ret(series: pd.Series, bars: int) -> float:
    if len(series) <= bars:
        return float("nan")
    first = float(series.iloc[-bars - 1])
    last = float(series.iloc[-1])
    return last / first - 1.0 if first > 0 else float("nan")


def atr(frame: pd.DataFrame, length: int = 14) -> pd.Series:
    prev = frame["close"].shift(1)
    tr = pd.concat(
        [
            frame["high"] - frame["low"],
            (frame["high"] - prev).abs(),
            (frame["low"] - prev).abs(),
        ],
        axis=1,
    ).max(axis=1)
    return tr.ewm(alpha=1 / length, adjust=False).mean()


def rsi(series: pd.Series, length: int = 14) -> pd.Series:
    delta = series.diff()
    gain = delta.clip(lower=0).ewm(alpha=1 / length, adjust=False).mean()
    loss = (-delta.clip(upper=0)).ewm(alpha=1 / length, adjust=False).mean()
    return 100 - 100 / (1 + gain / loss.replace(0, np.nan))


def daily_metrics(instrument: Instrument, frame: pd.DataFrame) -> dict[str, Any] | None:
    if frame is None or frame.empty:
        return None
    x = frame.copy().dropna(subset=["open", "high", "low", "close"])
    if len(x) < 220:
        return None
    close = x["close"].astype(float)
    price = float(close.iloc[-1])
    previous = float(close.iloc[-2])
    volume20 = float(x["volume"].tail(20).mean())
    ma20 = float(close.rolling(20).mean().iloc[-1])
    ma50 = float(close.rolling(50).mean().iloc[-1])
    ma100 = float(close.rolling(100).mean().iloc[-1])
    ma200 = float(close.rolling(200).mean().iloc[-1])
    atr14 = float(atr(x, 14).iloc[-1])
    vol20 = float(close.pct_change().tail(20).std(ddof=0) * math.sqrt(252))
    return {
        "ticker": instrument.ticker,
        "name": instrument.name,
        "market": instrument.market,
        "assetClass": instrument.asset_class,
        "currency": instrument.currency,
        "source": instrument.source,
        "currentPrice": price,
        "previousClose": previous,
        "todayChangePct": (price / previous - 1) * 100 if previous > 0 else 0.0,
        "ma20": ma20,
        "ma50": ma50,
        "ma100": ma100,
        "ma200": ma200,
        "atr14": atr14,
        "rsi14": float(rsi(close).iloc[-1]),
        "ret1m": _ret(close, 21),
        "ret3m": _ret(close, 63),
        "ret6m": _ret(close, 126),
        "ret12m": _ret(close, 252),
        "volatility20": vol20,
        "avgDollarValue": price * volume20,
        "volumeRatio20": float(x["volume"].iloc[-1] / volume20) if volume20 > 0 else 0.0,
        "high20": float(x["high"].iloc[-21:-1].max()),
        "high55": float(x["high"].iloc[-56:-1].max()),
        "low20": float(x["low"].iloc[-21:-1].min()),
        "recentCloses": [round(float(v), 6) for v in close.tail(30).tolist()],
        "lastDailyTimestamp": pd.Timestamp(x.index[-1]).isoformat(),
    }


def is_liquid(item: dict[str, Any]) -> bool:
    price = float(item.get("currentPrice") or 0)
    value = float(item.get("avgDollarValue") or 0)
    if item.get("assetClass") == "CRYPTO":
        return value > 0
    if item.get("currency") == "KRW":
        return price >= 1000 and value >= 5_000_000_000
    return price >= 3 and value >= 20_000_000


def rank_relative_strength(items: list[dict[str, Any]]) -> None:
    groups: dict[str, list[dict[str, Any]]] = {}
    for item in items:
        groups.setdefault(str(item.get("assetClass")), []).append(item)
    for group in groups.values():
        for item in group:
            item["relativeMomentumRaw"] = (
                float(item.get("ret3m") or -9) * 0.15
                + float(item.get("ret6m") or -9) * 0.50
                + float(item.get("ret12m") or -9) * 0.35
            )
        ordered = sorted(group, key=lambda row: float(row["relativeMomentumRaw"]))
        denominator = max(len(ordered) - 1, 1)
        for index, item in enumerate(ordered):
            item["relativeStrengthPercentile"] = index / denominator


def market_regime(by_ticker: dict[str, dict[str, Any]]) -> dict[str, Any]:
    benchmarks = [by_ticker.get(ticker) for ticker in ("SPY", "QQQ", "ACWI")]
    benchmarks = [row for row in benchmarks if row]
    below = sum(1 for row in benchmarks if float(row["currentPrice"]) < float(row["ma200"]))
    realized_vol = max([float(row.get("volatility20") or 0) for row in benchmarks] or [0.0])
    panic = below >= 2 and realized_vol >= 0.28
    risk_off = below >= 2 or realized_vol >= 0.35
    return {
        "panic": panic,
        "riskOff": risk_off,
        "equityBelow200Count": below,
        "realizedVolatility20Annualized": round(realized_vol, 4),
        "reason": (
            "광의 지수 다수가 200일선 아래이고 변동성이 높아 신규 위험자산 롱 차단"
            if panic
            else "광의 위험선호 약화로 포지션 크기 축소"
            if risk_off
            else "광의 추세와 변동성 정상"
        ),
    }


def score_daily(item: dict[str, Any], regime: dict[str, Any]) -> float:
    price = float(item["currentPrice"])
    ma20 = float(item["ma20"])
    ma50 = float(item["ma50"])
    ma100 = float(item["ma100"])
    ma200 = float(item["ma200"])
    ret3 = float(item["ret3m"])
    ret6 = float(item["ret6m"])
    ret12 = float(item["ret12m"])
    pctile = float(item.get("relativeStrengthPercentile") or 0)
    vol = float(item.get("volatility20") or 0)
    rsi14 = float(item.get("rsi14") or 50)
    gap20 = price / ma20 - 1 if ma20 > 0 else 0
    breakout = price >= float(item["high55"]) * 0.995 or price >= float(item["high20"]) * 0.998
    absolute = price > ma200 and ma50 > ma100 * 0.99 and ret3 > 0 and ret6 > 0 and ret12 > 0
    score = 0.0
    score += 25 if absolute else -20
    score += max(0.0, min(25.0, (pctile - 0.50) * 50.0))
    score += 12 if breakout else 4 if price > ma50 else -8
    score += 10 if is_liquid(item) else -30
    score += 8 if 0.10 <= vol <= 0.65 else 3 if vol < 0.10 else -10
    score += 8 if 42 <= rsi14 <= 72 else -10 if rsi14 > 80 else 0
    score += 7 if gap20 <= 0.12 and float(item["ret1m"]) <= 0.25 else -15
    if regime.get("panic") and item.get("assetClass") in {"US_STOCK", "KR_STOCK", "CRYPTO"}:
        score -= 35
    elif regime.get("riskOff") and item.get("assetClass") in {"US_STOCK", "KR_STOCK", "CRYPTO"}:
        score -= 12
    return max(0.0, min(100.0, score))


def ict_intraday(frame: pd.DataFrame) -> dict[str, Any]:
    if frame is None or frame.empty or len(frame) < 40:
        return {"bias": "UNKNOWN", "structure": "INSUFFICIENT_DATA", "event": "NONE", "liquidity": "NONE", "fvg": "NONE", "score": 0}
    x = frame.tail(160)
    highs: list[float] = []
    lows: list[float] = []
    for index in range(2, len(x) - 2):
        window = x.iloc[index - 2:index + 3]
        row = x.iloc[index]
        if float(row["high"]) >= float(window["high"].max()):
            highs.append(float(row["high"]))
        if float(row["low"]) <= float(window["low"].min()):
            lows.append(float(row["low"]))
    last = x.iloc[-1]
    close = float(last["close"])
    last_high, prior_high = (highs[-1] if highs else None), (highs[-2] if len(highs) > 1 else None)
    last_low, prior_low = (lows[-1] if lows else None), (lows[-2] if len(lows) > 1 else None)
    bullish = all(v is not None for v in (last_high, prior_high, last_low, prior_low)) and last_high > prior_high and last_low > prior_low
    bearish = all(v is not None for v in (last_high, prior_high, last_low, prior_low)) and last_high < prior_high and last_low < prior_low
    structure = "BULLISH_HH_HL" if bullish else "BEARISH_LH_LL" if bearish else "RANGE"
    event = (
        "CHOCH_UP" if bearish and last_high and close > last_high
        else "BOS_UP" if last_high and close > last_high
        else "CHOCH_DOWN" if bullish and last_low and close < last_low
        else "BOS_DOWN" if last_low and close < last_low
        else "NONE"
    )
    liquidity = (
        "SELL_SIDE_SWEEP" if last_low and float(last["low"]) < last_low < close
        else "BUY_SIDE_SWEEP" if last_high and float(last["high"]) > last_high > close
        else "NONE"
    )
    fvg = "NONE"
    for index in range(len(x) - 1, max(1, len(x) - 35), -1):
        older, current = x.iloc[index - 2], x.iloc[index]
        if float(current["low"]) > float(older["high"]):
            fvg = f"BULLISH {float(older['high']):.4f}~{float(current['low']):.4f}"
            break
        if float(current["high"]) < float(older["low"]):
            fvg = f"BEARISH {float(current['high']):.4f}~{float(older['low']):.4f}"
            break
    score = 0
    score += 2 if bullish else -2 if bearish else 0
    score += 2 if event in {"BOS_UP", "CHOCH_UP"} else -2 if event in {"BOS_DOWN", "CHOCH_DOWN"} else 0
    score += 2 if liquidity == "SELL_SIDE_SWEEP" else -2 if liquidity == "BUY_SIDE_SWEEP" else 0
    score += 1 if fvg.startswith("BULLISH") else -1 if fvg.startswith("BEARISH") else 0
    bias = "BULLISH" if score >= 3 else "BEARISH" if score <= -3 else "NEUTRAL"
    return {"bias": bias, "structure": structure, "event": event, "liquidity": liquidity, "fvg": fvg, "score": score}


def intraday_trigger(frame: pd.DataFrame) -> dict[str, Any]:
    if frame is None or frame.empty or len(frame) < 60:
        return {"ready": False, "reason": "15분봉 부족", "ict": ict_intraday(frame)}
    x = frame.tail(160).copy()
    latest = x.iloc[-1]
    high20 = float(x["high"].iloc[-21:-1].max())
    high40 = float(x["high"].iloc[-41:-1].max())
    volume20 = float(x["volume"].iloc[-21:-1].mean())
    volume_ratio = float(latest["volume"] / volume20) if volume20 > 0 else 0.0
    ema20 = float(x["close"].ewm(span=20, adjust=False).mean().iloc[-1])
    ema50 = float(x["close"].ewm(span=50, adjust=False).mean().iloc[-1])
    close = float(latest["close"])
    breakout = close >= high20 * 0.999 or close >= high40 * 0.999
    reclaim = float(x["close"].iloc[-2]) <= ema20 and close > ema20 and ema20 > ema50
    ict = ict_intraday(x)
    ict_ok = ict["bias"] == "BULLISH" or ict["event"] in {"BOS_UP", "CHOCH_UP"} or ict["liquidity"] == "SELL_SIDE_SWEEP"
    ready = bool((breakout or reclaim) and volume_ratio >= 1.35 and ict_ok)
    return {
        "ready": ready,
        "breakout": breakout,
        "reclaim": reclaim,
        "volumeRatio": round(volume_ratio, 3),
        "ema20": round(ema20, 6),
        "ema50": round(ema50, 6),
        "lastTimestamp": pd.Timestamp(x.index[-1]).isoformat(),
        "reason": f"breakout={breakout}; reclaim={reclaim}; volume={volume_ratio:.2f}x; ICT={ict['bias']}/{ict['event']}/{ict['liquidity']}",
        "ict": ict,
    }


def build_signal(item: dict[str, Any], trigger: dict[str, Any], regime: dict[str, Any], generated_at_kst: str) -> dict[str, Any]:
    price = float(item["currentPrice"])
    atr14 = float(item["atr14"])
    stop_distance = max(atr14 * 2.2, price * 0.035)
    stop = max(price - stop_distance, price * 0.78)
    risk = max(price - stop, price * 0.01)
    annual_vol = max(float(item.get("volatility20") or 0), 0.08)
    exposure = min(0.12 / annual_vol, 1.0)
    if regime.get("riskOff") and item.get("assetClass") in {"US_STOCK", "KR_STOCK", "CRYPTO"}:
        exposure *= 0.5
    score = min(100.0, float(item.get("dailyScore") or 0) + min(max(float(trigger["ict"]["score"]) * 2, -12), 12))
    date = generated_at_kst[:10]
    return {
        "id": f"{date}-{item['ticker']}-LONG-{STRATEGY_NAME}",
        "date": date,
        "generatedAtKst": generated_at_kst,
        "signalTimestamp": str(trigger.get("lastTimestamp") or item.get("lastDailyTimestamp")),
        "assetClass": item["assetClass"],
        "market": item["market"],
        "ticker": item["ticker"],
        "name": item["name"],
        "direction": "LONG",
        "strategy": f"{STRATEGY_NAME}: 절대추세+상대모멘텀+15분 트리거+변동성 목표",
        "strategyType": STRATEGY_NAME,
        "referencePrice": round(price, 6),
        "currentPrice": round(price, 6),
        "previousClose": round(float(item["previousClose"]), 6),
        "todayChangePct": round(float(item["todayChangePct"]), 3),
        "recentCloses": item["recentCloses"],
        "currency": item["currency"],
        "status": "ACTIVE_SIGNAL",
        "score": round(score, 1),
        "confidence": round(min(max(score / 10.0, 5.0), 9.4), 1),
        "relativeStrengthPercentile": round(float(item.get("relativeStrengthPercentile") or 0), 4),
        "entryLow": round(max(price - atr14 * 0.45, price * 0.99), 6),
        "entryHigh": round(price + atr14 * 0.20, 6),
        "stopLoss": round(stop, 6),
        "target1": round(price + risk * 2.0, 6),
        "target2": round(price + risk * 3.2, 6),
        "riskPct": round((price - stop) / price * 100.0, 2),
        "volatilityTargetExposurePct": round(exposure * 100.0, 1),
        "regime": regime,
        "ict": trigger["ict"],
        "trigger": trigger,
        "reason": f"3/6/12개월 절대 모멘텀 양수, 200일선 상단, 자산군 상대강도 {float(item.get('relativeStrengthPercentile') or 0)*100:.0f}백분위, {trigger.get('reason')}",
        "guardrail": "자동 추천은 주문이 아니다. 실시간 호가·슬리피지·공시·거래 가능 여부를 확인한다.",
    }
