from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"expected pattern not found in {path}: {old[:120]!r}")
    if text.count(old) != 1:
        raise RuntimeError(f"expected exactly one pattern in {path}, found {text.count(old)}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    main_activity = ROOT / "app/src/main/java/com/cryptotradecoach/MainActivity.kt"
    my_stocks = ROOT / "app/src/main/java/com/cryptotradecoach/MyStocksActivity.kt"
    recommendations = ROOT / "app/src/main/java/com/cryptotradecoach/RecommendationHistoryActivity.kt"

    replace_once(
        main_activity,
        '    val tabs = listOf("Current", "Search", "History", "Rules", "Settings")',
        '    val tabs = listOf("Current", "Search", "History", "Rules")',
    )
    replace_once(
        main_activity,
        '            4 -> SettingsTab(isRunning, scanIntervalMs, maxDisplayCount, minimumScore, gitHubSettings, settingsMessage, onStart, onStop, onIntervalSelected, onMaxDisplayChanged, onMinimumScoreChanged, onGitHubSettingsSaved, onGitHubSettingsTest, onRulesDownload, onReportUpload, onOpenInstallPermissionSettings, onDownloadAndInstallLatestApk)\n',
        '',
    )
    replace_once(
        main_activity,
        'private fun SettingsTab(isRunning: Boolean, scanIntervalMs: Long, maxDisplayCount: Int, minimumScore: Double, gitHubSettings: GitHubSettings, settingsMessage: String?, onStart: () -> Unit, onStop: () -> Unit, onIntervalSelected: (Long) -> Unit, onMaxDisplayChanged: (Int) -> Unit, onMinimumScoreChanged: (Double) -> Unit, onGitHubSettingsSaved: (GitHubSettings) -> Unit, onGitHubSettingsTest: (GitHubSettings) -> Unit, onRulesDownload: (GitHubSettings) -> Unit, onReportUpload: (GitHubSettings) -> Unit, onOpenInstallPermissionSettings: () -> Unit, onDownloadAndInstallLatestApk: (GitHubSettings) -> Unit) {',
        'internal fun GlobalSettingsPanel(isRunning: Boolean, scanIntervalMs: Long, maxDisplayCount: Int, minimumScore: Double, gitHubSettings: GitHubSettings, settingsMessage: String?, onStart: () -> Unit, onStop: () -> Unit, onIntervalSelected: (Long) -> Unit, onMaxDisplayChanged: (Int) -> Unit, onMinimumScoreChanged: (Double) -> Unit, onGitHubSettingsSaved: (GitHubSettings) -> Unit, onGitHubSettingsTest: (GitHubSettings) -> Unit, onRulesDownload: (GitHubSettings) -> Unit, onReportUpload: (GitHubSettings) -> Unit, onOpenInstallPermissionSettings: () -> Unit, onDownloadAndInstallLatestApk: (GitHubSettings) -> Unit) {',
    )

    replace_once(
        my_stocks,
        '            Text("${item.name} (${item.ticker})", fontWeight = FontWeight.Bold)',
        '            KoreanStockIdentityLabel(ticker = item.ticker, preferredName = item.name)',
    )
    replace_once(
        my_stocks,
        '            Text("${item.name} (${item.ticker})", fontWeight = FontWeight.Bold)',
        '            KoreanStockIdentityLabel(ticker = item.ticker, preferredName = item.name)',
    )
    replace_once(
        my_stocks,
        '            Text("${signal.name} (${signal.ticker})", fontWeight = FontWeight.Bold)',
        '            KoreanStockIdentityLabel(ticker = signal.ticker, preferredName = holding?.name ?: signal.name)',
    )

    replace_once(
        recommendations,
        '        TableCell("${record.name} (${record.ticker})", 190.dp, true)',
        '        KoreanStockIdentityLabel(\n            ticker = record.ticker,\n            preferredName = record.name,\n            modifier = Modifier.width(190.dp).padding(horizontal = 4.dp),\n            bold = true,\n            resolveKoreanCode = record.assetClass in setOf("KR_STOCK", "KR_ETF"),\n        )',
    )


if __name__ == "__main__":
    main()
