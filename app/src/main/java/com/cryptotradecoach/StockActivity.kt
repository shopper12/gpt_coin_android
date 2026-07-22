package com.cryptotradecoach

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptotradecoach.data.IctAnalysis
import com.cryptotradecoach.data.IctCandle
import com.cryptotradecoach.data.IctChartAnalyzer
import com.cryptotradecoach.data.WorkflowDispatchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val STOCK_API_BASE_URL = "https://stock-scanner-api-5sk6.onrender.com"
private const val STOCK_LATEST_URL = "$STOCK_API_BASE_URL/api/latest"
private const val STOCK_PERFORMANCE_URL = "$STOCK_API_BASE_URL/api/recommendation-performance"
private const val STOCK_BACKTEST_URL = "$STOCK_API_BASE_URL/api/kr-backtest"
private const val STOCK_STRATEGY_URL = "$STOCK_API_BASE_URL/api/kr-stock-strategy"
private const val STOCK_CHART_URL = "$STOCK_API_BASE_URL/api/kr-stock-chart"

class StockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val workflowRepository = WorkflowDispatchRepository(this)
        setContent {
            MaterialTheme {
                StockMenuScreen(
                    onBack = { finish() },
                    onWorkflow = { workflowRepository.dispatchUnifiedStrategyMonitor() },
                    onDocs = { openUrl(DOCS_URL) },
                    onStockRepo = { openUrl(STOCK_REPO_URL) },
                )
            }
        }
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private companion object {
        private const val DOCS_URL = "https://github.com/shopper12/gpt_coin_android/blob/main/docs/unified-trading-app.md"
        private const val STOCK_REPO_URL = "https://github.com/shopper12/stock_scanner"
    }
}

@Composable
private fun StockMenuScreen(
    onBack: () -> Unit,
    onWorkflow: suspend () -> String,
    onDocs: () -> Unit,
    onStockRepo: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf(StockUiState()) }
    var query by remember { mutableStateOf("") }
    var strategy by remember { mutableStateOf<StockStrategy?>(null) }
    var searching by remember { mutableStateOf(false) }
    var workflowRunning by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            uiState = uiState.copy(loading = true, error = null, message = null)
            runCatching {
                val snapshot = fetchStockSnapshot()
                val performance = fetchRecommendationPerformance()
                val backtest = fetchStockBacktest()
                uiState = StockUiState(
                    loading = false,
                    snapshot = snapshot,
                    performance = performance,
                    backtest = backtest,
                    message = "주식 서버 최신 데이터를 불러왔습니다.",
                )
            }.onFailure { error ->
                uiState = uiState.copy(loading = false, error = compactError(error))
            }
        }
    }

    fun runWorkflow() {
        if (workflowRunning) return
        scope.launch {
            workflowRunning = true
            uiState = uiState.copy(error = null, message = "자가검증 실행을 요청하는 중입니다.")
            runCatching { onWorkflow() }
                .onSuccess { uiState = uiState.copy(message = it) }
                .onFailure { uiState = uiState.copy(error = compactError(it), message = null) }
            workflowRunning = false
        }
    }

    fun search() {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            uiState = uiState.copy(error = "종목명 또는 6자리 코드를 입력하세요. 예: 삼성전자, 005930")
            return
        }
        scope.launch {
            searching = true
            uiState = uiState.copy(error = null, message = "기본 전략과 ICT 차트 구조를 함께 분석하는 중입니다.")
            runCatching {
                val base = fetchStockStrategy(trimmed)
                val ict = fetchStockIctAnalysis(base.code)
                applyIctToStrategy(base, ict)
            }.onSuccess {
                strategy = it
                uiState = uiState.copy(message = "종목 분석 완료: ${it.name.ifBlank { it.code }} ${it.action} / ICT ${it.ict.bias}")
            }.onFailure {
                uiState = uiState.copy(error = compactError(it), message = null)
            }
            searching = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("← 홈") }
                OutlinedButton(onClick = { runWorkflow() }, enabled = !workflowRunning) {
                    Text(if (workflowRunning) "요청 중" else "자가검증 실행")
                }
                OutlinedButton(onClick = onDocs) { Text("문서") }
            }
        }
        item {
            Text("Stock Strategy", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("기본 추세·수급 점수와 ICT 차트 구조를 분리 계산한 뒤 최종 매매 판단에서 함께 사용합니다.")
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("주식 전략 세트", fontWeight = FontWeight.Bold)
                    Text("KR_SHORT_STOCK: 한국 단기 일반계좌")
                    Text("KR_RETIREMENT_ETF: 퇴직연금 ETF")
                    Text("US_LONG_ETF: 미국 장기 ETF 분할매수")
                    Text("FX_CONVERSION: USD/KRW 환전 판단")
                    Text("섹터명은 참고 분류만 표시하며 추천 점수·순위에는 사용하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { refresh() }, enabled = !uiState.loading) { Text(if (uiState.loading) "Loading" else "새로고침") }
                        OutlinedButton(onClick = onStockRepo) { Text("원본 엔진") }
                    }
                }
            }
        }
        if (uiState.error != null) item { InfoCard("오류: ${uiState.error}") }
        if (uiState.message != null) item { InfoCard(uiState.message ?: "") }
        item { StockSummaryCard(uiState.snapshot) }
        item { PerformanceCard(uiState.performance) }
        item { BacktestCard(uiState.backtest, workflowRunning) { runWorkflow() } }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("종목 직접 검색 + ICT", fontWeight = FontWeight.Bold)
                    Text("종목명 또는 코드를 입력하면 진입·손절·목표가와 함께 BOS/CHOCH, 유동성 스윕, FVG, 프리미엄·디스카운트를 계산합니다.")
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("예: 삼성전자 또는 005930") },
                        singleLine = true,
                    )
                    Button(onClick = { search() }, enabled = !searching) { Text(if (searching) "ICT 분석 중" else "종목 분석") }
                }
            }
        }
        if (strategy != null) item { StrategyCard(strategy!!) }
        val stocks = uiState.snapshot?.stocks.orEmpty()
        if (stocks.isEmpty()) {
            item { InfoCard(if (uiState.loading) "주식 후보를 불러오는 중입니다." else "현재 표시할 한국 단기 후보가 없습니다. 자가검증 실행 후 다시 확인하세요.") }
        } else {
            items(stocks) { stock -> StockCandidateCard(stock) }
        }
    }
}

@Composable
private fun StockSummaryCard(snapshot: StockSnapshot?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("현재 주식 스캔", fontWeight = FontWeight.Bold)
            if (snapshot == null) {
                Text("아직 서버 데이터를 불러오지 못했습니다.")
                return@Column
            }
            Text("생성: ${snapshot.createdAtKst} / mode=${snapshot.mode}")
            Text("후보 ${snapshot.stocks.size}개 / 시세 확인 ${snapshot.quoteOk}/${snapshot.total} (${formatRatio(snapshot.quoteOkRate)})")
            val topSectors = snapshot.sectors.take(4).joinToString(" · ") { "${it.sector} ${it.selectedCount}개" }
            if (topSectors.isNotBlank()) Text("참고 섹터 분포: $topSectors")
        }
    }
}

@Composable
private fun PerformanceCard(performance: RecommendationPerformance?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("추천 성과", fontWeight = FontWeight.Bold)
            if (performance == null) {
                Text("성과 리포트를 아직 읽지 못했습니다.")
                return@Column
            }
            Text("생성: ${performance.createdAtKst}")
            Text("누적 ${performance.totalRecommendations}개 / 계산 가능 ${performance.measurableCount}개")
            Text("평균 ${formatSignedPercent(performance.avgPnlPct)} / 중앙값 ${formatSignedPercent(performance.medianPnlPct)} / 승률 ${formatRatio(performance.winRate)}")
            Text("손절 ${formatRatio(performance.hitStopRate)} / 목표1 ${formatRatio(performance.hitTarget1Rate)} / 목표2 ${formatRatio(performance.hitTarget2Rate)}")
        }
    }
}

@Composable
private fun BacktestCard(backtest: StockBacktest?, workflowRunning: Boolean, onWorkflow: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("주식 백테스트/자가진화", fontWeight = FontWeight.Bold)
            if (backtest == null) {
                Text("서버 백테스트 리포트가 아직 없습니다. 통합 자가검증을 실행하세요.")
            } else {
                Text("생성: ${backtest.createdAtKst} / 채택=${if (backtest.accepted) "예" else "아니오"} / 개선 ${formatNumber(backtest.improvement)}")
                Text("기준: 거래 ${backtest.baseTrades}건 / 평균 ${formatSignedPercent(backtest.baseAvgReturnPct)} / 승률 ${formatRatio(backtest.baseWinRate)} / PF ${formatNumber(backtest.baseProfitFactor)}")
                Text("최선: 거래 ${backtest.bestTrades}건 / 평균 ${formatSignedPercent(backtest.bestAvgReturnPct)} / 승률 ${formatRatio(backtest.bestWinRate)} / PF ${formatNumber(backtest.bestProfitFactor)}")
            }
            Button(onClick = onWorkflow, enabled = !workflowRunning) {
                Text(if (workflowRunning) "실행 요청 중" else "통합 백테스트/룰 진화 실행")
            }
        }
    }
}

@Composable
private fun StockCandidateCard(stock: StockCandidate) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${stock.name.ifBlank { stock.code }}(${stock.code}) | ${stock.sector}/${stock.strategyType}", fontWeight = FontWeight.Bold)
            Text("점수 ${formatNumber(stock.score)} / 현재가 ${formatPrice(stock.currentPrice)} / 위험 ${formatNumber(stock.riskPct)}%")
            Text("진입 ${formatPrice(stock.entry)} / 손절 ${formatPrice(stock.stopLoss)} / 목표 ${formatPrice(stock.target1)} → ${formatPrice(stock.target2)}")
            if (stock.reason.isNotBlank()) Text("근거: ${stock.reason}")
            if (stock.failureCondition.isNotBlank()) Text("무효화: ${stock.failureCondition}")
        }
    }
}

@Composable
private fun StrategyCard(strategy: StockStrategy) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${strategy.name.ifBlank { strategy.code }}(${strategy.code}) | ${strategy.sector}/${strategy.setup}", fontWeight = FontWeight.Bold)
            Text("판단: ${strategy.action} / ICT 반영점수 ${formatNumber(strategy.score)} 기준 ${formatNumber(strategy.threshold)}")
            if (strategy.actionReason.isNotBlank()) Text("판단근거: ${strategy.actionReason}")
            Text("현재가 ${formatPrice(strategy.currentPrice)} / 진입 ${formatPrice(strategy.entry)} / 손절 ${formatPrice(strategy.stopLoss)}")
            Text("목표 ${formatPrice(strategy.target1)} → ${formatPrice(strategy.target2)} / 포지션 ${formatPrice(strategy.positionSizeKrw)}원")
            Text("RSI ${formatNumber(strategy.rsi14)} / MA20 괴리 ${formatSignedPercent(strategy.gapMa20Pct)} / 20일 모멘텀 ${formatSignedPercent(strategy.momentum20dPct)}")
            Text("ICT: ${strategy.ict.summary}", fontWeight = FontWeight.Bold)
            Text("구조 ${strategy.ict.structure} / 이벤트 ${strategy.ict.structureEvent}")
            Text("유동성 ${strategy.ict.liquidityEvent} / FVG ${strategy.ict.fairValueGap} / 위치 ${strategy.ict.dealingRangeLocation}")
            if (strategy.ict.preferredEntryLow != null && strategy.ict.preferredEntryHigh != null) {
                Text("ICT 선호 진입 ${formatPrice(strategy.ict.preferredEntryLow)} ~ ${formatPrice(strategy.ict.preferredEntryHigh)} / 무효화 ${strategy.ict.invalidation?.let(::formatPrice) ?: "-"}")
            }
            if (strategy.reason.isNotBlank()) Text("기본 근거: ${strategy.reason}")
            if (strategy.failureCondition.isNotBlank()) Text("무효화: ${strategy.failureCondition}")
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) { Text(text, modifier = Modifier.padding(12.dp)) }
}

private suspend fun fetchStockSnapshot(): StockSnapshot = withContext(Dispatchers.IO) {
    parseStockSnapshot(JSONObject(httpJson("GET", STOCK_LATEST_URL, null)))
}

private suspend fun fetchRecommendationPerformance(): RecommendationPerformance? = withContext(Dispatchers.IO) {
    runCatching { parseRecommendationPerformance(JSONObject(httpJson("GET", STOCK_PERFORMANCE_URL, null))) }.getOrNull()
}

private suspend fun fetchStockBacktest(): StockBacktest? = withContext(Dispatchers.IO) {
    runCatching { parseStockBacktest(JSONObject(httpJson("GET", STOCK_BACKTEST_URL, null))) }.getOrNull()
}

private suspend fun fetchStockStrategy(query: String): StockStrategy = withContext(Dispatchers.IO) {
    parseStockStrategy(JSONObject(httpJson("POST", STOCK_STRATEGY_URL, JSONObject().put("query", query).toString())))
}

private suspend fun fetchStockIctAnalysis(code: String): IctAnalysis = withContext(Dispatchers.IO) {
    val json = JSONObject(httpJson("GET", "$STOCK_CHART_URL?code=$code&days=160", null))
    val array = json.optJSONArray("candles")
    val candles = buildList {
        if (array != null) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    IctCandle(
                        date = item.optString("date"),
                        open = item.optDouble("open", 0.0),
                        high = item.optDouble("high", 0.0),
                        low = item.optDouble("low", 0.0),
                        close = item.optDouble("close", 0.0),
                    )
                )
            }
        }
    }
    IctChartAnalyzer.analyze(candles)
}

private fun applyIctToStrategy(base: StockStrategy, ict: IctAnalysis): StockStrategy {
    val adjustedScore = (base.score + ict.scoreAdjustment).coerceIn(0.0, 100.0)
    var action = base.action
    var actionReason = base.actionReason
    if (ict.bias == "BEARISH" && action.contains("매수")) {
        action = "조건부 대기"
        actionReason += " ICT 약세 구조가 기본 매수 신호와 충돌하여 즉시 진입을 보류합니다."
    } else if (ict.bias == "BULLISH" && action == "조건부 매수" && adjustedScore >= base.threshold + 10.0) {
        action = "매수 후보"
        actionReason += " ICT 상승 구조·유동성 조건이 기본 신호를 확인했습니다."
    } else {
        actionReason += " ICT ${ict.bias} 조정 ${formatSignedPercent(ict.scoreAdjustment)}를 반영했습니다."
    }
    return base.copy(score = adjustedScore, action = action, actionReason = actionReason.trim(), ict = ict)
}

private fun httpJson(method: String, url: String, body: String?): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15_000
        readTimeout = if (method == "POST") 120_000 else 30_000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "UnifiedTradingCoach-Android")
        if (method == "POST") {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
    }
    try {
        if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("HTTP $code: ${text.take(300)}")
        if (text.trimStart().startsWith("<")) error("서버가 JSON 대신 HTML을 반환했습니다. Render 배포/재시작 상태를 확인하세요.")
        return text
    } finally {
        connection.disconnect()
    }
}

private fun parseStockSnapshot(json: JSONObject): StockSnapshot {
    val quality = json.optJSONObject("data_quality") ?: JSONObject()
    val stocksArray = json.optJSONArray("kr_short_stocks")
    val stocks = buildList {
        if (stocksArray != null) {
            for (i in 0 until stocksArray.length()) {
                val item = stocksArray.optJSONObject(i) ?: continue
                add(
                    StockCandidate(
                        code = item.optString("code", ""),
                        name = item.optString("name", ""),
                        sector = item.optString("sector", "기타"),
                        strategyType = item.optString("strategy_type", "KR_SHORT_STOCK"),
                        currentPrice = item.optDouble("current_price", 0.0),
                        score = item.optDouble("score", 0.0),
                        entry = item.optDouble("entry", 0.0),
                        stopLoss = item.optDouble("stop_loss", 0.0),
                        target1 = item.optDouble("target1", 0.0),
                        target2 = item.optDouble("target2", 0.0),
                        riskPct = item.optDouble("risk_pct", 0.0),
                        reason = item.optString("reason", ""),
                        failureCondition = item.optString("failure_condition", ""),
                    )
                )
            }
        }
    }
    val sectorsArray = json.optJSONArray("kr_sector_snapshot")
    val sectors = buildList {
        if (sectorsArray != null) {
            for (i in 0 until sectorsArray.length()) {
                val item = sectorsArray.optJSONObject(i) ?: continue
                add(StockSector(item.optString("sector", "기타"), item.optInt("selected_count", 0)))
            }
        }
    }
    return StockSnapshot(
        createdAtKst = json.optString("created_at_kst", "-"),
        mode = json.optString("mode", "unknown"),
        quoteOkRate = quality.optDouble("kr_short_quote_ok_rate", 0.0),
        quoteOk = quality.optInt("kr_short_quote_ok", 0),
        total = quality.optInt("kr_short_total", stocks.size),
        stocks = stocks,
        sectors = sectors,
    )
}

private fun parseRecommendationPerformance(json: JSONObject): RecommendationPerformance {
    val summary = json.optJSONObject("summary") ?: JSONObject()
    return RecommendationPerformance(
        createdAtKst = json.optString("created_at_kst", "-"),
        totalRecommendations = summary.optInt("total_recommendations", 0),
        measurableCount = summary.optInt("measurable_count", 0),
        avgPnlPct = summary.optDouble("avg_pnl_pct", 0.0),
        medianPnlPct = summary.optDouble("median_pnl_pct", 0.0),
        winRate = summary.optDouble("win_rate", 0.0),
        hitStopRate = summary.optDouble("hit_stop_rate", 0.0),
        hitTarget1Rate = summary.optDouble("hit_target1_rate", 0.0),
        hitTarget2Rate = summary.optDouble("hit_target2_rate", 0.0),
    )
}

private fun parseStockBacktest(json: JSONObject): StockBacktest {
    val base = json.optJSONObject("base_summary") ?: JSONObject()
    val best = json.optJSONObject("best_summary") ?: JSONObject()
    return StockBacktest(
        createdAtKst = json.optString("created_at_kst", "-"),
        accepted = json.optBoolean("accepted", false),
        improvement = json.optDouble("improvement", 0.0),
        baseTrades = base.optInt("trades", 0),
        baseAvgReturnPct = base.optDouble("avg_return_pct", 0.0),
        baseWinRate = base.optDouble("win_rate", 0.0),
        baseProfitFactor = base.optDouble("profit_factor", 0.0),
        bestTrades = best.optInt("trades", 0),
        bestAvgReturnPct = best.optDouble("avg_return_pct", 0.0),
        bestWinRate = best.optDouble("win_rate", 0.0),
        bestProfitFactor = best.optDouble("profit_factor", 0.0),
    )
}

private fun parseStockStrategy(json: JSONObject): StockStrategy {
    val metrics = json.optJSONObject("metrics") ?: JSONObject()
    return StockStrategy(
        code = json.optString("code", ""),
        name = json.optString("name", ""),
        sector = json.optString("sector", "기타"),
        action = json.optString("action", "관망"),
        actionReason = json.optString("action_reason", ""),
        score = json.optDouble("score", 0.0),
        threshold = json.optDouble("threshold", 0.0),
        setup = json.optString("setup", ""),
        currentPrice = json.optDouble("current_price", 0.0),
        entry = json.optDouble("entry", 0.0),
        stopLoss = json.optDouble("stop_loss", 0.0),
        target1 = json.optDouble("target1", 0.0),
        target2 = json.optDouble("target2", 0.0),
        riskPct = json.optDouble("risk_pct", 0.0),
        positionSizeKrw = json.optDouble("position_size_krw", 0.0),
        reason = json.optString("reason", ""),
        failureCondition = json.optString("failure_condition", ""),
        rsi14 = metrics.optDouble("rsi14", 0.0),
        gapMa20Pct = metrics.optDouble("gap_ma20_pct", 0.0),
        momentum20dPct = metrics.optDouble("momentum_20d_pct", 0.0),
        ict = IctChartAnalyzer.analyze(emptyList()),
    )
}

private data class StockUiState(
    val loading: Boolean = false,
    val snapshot: StockSnapshot? = null,
    val performance: RecommendationPerformance? = null,
    val backtest: StockBacktest? = null,
    val message: String? = null,
    val error: String? = null,
)

private data class StockSnapshot(
    val createdAtKst: String,
    val mode: String,
    val quoteOkRate: Double,
    val quoteOk: Int,
    val total: Int,
    val stocks: List<StockCandidate>,
    val sectors: List<StockSector>,
)

private data class StockSector(val sector: String, val selectedCount: Int)

private data class StockCandidate(
    val code: String,
    val name: String,
    val sector: String,
    val strategyType: String,
    val currentPrice: Double,
    val score: Double,
    val entry: Double,
    val stopLoss: Double,
    val target1: Double,
    val target2: Double,
    val riskPct: Double,
    val reason: String,
    val failureCondition: String,
)

private data class RecommendationPerformance(
    val createdAtKst: String,
    val totalRecommendations: Int,
    val measurableCount: Int,
    val avgPnlPct: Double,
    val medianPnlPct: Double,
    val winRate: Double,
    val hitStopRate: Double,
    val hitTarget1Rate: Double,
    val hitTarget2Rate: Double,
)

private data class StockBacktest(
    val createdAtKst: String,
    val accepted: Boolean,
    val improvement: Double,
    val baseTrades: Int,
    val baseAvgReturnPct: Double,
    val baseWinRate: Double,
    val baseProfitFactor: Double,
    val bestTrades: Int,
    val bestAvgReturnPct: Double,
    val bestWinRate: Double,
    val bestProfitFactor: Double,
)

private data class StockStrategy(
    val code: String,
    val name: String,
    val sector: String,
    val action: String,
    val actionReason: String,
    val score: Double,
    val threshold: Double,
    val setup: String,
    val currentPrice: Double,
    val entry: Double,
    val stopLoss: Double,
    val target1: Double,
    val target2: Double,
    val riskPct: Double,
    val positionSizeKrw: Double,
    val reason: String,
    val failureCondition: String,
    val rsi14: Double,
    val gapMa20Pct: Double,
    val momentum20dPct: Double,
    val ict: IctAnalysis,
)

private fun compactError(error: Throwable): String = (error.message ?: error::class.java.simpleName).take(220)
private fun formatPrice(value: Double): String = String.format("%,.0f", value)
private fun formatNumber(value: Double): String = String.format("%.2f", value)
private fun formatRatio(value: Double): String = String.format("%.1f%%", value * 100.0)
private fun formatSignedPercent(value: Double): String = String.format("%+.2f%%", value)
