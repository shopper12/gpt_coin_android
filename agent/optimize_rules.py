#!/usr/bin/env python3
"""Create a bounded rules candidate from live + backtest evidence.

The optimizer is deliberately simple and auditable. It only changes numeric
rules by at most merge-policy.riskLimits.maxNumericRuleChangePct.
"""

from __future__ import annotations

import argparse
import copy
from typing import Any

from common import clamp_numeric_change, diff_numeric, load_json, save_json, set_path


def _live_summary(report: dict[str, Any]) -> dict[str, float]:
    summaries = report.get("summaries") or report.get("strategies") or []
    if not summaries:
        return {"sample": 0.0, "avgReturn30m": 0.0, "stopHitRate": 0.0, "targetHitRate": 0.0, "mfeMaeRatio": 0.0}
    sample = sum(float(x.get("totalSignals", x.get("sampleSize", 0)) or 0) for x in summaries)
    if sample <= 0:
        return {"sample": 0.0, "avgReturn30m": 0.0, "stopHitRate": 0.0, "targetHitRate": 0.0, "mfeMaeRatio": 0.0}

    def wavg(key: str) -> float:
        total = 0.0
        weight_sum = 0.0
        for item in summaries:
            w = float(item.get("completedSignals", item.get("totalSignals", item.get("sampleSize", 0))) or 0)
            if w <= 0:
                continue
            weight_sum += w
            total += float(item.get(key, 0.0) or 0.0) * w
        return total / weight_sum if weight_sum else 0.0

    return {
        "sample": sample,
        "avgReturn30m": wavg("avgReturn30m"),
        "stopHitRate": wavg("stopHitRate"),
        "targetHitRate": wavg("targetHitRate"),
        "mfeMaeRatio": wavg("mfeMaeRatio"),
    }


def propose_rules(base: dict[str, Any], live_report: dict[str, Any], backtest: dict[str, Any], policy: dict[str, Any]) -> tuple[dict[str, Any], list[str]]:
    candidate = copy.deepcopy(base)
    reasons: list[str] = []
    max_change = float(policy.get("riskLimits", {}).get("maxNumericRuleChangePct", 10))
    live = _live_summary(live_report)
    bt = backtest.get("summary", {})

    live_bad = live["sample"] > 0 and live["avgReturn30m"] < 0
    live_good = live["sample"] > 0 and live["avgReturn30m"] > 0 and live["mfeMaeRatio"] >= float(policy.get("performanceGate", {}).get("mfeMaeRatioMin", 1.1))
    stop_bad = live["stopHitRate"] > 0.35
    bt_sparse = float(bt.get("sampleSize", 0) or 0) < int(policy.get("minSampleSize", 30))
    bt_bad = float(bt.get("avgReturn30m", 0.0) or 0.0) < 0

    def adjust(path: tuple[str, ...], multiplier: float, why: str) -> None:
        cur = base
        for part in path:
            cur = cur[part]
        proposed = clamp_numeric_change(float(cur), float(cur) * multiplier, max_change)
        if proposed != cur:
            set_path(candidate, path, round(proposed, 6))
            reasons.append(f"{'.'.join(path)}: {cur} -> {proposed:.6f}; {why}")

    if live_bad or bt_bad:
        adjust(("minimumScore",), 1.03, "negative forward/backtest return; require stronger signals")
        adjust(("compressionBreakout", "maxDistanceTo15mHighPct"), 0.95, "reduce late breakout entries")
        adjust(("compressionBreakout", "minVolumeAcceleration"), 1.05, "require stronger volume confirmation")
        adjust(("bearDecouplingBounce", "minFourHourVolumeMultiple"), 1.05, "raise 4h volume proof requirement")
        adjust(("bearDecouplingBounce", "maxTradeValueRank"), 0.95, "prefer higher-liquidity rank during weak outcomes")
    elif live_good and not bt_sparse:
        adjust(("minimumScore",), 0.98, "positive forward performance; allow slightly more candidates")
        adjust(("compressionBreakout", "maxDistanceTo15mHighPct"), 1.03, "positive performance; widen pre-breakout catchment")
        adjust(("bearDecouplingBounce", "maxTradeValueRank"), 1.03, "positive performance; allow slightly wider rank universe")
    elif bt_sparse:
        adjust(("minimumScore",), 0.98, "too few backtest samples; mildly increase signal count")
        adjust(("compressionBreakout", "maxDistanceTo15mHighPct"), 1.05, "too few breakout samples; widen high-distance threshold")
    elif stop_bad:
        adjust(("minimumScore",), 1.02, "stop hit rate high")
        adjust(("scoring", "overheat5mWeight"), 1.05, "penalize short-term overheat more")

    candidate["version"] = f"{base.get('version', 'rules')}-agent"
    return candidate, reasons


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rules", default="rules/strategy-rules.json")
    parser.add_argument("--report", default="reports/latest.json")
    parser.add_argument("--backtest", default="reports/backtest-latest.json")
    parser.add_argument("--policy", default="agent/merge-policy.json")
    parser.add_argument("--write-candidate", default="reports/candidate-rules.json")
    parser.add_argument("--write-rationale", default="reports/rule-change-rationale.md")
    args = parser.parse_args()

    base = load_json(args.rules)
    report = load_json(args.report, default={})
    backtest = load_json(args.backtest, default={})
    policy = load_json(args.policy)
    candidate, reasons = propose_rules(base, report, backtest, policy)
    save_json(args.write_candidate, candidate)

    diffs = diff_numeric(base, candidate)
    rationale = ["# Rule Change Rationale", "", f"Base version: `{base.get('version')}`", f"Candidate version: `{candidate.get('version')}`", ""]
    if reasons:
        rationale.append("## Reasons")
        rationale.extend(f"- {r}" for r in reasons)
    else:
        rationale.append("No numeric rule changes proposed.")
    rationale.append("")
    rationale.append("## Numeric diffs")
    if diffs:
        rationale.extend(f"- `{d['path']}`: {d['before']} -> {d['after']} ({d['changePct']:.2f}%)" for d in diffs)
    else:
        rationale.append("- none")
    rationale.append("")
    save_json("reports/optimization-diff.json", {"diffs": diffs, "reasons": reasons})
    from common import save_text

    save_text(args.write_rationale, "\n".join(rationale))
    print(f"Candidate written to {args.write_candidate}; changes={len(diffs)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
