#!/usr/bin/env python3
from __future__ import annotations

import argparse
import copy
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


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


def tune(rules: dict[str, Any], report: dict[str, Any]) -> tuple[dict[str, Any], list[str], list[str]]:
    proposed = copy.deepcopy(rules)
    changes: list[str] = []
    notes: list[str] = []
    summaries: dict[str, Any] = report.get("strategy_summary", {}) or {}
    total = integer(report, "total_signals", 0)
    notes.append(f"total_signals={total}")

    rows = [r for r in summaries.values() if isinstance(r, dict) and integer(r, "signals") > 0]
    signals = sum(integer(r, "signals") for r in rows)
    if signals:
        avg_ret = sum(num(r, "avg_return_pct") * integer(r, "signals") for r in rows) / signals
        stop_rate = sum(num(r, "stop_hit_rate_pct") * integer(r, "signals") for r in rows) / signals
        t1_rate = sum(num(r, "target1_hit_rate_pct") * integer(r, "signals") for r in rows) / signals
    else:
        avg_ret = stop_rate = t1_rate = 0.0
    notes.append(f"weighted_avg_return={avg_ret:+.2f}%")
    notes.append(f"weighted_t1_hit={t1_rate:.1f}%")
    notes.append(f"weighted_stop_hit={stop_rate:.1f}%")

    min_score = num(proposed, "minimumScore", 68.0)
    max_results = integer(proposed, "maxResults", 4)

    if total == 0:
        setv(proposed, "minimumScore", round(clamp(min_score - 4.0, 58.0, 82.0), 1), "no signals; loosen score gate", changes)
        setv(proposed, "maxResults", min(max_results + 1, 8), "no signals; show more candidates", changes)
        setv(proposed, "compressionBreakout.minVolumeAcceleration", round(clamp(num(proposed, "compressionBreakout.minVolumeAcceleration", 1.35) - 0.08, 1.05, 2.20), 2), "no signals; loosen volume gate", changes)
    elif signals < 12:
        setv(proposed, "minimumScore", round(clamp(min_score - 2.0, 58.0, 82.0), 1), "too few signals; loosen cautiously", changes)
        setv(proposed, "maxResults", min(max_results + 1, 8), "too few signals; keep more candidates", changes)
    elif stop_rate > 42.0 or avg_ret < -0.10:
        setv(proposed, "minimumScore", round(clamp(min_score + 2.0, 58.0, 82.0), 1), "weak result; tighten score gate", changes)
        setv(proposed, "scoring.overheat5mWeight", round(clamp(num(proposed, "scoring.overheat5mWeight", 8.0) + 0.6, 4.0, 14.0), 2), "reduce late 5m chase", changes)
        setv(proposed, "scoring.hardBlock5mPumpPct", round(clamp(num(proposed, "scoring.hardBlock5mPumpPct", 2.2) - 0.1, 1.4, 3.0), 2), "block stronger 5m spike", changes)
        setv(proposed, "compressionBreakout.minFiveMinuteVolumeRatio", round(clamp(num(proposed, "compressionBreakout.minFiveMinuteVolumeRatio", 1.2) + 0.05, 1.05, 1.8), 2), "require cleaner volume", changes)
    elif avg_ret > 0.18 and t1_rate > 35.0 and stop_rate < 32.0:
        setv(proposed, "minimumScore", round(clamp(min_score - 1.0, 58.0, 82.0), 1), "good result; loosen slightly", changes)
        setv(proposed, "maxResults", min(max_results + 1, 8), "good result; expose more candidates", changes)

    comp = summaries.get("COMPRESSION_BREAKOUT", {}) or {}
    if integer(comp, "signals") >= 8 and (num(comp, "stop_hit_rate_pct") > 42.0 or num(comp, "avg_return_pct") < -0.10):
        setv(proposed, "compressionBreakout.rangeCompressionRatio", round(clamp(num(proposed, "compressionBreakout.rangeCompressionRatio", 0.8) - 0.03, 0.55, 0.90), 2), "compression failed; require tighter range", changes)
        setv(proposed, "compressionBreakout.minVolumeAcceleration", round(clamp(num(proposed, "compressionBreakout.minVolumeAcceleration", 1.35) + 0.05, 1.05, 2.20), 2), "compression failed; require stronger volume", changes)

    sweep = summaries.get("SWEEP_RECLAIM", {}) or {}
    if integer(sweep, "signals") >= 8 and (num(sweep, "stop_hit_rate_pct") > 42.0 or num(sweep, "avg_return_pct") < -0.10):
        setv(proposed, "sweepReclaim.fiveMinuteLookback", min(integer(proposed.get("sweepReclaim", {}), "fiveMinuteLookback", 12) + 2, 20), "sweep failed; use wider prior-low window", changes)
        setv(proposed, "sweepReclaim.requireVolumeAboveAverage", True, "sweep failed; keep volume confirmation", changes)

    if changes:
        old_version = str(proposed.get("version", "rules")).split("+auto")[0]
        proposed["version"] = f"{old_version}+auto-{datetime.now(timezone.utc).strftime('%Y%m%d-%H%M')}"
    return proposed, changes, notes


def write_plan(path: Path, changes: list[str], notes: list[str], summary: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = ["# Auto Rules Reflection", "", f"Generated UTC: `{datetime.now(timezone.utc).isoformat()}`", "", "## Notes", ""]
    lines += [f"- {x}" for x in notes]
    lines += ["", "## Proposed changes", ""]
    lines += changes or ["- No changes. Guard thresholds were not met."]
    lines += ["", "## Strategy summary", "", "```json", json.dumps(summary, ensure_ascii=False, indent=2), "```", ""]
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--backtest", type=Path, default=Path("reports/backtest_latest.json"))
    parser.add_argument("--rules", type=Path, default=Path("rules/strategy-rules.json"))
    parser.add_argument("--out-rules", type=Path, default=Path("reports/proposed_strategy_rules.json"))
    parser.add_argument("--out-plan", type=Path, default=Path("reports/rules_update_plan.md"))
    parser.add_argument("--out-env", type=Path, default=Path("reports/auto_tune_decision.env"))
    args = parser.parse_args()

    report = load_json(args.backtest)
    current = load_json(args.rules)
    proposed, changes, notes = tune(current, report)
    changed = json.dumps(current, sort_keys=True) != json.dumps(proposed, sort_keys=True)
    write_json(args.out_rules, proposed)
    write_plan(args.out_plan, changes, notes, report.get("strategy_summary", {}))
    args.out_env.parent.mkdir(parents=True, exist_ok=True)
    args.out_env.write_text(f"RULES_CHANGED={'true' if changed else 'false'}\n", encoding="utf-8")
    print(f"RULES_CHANGED={'true' if changed else 'false'}")
    for item in changes:
        print(item)


if __name__ == "__main__":
    main()
