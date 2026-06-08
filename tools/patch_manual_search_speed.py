from pathlib import Path

path = Path("app/src/main/java/com/cryptotradecoach/data/UpbitApi.kt")
text = path.read_text(encoding="utf-8")

replacements = {
    "val fourHourCandles = fetchMinuteCandlesFast(market, unit = 240, count = 40)": "val fourHourCandles = emptyList<Candle>()",
    "private const val MANUAL_CONNECT_TIMEOUT_MS = 2_500": "private const val MANUAL_CONNECT_TIMEOUT_MS = 900",
    "private const val MANUAL_READ_TIMEOUT_MS = 2_500": "private const val MANUAL_READ_TIMEOUT_MS = 900",
}

changed = text
for old, new in replacements.items():
    if old not in changed:
        raise SystemExit(f"Missing expected text: {old}")
    changed = changed.replace(old, new)

if changed == text:
    raise SystemExit("No changes made")

path.write_text(changed, encoding="utf-8")
print("Patched manual search: skipped 240m candle fetch and reduced manual HTTP timeouts")
