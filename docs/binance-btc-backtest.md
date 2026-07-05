# Integrated Binance BTC backtest

`shopper12/backtest`의 BTC/Binance 연구용 백테스트 기능은 이제 이 앱 레포(`shopper12/gpt_coin_android`) 안에서 실행합니다.

## 위치

- 실행 파일: `tools/binance_btc_backtest.py`
- GitHub Actions: `.github/workflows/binance-btc-backtest.yml`
- 산출물:
  - `runtime_data/binance_klines.csv`
  - `reports/binance_btc_results.csv`
  - `reports/binance_btc_best.json`
  - `reports/binance_btc_best_trades.csv`
  - `reports/binance_btc_backtest_latest.md`

## GitHub Actions 실행

1. `gpt_coin_android` 레포의 **Actions**로 이동
2. **Binance BTC backtest** 선택
3. **Run workflow** 실행
4. 필요 시 입력값 조정

기본값:

- `symbol`: `BTCUSDT`
- `market`: `futures`
- `interval`: `5m`
- `start`: `2025-06-22`
- `end`: 비우면 오늘 UTC 날짜

## 로컬 실행

```bash
python -m pip install "numpy>=1.26" "pandas>=2.2"
python tools/binance_btc_backtest.py \
  --symbol BTCUSDT \
  --market futures \
  --interval 5m \
  --start 2025-06-22 \
  --end 2026-07-05 \
  --data runtime_data/binance_klines.csv \
  --output-dir reports
```

이미 다운로드한 kline CSV가 있으면 다음처럼 다운로드를 생략할 수 있습니다.

```bash
python tools/binance_btc_backtest.py \
  --skip-download \
  --data runtime_data/binance_klines.csv \
  --output-dir reports
```

## 통합 원칙

- Android 앱·Upbit 실시간 전략·Binance BTC 연구 백테스트를 한 레포에서 관리합니다.
- `backtest` 원본 레포는 더 이상 자동 실행 소스가 아닙니다.
- 중복 Actions를 막기 위해 Binance BTC 백테스트는 수동 실행 전용입니다.
- 산출물은 GitHub artifact로 받습니다. 리포트 파일을 자동 커밋하지 않습니다.

## 주의

이 백테스트는 bar 기반 연구 도구입니다. tick 정확도, 체결 대기열, 슬리피지 급변, 펀딩비, 실거래 실패 등을 완전히 반영하지 않습니다. 실매매 신호로 직접 사용하지 말고 전략 후보 검증용으로만 사용해야 합니다.
