package com.cryptotradecoach

import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.cryptotradecoach.data.MyStocksRepository
import com.cryptotradecoach.data.WatchlistItem
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
        val myStocksRepository = MyStocksRepository(this)
        setContent {
            MaterialTheme {
                RecommendationHistoryScreen(
                    onBack = { finish() },
                    onPriceRefresh = { workflowRepository.dispatchRecommendationPriceRefresh() },
                    onOpenMyStocks = { startActivity(Intent(this, MyStocksActivity::class.java)) },
                    initialWatchlistTickers = myStocksRepository.loadWatchlist().map { it.key }.toSet(),
                    onAddToMyStocks = { record ->
                        myStocksRepository.addWatchlist(
                            WatchlistItem(
                                ticker = record.ticker,
                                name = record.name,
                                market = record.market,
                                assetClass = record.assetClass,
                                currency = record.currency,
                                direction = record.direction,
                                referencePrice = record.referencePrice,
                                currentPrice = record.currentPrice,
                                sourceRecommendationId = record.id,
                            )
                        )
                    },
                )
            }
        }
    }
}

private enum class HistorySortKey(val label: String) {
    DATE("최신순"),
    RETURN("수익률"),
    SCORE("점수"),
}

private data class RecommendationRecord(
    val id: String,
    val date: String,
    val generatedAtKst: String,
    val assetClass: String,
    val market: String,
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
    onOpenMyStocks: () -> Unit,
    initialWatchlistTickers: Set<String>,
    onAddToMyStocks: (RecommendationRecord) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(HistoryUiState()) }
    var assetFilter by remember { mutableStateOf("ALL") }
    var statusFilter by remember { mutableStateOf("ALL") }
    var sortKey by remember { mutableStateOf(HistorySortKey.DATE) }
    var priceRefreshRunning by remember { mutableStateOf(false) }
    var watchlistTickers by remember { mutableStateOf(initialWatchlistTickers) }

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
                        message = "추천·자동 시그널 ${payload.records.size}건을 불러왔습니다.",
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
            state = state.copy(error = null, message = "기존 추천 현재가 갱신을 요청했습니다.")
            runCatching { onPriceRefresh() }
                .onSuccess { state = state.copy(message = it) }
                .onFailure { state = state.copy(error = it.message ?: it.javaClass.simpleName, message = null) }
            priceRefreshRunning = false
        }
    }

    fun addToMyStocks(record: RecommendationRecord) {
        runCatching { onAddToMyStocks(record) }
            .onSuccess { saved ->
                if (saved) {
                    watchlistTickers = watchlistTickers + record.ticker.uppercase()
                    state = state.copy(message = "${record.name}(${record.ticker})을 내 종목에 담았습니다.", error = null)
                } else {
                    state = state.copy(error = "관심종목을 저장하지 못했습니다.", message = null)
                }
            }
            .onFailure {
                state = state.copy(error = it.message ?: it.javaClass.simpleName, message = null)
            }
    }

    LaunchedEffect(Unit) { refresh() }

    val filtered = state.records.filter { record ->
        val assetOk = when (assetFilter) {
            "KR" -> record.assetClass in setOf("KR_ETF", "KR_STOCK")
            "US" -> record.assetClass in setOf("US_STOCK", "ETF", "BOND", "COMMODITY", "FX")
            "CRYPTO" -> record.assetClass == "CRYPTO"
            else -> true
        }
        val statusOk = when (statusFilter) {
            "EXECUTED" -> record.isExecuted
            "WAIT" -> !record.isExecuted
            else -> true
        }
        assetOk && statusOk
    }

    val sorted = when (sortKey) {
        HistorySortKey.DATE -> filtered.sortedByDescending { it.generatedAtKst.ifBlank { it.date } }
        HistorySortKey.RETURN -> filtered.sortedWith(compareByDescending<RecommendationRecord> { it.returnPct ?: Double.NEGATIVE_INFINITY })
        HistorySortKey.SCORE -> filtered.sortedWith(compareByDescending<RecommendationRecord> { it.score ?: Double.NEGATIVE_INFINITY })
    }
    val measurable = filtered.mapNotNull { it.returnPct }
    val winners = measurable.count { it > 0.0 }
    val avg = measurable.takeIf { it.isNotEmpty() }?.average()
    val winRate = measurable.takeIf { it.isNotEmpty() }?.let { winners * 100.0 / it.size }
    val waiting = filtered.count { !it.isExecuted }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("추천·성과", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("표를 읽지 말고, 결과가 좋아지고 있는지만 확인하세요.")
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("← 홈") }
                OutlinedButton(onClick = onOpenMyStocks) { Text("내 종목") }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("성과 한눈에", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("평균 ${avg?.let { signed(it) } ?: "계산 전"}  ·  승률 ${winRate?.let { "%.1f%%".format(it) } ?: "계산 전"}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("현재 표시 ${filtered.size}건 · 체결성과 ${measurable.size}건 · 조건대기 ${waiting}건")
                    Text("기간 ${state.coverageStart.ifBlank { "-" }} ~ ${state.coverageEnd.ifBlank { "-" }}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        when {
                            measurable.size < 10 -> "판단: 표본이 아직 적습니다. 승률보다 규칙 준수를 봅니다."
                            avg != null && avg > 0.0 -> "판단: 현재 계산 가능한 추천은 평균 플러스입니다."
                            avg != null -> "판단: 평균 성과가 음수입니다. 새 매매보다 실패원인 점검이 우선입니다."
                            else -> "판단: 현재가 갱신 후 성과를 계산할 수 있습니다."
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(selected = assetFilter == "ALL", onClick = { assetFilter = "ALL" }, label = { Text("전체") })
                FilterChip(selected = assetFilter == "KR", onClick = { assetFilter = "KR" }, label = { Text("한국") })
                FilterChip(selected = assetFilter == "US", onClick = { assetFilter = "US" }, label = { Text("미국·글로벌") })
                FilterChip(selected = assetFilter == "CRYPTO", onClick = { assetFilter = "CRYPTO" }, label = { Text("코인") })
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(selected = statusFilter == "ALL", onClick = { statusFilter = "ALL" }, label = { Text("전체 상태") })
                FilterChip(selected = statusFilter == "EXECUTED", onClick = { statusFilter = "EXECUTED" }, label = { Text("체결·진행") })
                FilterChip(selected = statusFilter == "WAIT", onClick = { statusFilter = "WAIT" }, label = { Text("조건대기") })
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HistorySortKey.entries.forEach { key ->
                    FilterChip(selected = sortKey == key, onClick = { sortKey = key }, label = { Text(key.label) })
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { refresh() }, enabled = !state.loading) { Text(if (state.loading) "불러오는 중" else "목록 새로고침") }
                OutlinedButton(onClick = { requestPriceRefresh() }, enabled = !priceRefreshRunning) {
                    Text(if (priceRefreshRunning) "갱신 요청 중" else "현재가 갱신")
                }
            }
        }

        state.error?.let { item { HistoryInfoCard("오류: $it") } }
        state.message?.let { item { HistoryInfoCard(it) } }
        if (!state.loading && sorted.isEmpty()) item { HistoryInfoCard("표시할 추천 이력이 없습니다.") }

        items(sorted, key = { it.id }) { record ->
            RecommendationCard(
                record = record,
                saved = record.ticker.uppercase() in watchlistTickers,
                onAddToMyStocks = { addToMyStocks(record) },
            )
        }
    }
}

@Composable
private fun RecommendationCard(
    record: RecommendationRecord,
    saved: Boolean,
    onAddToMyStocks: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            KoreanStockIdentityLabel(
                ticker = record.ticker,
                preferredName = record.name,
                modifier = Modifier.fillMaxWidth(),
                bold = true,
                resolveKoreanCode = record.assetClass in setOf("KR_STOCK", "KR_ETF"),
            )
            Text("${directionKoreanHistory(record.direction)} · ${statusKoreanHistory(record.status)} · 점수 ${record.score?.let { "%.0f".format(it) } ?: "-"}")
            Text(
                "추천 이후 ${record.returnPct?.let { signed(it) } ?: if (record.isExecuted) "계산 전" else "미체결"}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text("현재 ${price(record.currentPrice, record.currency)}  ·  추천 ${price(record.referencePrice, record.currency)}")
            record.todayChangePct?.let { Text("오늘 ${signed(it)}") }
            Text("추천 ${record.generatedAtKst.ifBlank { record.date }.take(16)} · ${record.assetClass}", style = MaterialTheme.typography.bodySmall)
            if (record.strategy.isNotBlank()) Text("전략: ${record.strategy}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onAddToMyStocks, enabled = !saved, modifier = Modifier.fillMaxWidth()) {
                Text(if (saved) "내 종목에 담김" else "내 종목에 담기")
            }
        }
    }
}

@Composable
private fun HistoryInfoCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) { Text(text, modifier = Modifier.padding(12.dp)) }
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
                    market = item.optString("market"),
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
        "KR ETF" -> "KR_ETF"
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

private fun directionKoreanHistory(value: String): String = when (value.uppercase()) {
    "LONG" -> "상승 전략"
    "SHORT" -> "하락 전략"
    "INVERSE" -> "인버스"
    "DEFENSIVE" -> "방어"
    else -> value
}

private fun statusKoreanHistory(value: String): String = when (value.uppercase()) {
    "IMMEDIATE", "ACTIVE_SIGNAL" -> "실행·진행"
    "CONDITIONAL", "UNTRIGGERED" -> "조건 대기"
    "WATCH", "SOURCE_REVIEW_REQUIRED" -> "관찰만"
    "STOPPED_OUT" -> "손절 종료"
    "HIT_TARGET", "TARGET1_HIT" -> "목표 도달"
    "EXPIRED" -> "만료"
    else -> value.ifBlank { "기록" }
}

private fun signed(value: Double): String = "%+.2f%%".format(value)
private fun price(value: Double?, currency: String): String = value?.let { "%,.2f %s".format(it, currency) } ?: "미확인"

private const val CHAT_HISTORY_URL =
    "https://raw.githubusercontent.com/shopper12/gpt_coin_android/main/reports/chatgpt_recommendation_history.json"
private const val GLOBAL_HISTORY_URL =
    "https://raw.githubusercontent.com/shopper12/gpt_coin_android/main/reports/global_market_recommendation_history.json"
