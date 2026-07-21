package com.cryptotradecoach

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    val market: String,
    val ticker: String,
    val name: String,
    val direction: String,
    val strategy: String,
    val referencePrice: Double?,
    val referencePriceMethod: String,
    val entry: String,
    val stop: String,
    val target1: String,
    val target2: String,
    val currentPrice: Double?,
    val currentPriceDate: String,
    val todayChangePct: Double?,
    val todayChangeDate: String,
    val currency: String,
    val source: String,
    val status: String,
    val note: String,
    val recoveryConfidence: String,
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
            state = state.copy(error = null, message = "현재가 갱신 실행을 요청하는 중입니다.")
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
    val avg = if (measurable.isEmpty()) null else measurable.average()

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
                    Text(if (priceRefreshRunning) "요청 중" else "현재가 갱신 실행")
                }
            }
        }
        item {
            Text("ChatGPT 과거 추천·수익률", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("모든 저장 추천을 엑셀형 한 줄 표로 표시하고 수익률·날짜·이름·오늘 등락률로 정렬합니다.")
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("아카이브·성과 요약", fontWeight = FontWeight.Bold)
                    Text("기간 ${state.coverageStart.ifBlank { "미확인" }} ~ ${state.coverageEnd.ifBlank { "미확인" }}")
                    Text("아카이브 ${state.declaredCount}건 / 현재 표시 ${filtered.size}건 / 수익률 계산 가능 ${measurable.size}건")
                    Text("평균 ${avg?.let { signed(it) } ?: "계산 불가"} / 승률 ${if (measurable.isEmpty()) "계산 불가" else "%.1f%%".format(winners * 100.0 / measurable.size)}")
                    Text("조건부·원문 재검토 기록은 체결 확인 전 성과 계산에서 제외합니다.", style = MaterialTheme.typography.bodySmall)
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
            item { RecommendationTableHeader(tableScrollState) }
            items(sorted, key = { it.id }) { record ->
                RecommendationTableRow(record, tableScrollState)
            }
        }
    }
}

@Composable
private fun RecommendationTableHeader(scrollState: androidx.compose.foundation.ScrollState) {
    Row(
        modifier = Modifier.horizontalScroll(scrollState).padding(vertical = 8.dp),
    ) {
        TableCell("수익률", 90.dp, true)
        TableCell("날짜", 105.dp, true)
        TableCell("이름", 170.dp, true)
        TableCell("오늘 등락", 100.dp, true)
        TableCell("티커", 100.dp, true)
        TableCell("현재가", 125.dp, true)
        TableCell("기준가", 125.dp, true)
        TableCell("방향", 85.dp, true)
        TableCell("상태", 155.dp, true)
        TableCell("전략", 360.dp, true)
    }
    HorizontalDivider()
}

@Composable
private fun RecommendationTableRow(
    record: RecommendationRecord,
    scrollState: androidx.compose.foundation.ScrollState,
) {
    Row(
        modifier = Modifier.horizontalScroll(scrollState).padding(vertical = 8.dp),
    ) {
        TableCell(record.returnPct?.let { signed(it) } ?: if (record.isExecuted) "계산 불가" else "미체결", 90.dp, true)
        TableCell(record.date, 105.dp)
        TableCell(record.name, 170.dp, true)
        TableCell(record.todayChangePct?.let { signed(it) } ?: "미확인", 100.dp)
        TableCell(record.ticker, 100.dp)
        TableCell(price(record.currentPrice, record.currency), 125.dp)
        TableCell(price(record.referencePrice, record.currency), 125.dp)
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
            val item = rows.getJSONObject(i)
            add(
                RecommendationRecord(
                    id = item.optString("id", "row-$i"),
                    date = item.optString("date"),
                    assetClass = item.optString("assetClass", "UNKNOWN"),
                    market = item.optString("market"),
                    ticker = item.optString("ticker"),
                    name = item.optString("name", item.optString("ticker")),
                    direction = item.optString("direction", "LONG"),
                    strategy = item.optString("strategy"),
                    referencePrice = item.optNullableDouble("referencePrice"),
                    referencePriceMethod = item.optString("referencePriceMethod"),
                    entry = item.optString("entry", "기록 없음"),
                    stop = item.optString("stop", "기록 없음"),
                    target1 = item.optString("target1", "기록 없음"),
                    target2 = item.optString("target2", "기록 없음"),
                    currentPrice = item.optNullableDouble("currentPrice"),
                    currentPriceDate = item.optString("currentPriceDate"),
                    todayChangePct = item.optNullableDouble("todayChangePct"),
                    todayChangeDate = item.optString("todayChangeDate"),
                    currency = item.optString("currency"),
                    source = item.optString("source", "ChatGPT conversation archive"),
                    status = item.optString("status", "ARCHIVED"),
                    note = item.optString("note"),
                    recoveryConfidence = item.optString("recoveryConfidence", "UNKNOWN"),
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
