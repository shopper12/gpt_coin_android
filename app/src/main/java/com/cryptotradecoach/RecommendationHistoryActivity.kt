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
    DATE("날짜"),
    NAME("이름"),
    TODAY_CHANGE("오늘 등락"),
}

private data class RecommendationRecord(
    val id: String,
    val date: String,
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
)

private data class HistoryUiState(
    val loading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val coverageStart: String = "",
    val coverageEnd: String = "",
    val declaredCount: Int = 0,
    val records: List<RecommendationRecord> = emptyList(),
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
    var sortKey by remember { mutableStateOf(HistorySortKey.RETURN) }
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
                        message = "ChatGPT 추천 이력 ${payload.records.size}건을 불러왔습니다.",
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
            state = state.copy(error = null, message = "현재가와 간략차트 갱신을 요청하는 중입니다.")
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
                    Text(if (state.loading) "불러오는 중" else "이력 다시 불러오기")
                }
                OutlinedButton(onClick = { requestPriceRefresh() }, enabled = !priceRefreshRunning) {
                    Text(if (priceRefreshRunning) "요청 중" else "현재가·차트 갱신")
                }
            }
        }
        item {
            Text("추천 이력·현재 수익률", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("종목·현재가·추천가·추천가부터 수익률·간략차트를 한 줄로 보고 옆으로 스크롤합니다.")
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("아카이브·성과 요약", fontWeight = FontWeight.Bold)
                    Text("기간 ${state.coverageStart.ifBlank { "미확인" }} ~ ${state.coverageEnd.ifBlank { "미확인" }}")
                    Text("아카이브 ${state.declaredCount}건 / 현재 표시 ${filtered.size}건 / 수익률 계산 가능 ${measurable.size}건")
                    Text("평균 ${avg?.let { signed(it) } ?: "계산 불가"} / 승률 ${if (measurable.isEmpty()) "계산 불가" else "%.1f%%".format(winners * 100.0 / measurable.size)}")
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("ALL", "KR_STOCK", "US_STOCK", "ETF", "CRYPTO").forEach { key ->
                    FilterChip(selected = assetFilter == key, onClick = { assetFilter = key }, label = { Text(key) })
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("ALL", "EXECUTED", "CONDITIONAL").forEach { key ->
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
        TableCell("종목", 180.dp, true)
        TableCell("현재가", 130.dp, true)
        TableCell("추천가", 130.dp, true)
        TableCell("추천가부터 수익률", 150.dp, true)
        TableCell("간략차트", 170.dp, true)
        TableCell("추천일", 110.dp, true)
        TableCell("오늘 등락", 105.dp, true)
        TableCell("방향", 85.dp, true)
        TableCell("상태", 155.dp, true)
        TableCell("전략", 360.dp, true)
    }
    HorizontalDivider()
}

@Composable
private fun RecommendationTableRow(record: RecommendationRecord) {
    Row(modifier = Modifier.padding(vertical = 6.dp)) {
        TableCell("${record.name} (${record.ticker})", 180.dp, true)
        TableCell(price(record.currentPrice, record.currency), 130.dp)
        TableCell(price(record.referencePrice, record.currency), 130.dp)
        TableCell(record.returnPct?.let { signed(it) } ?: if (record.isExecuted) "계산 불가" else "미체결", 150.dp, true)
        SparklineCell(record.recentCloses, 170.dp)
        TableCell(record.date, 110.dp)
        TableCell(record.todayChangePct?.let { signed(it) } ?: "미확인", 105.dp)
        TableCell(record.direction, 85.dp)
        TableCell(record.status, 155.dp)
        TableCell(record.strategy, 360.dp)
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
            HistorySortKey.TODAY_CHANGE -> compareNullable(a.todayChangePct, b.todayChangePct, descending)
            HistorySortKey.DATE -> if (descending) b.date.compareTo(a.date) else a.date.compareTo(b.date)
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
    val connection = URL(HISTORY_URL).openConnection() as HttpURLConnection
    connection.connectTimeout = 8000
    connection.readTimeout = 12000
    connection.requestMethod = "GET"
    connection.setRequestProperty("Accept", "application/json")
    try {
        if (connection.responseCode !in 200..299) error("추천 이력 HTTP ${connection.responseCode}")
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        parseHistory(text)
    } finally {
        connection.disconnect()
    }
}

private fun parseHistory(text: String): HistoryPayload {
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
                    id = item.optString("id", "row-$i"),
                    date = item.optString("date"),
                    assetClass = item.optString("assetClass", "UNKNOWN"),
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
                )
            )
        }
    }.sortedByDescending { it.date }

    return HistoryPayload(
        coverageStart = root.optString("coverageStart"),
        coverageEnd = root.optString("coverageEnd"),
        declaredCount = root.optInt("recordCount", records.size),
        records = records,
    )
}

private fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val value = optDouble(key, Double.NaN)
    return if (value.isFinite()) value else null
}

private fun signed(value: Double): String = "%+.2f%%".format(value)
private fun price(value: Double?, currency: String): String = value?.let { "%,.2f %s".format(it, currency) } ?: "미확인"

private const val HISTORY_URL = "https://raw.githubusercontent.com/shopper12/gpt_coin_android/main/reports/chatgpt_recommendation_history.json"
