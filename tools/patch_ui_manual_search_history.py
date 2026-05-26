# Trigger app-ui-maintenance workflow after manual UI patch changes.
from pathlib import Path

p = Path('app/src/main/java/com/cryptotradecoach/MainActivity.kt')
s = p.read_text(encoding='utf-8')

s = s.replace(
'''                    performanceRows = state.performanceRows,
                    scanDiagnostics = state.scanDiagnostics,''',
'''                    performanceRows = state.performanceRows,
                    manualStrategy = state.manualStrategy,
                    manualMessage = state.manualMessage,
                    scanDiagnostics = state.scanDiagnostics,''')

s = s.replace(
'''                    onPerformanceRefresh = viewModel::refreshPerformance,
                    onReportUpload = viewModel::uploadLatestReport,''',
'''                    onPerformanceRefresh = viewModel::refreshPerformance,
                    onManualAnalyze = viewModel::analyzeManualSymbol,
                    onManualSave = viewModel::saveManualStrategy,
                    onReportUpload = viewModel::uploadLatestReport,''')

s = s.replace(
'''    performanceRows: List<StrategyPerformanceEntity>,
    scanDiagnostics: ScanDiagnostics,''',
'''    performanceRows: List<StrategyPerformanceEntity>,
    manualStrategy: TradeStrategy?,
    manualMessage: String?,
    scanDiagnostics: ScanDiagnostics,''')

s = s.replace(
'''    onPerformanceRefresh: () -> Unit,
    onReportUpload: (GitHubSettings) -> Unit,''',
'''    onPerformanceRefresh: () -> Unit,
    onManualAnalyze: (String) -> Unit,
    onManualSave: () -> Unit,
    onReportUpload: (GitHubSettings) -> Unit,''')

s = s.replace('val tabs = listOf("Current", "History", "Performance", "Rules", "Settings")', 'val tabs = listOf("Current", "Search", "History", "Performance", "Rules", "Settings")')

s = s.replace(
'''            0 -> CurrentStrategiesTab(activeStrategies, scanDiagnostics, lastScanAt, minimumScore)
            1 -> StrategyHistoryTab(historyBySymbol)
            2 -> PerformanceTab(performanceRows, onPerformanceRefresh)
            3 -> RulesTab(currentRulesText, settingsMessage, onRulesRefresh) { onRulesDownload(gitHubSettings) }
            4 -> SettingsTab(''',
'''            0 -> CurrentStrategiesTab(activeStrategies, scanDiagnostics, lastScanAt, minimumScore)
            1 -> ManualSearchTab(manualStrategy, manualMessage, onManualAnalyze, onManualSave)
            2 -> StrategyHistoryTab(historyBySymbol)
            3 -> PerformanceTab(performanceRows, onPerformanceRefresh)
            4 -> RulesTab(currentRulesText, settingsMessage, onRulesRefresh) { onRulesDownload(gitHubSettings) }
            5 -> SettingsTab(''')

old_history = '''@Composable
private fun StrategyHistoryTab(historyBySymbol: Map<String, List<StrategyHistoryEntity>>) {
    var menuExpanded by remember { mutableStateOf(false) }
    var selectedSymbol by remember(historyBySymbol) { mutableStateOf(historyBySymbol.keys.firstOrNull()) }
    val selectedHistory = selectedSymbol?.let { historyBySymbol[it].orEmpty() }.orEmpty()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (historyBySymbol.isEmpty()) item { EmptyCard("No strategy history.") } else {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("종목별 시간순 전략 변화", fontWeight = FontWeight.Bold)
                        Text("각 줄은 시간 → 이벤트 → 현재 매매전략 순서입니다.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { menuExpanded = true }) { Text(selectedSymbol ?: "Select symbol") }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            historyBySymbol.keys.forEach { symbol ->
                                DropdownMenuItem(text = { Text(symbol) }, onClick = { selectedSymbol = symbol; menuExpanded = false })
                            }
                        }
                    }
                }
                items(selectedHistory) { HistoryCard(it) }
            }
        }
    }
}
'''
new_history = '''@Composable
private fun StrategyHistoryTab(historyBySymbol: Map<String, List<StrategyHistoryEntity>>) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("종목별 히스토리", fontWeight = FontWeight.Bold)
                Text("선택 드롭다운 없이 종목별 섹션을 한 번에 펼쳐서 보여줍니다.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (historyBySymbol.isEmpty()) {
            item { EmptyCard("No strategy history.") }
        } else {
            historyBySymbol.toSortedMap().forEach { (symbol, rows) ->
                item { SymbolHistorySection(symbol, rows) }
            }
        }
    }
}

@Composable
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
s = s.replace(old_history, new_history)

manual_tab = '''
@Composable
private fun ManualSearchTab(
    manualStrategy: TradeStrategy?,
    manualMessage: String?,
    onManualAnalyze: (String) -> Unit,
    onManualSave: () -> Unit,
) {
    var symbol by rememberSaveable { mutableStateOf("") }
    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("수동 종목 분석", fontWeight = FontWeight.Bold)
                    Text("업비트 KRW 종목을 입력하면 현재 rules 기준으로 매매 전략을 계산합니다. 예: XRP 또는 KRW-XRP", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = symbol, onValueChange = { symbol = it }, label = { Text("Symbol") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onManualAnalyze(symbol) }) { Text("Analyze") }
                        OutlinedButton(onClick = onManualSave, enabled = manualStrategy != null) { Text("Save strategy") }
                    }
                    manualMessage?.let { Text(it) }
                }
            }
        }
        manualStrategy?.let { item { StrategyCard(it) } }
    }
}
'''
if 'private fun ManualSearchTab(' not in s:
    s = s.replace(new_history, new_history + manual_tab)

# Dropdown imports become unused after replacing history selector.
s = s.replace('import androidx.compose.material3.DropdownMenu\n', '')
s = s.replace('import androidx.compose.material3.DropdownMenuItem\n', '')
# Keep clickable import because HistoryCard still uses Modifier.clickable.
if 'import androidx.compose.foundation.clickable\n' not in s:
    s = s.replace('import androidx.compose.foundation.layout.Arrangement\n', 'import androidx.compose.foundation.clickable\nimport androidx.compose.foundation.layout.Arrangement\n')

p.write_text(s, encoding='utf-8')