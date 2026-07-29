from __future__ import annotations

import csv
import datetime as dt
import io
import json
import re
import time
import urllib.parse
import urllib.request
from typing import Any, Iterable
from zoneinfo import ZoneInfo

import pandas as pd

from global_market_strategy import Instrument

NASDAQ_LISTED = "https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt"
OTHER_LISTED = "https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt"
UPBIT_MARKETS = "https://api.upbit.com/v1/market/all?isDetails=false"
UPBIT_TICKER = "https://api.upbit.com/v1/ticker"
UPBIT_DAY = "https://api.upbit.com/v1/candles/days"
UPBIT_MINUTE15 = "https://api.upbit.com/v1/candles/minutes/15"

EXCLUDED_NAME = re.compile(
    r"\b(warrant|warrants|right|rights|unit|units|preferred|depositary share|"
    r"contingent value|acquisition corp|blank check)\b",
    re.IGNORECASE,
)

STATIC_MULTI_ASSET: tuple[Instrument, ...] = (
    Instrument("SPY", "SPDR S&P 500 ETF", "US", "ETF", "USD", "static"),
    Instrument("QQQ", "Invesco Nasdaq 100 ETF", "US", "ETF", "USD", "static"),
    Instrument("IWM", "iShares Russell 2000 ETF", "US", "ETF", "USD", "static"),
    Instrument("DIA", "SPDR Dow Jones ETF", "US", "ETF", "USD", "static"),
    Instrument("ACWI", "iShares MSCI ACWI ETF", "GLOBAL", "ETF", "USD", "static"),
    Instrument("EFA", "iShares MSCI EAFE ETF", "GLOBAL", "ETF", "USD", "static"),
    Instrument("EEM", "iShares MSCI Emerging Markets ETF", "GLOBAL", "ETF", "USD", "static"),
    Instrument("EWY", "iShares MSCI South Korea ETF", "GLOBAL", "ETF", "USD", "static"),
    Instrument("KORU", "Direxion Korea Bull 3X", "GLOBAL", "ETF", "USD", "static"),
    Instrument("FXI", "iShares China Large-Cap ETF", "GLOBAL", "ETF", "USD", "static"),
    Instrument("INDA", "iShares MSCI India ETF", "GLOBAL", "ETF", "USD", "static"),
    Instrument("EWJ", "iShares MSCI Japan ETF", "GLOBAL", "ETF", "USD", "static"),
    Instrument("VGK", "Vanguard FTSE Europe ETF", "GLOBAL", "ETF", "USD", "static"),
    Instrument("XLK", "Technology Select Sector ETF", "US", "ETF", "USD", "static"),
    Instrument("SMH", "VanEck Semiconductor ETF", "US", "ETF", "USD", "static"),
    Instrument("SOXX", "iShares Semiconductor ETF", "US", "ETF", "USD", "static"),
    Instrument("XLF", "Financial Select Sector ETF", "US", "ETF", "USD", "static"),
    Instrument("XLE", "Energy Select Sector ETF", "US", "ETF", "USD", "static"),
    Instrument("XLI", "Industrial Select Sector ETF", "US", "ETF", "USD", "static"),
    Instrument("XLV", "Health Care Select Sector ETF", "US", "ETF", "USD", "static"),
    Instrument("XBI", "SPDR S&P Biotech ETF", "US", "ETF", "USD", "static"),
    Instrument("XLU", "Utilities Select Sector ETF", "US", "ETF", "USD", "static"),
    Instrument("XLP", "Consumer Staples Select Sector ETF", "US", "ETF", "USD", "static"),
    Instrument("XLY", "Consumer Discretionary Select Sector ETF", "US", "ETF", "USD", "static"),
    Instrument("XLC", "Communication Services Select Sector ETF", "US", "ETF", "USD", "static"),
    Instrument("XLRE", "Real Estate Select Sector ETF", "US", "ETF", "USD", "static"),
    Instrument("SH", "ProShares Short S&P 500", "US", "ETF", "USD", "static"),
    Instrument("PSQ", "ProShares Short QQQ", "US", "ETF", "USD", "static"),
    Instrument("RWM", "ProShares Short Russell 2000", "US", "ETF", "USD", "static"),
    Instrument("SQQQ", "ProShares UltraPro Short QQQ", "US", "ETF", "USD", "static"),
    Instrument("SOXS", "Direxion Semiconductor Bear 3X", "US", "ETF", "USD", "static"),
    Instrument("TLT", "iShares 20+ Year Treasury Bond ETF", "US", "BOND", "USD", "static"),
    Instrument("IEF", "iShares 7-10 Year Treasury Bond ETF", "US", "BOND", "USD", "static"),
    Instrument("HYG", "iShares High Yield Corporate Bond ETF", "US", "BOND", "USD", "static"),
    Instrument("LQD", "iShares Investment Grade Corporate Bond ETF", "US", "BOND", "USD", "static"),
    Instrument("GLD", "SPDR Gold Shares", "GLOBAL", "COMMODITY", "USD", "static"),
    Instrument("SLV", "iShares Silver Trust", "GLOBAL", "COMMODITY", "USD", "static"),
    Instrument("USO", "United States Oil Fund", "GLOBAL", "COMMODITY", "USD", "static"),
    Instrument("UNG", "United States Natural Gas Fund", "GLOBAL", "COMMODITY", "USD", "static"),
    Instrument("CPER", "United States Copper Index Fund", "GLOBAL", "COMMODITY", "USD", "static"),
    Instrument("DBA", "Invesco DB Agriculture Fund", "GLOBAL", "COMMODITY", "USD", "static"),
    Instrument("DBC", "Invesco DB Commodity Index", "GLOBAL", "COMMODITY", "USD", "static"),
    Instrument("UUP", "Invesco US Dollar Bullish Fund", "GLOBAL", "FX", "USD", "static"),
    Instrument("FXE", "Invesco CurrencyShares Euro Trust", "GLOBAL", "FX", "USD", "static"),
    Instrument("FXY", "Invesco CurrencyShares Japanese Yen Trust", "GLOBAL", "FX", "USD", "static"),
    Instrument("BTC-USD", "Bitcoin", "CRYPTO", "CRYPTO", "USD", "static"),
    Instrument("ETH-USD", "Ethereum", "CRYPTO", "CRYPTO", "USD", "static"),
    Instrument("SOL-USD", "Solana", "CRYPTO", "CRYPTO", "USD", "static"),
    Instrument("XRP-USD", "XRP", "CRYPTO", "CRYPTO", "USD", "static"),
)

KR_ETF_FALLBACK: tuple[Instrument, ...] = (
    Instrument("069500.KS", "KODEX 200", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("102110.KS", "TIGER 200", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("278530.KS", "KODEX 200TR", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("229200.KS", "KODEX 코스닥150", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("232080.KS", "TIGER 코스닥150", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("122630.KS", "KODEX 레버리지", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("114800.KS", "KODEX 인버스", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("252670.KS", "KODEX 200선물인버스2X", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("233740.KS", "KODEX 코스닥150레버리지", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("251340.KS", "KODEX 코스닥150선물인버스", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("091160.KS", "KODEX 반도체", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("091230.KS", "TIGER 반도체", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("305720.KS", "KODEX 2차전지산업", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("364980.KS", "TIGER 2차전지TOP10", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("466920.KS", "SOL 조선TOP3플러스", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("449450.KS", "PLUS K방산", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("139260.KS", "TIGER 200 IT", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("117700.KS", "KODEX 건설", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("117680.KS", "KODEX 철강", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("117460.KS", "KODEX 에너지화학", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("102970.KS", "KODEX 증권", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("091170.KS", "KODEX 은행", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("102780.KS", "KODEX 삼성그룹", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("381180.KS", "TIGER 미국필라델피아반도체나스닥", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("390390.KS", "KODEX 미국반도체MV", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("360750.KS", "TIGER 미국S&P500", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("133690.KS", "TIGER 미국나스닥100", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("379800.KS", "KODEX 미국S&P500", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("379810.KS", "KODEX 미국나스닥100", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("458730.KS", "TIGER 미국배당다우존스", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("261220.KS", "KODEX WTI원유선물(H)", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("132030.KS", "KODEX 골드선물(H)", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("273130.KS", "KODEX 종합채권(AA-이상)액티브", "KR", "KR_ETF", "KRW", "static:kr_etf"),
    Instrument("439870.KS", "KODEX 국고채30년액티브", "KR", "KR_ETF", "KRW", "static:kr_etf"),
)


def _http_text(url: str, timeout: int = 30) -> str:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "UnifiedTradingCoach/2.0", "Accept": "application/json,text/plain,*/*"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read().decode("utf-8", errors="replace")


def _http_json(url: str, timeout: int = 30) -> Any:
    return json.loads(_http_text(url, timeout=timeout))


def unique(rows: Iterable[Instrument]) -> list[Instrument]:
    by_ticker: dict[str, Instrument] = {}
    for item in rows:
        ticker = item.ticker.strip().upper()
        if ticker and ticker not in by_ticker:
            by_ticker[ticker] = Instrument(ticker, item.name.strip() or ticker, item.market, item.asset_class, item.currency, item.source)
    return list(by_ticker.values())


def _pipe_table(text: str) -> list[dict[str, str]]:
    lines = [line for line in text.splitlines() if line.strip() and not line.startswith("File Creation Time")]
    return list(csv.DictReader(io.StringIO("\n".join(lines)), delimiter="|")) if lines else []


def load_us_listed() -> list[Instrument]:
    output: list[Instrument] = []
    for url, symbol_key, exchange in ((NASDAQ_LISTED, "Symbol", "NASDAQ"), (OTHER_LISTED, "ACT Symbol", "NYSE_AMEX")):
        try:
            for row in _pipe_table(_http_text(url)):
                symbol = str(row.get(symbol_key) or "").strip().upper()
                name = str(row.get("Security Name") or "").strip()
                if not symbol or str(row.get("Test Issue") or "N").upper() == "Y" or EXCLUDED_NAME.search(name):
                    continue
                if any(char in symbol for char in ("^", "/", " ")):
                    continue
                is_etf = str(row.get("ETF") or "N").upper() == "Y"
                output.append(Instrument(symbol.replace(".", "-"), name or symbol, "US", "ETF" if is_etf else "US_STOCK", "USD", f"nasdaq_trader:{exchange}"))
        except Exception as exc:
            print(f"US listing warning {url}: {exc}")
    return unique(output)


def _recent_krx_dates(limit: int = 8) -> list[str]:
    today = dt.datetime.now(ZoneInfo("Asia/Seoul")).date()
    output: list[str] = []
    offset = 1
    while len(output) < limit:
        candidate = today - dt.timedelta(days=offset)
        offset += 1
        if candidate.weekday() < 5:
            output.append(candidate.strftime("%Y%m%d"))
    return output


def load_kr_listed() -> list[Instrument]:
    try:
        from pykrx import stock
    except Exception as exc:
        print(f"pykrx unavailable: {exc}")
        return []
    output: list[Instrument] = []
    for market, suffix in (("KOSPI", ".KS"), ("KOSDAQ", ".KQ")):
        for business_date in _recent_krx_dates():
            try:
                codes = list(stock.get_market_ticker_list(business_date, market=market))
                if not codes:
                    continue
                for code in codes:
                    code = str(code).zfill(6)
                    output.append(
                        Instrument(
                            f"{code}{suffix}",
                            stock.get_market_ticker_name(code) or code,
                            "KR",
                            "KR_STOCK",
                            "KRW",
                            f"pykrx:{market}:{business_date}",
                        )
                    )
                break
            except Exception as exc:
                print(f"KR listing warning {market} {business_date}: {exc}")
    return unique(output)


def load_kr_etfs() -> list[Instrument]:
    output: list[Instrument] = []
    try:
        from pykrx import stock
    except Exception as exc:
        print(f"pykrx ETF unavailable; using core fallback: {exc}")
        return list(KR_ETF_FALLBACK)
    for business_date in _recent_krx_dates():
        try:
            codes = list(stock.get_etf_ticker_list(business_date))
            if not codes:
                continue
            for code in codes:
                code = str(code).zfill(6)
                output.append(
                    Instrument(
                        f"{code}.KS",
                        stock.get_etf_ticker_name(code) or code,
                        "KR",
                        "KR_ETF",
                        "KRW",
                        f"pykrx:ETF:{business_date}",
                    )
                )
            break
        except Exception as exc:
            print(f"KR ETF listing warning {business_date}: {exc}")
    if not output:
        print("KR ETF listing returned no rows; using core fallback.")
    return unique([*output, *KR_ETF_FALLBACK])


def load_upbit_top(limit: int = 40) -> list[Instrument]:
    try:
        markets = [row for row in _http_json(UPBIT_MARKETS) if str(row.get("market", "")).startswith("KRW-")]
        names = {row["market"]: row.get("korean_name") or row.get("english_name") or row["market"] for row in markets}
        codes = list(names)
        tickers: list[dict] = []
        for offset in range(0, len(codes), 100):
            query = urllib.parse.urlencode({"markets": ",".join(codes[offset:offset + 100])})
            tickers.extend(_http_json(f"{UPBIT_TICKER}?{query}"))
            time.sleep(0.12)
        top = sorted(tickers, key=lambda row: float(row.get("acc_trade_price_24h") or 0), reverse=True)[:limit]
        return [Instrument(str(row["market"]), str(names.get(row["market"]) or row["market"]), "CRYPTO", "CRYPTO", "KRW", "upbit") for row in top]
    except Exception as exc:
        print(f"Upbit universe warning: {exc}")
        return []


def _extract_frame(downloaded: pd.DataFrame, ticker: str, single: bool) -> pd.DataFrame:
    if downloaded is None or downloaded.empty:
        return pd.DataFrame()
    frame = downloaded
    if isinstance(downloaded.columns, pd.MultiIndex):
        level0 = set(map(str, downloaded.columns.get_level_values(0)))
        level1 = set(map(str, downloaded.columns.get_level_values(1)))
        if ticker in level0:
            frame = downloaded[ticker]
        elif ticker in level1:
            frame = downloaded.xs(ticker, level=1, axis=1)
        else:
            return pd.DataFrame()
    elif not single:
        return pd.DataFrame()
    frame = frame.copy()
    frame.columns = [str(column).strip().lower().replace(" ", "_") for column in frame.columns]
    needed = ["open", "high", "low", "close", "volume"]
    if any(column not in frame.columns for column in needed):
        return pd.DataFrame()
    frame = frame[needed].apply(pd.to_numeric, errors="coerce").dropna(subset=["close"])
    frame.index = pd.to_datetime(frame.index, utc=True, errors="coerce")
    return frame[~frame.index.isna()].sort_index()


def download_yahoo(instruments: list[Instrument], *, period: str, interval: str, batch_size: int) -> dict[str, pd.DataFrame]:
    import yfinance as yf

    frames: dict[str, pd.DataFrame] = {}
    tickers_all = [item.ticker for item in instruments if not item.ticker.startswith("KRW-")]
    for start in range(0, len(tickers_all), batch_size):
        tickers = tickers_all[start:start + batch_size]
        if not tickers:
            continue
        try:
            data = yf.download(
                tickers=tickers,
                period=period,
                interval=interval,
                auto_adjust=True,
                prepost=interval.endswith(("m", "h")),
                group_by="ticker",
                threads=True,
                progress=False,
                timeout=30,
            )
            for ticker in tickers:
                frame = _extract_frame(data, ticker, single=len(tickers) == 1)
                if not frame.empty:
                    frames[ticker] = frame
        except Exception as exc:
            print(f"Yahoo batch warning {tickers[:2]}: {exc}")
        time.sleep(0.15)
    return frames


def _upbit_candle_rows(instrument: Instrument, *, intraday: bool) -> list[dict[str, Any]]:
    endpoint = UPBIT_MINUTE15 if intraday else UPBIT_DAY
    target = 200 if intraday else 320
    rows: list[dict[str, Any]] = []
    to_value: str | None = None
    while len(rows) < target:
        params: dict[str, Any] = {"market": instrument.ticker, "count": min(200, target - len(rows))}
        if to_value:
            params["to"] = to_value
        batch = _http_json(f"{endpoint}?{urllib.parse.urlencode(params)}")
        if not isinstance(batch, list) or not batch:
            break
        rows.extend(batch)
        oldest = batch[-1]
        utc_text = str(oldest.get("candle_date_time_utc") or "")
        if not utc_text or len(batch) < int(params["count"]):
            break
        to_value = f"{utc_text}Z"
        time.sleep(0.12)
    by_timestamp = {int(row.get("timestamp") or 0): row for row in rows if row.get("timestamp")}
    return [by_timestamp[key] for key in sorted(by_timestamp, reverse=True)]


def upbit_history(instrument: Instrument, *, intraday: bool) -> pd.DataFrame:
    rows = _upbit_candle_rows(instrument, intraday=intraday)
    frame = pd.DataFrame(
        {
            "timestamp": [row.get("timestamp") for row in rows],
            "open": [row.get("opening_price") for row in rows],
            "high": [row.get("high_price") for row in rows],
            "low": [row.get("low_price") for row in rows],
            "close": [row.get("trade_price") for row in rows],
            "volume": [row.get("candle_acc_trade_volume") for row in rows],
        }
    )
    if frame.empty:
        return frame
    frame["timestamp"] = pd.to_datetime(frame["timestamp"], unit="ms", utc=True)
    return frame.set_index("timestamp").sort_index().apply(pd.to_numeric, errors="coerce").dropna(subset=["close"])
