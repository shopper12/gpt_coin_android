#!/usr/bin/env python3
"""EC2 watcher for strategy report -> backtest -> bounded rules update.

Run from repository root:
  python agent/ec2_watcher.py --once
  python agent/ec2_watcher.py

Environment:
  GIT_AUTHOR_NAME, GIT_AUTHOR_EMAIL, GIT_COMMITTER_NAME, GIT_COMMITTER_EMAIL optional
  AUTO_PUSH=1 to push automatically when a rules update is committed
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
import time
from pathlib import Path
from typing import Any

from common import AgentError, env_bool, load_json, repo_path, run, save_json, save_text
from evaluate_rules import evaluate_live_report
from validate_rules import validate_rules


def file_hash(path: Path) -> str:
    if not path.exists():
        return "missing"
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git_pull(branch: str) -> None:
    run(["git", "fetch", "origin", branch])
    run(["git", "checkout", branch])
    run(["git", "pull", "--rebase", "origin", branch])


def ensure_clean_allowed(config: dict[str, Any]) -> None:
    status = run(["git", "status", "--porcelain"], check=True).stdout.splitlines()
    if not status:
        return
    allowed = tuple(config.get("allowedAutoWritePaths", []))
    bad: list[str] = []
    for line in status:
        path = line[3:].strip()
        if not path.startswith(allowed):
            bad.append(line)
    if bad:
        raise AgentError("Working tree has unexpected changes:\n" + "\n".join(bad))


def copy_candidate_to_rules(config: dict[str, Any]) -> None:
    candidate = repo_path("reports/candidate-rules.json")
    if not candidate.exists():
        raise AgentError("candidate rules not found")
    shutil.copyfile(candidate, repo_path(config["rulesPath"]))


def write_decision(decision: dict[str, Any], config: dict[str, Any]) -> None:
    save_json(config["decisionPath"], decision)


def commit_allowed_changes(config: dict[str, Any]) -> bool:
    allowed = config.get("allowedAutoWritePaths", [])
    changed = run(["git", "status", "--porcelain"], check=True).stdout.splitlines()
    if not changed:
        return False
    paths: list[str] = []
    for line in changed:
        path = line[3:].strip()
        if any(path == a or path.startswith(a.rstrip("/") + "/") for a in allowed):
            paths.append(path)
        else:
            raise AgentError(f"Refusing to commit non-allowed path: {path}")
    run(["git", "add", "--"] + paths)
    run(["git", "commit", "-m", "Update strategy rules from validated report"])
    return True


def run_backtest_cmd(markets: int, candles: int) -> None:
    run(["python", "agent/backtest.py", "--markets", str(markets), "--candles", str(candles)])


def run_optimize_cmd() -> None:
    run(["python", "agent/optimize_rules.py"])


def process_once(args: argparse.Namespace) -> dict[str, Any]:
    config = load_json("strategy/config.json")
    policy = load_json("agent/merge-policy.json")
    branch = config.get("watchBranch", "fix/runtime-phone-error")
    if not args.no_pull:
        git_pull(branch)
    ensure_clean_allowed(config)

    report_path = repo_path(config["reportPath"])
    before_hash = file_hash(report_path)
    if not report_path.exists():
        decision = {"ok": False, "reason": "report missing", "reportPath": config["reportPath"]}
        write_decision(decision, config)
        return decision

    report = load_json(report_path)
    live = evaluate_live_report(report, policy)
    save_json(config["decisionPath"], {"phase": "live", **live})
    if not live["ok"] and not args.force:
        return {"ok": False, "phase": "live", "reason": "live gate failed", "live": live}

    run_backtest_cmd(args.markets, args.candles)
    backtest = load_json("reports/backtest-latest.json")
    bt_summary = backtest.get("summary", {})
    min_sample = int(policy.get("minSampleSize", 30))
    bt_ok = int(bt_summary.get("sampleSize", 0) or 0) >= min_sample and float(bt_summary.get("mfeMaeRatio", 0.0) or 0.0) >= float(policy.get("performanceGate", {}).get("mfeMaeRatioMin", 1.1))
    if not bt_ok and not args.force:
        decision = {"ok": False, "phase": "backtest", "reason": "backtest gate failed", "backtestSummary": bt_summary, "reportHash": before_hash}
        write_decision(decision, config)
        return decision

    run_optimize_cmd()
    candidate = load_json("reports/candidate-rules.json")
    base = load_json(config["rulesPath"])
    errors = validate_rules(candidate, policy, base=base)
    if errors:
        decision = {"ok": False, "phase": "validate", "errors": errors, "reportHash": before_hash}
        write_decision(decision, config)
        return decision

    if candidate == base:
        decision = {"ok": True, "phase": "noop", "reason": "candidate equals current rules", "reportHash": before_hash}
        write_decision(decision, config)
        return decision

    copy_candidate_to_rules(config)
    decision = {
        "ok": True,
        "phase": "rules-updated",
        "reportHash": before_hash,
        "live": live,
        "backtestSummary": bt_summary,
    }
    write_decision(decision, config)

    committed = False
    if config.get("autoCommit", True) and not args.no_commit:
        committed = commit_allowed_changes(config)
    if committed and (config.get("autoPush", True) or env_bool("AUTO_PUSH", False)) and not args.no_push:
        run(["git", "push", "origin", branch])
    decision["committed"] = committed
    return decision


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--force", action="store_true", help="continue through gates for dry-run/testing")
    parser.add_argument("--no-pull", action="store_true")
    parser.add_argument("--no-commit", action="store_true")
    parser.add_argument("--no-push", action="store_true")
    parser.add_argument("--markets", type=int, default=20)
    parser.add_argument("--candles", type=int, default=160)
    args = parser.parse_args()
    config = load_json("strategy/config.json")
    poll = int(config.get("pollSeconds", 300))
    last_hash = None
    while True:
        try:
            report_path = repo_path(config["reportPath"])
            current_hash = file_hash(report_path)
            if args.once or current_hash != last_hash:
                decision = process_once(args)
                print(decision)
                last_hash = current_hash
        except Exception as exc:  # keep EC2 watcher alive
            print(f"WATCHER ERROR: {exc}")
        if args.once:
            break
        time.sleep(poll)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
