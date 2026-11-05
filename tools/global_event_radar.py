#!/usr/bin/env python3
"""Pre-event news discovery, NOT a verified probability or trade signal.

Runs alongside the price scanner. News hits are leads for subsequent primary-source
verification in the 07:50/20:50 briefing, never automatic trade recommendations.
"""
import datetime as dt
import json
import os
import urllib.parse
import urllib.request
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "reports" / "global_event_radar_latest.json"
TOPICS = {
    "elections_policy": '(election OR "polling" OR "prediction market" OR tariff OR sanctions)',
    "central_banks": '("Federal Reserve" OR FOMC OR "Bank of Korea" OR ECB OR "interest rate decision")',
    "regulation_law": '(antitrust OR "FDA approval" OR "court ruling" OR "export controls")',
    "geopolitics": '(geopolitical OR ceasefire OR "oil supply" OR "trade restrictions")',
}
MAX_PER_TOPIC = 12

def fetch(topic):
    # GDELT is a public NEWS DISCOVERY index, not an official event calendar.
    params = urllib.parse.urlencode({
        "query": topic + " sourcelang:english",
        "mode": "ArtList", "format": "json", "maxrecords": MAX_PER_TOPIC,
        "timespan": "1d", "sort": "datedesc",
    })
    req = urllib.request.Request(
        "https://api.gdeltproject.org/api/v2/doc/doc?" + params,
        headers={"User-Agent": "global-event-radar/1.0"},
    )
    with urllib.request.urlopen(req, timeout=18) as response:
        payload = json.load(response)
    return [
        {
            "title": str(a.get("title", ""))[:300],
            "url": a.get("url"),
            "source": a.get("domain"),
            "published": a.get("seendate"),
            "status": "UNVERIFIED_NEWS_LEAD",
        }
        for a in payload.get("articles", [])
        if a.get("url") and a.get("title")
    ]

def main():
    results, errors = {}, {}
    for name, query in TOPICS.items():
        try:
            results[name] = fetch(query)
        except Exception as exc:
            results[name] = []
            errors[name] = type(exc).__name__ + ": " + str(exc)[:160]
    now = dt.datetime.now(ZoneInfo("Asia/Seoul")).isoformat()
    report = {
        "schemaVersion": 1,
        "generatedAtKst": now,
        "purpose": "PRE_EVENT_DISCOVERY_ONLY",
        "source": "GDELT public news index; articles require primary-source verification",
        "notVerified": ["event occurrence", "future event date", "event probability", "trade direction"],
        "topics": results,
        "errors": errors,
        "status": "PARTIAL" if errors else "FETCHED_UNVERIFIED",
        "alertEligible": False,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"generatedAtKst": now, "hits": {k: len(v) for k,v in results.items()}, "errors": errors}))
    if len(errors) == len(TOPICS) and os.getenv("FAIL_ON_ALL_EVENT_SOURCES") == "1":
        raise SystemExit(2)

if __name__ == "__main__":
    main()
