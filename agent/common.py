#!/usr/bin/env python3
"""Shared utilities for the strategy automation agent.

The agent intentionally uses only the Python standard library so it can run on a
small EC2 instance without extra packages.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


class AgentError(RuntimeError):
    pass


@dataclass(frozen=True)
class RunResult:
    code: int
    stdout: str
    stderr: str


def repo_path(*parts: str) -> Path:
    return ROOT.joinpath(*parts)


def load_json(path: str | Path, default: Any | None = None) -> Any:
    p = Path(path)
    if not p.is_absolute():
        p = repo_path(str(p))
    if not p.exists():
        if default is not None:
            return default
        raise AgentError(f"JSON file not found: {p}")
    with p.open("r", encoding="utf-8") as f:
        return json.load(f)


def save_json(path: str | Path, data: Any) -> None:
    p = Path(path)
    if not p.is_absolute():
        p = repo_path(str(p))
    p.parent.mkdir(parents=True, exist_ok=True)
    with p.open("w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")


def save_text(path: str | Path, text: str) -> None:
    p = Path(path)
    if not p.is_absolute():
        p = repo_path(str(p))
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8", newline="\n")


def run(cmd: list[str], check: bool = True, cwd: Path | None = None) -> RunResult:
    proc = subprocess.run(
        cmd,
        cwd=str(cwd or ROOT),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    result = RunResult(proc.returncode, proc.stdout.strip(), proc.stderr.strip())
    if check and proc.returncode != 0:
        raise AgentError(f"Command failed: {' '.join(cmd)}\nSTDOUT:\n{result.stdout}\nSTDERR:\n{result.stderr}")
    return result


def now_ms() -> int:
    return int(time.time() * 1000)


def env_bool(name: str, default: bool = False) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "y", "on"}


def http_json(url: str, headers: dict[str, str] | None = None, timeout: int = 15) -> Any:
    request = Request(url, headers={"User-Agent": "gpt-coin-agent", **(headers or {})})
    try:
        with urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        raise AgentError(f"HTTP {e.code} for {url}: {body[:300]}") from e
    except URLError as e:
        raise AgentError(f"Network error for {url}: {e}") from e


def numeric_paths(data: Any, prefix: tuple[str, ...] = ()) -> Iterable[tuple[tuple[str, ...], float]]:
    if isinstance(data, dict):
        for key, value in data.items():
            yield from numeric_paths(value, prefix + (str(key),))
    elif isinstance(data, list):
        for idx, value in enumerate(data):
            yield from numeric_paths(value, prefix + (str(idx),))
    elif isinstance(data, (int, float)) and not isinstance(data, bool):
        yield prefix, float(data)


def get_path(data: dict[str, Any], path: tuple[str, ...]) -> Any:
    cur: Any = data
    for part in path:
        cur = cur[int(part)] if isinstance(cur, list) else cur[part]
    return cur


def set_path(data: dict[str, Any], path: tuple[str, ...], value: Any) -> None:
    cur: Any = data
    for part in path[:-1]:
        cur = cur[int(part)] if isinstance(cur, list) else cur[part]
    last = path[-1]
    if isinstance(cur, list):
        cur[int(last)] = value
    else:
        cur[last] = value


def clamp_numeric_change(base: float, proposed: float, max_pct: float) -> float:
    if base == 0:
        return proposed
    limit = abs(base) * max_pct / 100.0
    return max(base - limit, min(base + limit, proposed))


def diff_numeric(base: dict[str, Any], candidate: dict[str, Any]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    base_values = {path: value for path, value in numeric_paths(base)}
    for path, new_value in numeric_paths(candidate):
        old_value = base_values.get(path)
        if old_value is None or old_value == new_value:
            continue
        pct = 0.0 if old_value == 0 else ((new_value - old_value) / old_value) * 100.0
        out.append({"path": ".".join(path), "before": old_value, "after": new_value, "changePct": pct})
    return out


def fail(message: str, code: int = 1) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(code)
