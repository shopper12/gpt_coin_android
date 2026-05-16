#!/usr/bin/env python3
"""Validate strategy-rules.json and optional candidate changes.

Usage:
  python agent/validate_rules.py
  python agent/validate_rules.py --rules rules/candidate.json --base rules/strategy-rules.json
"""

from __future__ import annotations

import argparse
from typing import Any

from common import AgentError, diff_numeric, load_json, save_json

REQUIRED_TOP_LEVEL = {
    "version",
    "minimumScore",
    "maxResults",
    "validForMinutes",
    "entryBandPct",
    "compressionBreakout",
    "sweepReclaim",
    "trendPullback",
    "bearDecouplingBounce",
    "scoring",
    "risk",
}

RANGES: dict[str, tuple[float, float]] = {
    "minimumScore": (0.0, 100.0),
    "maxResults": (1.0, 20.0),
    "validForMinutes": (1.0, 240.0),
    "entryBandPct": (0.0, 5.0),
    "compressionBreakout.rangeCompressionRatio": (0.4, 1.2),
    "compressionBreakout.maxDistanceTo15mHighPct": (0.2, 10.0),
    "compressionBreakout.minVolumeAcceleration": (0.5, 6.0),
    "compressionBreakout.minFiveMinuteVolumeRatio": (0.5, 5.0),
    "sweepReclaim.fiveMinuteLookback": (4.0, 60.0),
    "sweepReclaim.fifteenMinuteLookback": (4.0, 40.0),
    "trendPullback.higherTimeframeMaPeriod": (20.0, 200.0),
    "trendPullback.fifteenMinuteMaPeriod": (5.0, 80.0),
    "trendPullback.min15mMaMultiplier": (0.94, 1.02),
    "trendPullback.minPriorLowMultiplier": (0.90, 1.02),
    "trendPullback.pullbackLookback": (4.0, 40.0),
    "trendPullback.reclaimLookback": (2.0, 30.0),
    "bearDecouplingBounce.btcWeakBelowPct": (-10.0, 2.0),
    "bearDecouplingBounce.altStrongAbovePct": (-2.0, 15.0),
    "bearDecouplingBounce.maxTradeValueRank": (1.0, 80.0),
    "bearDecouplingBounce.minFourHourVolumeMultiple": (0.8, 8.0),
    "bearDecouplingBounce.maxPreviousFourHourVolumeMultiple": (1.0, 10.0),
    "bearDecouplingBounce.maxPriceOver240mMa20Pct": (-5.0, 30.0),
    "bearDecouplingBounce.maxBearishUpperWickPct": (10.0, 95.0),
    "bearDecouplingBounce.decouplingScoreCap": (5.0, 50.0),
    "bearDecouplingBounce.wickPenalty": (0.0, 40.0),
    "scoring.overheat24hBasePct": (1.0, 60.0),
    "scoring.overheat24hWeight": (0.0, 10.0),
    "scoring.overheat30mBasePct": (0.5, 30.0),
    "scoring.overheat30mWeight": (0.0, 15.0),
    "scoring.overheat5mBasePct": (0.2, 20.0),
    "scoring.overheat5mWeight": (0.0, 20.0),
    "scoring.overheatMax": (0.0, 60.0),
    "risk.defaultStopMultiplier": (0.90, 0.999),
    "risk.minimumRiskPct": (0.01, 5.0),
    "risk.minimumExpectedReturnPct": (0.01, 10.0),
}

INTEGER_PATHS = {
    "maxResults",
    "validForMinutes",
    "sweepReclaim.fiveMinuteLookback",
    "sweepReclaim.fifteenMinuteLookback",
    "trendPullback.higherTimeframeMaPeriod",
    "trendPullback.fifteenMinuteMaPeriod",
    "trendPullback.pullbackLookback",
    "trendPullback.reclaimLookback",
    "bearDecouplingBounce.maxTradeValueRank",
}


def flatten(data: Any, prefix: str = "") -> dict[str, Any]:
    out: dict[str, Any] = {}
    if isinstance(data, dict):
        for key, value in data.items():
            child = f"{prefix}.{key}" if prefix else key
            out.update(flatten(value, child))
    else:
        out[prefix] = data
    return out


def validate_rules(rules: dict[str, Any], policy: dict[str, Any], base: dict[str, Any] | None = None) -> list[str]:
    errors: list[str] = []
    missing = REQUIRED_TOP_LEVEL - set(rules.keys())
    if missing:
        errors.append(f"missing top-level keys: {sorted(missing)}")

    flat = flatten(rules)
    for path, (lo, hi) in RANGES.items():
        value = flat.get(path)
        if value is None:
            errors.append(f"missing required numeric key: {path}")
            continue
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            errors.append(f"{path} must be numeric, got {type(value).__name__}")
            continue
        if not lo <= float(value) <= hi:
            errors.append(f"{path} out of range: {value} not in [{lo}, {hi}]")
        if path in INTEGER_PATHS and int(value) != value:
            errors.append(f"{path} must be integer-like, got {value}")

    if not isinstance(flat.get("sweepReclaim.requireVolumeAboveAverage"), bool):
        errors.append("sweepReclaim.requireVolumeAboveAverage must be boolean")

    if base is not None:
        limit = float(policy.get("riskLimits", {}).get("maxNumericRuleChangePct", 10))
        for item in diff_numeric(base, rules):
            if abs(float(item["changePct"])) > limit + 1e-9:
                errors.append(
                    f"numeric change too large at {item['path']}: {item['changePct']:.2f}% > {limit:.2f}%"
                )
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rules", default="rules/strategy-rules.json")
    parser.add_argument("--base", default=None)
    parser.add_argument("--policy", default="agent/merge-policy.json")
    parser.add_argument("--write-result", default=None)
    args = parser.parse_args()

    rules = load_json(args.rules)
    policy = load_json(args.policy)
    base = load_json(args.base) if args.base else None
    errors = validate_rules(rules, policy, base)
    result = {"ok": not errors, "errors": errors}
    if args.write_result:
        save_json(args.write_result, result)
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print("Rules validation passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
