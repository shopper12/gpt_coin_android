from __future__ import annotations

import csv
import io
import json
import re
import time
import urllib.parse
import urllib.request
from typing import Any, Iterable

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


def load_kr_listed() -> list[Instrument]:
    try:
        from pykrx import stock
    except Exception as exc:
        print(f"pykrx unavailable: {exc}")
        return []
    output: list[Instrument] = []
    for market, suffix in (("KOSPI", ".KS"), ("KOSDAQ", ".KQ")):
        try:
            for code in stock.get_market_ticker_list(market=market):
                code = str(code).zfill(6)
                output.append(Instrument(f"{code}{suffix}", stock.get_market_ticker_name(code) or code, "KR", "KR_STOCK", "KRW", f"pykrx:{market}"))
        except Exception as exc:
            print(f"KR listing warning {market}: {exc}")
    return unique(output)


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
            data = yf.download(tickers=tickers, period=period, interval=interval, auto_adjust=True, group_by="ticker", threads=True, progress=False, timeout=30)
            for ticker in tickers:
                frame = _extract_frame(data, ticker, single=len(tickers) == 1)
                if not frame.empty:
                    frames[ticker] = frame
        except Exception as exc:
            print(f"Yahoo batch warning {tickers[:2]}: {exc}")
        time.sleep(0.15)
    return frames


def upbit_history(instrument: Instrument, *, intraday: bool) -> pd.DataFrame:
    endpoint = UPBIT_MINUTE15 if intraday else UPBIT_DAY
    query = urllib.parse.urlencode({"market": instrument.ticker, "count": 200})
    rows = _http_json(f"{endpoint}?{query}")
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
    frame["timestamp"] = pd.to_datetime(frame["timestamp"], unit="ms", utc=True)
    return frame.set_index("timestamp").sort_index().apply(pd.to_numeric, errors="coerce").dropna(subset=["close"])
