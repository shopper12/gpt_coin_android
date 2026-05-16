#!/usr/bin/env python3
"""Evaluate live forward-performance report against merge-policy gates."""

from __future__ import annotations

import argparse
from typing import Any

from common import load_json, save_json, now_ms


def _summary(report: dict[str, Any]) -> dict[str, Any]:
    summaries = report.get("summaries") or report.get("strategies") or []
    if not summaries:
        return {
            "sampleSize": 0,
            "completedSignals": 0,
            "avgReturn30m": 0.0,
            "targetHitRate": 0.0,
            "stopHitRate": 0.0,
            "mfeMaeRatio": 0.0,
            "byStrategy": [],
        }
    sample = sum(int(x.get("totalSignals", x.get("sampleSize", 0)) or 0) for x in summaries)
    completed = sum(int(x.get("completedSignals", 0) or 0) for x in summaries)

    def weighted_avg(key: str) -> float:
        total_weight = 0
        total = 0.0
        for item in summaries:
            weight = int(item.get("completedSignals", item.get("totalSignals", item.get("sampleSize", 0))) or 0)
            if weight <= 0:
                continue
            total_weight += weight
            total += float(item.get(key, 0.0) or 0.0) * weight
        return total / total_weight if total_weight else 0.0

    return {
        "sampleSize": sample,
        "completedSignals": completed,
        "avgReturn30m": weighted_avg("avgReturn30m"),
        "targetHitRate": weighted_avg("targetHitRate"),
        "stopHitRate": weighted_avg("stopHitRate"),
        "mfeMaeRatio": weighted_avg("mfeMaeRatio"),
        "byStrategy": summaries,
    }


def evaluate_live_report(report: dict[str, Any], policy: dict[str, Any]) -> dict[str, Any]:
    min_sample = int(policy.get("minSampleSize", 30))
    perf_gate = policy.get("performanceGate", {})
    min_mfe_mae = float(perf_gate.get("mfeMaeRatioMin", 1.1))
    summary = _summary(report)
    failures: list[str] = []
    if summary["sampleSize"] < min_sample:
        failures.append(f"sampleSize {summary['sampleSize']} < minSampleSize {min_sample}")
    if summary["mfeMaeRatio"] < min_mfe_mae:
        failures.append(f"mfeMaeRatio {summary['mfeMaeRatio']:.4f} < {min_mfe_mae:.4f}")
    return {
        "generatedAt": now_ms(),
        "ok": not failures,
        "failures": failures,
        "summary": summary,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", default="reports/latest.json")
    parser.add_argument("--policy", default="agent/merge-policy.json")
    parser.add_argument("--write-result", default="reports/merge-decision.json")
    args = parser.parse_args()
    report = load_json(args.report, default={})
    policy = load_json(args.policy)
    result = evaluate_live_report(report, policy)
    save_json(args.write_result, result)
    print("Live report evaluation:", "PASS" if result["ok"] else "FAIL")
    for failure in result["failures"]:
        print("-", failure)
    return 0 if result["ok"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
