# Unified trading app architecture

`gpt_coin_android`가 단일 모바일 앱 레포입니다. 기존 역할은 아래처럼 합칩니다.

| 기존 레포 | 통합 후 역할 |
|---|---|
| `gpt_coin_android` | 모바일 앱, 코인 메뉴, 코인 실시간 스캐너, 코인 룰, 통합 GitHub Actions |
| `stock_scanner` | 주식 전략 소스의 역사 참조. 통합 모니터가 체크아웃해서 실행하고 산출물은 이 레포로 복사 |
| `backtest` | BTC/Binance 연구 백테스트의 역사 참조. 실행 기능은 `tools/binance_btc_backtest.py`로 이관 완료 |

## 모바일 앱 메뉴

새 런처는 `HomeActivity`입니다.

- 코인 메뉴: 기존 `MainActivity`
- 주식 메뉴: 새 `StockActivity`

앱 첫 화면에서 코인 전략과 주식 전략을 분리해서 들어갑니다.

## 코인 전략 루프

기준 파일:

- `rules/strategy-rules.json`

검증 루프:

1. `tools/backtest_upbit_strategies.py`로 Upbit KRW 전략 백테스트
2. `tools/analyze_missed_pumps.py`로 놓친 급등 감시
3. `tools/auto_tune_rules.py`로 후보 룰 생성
4. guard가 허용한 경우에만 `rules/strategy-rules.json` 갱신

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

## 설계 원칙

- 앱은 하나: `gpt_coin_android`
- 메뉴는 둘: 코인/주식
- 룰은 둘: 코인 룰/주식 룰
- 검증 루프는 하나: Unified strategy monitor
- 무조건 수익률을 올리는 방식이 아니라, 백테스트 guard를 통과한 룰만 반영

## 남은 고도화

현재 주식 메뉴는 전략 상태와 통합 모니터 진입점을 보여주는 메뉴입니다. 다음 단계는 `reports/unified_strategy_monitor_latest.json`를 앱에서 직접 읽어 최신 주식 추천 종목, 진입가, 손절가, 목표가, 최근 백테스트 성과를 카드로 렌더링하는 것입니다.
