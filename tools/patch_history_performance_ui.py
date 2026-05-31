from pathlib import Path

p = Path('app/src/main/java/com/cryptotradecoach/MainActivity.kt')
s = p.read_text(encoding='utf-8')

# Ensure horizontal scroll import for one-line per-symbol strategy timeline.
if 'import androidx.compose.foundation.horizontalScroll\n' not in s:
    s = s.replace('import androidx.compose.foundation.clickable\n', 'import androidx.compose.foundation.clickable\nimport androidx.compose.foundation.horizontalScroll\n', 1)
if 'import androidx.compose.foundation.rememberScrollState\n' not in s:
    s = s.replace('import androidx.compose.foundation.horizontalScroll\n', 'import androidx.compose.foundation.horizontalScroll\nimport androidx.compose.foundation.rememberScrollState\n', 1)

old_symbol = '''@Composable
private fun SymbolHistorySection(symbol: String, rows: List<StrategyHistoryEntity>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(symbol, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            rows.take(12).forEach { HistoryCard(it) }
            if (rows.size > 12) Text("외 ${rows.size - 12}건 더 있음", style = MaterialTheme.typography.bodySmall)
        }
    }
}
'''
new_symbol = '''@Composable
private fun SymbolHistorySection(symbol: String, rows: List<StrategyHistoryEntity>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(symbol, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rows.take(20).forEach { HistoryTimelineChip(it) }
            }
            if (rows.size > 20) Text("외 ${rows.size - 20}건 더 있음", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HistoryTimelineChip(history: StrategyHistoryEntity) {
    Card {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(history.createdAt.toTimeText(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            Text(history.eventType.toKoreanEventName(), style = MaterialTheme.typography.bodySmall)
            Text(history.newSummary?.toCompactPlan() ?: history.message, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}
'''
if old_symbol in s:
    s = s.replace(old_symbol, new_symbol, 1)

# Make performance row show current real-time judgement directly.
s = s.replace(
'''            Text("${row.symbol} | ${row.strategyType} | ${if (row.isComplete) "COMPLETE" else "OPEN"}", fontWeight = FontWeight.Bold)
            Text("Created: ${row.createdAt.toTimeText()} | Updated: ${row.lastUpdatedAt.toTimeText()}")
            Text("Entry: ${row.entryPrice.price()} | Latest: ${row.latestPrice.price()} | Score: ${row.score.one()}")
            Text("Return: 5m ${row.return5m.percentOrDash()} / 15m ${row.return15m.percentOrDash()} / 30m ${row.return30m.percentOrDash()} / 60m ${row.return60m.percentOrDash()}")
            Text("MFE: ${row.mfePct.percent()} | MAE: ${row.maePct.percent()}")
            Text("Target1: ${row.target1Hit} | Target2: ${row.target2Hit} | Stop: ${row.stopHit} | Expired: ${row.expired}")
            Text("Rank by value: ${row.rankByTradeValue}")
''',
'''            Text("${row.symbol} | ${row.strategyType} | ${row.outcomeLabel()}", fontWeight = FontWeight.Bold)
            Text("Created: ${row.createdAt.toTimeText()} | Updated: ${row.lastUpdatedAt.toTimeText()}")
            Text("실제가: ${row.latestPrice.price()} | 진입가: ${row.entryPrice.price()} | 현재수익: ${row.liveReturnPct().percent()} | Score: ${row.score.one()}")
            Text("Return: 5m ${row.return5m.percentOrDash()} / 15m ${row.return15m.percentOrDash()} / 30m ${row.return30m.percentOrDash()} / 60m ${row.return60m.percentOrDash()}")
            Text("MFE: ${row.mfePct.percent()} | MAE: ${row.maePct.percent()}")
            Text("판정: ${row.outcomeDetail()} | 목표1 ${row.target1.price()} | 목표2 ${row.target2.price()} | 손절 ${row.stopLoss.price()}")
            Text("Rank by value: ${row.rankByTradeValue}")
''',
1,
)

if 'private fun StrategyPerformanceEntity.liveReturnPct()' not in s:
    helper = '''
private fun StrategyPerformanceEntity.liveReturnPct(): Double {
    return if (entryPrice <= 0.0) 0.0 else (latestPrice - entryPrice) / entryPrice * 100.0
}

private fun StrategyPerformanceEntity.outcomeLabel(): String = when {
    target2Hit -> "성공-최종목표"
    target1Hit -> "성공-1차목표"
    stopHit -> "실패-손절"
    expired -> "만료"
    isComplete -> "완료"
    liveReturnPct() > 0.0 -> "진행중-수익"
    liveReturnPct() < 0.0 -> "진행중-손실"
    else -> "진행중"
}

private fun StrategyPerformanceEntity.outcomeDetail(): String = when {
    target2Hit -> "target2 도달"
    target1Hit -> "target1 도달"
    stopHit -> "stop 도달"
    expired -> "60분 검증 만료"
    isComplete -> "완료"
    else -> "진행중, 현재 ${liveReturnPct().percent()}"
}

'''
    s = s.replace('private fun TradeStrategy.koreanPlanLine(): String {', helper + 'private fun TradeStrategy.koreanPlanLine(): String {', 1)

p.write_text(s, encoding='utf-8')
print('patched history and performance UI')
