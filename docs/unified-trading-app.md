# Unified trading app architecture

`gpt_coin_android`가 최종 단일 모바일 앱 레포입니다. 코인과 주식 기능은 같은 APK 안에 넣되, 메뉴·룰·백테스트 산출물은 분리합니다.

| 기존 레포 | 통합 후 역할 |
|---|---|
| `gpt_coin_android` | 최종 단일 Android 앱, 코인 메뉴, 주식 메뉴, 코인 룰, 주식 룰, 통합 GitHub Actions |
| `stock_scanner` | 주식 스캐너/백테스트 엔진의 원본 소스. 별도 APK는 중단하고 앱 화면은 `gpt_coin_android`에서 제공합니다. |
| `backtest` | BTC/Binance 연구 백테스트의 역사 참조. 실행 기능은 `tools/binance_btc_backtest.py`와 `Binance BTC backtest` workflow로 이관 완료 |

## 모바일 앱 메뉴

런처는 `HomeActivity`입니다.

- 코인 메뉴: `MainActivity`
- 주식 메뉴: `StockActivity`

앱 첫 화면에서 코인 전략과 주식 전략을 분리해서 들어갑니다. 두 메뉴는 같은 APK 안에 있지만 상태와 룰을 섞지 않습니다.

## 코인 전략 루프

기준 파일:

- `rules/strategy-rules.json`

검증 루프:

1. `tools/backtest_upbit_strategies.py`로 Upbit KRW 전략 백테스트
2. `tools/analyze_missed_pumps.py`로 놓친 급등 감시
3. `tools/auto_tune_rules.py`로 후보 룰 생성
4. guard가 허용한 경우에만 `rules/strategy-rules.json` 갱신

앱 화면:

- `MainActivity`
- ACTIVE 코인 신호
- 수동 코인 분석
- 성과/백테스트/진화 로그

## 주식 전략 루프

기준 파일:

- `rules/stock-strategy-rules.json`

검증 루프:

1. `stock_scanner`를 GitHub Actions 작업공간에 체크아웃
2. `rules/stock-strategy-rules.json`을 `stock_scanner/rules/kr_short_rules.json`로 주입
3. `scan_once.py`로 한국 단기 주식, 퇴직연금 ETF, 미국 장기 ETF, 환전 판단 스캔
4. `tools/kr_short_check.py --apply`로 KR_SHORT 룰 백테스트/진화
5. 갱신된 `stock_scanner/rules/kr_short_rules.json`을 다시 `rules/stock-strategy-rules.json`로 복사
6. 주식 산출물을 `reports/stock/` 아래에 저장

앱 화면:

- `StockActivity`
- `stock_scanner` Render API의 최신 한국 단기 후보 표시
- 추천 성과 표시
- 주식 백테스트 요약 표시
- 단일 종목 직접 검색 전략 표시

## BTC/Binance 연구 백테스트

`backtest` 레포의 기능은 앱 레포로 이관되었습니다.

- 실행 파일: `tools/binance_btc_backtest.py`
- Workflow: `.github/workflows/binance-btc-backtest.yml`
- 문서: `docs/binance-btc-backtest.md`

이 기능은 코인 실시간 스캐너와 별개입니다. BTC 5분봉/선물 데이터 기반 연구 백테스트이며, 전략 후보 검증용입니다.

## 통합 모니터

Workflow:

- `.github/workflows/unified-strategy-monitor.yml`

실행 방식:

- 수동 실행 가능
- 매일 06:00 KST 자동 실행

산출물:

- `reports/unified_strategy_monitor_latest.json`
- `reports/coin/**`
- `reports/stock/**`
- `rules/strategy-rules.json`
- `rules/stock-strategy-rules.json`

별도 BTC 연구 백테스트 산출물:

- `reports/binance_btc_results.csv`
- `reports/binance_btc_best.json`
- `reports/binance_btc_best_trades.csv`
- `reports/binance_btc_backtest_latest.md`

## 설계 원칙

- 앱은 하나: `gpt_coin_android`
- 메뉴는 둘: 코인/주식
- 룰은 둘: 코인 룰/주식 룰
- 검증 루프는 분리: 코인 검증, 주식 검증, BTC 연구 검증
- 룰 갱신은 guard 통과 시에만 허용
- 수익률 극대화는 단순 최고 수익률이 아니라, 거래 수·승률·PF·손절률·누락 급등 감사를 통과한 범위에서만 수행

## 현재 상태

완료:

- 단일 앱 런처 `HomeActivity`
- 코인 메뉴 `MainActivity`
- 주식 메뉴 `StockActivity`
- 주식 기본 룰 `rules/stock-strategy-rules.json`
- 코인/주식 통합 자가검증 workflow
- BTC/Binance 연구 백테스트 이관
- `backtest` 레포 workflow 퇴역

주의:

- `stock_scanner`의 Python 엔진은 아직 주식 데이터 수집/백테스트 실행 소스로 사용합니다.
- 별도 주식 APK는 만들지 않고, 모바일 진입점은 `gpt_coin_android`의 주식 메뉴입니다.
- 실제 주문 자동화는 포함하지 않습니다. 모든 결과는 후보·검증·알림용입니다.
