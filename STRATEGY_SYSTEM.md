# Strategy Validation System

The app stores scanner output in Room instead of keeping strategy records only in memory. `CoinScannerService` still scans Upbit KRW markets every 30 seconds, but each scan also persists price snapshots and runs strategy lifecycle analysis.

## Current Review Structure

- `SignalEngine` creates candidate strategy signals.
- `SignalHistoryRepository` persists scan data, records lifecycle events, detects missed signals, creates strategy reviews, and stores guideline change suggestions.
- `ScannerStateStore` exposes the latest DB-backed state to the Compose UI.
- Guideline suggestions are stored for review only. They are not automatically applied to `SignalEngine`.

## History Recording Conditions

`SignalHistoryEntity` is event based, not scan based. The app records:

- `INITIAL_SIGNAL` when a new market strategy first appears.
- `PRICE_MOVED_5_PERCENT` when current price moves at least +/-5% from the baseline price.
- `STOP_HIT` when price reaches or falls below stop loss.
- `TARGET_HIT` when price reaches or exceeds target.
- `STRATEGY_CHANGED` when a market changes strategy name.
- `EXPIRED` when no target or stop occurs within 6 hours.
- `INVALIDATED` is reserved for future rule-based invalidation.

Cooldown checks reduce repeated writes for the same market and event type.

## Missed Signal Criteria

A missed signal is stored when one of these conditions is met and the market had no active signal or `INITIAL_SIGNAL` in the previous 30 minutes:

- Top 10 by signed change rate.
- At least +3% jump from the previous snapshot.
- At least +5% rise within 30 minutes.
- Trade value rank becomes high while the market is absent from current top signals.

Missed reasons are classified into trade-value filtering, change-rate mismatch, low volume score, sideways breakout miss, reversal miss, low score, or unknown.

## Guideline Suggestions

`GuidelineChangeEntity.applied` defaults to `false`. This release only records proposed before/after rules with evidence. A future approval flow can read unapplied suggestions and deliberately map accepted changes into `SignalEngine`.

## Room Tables

- `signal_history`
- `price_snapshots`
- `missed_signals`
- `strategy_reviews`
- `guideline_changes`

Price snapshots are retained for the latest 24 hours and capped to the latest 5,000 rows.

## Next Improvements

- Add a user approval screen that can mark guideline suggestions as applied.
- Add richer strategy versioning when approved rules are implemented.
- Track realized returns after target, stop, and expiry with longer windows.
- Add database migrations instead of destructive migration during schema evolution.
- Add automated repository tests for missed-signal and review aggregation rules.
