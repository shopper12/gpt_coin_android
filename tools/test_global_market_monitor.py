from __future__ import annotations

import sys
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

from global_market_monitor import (  # noqa: E402
    FAST_INTRADAY_LIMIT,
    MAX_SIGNALS,
    can_emit_signal,
    select_daily_candidates,
    select_final_signals,
    select_intraday_candidates,
)
from global_market_strategy import intraday_trigger  # noqa: E402
from global_market_universe import KR_ETF_FALLBACK  # noqa: E402


class KoreanEtfMonitoringTest(unittest.TestCase):
    def test_core_korean_etf_universe_is_never_empty(self) -> None:
        self.assertGreaterEqual(len(KR_ETF_FALLBACK), 20)
        self.assertTrue(all(item.market == "KR" for item in KR_ETF_FALLBACK))
        self.assertTrue(all(item.asset_class == "KR_ETF" for item in KR_ETF_FALLBACK))
        self.assertTrue(all(item.ticker.endswith(".KS") for item in KR_ETF_FALLBACK))

    def test_korean_etfs_receive_intraday_slots_even_when_ranked_after_us_rows(self) -> None:
        us_rows = [
            {"ticker": f"US{index:03d}", "assetClass": "US_STOCK", "dailyScore": 100 - index / 10}
            for index in range(120)
        ]
        kr_rows = [
            {"ticker": f"{index:06d}.KS", "assetClass": "KR_ETF", "dailyScore": 75 - index / 10}
            for index in range(10)
        ]
        selected = select_intraday_candidates([*us_rows, *kr_rows])
        self.assertEqual(len(selected), FAST_INTRADAY_LIMIT)
        self.assertEqual(
            {row["ticker"] for row in kr_rows},
            {row["ticker"] for row in selected if row["assetClass"] == "KR_ETF"},
        )

    def test_valid_korean_etf_signals_are_not_pushed_out_by_us_signals(self) -> None:
        us_signals = [
            {
                "id": f"us-{index}",
                "ticker": f"US{index:03d}",
                "assetClass": "US_STOCK",
                "score": 100 - index,
                "confidence": 9.0,
            }
            for index in range(20)
        ]
        kr_signals = [
            {
                "id": f"kr-{index}",
                "ticker": f"{index:06d}.KS",
                "assetClass": "KR_ETF",
                "score": 75 - index,
                "confidence": 7.5,
            }
            for index in range(3)
        ]
        selected = select_final_signals([*us_signals, *kr_signals])
        self.assertEqual(len(selected), MAX_SIGNALS)
        self.assertEqual(
            {row["id"] for row in kr_signals},
            {row["id"] for row in selected if row["assetClass"] == "KR_ETF"},
        )

    def test_korean_etf_is_observed_without_bypassing_daily_signal_guard(self) -> None:
        metrics = [
            {
                "ticker": "069500.KS",
                "assetClass": "KR_ETF",
                "currentPrice": 10_000,
                "ma200": 12_000,
                "ret1m": -0.05,
                "ret3m": -0.10,
                "ret6m": -0.20,
                "ret12m": 0.10,
                "dailyScore": 10,
                "relativeStrengthPercentile": 0.70,
                "avgDollarValue": 1_000_000_000,
            }
        ]
        selected = select_daily_candidates(metrics)
        self.assertEqual([row["ticker"] for row in selected], ["069500.KS"])
        self.assertFalse(selected[0]["dailySignalEligible"])
        self.assertFalse(can_emit_signal(selected[0], {"ready": True}, fresh=True))

    def test_korean_etf_uses_its_documented_volume_threshold(self) -> None:
        index = pd.date_range("2026-07-28 00:00:00+00:00", periods=80, freq="15min")
        close = np.linspace(10_000.0, 10_800.0, len(index))
        frame = pd.DataFrame(
            {
                "open": close - 10,
                "high": close + 20,
                "low": close - 20,
                "close": close,
                "volume": [1_000.0] * 79 + [1_250.0],
            },
            index=index,
        )
        self.assertEqual(intraday_trigger(frame, asset_class="KR_ETF")["volumeThreshold"], 1.20)
        self.assertEqual(intraday_trigger(frame, asset_class="US_STOCK")["volumeThreshold"], 1.35)


if __name__ == "__main__":
    unittest.main()
