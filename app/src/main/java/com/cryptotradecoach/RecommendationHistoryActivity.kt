package com.cryptotradecoach

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cryptotradecoach.data.WorkflowDispatchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class RecommendationHistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val workflowRepository = WorkflowDispatchRepository(this)
        setContent {
            MaterialTheme {
                RecommendationHistoryScreen(
                    onBack = { finish() },
                    onPriceRefresh = { workflowRepository.dispatchRecommendationPriceRefresh() },
                )
            }
        }
    }
}

private enum class HistorySortKey(val label: String) {
    RETURN("수익률"),
    SCORE("점수"),
    DATE("날짜"),
    NAME("이름"),
    TODAY_CHANGE("오늘 등락"),
}

private data class RecommendationRecord(
    val id: String,
    val date: String,
    val generatedAtKst: String,
    val assetClass: String,
    val ticker: String,
    val name: String,
    val direction: String,
    val strategy: String,
    val referencePrice: Double?,
    val currentPrice: Double?,
    val todayChangePct: Double?,
    val recentCloses: List<Double>,
    val currency: String,
    val status: String,
    val score: Double?,
) {
    val isExecuted: Boolean
        get() = status.uppercase() !in setOf("CONDITIONAL", "UNTRIGGERED", "SOURCE_REVIEW_REQUIRED", "WATCH")

    val returnPct: Double?
        get() {
            if (!isExecuted) return null
            val base = referencePrice ?: return null
            val current = currentPrice ?: return null
            if (base <= 0.0) return null
            val raw = (current / base - 1.0) * 100.0
            return if (direction.equals("SHORT", true)) -raw else raw
        }
}

private data class HistoryPayload(
    val coverageStart: String,
    val coverageEnd: String,
    val declaredCount: Int,
    val records: List<RecommendationRecord>,
    val loadedSources: List<String>,
)

private data class HistoryUiState(
    val loading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val coverageStart: String = "",
    val coverageEnd: String = "",
    val declaredCount: Int = 0,
    val records: List<RecommendationRecord> = emptyList(),
    val loadedSources: List<String> = emptyList(),
)

@Composable
private fun RecommendationHistoryScreen(
    onBack: () -> Unit,
    onPriceRefresh: suspend () -> String,
) {
    val scope = rememberCoroutineScope()
    val tableScrollState = rememberScrollState()
    var state by remember { mutableStateOf(HistoryUiState()) }
    var assetFilter by remember { mutableStateOf("ALL") }
    var statusFilter by remember { mutableStateOf("ALL") }
    var sortKey by remember { mutableStateOf(HistorySortKey.DATE) }
    var sortDescending by remember { mutableStateOf(true) }
    var priceRefreshRunning by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            state = state.copy(loading = true, error = null, message = null)
            runCatching { loadRecommendationHistory() }
                .onSuccess { payload ->
                    state = HistoryUiState(
                        loading = false,
                        coverageStart = payload.coverageStart,
                        coverageEnd = payload.coverageEnd,
                        declaredCount = payload.declaredCount,
                        records = payload.records,
                        loadedSources = payload.loadedSources,
                        message = "통합 추천·자동 시그널 ${payload.records.size}건을 불러왔습니다.",
                    )
                }
                .onFailure { error ->
                    state = state.copy(loading = false, error = error.message ?: error.javaClass.simpleName)
                }
        }
    }

    fun requestPriceRefresh() {
        if (priceRefreshRunning) return
        scope.launch {
            priceRefreshRunning = true
            state = state.copy(error = null, message = "기존 추천 현재가와 간략차트 갱신을 요청하는 중입니다.")
            runCatching { onPriceRefresh() }
                .onSuccess { state = state.copy(message = it) }
                .onFailure { state = state.copy(error = it.message ?: it.javaClass.simpleName, message = null) }
            priceRefreshRunning = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val filtered = state.records.filter { record ->
        val assetOk = assetFilter == "ALL" || record.assetClass == assetFilter
        val statusOk = when (statusFilter) {
            "ACTIVE_SIGNAL" -> record.status == "ACTIVE_SIGNAL"
            "EXECUTED" -> record.isExecuted
            "CONDITIONAL" -> !record.isExecuted
            else -> true
        }
        assetOk && statusOk
    }
    val sorted = sortRecords(filtered, sortKey, sortDescending)
    val measurable = filtered.mapNotNull { it.returnPct }
    val winners = measurable.count { it > 0.0 }
    val avg = measurable.takeIf { it.isNotEmpty() }?.average()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("← 홈") }
                Button(onClick = { refresh() }, enabled = !state.loading) {
                    Text(if (state.loading) "불러오는 중" else "추천 목록 새로고침")
                }
                OutlinedButton(onClick = { requestPriceRefresh() }, enabled = !priceRefreshRunning) {
                    Text(if (priceRefreshRunning) "요청 중" else "기존 이력 가격 갱신")
                }
            }
        }
        item {
            Text("통합 추천·전세계 자동 시그널", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("기존 추천과 15분마다 생성되는 전세계 주식·ETF·채권·원자재·FX·코인 시그널을 한 표로 표시합니다.")
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("아카이브·성과 요약", fontWeight = FontWeight.Bold)
                    Text("기간 ${state.coverageStart.ifBlank { "미확인" }} ~ ${state.coverageEnd.ifBlank { "미확인" }}")
                    Text("통합 ${state.declaredCount}건 / 현재 표시 ${filtered.size}건 / 수익률 계산 가능 ${measurable.size}건")
                    Text("평균 ${avg?.let { signed(it) } ?: "계산 불가"} / 승률 ${if (measurable.isEmpty()) "계산 불가" else "%.1f%%".format(winners * 100.0 / measurable.size)}")
                    if (state.loadedSources.isNotEmpty()) Text("데이터: ${state.loadedSources.joinToString(" + ")}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("ALL", "KR_STOCK", "US_STOCK", "ETF", "BOND", "COMMODITY", "FX", "CRYPTO").forEach { key ->
                    FilterChip(selected = assetFilter == key, onClick = { assetFilter = key }, label = { Text(key) })
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("ALL", "ACTIVE_SIGNAL", "EXECUTED", "CONDITIONAL").forEach { key ->
                    FilterChip(selected = statusFilter == key, onClick = { statusFilter = key }, label = { Text(key) })
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("정렬", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                HistorySortKey.entries.forEach { key ->
                    FilterChip(
                        selected = sortKey == key,
                        onClick = {
                            if (sortKey == key) {
                                sortDescending = !sortDescending
                            } else {
                                sortKey = key
                                sortDescending = key != HistorySortKey.NAME
                            }
                        },
                        label = { Text(key.label) },
                    )
                }
                OutlinedButton(onClick = { sortDescending = !sortDescending }) {
                    Text(if (sortDescending) "내림차순" else "오름차순")
                }
            }
        }
        if (state.error != null) item { InfoHistoryCard("오류: ${state.error}") }
        if (state.message != null) item { InfoHistoryCard(state.message ?: "") }
        if (!state.loading && sorted.isEmpty()) item { InfoHistoryCard("표시할 추천 이력이 없습니다.") }
        if (sorted.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(tableScrollState),
                ) {
                    RecommendationTableHeader()
                    sorted.forEach { record -> RecommendationTableRow(record) }
                }
            }
        }
    }
}

@Composable
private fun RecommendationTableHeader() {
    Row(modifier = Modifier.padding(vertical = 8.dp)) {
        TableCell("종목", 190.dp, true)
        TableCell("현재가", 130.dp, true)
        TableCell("추천가", 130.dp, true)
        TableCell("추천가부터 수익률", 150.dp, true)
        TableCell("점수", 75.dp, true)
        TableCell("간략차트", 170.dp, true)
        TableCell("추천일", 115.dp, true)
        TableCell("오늘 등락", 105.dp, true)
        TableCell("자산", 110.dp, true)
        TableCell("방향", 85.dp, true)
        TableCell("상태", 155.dp, true)
        TableCell("전략", 390.dp, true)
    }
    HorizontalDivider()
}

@Composable
private fun RecommendationTableRow(record: RecommendationRecord) {
    Row(modifier = Modifier.padding(vertical = 6.dp)) {
        TableCell("${record.name} (${record.ticker})", 190.dp, true)
        TableCell(price(record.currentPrice, record.currency), 130.dp)
        TableCell(price(record.referencePrice, record.currency), 130.dp)
        TableCell(record.returnPct?.let { signed(it) } ?: if (record.isExecuted) "계산 불가" else "미체결", 150.dp, true)
        TableCell(record.score?.let { "%.1f".format(it) } ?: "-", 75.dp)
        SparklineCell(record.recentCloses, 170.dp)
        TableCell(record.generatedAtKst.ifBlank { record.date }.take(16), 115.dp)
        TableCell(record.todayChangePct?.let { signed(it) } ?: "미확인", 105.dp)
        TableCell(record.assetClass, 110.dp)
        TableCell(record.direction, 85.dp)
        TableCell(record.status, 155.dp)
        TableCell(record.strategy, 390.dp)
    }
    HorizontalDivider()
}

@Composable
private fun TableCell(text: String, width: Dp, bold: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.width(width).padding(horizontal = 4.dp),
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
    )
}

@Composable
private fun SparklineCell(values: List<Double>, width: Dp) {
    if (values.size < 2) {
        TableCell("갱신 필요", width)
        return
    }
    val minValue = values.minOrNull() ?: return
    val maxValue = values.maxOrNull() ?: return
    val range = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
    val lineColor = if (values.last() >= values.first()) Color(0xFF2E7D32) else Color(0xFFC62828)
    Canvas(
        modifier = Modifier.width(width).height(42.dp).padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        val step = if (values.size <= 1) 0f else size.width / (values.size - 1)
        for (index in 1 until values.size) {
            val previousX = (index - 1) * step
            val currentX = index * step
            val previousY = size.height - ((values[index - 1] - minValue) / range).toFloat() * size.height
            val currentY = size.height - ((values[index] - minValue) / range).toFloat() * size.height
            drawLine(lineColor, Offset(previousX, previousY), Offset(currentX, currentY), strokeWidth = 3f)
        }
    }
}

@Composable
private fun InfoHistoryCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) { Text(text, modifier = Modifier.padding(12.dp)) }
}

private fun sortRecords(
    records: List<RecommendationRecord>,
    sortKey: HistorySortKey,
    descending: Boolean,
): List<RecommendationRecord> {
    val comparator = Comparator<RecommendationRecord> { a, b ->
        when (sortKey) {
            HistorySortKey.RETURN -> compareNullable(a.returnPct, b.returnPct, descending)
            HistorySortKey.SCORE -> compareNullable(a.score, b.score, descending)
            HistorySortKey.TODAY_CHANGE -> compareNullable(a.todayChangePct, b.todayChangePct, descending)
            HistorySortKey.DATE -> if (descending) b.generatedAtKst.ifBlank { b.date }.compareTo(a.generatedAtKst.ifBlank { a.date }) else a.generatedAtKst.ifBlank { a.date }.compareTo(b.generatedAtKst.ifBlank { b.date })
            HistorySortKey.NAME -> if (descending) b.name.lowercase().compareTo(a.name.lowercase()) else a.name.lowercase().compareTo(b.name.lowercase())
        }
    }
    return records.sortedWith(comparator)
}

private fun compareNullable(a: Double?, b: Double?, descending: Boolean): Int {
    if (a == null && b == null) return 0
    if (a == null) return 1
    if (b == null) return -1
    return if (descending) b.compareTo(a) else a.compareTo(b)
}

private suspend fun loadRecommendationHistory(): HistoryPayload = withContext(Dispatchers.IO) {
    val chat = runCatching { fetchHistory(CHAT_HISTORY_URL, "기존 ChatGPT 추천") }.getOrNull()
    val global = runCatching { fetchHistory(GLOBAL_HISTORY_URL, "전세계 자동 시그널") }.getOrNull()
    val available = listOfNotNull(chat, global)
    if (available.isEmpty()) error("추천 이력 데이터 두 곳 모두 불러오지 못했습니다.")
    val records = available
        .flatMap { it.records }
        .distinctBy { it.id }
        .sortedByDescending { it.generatedAtKst.ifBlank { it.date } }
    val startDates = available.mapNotNull { it.coverageStart.takeIf(String::isNotBlank) }
    val endDates = available.mapNotNull { it.coverageEnd.takeIf(String::isNotBlank) }
    HistoryPayload(
        coverageStart = startDates.minOrNull().orEmpty(),
        coverageEnd = endDates.maxOrNull().orEmpty(),
        declaredCount = records.size,
        records = records,
        loadedSources = available.flatMap { it.loadedSources }.distinct(),
    )
}

private fun fetchHistory(url: String, sourceLabel: String): HistoryPayload {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 18_000
    connection.requestMethod = "GET"
    connection.setRequestProperty("Accept", "application/json")
    connection.setRequestProperty("User-Agent", "UnifiedTradingCoach-Android")
    return try {
        if (connection.responseCode !in 200..299) error("$sourceLabel HTTP ${connection.responseCode}")
        parseHistory(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }, sourceLabel)
    } finally {
        connection.disconnect()
    }
}

private fun parseHistory(text: String, sourceLabel: String): HistoryPayload {
    val root = JSONObject(text)
    val rows = root.optJSONArray("recommendations") ?: JSONArray()
    val records = buildList {
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i) ?: continue
            val recent = item.optJSONArray("recentCloses") ?: JSONArray()
            val closes = buildList {
                for (index in 0 until recent.length()) {
                    val value = recent.optDouble(index, Double.NaN)
                    if (value.isFinite()) add(value)
                }
            }
            add(
                RecommendationRecord(
                    id = item.optString("id", "$sourceLabel-row-$i"),
                    date = item.optString("date"),
                    generatedAtKst = item.optString("generatedAtKst"),
                    assetClass = normalizeAssetClass(item.optString("assetClass", "UNKNOWN")),
                    ticker = item.optString("ticker"),
                    name = item.optString("name", item.optString("ticker")),
                    direction = item.optString("direction", "LONG"),
                    strategy = item.optString("strategy"),
                    referencePrice = item.optNullableDouble("referencePrice"),
                    currentPrice = item.optNullableDouble("currentPrice"),
                    todayChangePct = item.optNullableDouble("todayChangePct"),
                    recentCloses = closes,
                    currency = item.optString("currency"),
                    status = item.optString("status", "ARCHIVED"),
                    score = item.optNullableDouble("score"),
                )
            )
        }
    }
    return HistoryPayload(
        coverageStart = root.optString("coverageStart"),
        coverageEnd = root.optString("coverageEnd"),
        declaredCount = root.optInt("recordCount", records.size),
        records = records,
        loadedSources = listOf(sourceLabel),
    )
}

private fun normalizeAssetClass(value: String): String {
    return when (value.uppercase()) {
        "US STOCK" -> "US_STOCK"
        "KR STOCK" -> "KR_STOCK"
        "EQUITY ETF" -> "ETF"
        "PRECIOUS METAL", "ENERGY COMMODITY", "COMMODITY BASKET", "AGRICULTURE" -> "COMMODITY"
        else -> value.uppercase().ifBlank { "UNKNOWN" }
    }
}

private fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val value = optDouble(key, Double.NaN)
    return if (value.isFinite()) value else null
}

private fun signed(value: Double): String = "%+.2f%%".format(value)
private fun price(value: Double?, currency: String): String = value?.let { "%,.2f %s".format(it, currency) } ?: "미확인"

private const val CHAT_HISTORY_URL =
    "https://raw.githubusercontent.com/shopper12/gpt_coin_android/main/reports/chatgpt_recommendation_history.json"
private const val GLOBAL_HISTORY_URL =
    "https://raw.githubusercontent.com/shopper12/gpt_coin_android/main/reports/global_market_recommendation_history.json"
