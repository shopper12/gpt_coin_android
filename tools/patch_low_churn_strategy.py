from pathlib import Path

rules_path = Path("rules/strategy-rules.json")
service_path = Path("app/src/main/java/com/cryptotradecoach/service/CoinScannerService.kt")
state_path = Path("app/src/main/java/com/cryptotradecoach/service/ScannerStateStore.kt")

# 1) Make rescue/pre-pump promotion stricter. Rescue should be a watch filter first,
# not a low-threshold ACTIVE generator.
service = service_path.read_text(encoding="utf-8")
service_replacements = {
    "if (score < minimumScore.coerceAtMost(68.0)) return null": "if (score < (minimumScore + 3.0).coerceAtMost(86.0)) return null",
    ".take(12)\n            .toSet()": ".take(6)\n            .toSet()",
    "return if (recent.size >= 3) recent else FALLBACK_RESCUE_MARKETS": "return if (recent.size >= 3) recent else emptySet()",
    "BtcRegime.BULL -> 2.5": "BtcRegime.BULL -> 3.5",
    "BtcRegime.NEUTRAL -> 4.0": "BtcRegime.NEUTRAL -> 5.0",
}
for old, new in service_replacements.items():
    if old not in service:
        raise SystemExit(f"Missing expected CoinScannerService text: {old}")
    service = service.replace(old, new)
service_path.write_text(service, encoding="utf-8")

# 2) Raise the default score floor and reduce scan churn slightly.
state = state_path.read_text(encoding="utf-8")
state_replacements = {
    "const val DEFAULT_SCAN_INTERVAL_MS = 180_000L": "const val DEFAULT_SCAN_INTERVAL_MS = 300_000L",
    "const val DEFAULT_MINIMUM_SCORE = 74.0": "const val DEFAULT_MINIMUM_SCORE = 78.0",
}
for old, new in state_replacements.items():
    if old not in state:
        raise SystemExit(f"Missing expected ScannerStateStore text: {old}")
    state = state.replace(old, new)
state_path.write_text(state, encoding="utf-8")

print("Patched low-churn behavior: stricter rescue ACTIVE threshold, fewer rescue markets, slower default scan, higher default score")
