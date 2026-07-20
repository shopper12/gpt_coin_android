package com.cryptotradecoach

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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

class RecommendationHistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RecommendationHistoryScreen(onBack = { finish() })
            }
        }
    }
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
    val entry: String,
    val stop: String,
    val target1: String,
    val target2: String,
    val currentPrice: Double?,
    val currentPriceDate: String,
    val currency: String,
    val source: String,
    val status: String,
    val note: String,
) {
    val returnPct: Double?
        get() {
            val base = referencePrice ?: return null
            val current = currentPrice ?: return null
            if (base <= 0.0) return null
            val raw = (current / base - 1.0) * 100.0
            return if (direction.equals("SHORT", true) || direction.equals("INVERSE", true)) -raw else raw
        }
}

private data class HistoryUiState(
    val loading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val records: List<RecommendationRecord> = emptyList(),
)

@Composable
private fun RecommendationHistoryScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(HistoryUiState()) }
    var filter by remember { mutableStateOf("ALL") }

    fun refresh() {
        scope.launch {
            state = state.copy(loading = true, error = null, message = null)
            runCatching { loadRecommendationHistory() }
                .onSuccess { rows ->
                    state = HistoryUiState(
                        loading = false,
                        records = rows,
                        message = "추천 이력 ${rows.size}건을 불러왔습니다.",
                    )
                }
                .onFailure { error ->
                    state = state.copy(loading = false, error = error.message ?: error.javaClass.simpleName)
                }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    val filtered = state.records.filter { filter == "ALL" || it.assetClass == filter }
    val measurable = filtered.mapNotNull { it.returnPct }
    val winners = measurable.count { it > 0.0 }
    val avg = if (measurable.isEmpty()) null else measurable.average()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("← 홈") }
                Button(onClick = { refresh() }, enabled = !state.loading) {
                    Text(if (state.loading) "갱신 중" else "현재가 갱신")
                }
            }
        }
        item {
            Text("과거 추천·수익률", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("최소 6개월 추천 이력의 원래 전략과 현재 기준 성과를 종목별로 확인합니다.")
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("성과 요약", fontWeight = FontWeight.Bold)
                    Text("표시 ${filtered.size}건 / 수익률 계산 가능 ${measurable.size}건")
                    Text("평균 ${avg?.let { signed(it) } ?: "계산 불가"} / 승률 ${if (measurable.isEmpty()) "계산 불가" else "%.1f%%".format(winners * 100.0 / measurable.size)}")
                    Text("기준가 또는 현재가가 없는 기록은 수익률을 임의 계산하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL", "KR_STOCK", "US_STOCK", "ETF", "CRYPTO").forEach { key ->
                    FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(key) })
                }
            }
        }
        if (state.error != null) item { InfoHistoryCard("오류: ${state.error}") }
        if (state.message != null) item { InfoHistoryCard(state.message ?: "") }
        if (!state.loading && filtered.isEmpty()) item { InfoHistoryCard("표시할 추천 이력이 없습니다.") }
        items(filtered, key = { it.id }) { record -> RecommendationCard(record) }
    }
}

@Composable
private fun RecommendationCard(record: RecommendationRecord) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${record.name} (${record.ticker})", fontWeight = FontWeight.Bold)
            Text("${record.date} · ${record.market} · ${record.direction} · ${record.assetClass}")
            Text("전략: ${record.strategy}")
            Text("기준가 ${price(record.referencePrice, record.currency)} / 현재가 ${price(record.currentPrice, record.currency)}")
            Text("현재 수익률: ${record.returnPct?.let { signed(it) } ?: "계산 불가"}", fontWeight = FontWeight.Bold)
            Text("진입 ${record.entry} / 손절 ${record.stop}")
            Text("목표 ${record.target1} → ${record.target2}")
            Text("현재가 기준일 ${record.currentPriceDate.ifBlank { "미확인" }} / 상태 ${record.status}")
            if (record.note.isNotBlank()) Text("메모: ${record.note}", style = MaterialTheme.typography.bodySmall)
            Text("출처: ${record.source}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InfoHistoryCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) { Text(text, modifier = Modifier.padding(12.dp)) }
}

private suspend fun loadRecommendationHistory(): List<RecommendationRecord> = withContext(Dispatchers.IO) {
    val connection = URL(HISTORY_URL).openConnection() as HttpURLConnection
    connection.connectTimeout = 8000
    connection.readTimeout = 10000
    connection.requestMethod = "GET"
    connection.setRequestProperty("Accept", "application/json")
    try {
        if (connection.responseCode !in 200..299) error("추천 이력 HTTP ${connection.responseCode}")
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        parseHistory(text).filter { isWithinHistoryWindow(it.date) }.sortedByDescending { it.date }
    } finally {
        connection.disconnect()
    }
}

private fun parseHistory(text: String): List<RecommendationRecord> {
    val root = JSONObject(text)
    val rows = root.optJSONArray("recommendations") ?: JSONArray()
    return buildList {
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
                    entry = item.optString("entry", "기록 없음"),
                    stop = item.optString("stop", "기록 없음"),
                    target1 = item.optString("target1", "기록 없음"),
                    target2 = item.optString("target2", "기록 없음"),
                    currentPrice = item.optNullableDouble("currentPrice"),
                    currentPriceDate = item.optString("currentPriceDate"),
                    currency = item.optString("currency"),
                    source = item.optString("source", "ChatGPT briefing archive"),
                    status = item.optString("status", "ARCHIVED"),
                    note = item.optString("note"),
                )
            )
        }
    }
}

private fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val value = optDouble(key, Double.NaN)
    return if (value.isFinite()) value else null
}

private fun isWithinHistoryWindow(date: String): Boolean = runCatching {
    val d = LocalDate.parse(date)
    val today = LocalDate.now()
    !d.isAfter(today) && ChronoUnit.MONTHS.between(d.withDayOfMonth(1), today.withDayOfMonth(1)) <= 12
}.getOrDefault(true)

private fun signed(value: Double): String = "%+.2f%%".format(value)
private fun price(value: Double?, currency: String): String = value?.let { "%,.2f %s".format(it, currency) } ?: "미확인"

private const val HISTORY_URL = "https://raw.githubusercontent.com/shopper12/gpt_coin_android/main/reports/chatgpt_recommendation_history.json"
