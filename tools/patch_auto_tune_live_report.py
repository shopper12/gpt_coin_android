from pathlib import Path

p = Path('tools/auto_tune_rules.py')
s = p.read_text(encoding='utf-8')

if 'def tune_from_live_report(' not in s:
    fn = '''

def tune_from_live_report(proposed: dict[str, Any], live: dict[str, Any], changes: list[str], notes: list[str]) -> None:
    review = live.get("liveReview", {}) if isinstance(live, dict) else {}
    if not isinstance(review, dict) or not review:
        notes.append("live_review=missing")
        return
    sample = integer(review, "sampleSize", 0)
    stop_rate = num(review, "stopHitRate", 0.0) * 100.0
    target_rate = num(review, "targetHitRate", 0.0) * 100.0
    avg_mfe = num(review, "averageMfePercent", 0.0)
    avg_mae = num(review, "averageMaePercent", 0.0)
    missed = integer(review, "missedSignals", 0)
    diagnosis = str(review.get("diagnosis", ""))
    notes.append(f"live_sample={sample}")
    notes.append(f"live_stop_rate={stop_rate:.1f}%")
    notes.append(f"live_target_rate={target_rate:.1f}%")
    notes.append(f"live_avg_mfe={avg_mfe:+.2f}%")
    notes.append(f"live_avg_mae={avg_mae:+.2f}%")
    notes.append(f"live_missed_signals={missed}")
    notes.append("live_diagnosis=" + diagnosis)

    if sample < 20:
        notes.append("live_adjustment=skipped_low_sample")
        return

    min_score = num(proposed, "minimumScore", 74.0)
    max_results = integer(proposed, "maxResults", 3)

    if stop_rate >= 42.0:
        setv(proposed, "minimumScore", round(clamp(min_score + 1.0, 60.0, 82.0), 1), "live review: high stop rate; tighten score gate", changes)
        setv(proposed, "maxResults", max(1, max_results - 1), "live review: high stop rate; reduce displayed signals", changes)
        setv(proposed, "prePumpRotation.maxChange5mPct", round(clamp(num(getv(proposed, "prePumpRotation", {}), "maxChange5mPct", 1.2) - 0.05, 0.7, 3.0), 2), "live review: avoid late 5m chase", changes)
        setv(proposed, "prePumpRotation.minVolumeAcceleration", round(clamp(num(getv(proposed, "prePumpRotation", {}), "minVolumeAcceleration", 1.65) + 0.05, 1.05, 2.3), 2), "live review: require stronger volume", changes)
        return

    if target_rate <= 18.0 and avg_mfe < 0.45:
        setv(proposed, "minimumScore", round(clamp(min_score + 1.0, 60.0, 82.0), 1), "live review: weak target hit and MFE", changes)
        setv(proposed, "compressionBreakout.rangeCompressionRatio", round(clamp(num(getv(proposed, "compressionBreakout", {}), "rangeCompressionRatio", 0.72) - 0.02, 0.55, 0.90), 2), "live review: require tighter compression", changes)
        setv(proposed, "compressionBreakout.minVolumeAcceleration", round(clamp(num(getv(proposed, "compressionBreakout", {}), "minVolumeAcceleration", 1.55) + 0.05, 1.05, 2.3), 2), "live review: require stronger breakout volume", changes)
        return

    if missed >= 10 and stop_rate < 35.0:
        setv(proposed, "candidateSelection.maxCandleTargets", min(integer(getv(proposed, "candidateSelection", {}), "maxCandleTargets", 28) + 3, 80), "live review: many missed signals but stop rate acceptable", changes)
        setv(proposed, "candidateSelection.topChangeRateCount", min(integer(getv(proposed, "candidateSelection", {}), "topChangeRateCount", 10) + 2, 80), "live review: widen rotation candidates slightly", changes)
        return

    if avg_mfe > abs(avg_mae) * 1.8 and target_rate >= 35.0 and stop_rate < 30.0:
        setv(proposed, "maxResults", min(max_results + 1, 5), "live review: positive edge; expose one more signal", changes)
        return

    notes.append("live_adjustment=no_change")
'''
    s = s.replace('\ndef write_plan(', fn + '\ndef write_plan(', 1)

s = s.replace(
    'def tune(rules: dict[str, Any], report: dict[str, Any], missed: dict[str, Any]) -> tuple[dict[str, Any], list[str], list[str]]:',
    'def tune(rules: dict[str, Any], report: dict[str, Any], missed: dict[str, Any], live: dict[str, Any] | None = None) -> tuple[dict[str, Any], list[str], list[str]]:',
)
s = s.replace(
    '    tune_from_missed(proposed, missed, avg_ret, stop_rate, changes, notes)\n    if changes:',
    '    tune_from_missed(proposed, missed, avg_ret, stop_rate, changes, notes)\n    if live:\n        tune_from_live_report(proposed, live, changes, notes)\n    if changes:',
)
s = s.replace(
    '    parser.add_argument("--out-rules", type=Path, default=Path("reports/proposed_strategy_rules.json"))',
    '    parser.add_argument("--live-report", type=Path, default=Path("reports/latest.json"))\n    parser.add_argument("--out-rules", type=Path, default=Path("reports/proposed_strategy_rules.json"))',
)
s = s.replace(
    '    current = load_json(args.rules)\n    proposed, changes, notes = tune(current, report, missed)',
    '    current = load_json(args.rules)\n    live = load_json_optional(args.live_report)\n    proposed, changes, notes = tune(current, report, missed, live)',
)

p.write_text(s, encoding='utf-8')
print('patched live report auto tune')
