#!/usr/bin/env python3
from __future__ import annotations

import argparse
import copy
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

DEFAULT_CANDIDATE_SELECTION = {
    "maxCandleTargets": 35,
    "topTradeValueCount": 40,
    "topChangeRateCount": 15,
    "volumeBuildupCount": 15,
    "quietAccumulationCount": 10,
    "medianTradeValueMultiplier": 1.5,
    "minBuildupChangeRatePct": -2.0,
    "maxBuildupChangeRatePct": 5.0,
    "maxQuietAbsChangeRatePct": 1.5,
}

DEFAULT_PRE_PUMP = {
    "enabled": True,
    "minChange24hPct": -4.0,
    "maxChange24hPct": 8.5,
    "maxChange30mPct": 3.2,
    "maxChange5mPct": 1.8,
    "maxTradeValueRank": 25,
    "maxChangeRank": 35,
    "minRotation30mPct": 0.7,
    "minVolumeAcceleration": 1.45,
    "minFiveMinuteVolumeRatio": 1.6,
    "minFifteenMinuteVolumeRatio": 1.35,
    "maxRangePct": 4.2,
    "minRangePosition": 0.55,
    "minHighProximityMultiplier": 0.992,
    "minCloseStairCount": 2,
}


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def load_json_optional(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return load_json(path)


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def num(row: dict[str, Any], key: str, default: float = 0.0) -> float:
    try:
        return float(row.get(key, default) or default)
    except (TypeError, ValueError):
        return default


def integer(row: dict[str, Any], key: str, default: int = 0) -> int:
    try:
        return int(row.get(key, default) or default)
    except (TypeError, ValueError):
        return default


def clamp(x: float, low: float, high: float) -> float:
    return max(low, min(high, x))


def setv(root: dict[str, Any], path: str, value: Any, why: str, changes: list[str]) -> None:
    cur = root
    parts = path.split(".")
    for part in parts[:-1]:
        cur = cur.setdefault(part, {})
    old = cur.get(parts[-1])
    if old != value:
        cur[parts[-1]] = value
        changes.append(f"- `{path}`: `{old}` -> `{value}` / {why}")


def getv(root: dict[str, Any], path: str, default: Any) -> Any:
    cur: Any = root
    for part in path.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return default
        cur = cur[part]
    return cur


def ensure_defaults(root: dict[str, Any]) -> None:
    candidate = root.setdefault("candidateSelection", {})
    if isinstance(candidate, dict):
        for key, value in DEFAULT_CANDIDATE_SELECTION.items():
            candidate.setdefault(key, value)
    pre = root.setdefault("prePumpRotation", {})
    if isinstance(pre, dict):
        for key, value in DEFAULT_PRE_PUMP.items():
            pre.setdefault(key, value)


def weighted_metrics(report: dict[str, Any]) -> tuple[int, float, float, float]:
    summaries: dict[str, Any] = report.get("strategy_summary", {}) or {}
    rows = [r for r in summaries.values() if isinstance(r, dict) and integer(r, "signals") > 0]
    signals = sum(integer(r, "signals") for r in rows)
    if not signals:
        return 0, 0.0, 0.0, 0.0
    avg_ret = sum(num(r, "avg_return_pct") * integer(r, "signals") for r in rows) / signals
    stop_rate = sum(num(r, "stop_hit_rate_pct") * integer(r, "signals") for r in rows) / signals
    t1_rate = sum(num(r, "target1_hit_rate_pct") * integer(r, "signals") for r in rows) / signals
    return signals, avg_ret, stop_rate, t1_rate


def tune_from_backtest(proposed: dict[str, Any], report: dict[str, Any], changes: list[str], notes: list[str]) -> tuple[float, float, float, int]:
    summaries: dict[str, Any] = report.get("strategy_summary", {}) or {}
    total = integer(report, "total_signals", 0)
    signals, avg_ret, stop_rate, t1_rate = weighted_metrics(report)
    notes.append(f"total_signals={total}")
    notes.append(f"weighted_avg_return={avg_ret:+.2f}%")
    notes.append(f"weighted_t1_hit={t1_rate:.1f}%")
    notes.append(f"weighted_stop_hit={stop_rate:.1f}%")

    min_score = num(proposed, "minimumScore", 68.0)
    max_results = integer(proposed, "maxResults", 4)

    if total == 0:
        setv(proposed, "minimumScore", round(clamp(min_score - 4.0, 58.0, 82.0), 1), "no signals; loosen score gate", changes)
        setv(proposed, "maxResults", min(max_results + 1, 8), "no signals; show more candidates", changes)
        setv(proposed, "candidateSelection.maxCandleTargets", min(integer(getv(proposed, "candidateSelection", {}), "maxCandleTargets", 35) + 10, 80), "no signals; scan wider market pool", changes)
        setv(proposed, "candidateSelection.topTradeValueCount", min(integer(getv(proposed, "candidateSelection", {}), "topTradeValueCount", 40) + 10, 100), "no signals; widen liquid universe", changes)
        setv(proposed, "candidateSelection.topChangeRateCount", min(integer(getv(proposed, "candidateSelection", {}), "topChangeRateCount", 15) + 5, 80), "no signals; widen rotation universe", changes)
        setv(proposed, "candidateSelection.volumeBuildupCount", min(integer(getv(proposed, "candidateSelection", {}), "volumeBuildupCount", 15) + 5, 80), "no signals; include more buildup candidates", changes)
        setv(proposed, "compressionBreakout.minVolumeAcceleration", round(clamp(num(getv(proposed, "compressionBreakout", {}), "minVolumeAcceleration", 1.35) - 0.08, 1.05, 2.20), 2), "no signals; loosen compression volume gate", changes)
        setv(proposed, "prePumpRotation.minVolumeAcceleration", round(clamp(num(getv(proposed, "prePumpRotation", {}), "minVolumeAcceleration", 1.45) - 0.08, 1.05, 2.20), 2), "no signals; loosen pre-pump volume gate", changes)
        setv(proposed, "prePumpRotation.maxTradeValueRank", min(integer(getv(proposed, "prePumpRotation", {}), "maxTradeValueRank", 25) + 5, 60), "no signals; widen pre-pump liquidity rank", changes)
    elif signals < 12:
        setv(proposed, "minimumScore", round(clamp(min_score - 2.0, 58.0, 82.0), 1), "too few signals; loosen cautiously", changes)
        setv(proposed, "maxResults", min(max_results + 1, 8), "too few signals; keep more candidates", changes)
        setv(proposed, "candidateSelection.maxCandleTargets", min(integer(getv(proposed, "candidateSelection", {}), "maxCandleTargets", 35) + 5, 80), "too few signals; scan wider market pool", changes)
        setv(proposed, "candidateSelection.topChangeRateCount", min(integer(getv(proposed, "candidateSelection", {}), "topChangeRateCount", 15) + 3, 80), "too few signals; widen rotation universe", changes)
        setv(proposed, "prePumpRotation.maxChangeRank", min(integer(getv(proposed, "prePumpRotation", {}), "maxChangeRank", 35) + 3, 70), "too few signals; widen relative-strength rank", changes)
    elif stop_rate > 42.0 or avg_ret < -0.10:
        setv(proposed, "minimumScore", round(clamp(min_score + 2.0, 58.0, 82.0), 1), "weak result; tighten score gate", changes)
        setv(proposed, "scoring.overheat5mWeight", round(clamp(num(getv(proposed, "scoring", {}), "overheat5mWeight", 8.0) + 0.6, 4.0, 14.0), 2), "reduce late 5m chase", changes)
        setv(proposed, "scoring.hardBlock5mPumpPct", round(clamp(num(getv(proposed, "scoring", {}), "hardBlock5mPumpPct", 2.2) - 0.1, 1.4, 3.0), 2), "block stronger 5m spike", changes)
        setv(proposed, "compressionBreakout.minFiveMinuteVolumeRatio", round(clamp(num(getv(proposed, "compressionBreakout", {}), "minFiveMinuteVolumeRatio", 1.2) + 0.05, 1.05, 1.8), 2), "require cleaner volume", changes)
        setv(proposed, "prePumpRotation.maxChange5mPct", round(clamp(num(getv(proposed, "prePumpRotation", {}), "maxChange5mPct", 1.8) - 0.1, 0.8, 3.0), 2), "weak result; avoid late pre-pump entries", changes)
    elif avg_ret > 0.18 and t1_rate > 35.0 and stop_rate < 32.0:
        setv(proposed, "minimumScore", round(clamp(min_score - 1.0, 58.0, 82.0), 1), "good result; loosen slightly", changes)
        setv(proposed, "maxResults", min(max_results + 1, 8), "good result; expose more candidates", changes)
        setv(proposed, "candidateSelection.volumeBuildupCount", min(integer(getv(proposed, "candidateSelection", {}), "volumeBuildupCount", 15) + 2, 80), "good result; include more buildup candidates", changes)

    comp = summaries.get("COMPRESSION_BREAKOUT", {}) or {}
    if integer(comp, "signals") >= 8 and (num(comp, "stop_hit_rate_pct") > 42.0 or num(comp, "avg_return_pct") < -0.10):
        setv(proposed, "compressionBreakout.rangeCompressionRatio", round(clamp(num(getv(proposed, "compressionBreakout", {}), "rangeCompressionRatio", 0.8) - 0.03, 0.55, 0.90), 2), "compression failed; require tighter range", changes)
        setv(proposed, "compressionBreakout.minVolumeAcceleration", round(clamp(num(getv(proposed, "compressionBreakout", {}), "minVolumeAcceleration", 1.35) + 0.05, 1.05, 2.20), 2), "compression failed; require stronger volume", changes)

    pre = summaries.get("PRE_PUMP_ROTATION", {}) or {}
    if integer(pre, "signals") >= 8 and (num(pre, "stop_hit_rate_pct") > 42.0 or num(pre, "avg_return_pct") < -0.10):
        setv(proposed, "prePumpRotation.maxChange30mPct", round(clamp(num(getv(proposed, "prePumpRotation", {}), "maxChange30mPct", 3.2) - 0.15, 1.5, 5.0), 2), "pre-pump failed; avoid 30m chase", changes)
        setv(proposed, "prePumpRotation.maxRangePct", round(clamp(num(getv(proposed, "prePumpRotation", {}), "maxRangePct", 4.2) - 0.15, 2.0, 6.0), 2), "pre-pump failed; require tighter setup", changes)
        setv(proposed, "prePumpRotation.minRangePosition", round(clamp(num(getv(proposed, "prePumpRotation", {}), "minRangePosition", 0.55) + 0.02, 0.35, 0.80), 2), "pre-pump failed; require better box position", changes)

    sweep = summaries.get("SWEEP_RECLAIM", {}) or {}
    if integer(sweep, "signals") >= 8 and (num(sweep, "stop_hit_rate_pct") > 42.0 or num(sweep, "avg_return_pct") < -0.10):
        setv(proposed, "sweepReclaim.fiveMinuteLookback", min(integer(getv(proposed, "sweepReclaim", {}), "fiveMinuteLookback", 12) + 2, 20), "sweep failed; use wider prior-low window", changes)
        setv(proposed, "sweepReclaim.requireVolumeAboveAverage", True, "sweep failed; keep volume confirmation", changes)
    return avg_ret, stop_rate, t1_rate, signals


def tune_from_missed(proposed: dict[str, Any], missed: dict[str, Any], avg_ret: float, stop_rate: float, changes: list[str], notes: list[str]) -> None:
    missed_count = integer(missed, "missed_pump_count", 0)
    reason_counts: dict[str, Any] = missed.get("reason_counts", {}) or {}
    notes.append(f"missed_pump_count={missed_count}")
    if reason_counts:
        top_reason = max(reason_counts.items(), key=lambda item: int(item[1] or 0))[0]
        notes.append("missed_top_reason=" + top_reason)
    else:
        return

    if missed_count < 5:
        notes.append("missed_pump_adjustment=skipped_low_sample")
        return

    weak_live_profile = stop_rate > 42.0 or avg_ret < -0.10
    if weak_live_profile:
        notes.append("missed_pump_adjustment=restricted_because_backtest_is_weak")

    candidate_pool = int(reason_counts.get("CANDIDATE_POOL_TOO_NARROW", 0) or 0)
    low_volume = int(reason_counts.get("VOLUME_IGNITION_TOO_LATE_OR_LOW", 0) or 0)
    late_move = int(reason_counts.get("ALREADY_MOVING_BEFORE_SIGNAL", 0) or 0)
    low_rs = int(reason_counts.get("RELATIVE_STRENGTH_RANK_TOO_LOW", 0) or 0)
    strict_filter = int(reason_counts.get("SCORING_OR_STRUCTURE_FILTER_TOO_STRICT", 0) or 0)

    if candidate_pool >= 3:
        setv(proposed, "maxResults", min(integer(proposed, "maxResults", 4) + 1, 8), "missed pumps: displayed result set too narrow; show more candidates", changes)
        setv(proposed, "candidateSelection.maxCandleTargets", min(integer(getv(proposed, "candidateSelection", {}), "maxCandleTargets", 35) + 10, 80), "missed pumps: scanned candle pool too narrow", changes)
        setv(proposed, "candidateSelection.topTradeValueCount", min(integer(getv(proposed, "candidateSelection", {}), "topTradeValueCount", 40) + 10, 100), "missed pumps: liquid universe too narrow", changes)
        setv(proposed, "candidateSelection.topChangeRateCount", min(integer(getv(proposed, "candidateSelection", {}), "topChangeRateCount", 15) + 5, 80), "missed pumps: rotation universe too narrow", changes)
        setv(proposed, "candidateSelection.volumeBuildupCount", min(integer(getv(proposed, "candidateSelection", {}), "volumeBuildupCount", 15) + 5, 80), "missed pumps: buildup bucket too narrow", changes)
        setv(proposed, "candidateSelection.quietAccumulationCount", min(integer(getv(proposed, "candidateSelection", {}), "quietAccumulationCount", 10) + 3, 80), "missed pumps: quiet accumulation bucket too narrow", changes)
        setv(proposed, "candidateSelection.medianTradeValueMultiplier", round(clamp(num(getv(proposed, "candidateSelection", {}), "medianTradeValueMultiplier", 1.5) - 0.1, 0.5, 3.0), 2), "missed pumps: buildup liquidity threshold too strict", changes)
        setv(proposed, "prePumpRotation.maxTradeValueRank", min(integer(getv(proposed, "prePumpRotation", {}), "maxTradeValueRank", 25) + 5, 60), "missed pumps: liquidity rank gate too narrow", changes)
        setv(proposed, "prePumpRotation.maxChangeRank", min(integer(getv(proposed, "prePumpRotation", {}), "maxChangeRank", 35) + 5, 70), "missed pumps: relative-strength rank gate too narrow", changes)

    if low_volume >= 3 and not weak_live_profile:
        setv(proposed, "candidateSelection.volumeBuildupCount", min(integer(getv(proposed, "candidateSelection", {}), "volumeBuildupCount", 15) + 5, 80), "missed pumps: include more volume buildup candidates", changes)
        setv(proposed, "candidateSelection.medianTradeValueMultiplier", round(clamp(num(getv(proposed, "candidateSelection", {}), "medianTradeValueMultiplier", 1.5) - 0.08, 0.5, 3.0), 2), "missed pumps: allow earlier lower-value buildup", changes)
        setv(proposed, "candidateSelection.maxBuildupChangeRatePct", round(clamp(num(getv(proposed, "candidateSelection", {}), "maxBuildupChangeRatePct", 5.0) + 0.5, 0.0, 15.0), 2), "missed pumps: allow stronger buildup before breakout", changes)
        setv(proposed, "compressionBreakout.minVolumeAcceleration", round(clamp(num(getv(proposed, "compressionBreakout", {}), "minVolumeAcceleration", 1.35) - 0.05, 1.05, 2.20), 2), "missed pumps: early volume gate too late", changes)
        setv(proposed, "compressionBreakout.minFiveMinuteVolumeRatio", round(clamp(num(getv(proposed, "compressionBreakout", {}), "minFiveMinuteVolumeRatio", 1.2) - 0.03, 1.05, 1.80), 2), "missed pumps: 5m volume gate too strict", changes)
        setv(proposed, "prePumpRotation.minVolumeAcceleration", round(clamp(num(getv(proposed, "prePumpRotation", {}), "minVolumeAcceleration", 1.45) - 0.05, 1.05, 2.20), 2), "missed pumps: pre-pump volume gate too late", changes)
        setv(proposed, "prePumpRotation.minFiveMinuteVolumeRatio", round(clamp(num(getv(proposed, "prePumpRotation", {}), "minFiveMinuteVolumeRatio", 1.6) - 0.05, 1.05, 2.20), 2), "missed pumps: pre-pump 5m volume gate too strict", changes)
        setv(proposed, "prePumpRotation.minFifteenMinuteVolumeRatio", round(clamp(num(getv(proposed, "prePumpRotation", {}), "minFifteenMinuteVolumeRatio", 1.35) - 0.04, 1.05, 2.00), 2), "missed pumps: pre-pump 15m volume gate too strict", changes)

    if strict_filter >= 3 and not weak_live_profile:
        setv(proposed, "minimumScore", round(clamp(num(proposed, "minimumScore", 68.0) - 1.0, 58.0, 82.0), 1), "missed pumps: score/structure filter too strict", changes)
        setv(proposed, "candidateSelection.quietAccumulationCount", min(integer(getv(proposed, "candidateSelection", {}), "quietAccumulationCount", 10) + 3, 80), "missed pumps: include more quiet accumulation candidates", changes)
        setv(proposed, "candidateSelection.maxQuietAbsChangeRatePct", round(clamp(num(getv(proposed, "candidateSelection", {}), "maxQuietAbsChangeRatePct", 1.5) + 0.2, 0.1, 5.0), 2), "missed pumps: quiet accumulation filter too strict", changes)
        setv(proposed, "compressionBreakout.maxDistanceTo15mHighPct", round(clamp(num(getv(proposed, "compressionBreakout", {}), "maxDistanceTo15mHighPct", 2.0) + 0.15, 0.8, 3.5), 2), "missed pumps: allow setup slightly farther from 15m high", changes)
        setv(proposed, "prePumpRotation.maxRangePct", round(clamp(num(getv(proposed, "prePumpRotation", {}), "maxRangePct", 4.2) + 0.15, 2.0, 6.0), 2), "missed pumps: pre-pump range gate too strict", changes)
        setv(proposed, "prePumpRotation.minRangePosition", round(clamp(num(getv(proposed, "prePumpRotation", {}), "minRangePosition", 0.55) - 0.02, 0.35, 0.80), 2), "missed pumps: pre-pump position gate too strict", changes)
        setv(proposed, "prePumpRotation.minHighProximityMultiplier", round(clamp(num(getv(proposed, "prePumpRotation", {}), "minHighProximityMultiplier", 0.992) - 0.001, 0.980, 0.998), 3), "missed pumps: high-proximity gate too strict", changes)

    if late_move >= 3:
        setv(proposed, "candidateSelection.topChangeRateCount", min(integer(getv(proposed, "candidateSelection", {}), "topChangeRateCount", 15) + 5, 80), "missed pumps already moving; track more rotation names earlier", changes)
        setv(proposed, "scoring.overheat5mWeight", round(clamp(num(getv(proposed, "scoring", {}), "overheat5mWeight", 8.0) + 0.4, 4.0, 14.0), 2), "missed pumps were already moving; avoid late chase", changes)
        setv(proposed, "scoring.hardBlock5mPumpPct", round(clamp(num(getv(proposed, "scoring", {}), "hardBlock5mPumpPct", 2.2) - 0.05, 1.4, 3.0), 2), "missed pumps were late; block later entries sooner", changes)
        setv(proposed, "prePumpRotation.maxChange5mPct", round(clamp(num(getv(proposed, "prePumpRotation", {}), "maxChange5mPct", 1.8) - 0.05, 0.8, 3.0), 2), "missed pumps were already moving; demand earlier entry", changes)
        setv(proposed, "prePumpRotation.maxChange30mPct", round(clamp(num(getv(proposed, "prePumpRotation", {}), "maxChange30mPct", 3.2) - 0.10, 1.5, 5.0), 2), "missed pumps were already moving; demand earlier 30m entry", changes)

    if low_rs >= 3:
        setv(proposed, "candidateSelection.topChangeRateCount", min(integer(getv(proposed, "candidateSelection", {}), "topChangeRateCount", 15) + 5, 80), "missed pumps: relative-strength universe too narrow", changes)
        setv(proposed, "prePumpRotation.maxChangeRank", min(integer(getv(proposed, "prePumpRotation", {}), "maxChangeRank", 35) + 5, 70), "missed pumps: relative-strength rank gate too narrow", changes)
        setv(proposed, "prePumpRotation.minRotation30mPct", round(clamp(num(getv(proposed, "prePumpRotation", {}), "minRotation30mPct", 0.7) - 0.05, 0.2, 1.5), 2), "missed pumps: 30m rotation trigger too strict", changes)



def should_allow_rule_change(report: dict[str, Any], missed: dict[str, Any], notes: list[str]) -> bool:
    total = integer(report, "total_signals", 0)
    missed_count = integer(missed, "missed_pump_count", 0) if missed else 0
    generated = str(report.get("generated_at_utc", ""))[:10]
    if total < 20 and missed_count < 10:
        notes.append("rule_change_guard=blocked_low_sample_total_signals_lt20_and_missed_lt10")
        return False
    if generated:
        notes.append("rule_change_guard=daily_workflow_only; generated_date=" + generated)
    return True

def tune(rules: dict[str, Any], report: dict[str, Any], missed: dict[str, Any]) -> tuple[dict[str, Any], list[str], list[str]]:
    proposed = copy.deepcopy(rules)
    ensure_defaults(proposed)
    changes: list[str] = []
    notes: list[str] = []
    if not should_allow_rule_change(report, missed, notes):
        return proposed, [], notes
    avg_ret, stop_rate, _t1_rate, _signals = tune_from_backtest(proposed, report, changes, notes)
    tune_from_missed(proposed, missed, avg_ret, stop_rate, changes, notes)
    if changes:
        old_version = str(proposed.get("version", "rules")).split("+auto")[0]
        proposed["version"] = f"{old_version}+auto-{datetime.now(timezone.utc).strftime('%Y%m%d-%H%M')}"
    return proposed, changes, notes


def write_plan(path: Path, changes: list[str], notes: list[str], summary: dict[str, Any], missed: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = ["# Auto Rules Reflection", "", f"Generated UTC: `{datetime.now(timezone.utc).isoformat()}`", "", "## Notes", ""]
    lines += [f"- {x}" for x in notes]
    lines += ["", "## Proposed changes", ""]
    lines += changes or ["- No changes. Guard thresholds were not met."]
    lines += ["", "## Strategy summary", "", "```json", json.dumps(summary, ensure_ascii=False, indent=2), "```", ""]
    if missed:
        lines += ["", "## Missed pump summary", "", "```json", json.dumps(missed.get("reason_counts", {}), ensure_ascii=False, indent=2), "```", ""]
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--backtest", type=Path, default=Path("reports/backtest_latest.json"))
    parser.add_argument("--missed", type=Path, default=Path("reports/missed_pumps_latest.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--out-rules", type=Path, default=Path("reports/proposed_strategy_rules.json"))
    parser.add_argument("--out-plan", type=Path, default=Path("reports/rules_update_plan.md"))
    parser.add_argument("--out-env", type=Path, default=Path("reports/auto_tune_decision.env"))
    args = parser.parse_args()

    report = load_json(args.backtest)
    missed = load_json_optional(args.missed)
    current = load_json(args.rules)
    proposed, changes, notes = tune(current, report, missed)
    changed = json.dumps(current, sort_keys=True) != json.dumps(proposed, sort_keys=True)
    write_json(args.out_rules, proposed)
    write_plan(args.out_plan, changes, notes, report.get("strategy_summary", {}), missed)
    args.out_env.parent.mkdir(parents=True, exist_ok=True)
    args.out_env.write_text(f"RULES_CHANGED={'true' if changed else 'false'}\n", encoding="utf-8")
    print(f"RULES_CHANGED={'true' if changed else 'false'}")
    for item in changes:
        print(item)


if __name__ == "__main__":
    main()
