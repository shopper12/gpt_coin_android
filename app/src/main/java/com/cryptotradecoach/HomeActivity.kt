package com.cryptotradecoach

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.core.content.ContextCompat
import com.cryptotradecoach.data.WorkflowDispatchRepository
import com.cryptotradecoach.service.GlobalMarketSignalScheduler
import com.cryptotradecoach.service.SignalNotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val LATEST_BRIEFING_URL = "https://raw.githubusercontent.com/shopper12/gpt_coin_android/main/reports/chatgpt_recommendations_latest.json"

class HomeActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SignalNotificationHelper(this).ensureChannels()
        GlobalMarketSignalScheduler.schedule(this)
        requestNotificationPermissionIfNeeded()
        val workflowRepository = WorkflowDispatchRepository(this)
        setContent {
            MaterialTheme {
                MoneyDashboardScreen(
                    onCoin = { startActivity(Intent(this, MainActivity::class.java)) },
                    onStock = { startActivity(Intent(this, StockActivity::class.java)) },
                    onRecommendationHistory = { startActivity(Intent(this, RecommendationHistoryActivity::class.java)) },
                    onMyStocks = { startActivity(Intent(this, MyStocksActivity::class.java)) },
                    onSettings = { startActivity(Intent(this, GlobalSettingsActivity::class.java)) },
                    onBtcMonitor = { startActivity(Intent(this, BacktestMonitorActivity::class.java)) },
                    onWorkflow = { workflowRepository.dispatchUnifiedStrategyMonitor() },
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private data class DashboardSignal(
    val name: String,
    val ticker: String,
    val direction: String,
    val status: String,
    val score: Int,
    val confidence: Int,
    val currency: String,
    val currentPrice: Double?,
    val entryLow: Double?,
    val entryHigh: Double?,
    val stopLoss: Double?,
    val target1: Double?,
    val target2: Double?,
    val reason: String,
    val risk: String,
)

private data class DashboardPayload(
    val generatedAtKst: String = "",
    val briefingSlot: String = "",
    val signals: List<DashboardSignal> = emptyList(),
)

@Composable
private fun MoneyDashboardScreen(
    onCoin: () -> Unit,
    onStock: () -> Unit,
    onRecommendationHistory: () -> Unit,
    onMyStocks: () -> Unit,
    onSettings: () -> Unit,
    onBtcMonitor: () -> Unit,
    onWorkflow: suspend () -> String,
) {
    val scope = rememberCoroutineScope()
    var dashboard by remember { mutableStateOf(DashboardPayload()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var workflowRunning by remember { mutableStateOf(false) }
    var workflowMessage by remember { mutableStateOf<String?>(null) }

    suspend fun refreshDashboard() {
        loading = true
        loadError = null
        runCatching { loadLatestBriefing() }
            .onSuccess { dashboard = it }
            .onFailure { loadError = it.message ?: it.javaClass.simpleName }
        loading = false
    }

    fun runWorkflow() {
        if (workflowRunning) return
        scope.launch {
            workflowRunning = true
            workflowMessage = null
            runCatching { onWorkflow() }
                .onSuccess { workflowMessage = it }
                .onFailure { workflowMessage = "실행 요청 실패: ${(it.message ?: it.javaClass.simpleName).take(180)}" }
            workflowRunning = false
        }
    }

    LaunchedEffect(Unit) { refreshDashboard() }

    val ranked = dashboard.signals.sortedByDescending { it.score }
    val first = ranked.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 18.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("내 돈 대시보드", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("공부하지 말고, 오늘 필요한 것만 보세요.", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "브리핑 ${friendlyTimestamp(dashboard.generatedAtKst)} · 슬롯 ${friendlySlot(dashboard.briefingSlot)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("지금 할 일 1개", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (first == null) {
                        Text(if (loading) "최신 브리핑을 불러오는 중입니다." else "지금 확인된 실행 전략이 없습니다. 새로고침만 한 번 해주세요.")
                    } else {
                        Text("${first.name} (${first.ticker})", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("${directionKorean(first.direction)} · ${statusKorean(first.status)} · 점수 ${first.score} · 신뢰 ${first.confidence}%")
                        Text(actionSentence(first), fontWeight = FontWeight.Bold)
                        Text("진입 ${rangeText(first.entryLow, first.entryHigh, first.currency)}  ·  손절 ${priceText(first.stopLoss, first.currency)}")
                    }
                    Button(
                        onClick = { scope.launch { refreshDashboard() } },
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (loading) "불러오는 중" else "최신 브리핑 새로고침") }
                    loadError?.let { Text("불러오기 오류: $it", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("오늘 이것만 지키기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("① 4개 전략만 본다. 새 종목을 더 찾지 않는다.")
                    Text("② 진입구간 밖에서는 아무리 좋아 보여도 추격하지 않는다.")
                    Text("③ 손절 가격을 먼저 보고, 감당 안 되면 매수하지 않는다.")
                }
            }
        }

        item { Text("오늘의 4개 전략", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }

        if (ranked.isEmpty()) {
            item { SimpleInfoCard("브리핑 카드가 아직 없습니다. 최신 브리핑 새로고침을 눌러주세요.") }
        } else {
            items(ranked.take(4)) { signal -> DashboardSignalCard(signal) }
        }

        item {
            Text("필요할 때만 열기", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            Text("분석 메뉴는 아래로 내렸습니다. 첫 화면에서는 돈 결정을 먼저 보세요.", style = MaterialTheme.typography.bodySmall)
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRecommendationHistory, modifier = Modifier.weight(1f)) { Text("추천·성과") }
                Button(onClick = onMyStocks, modifier = Modifier.weight(1f)) { Text("내 종목") }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onStock, modifier = Modifier.weight(1f)) { Text("주식 분석") }
                OutlinedButton(onClick = onCoin, modifier = Modifier.weight(1f)) { Text("코인 분석") }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBtcMonitor, modifier = Modifier.weight(1f)) { Text("BTC 모니터") }
                OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f)) { Text("설정") }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("시스템 점검", fontWeight = FontWeight.Bold)
                    Text("전략이 이상해 보일 때만 실행하세요. 평소에는 건드릴 필요 없습니다.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { runWorkflow() },
                        enabled = !workflowRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (workflowRunning) "자가검증 요청 중" else "코인·주식 자가검증 실행") }
                    workflowMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        item { Text("", modifier = Modifier.padding(bottom = 16.dp)) }
    }
}

@Composable
private fun DashboardSignalCard(signal: DashboardSignal) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${signal.name} (${signal.ticker})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${directionKorean(signal.direction)} · ${statusKorean(signal.status)}", fontWeight = FontWeight.Bold)
                }
                Text("${signal.score}점", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Text(actionSentence(signal), fontWeight = FontWeight.Bold)
            Text("진입  ${rangeText(signal.entryLow, signal.entryHigh, signal.currency)}")
            Text("손절  ${priceText(signal.stopLoss, signal.currency)}")
            Text("목표  ${priceText(signal.target1, signal.currency)} → ${priceText(signal.target2, signal.currency)}")
            if (signal.reason.isNotBlank()) Text("왜? ${signal.reason}", style = MaterialTheme.typography.bodySmall)
            if (signal.risk.isNotBlank()) Text("주의: ${signal.risk}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SimpleInfoCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) { Text(text, modifier = Modifier.padding(14.dp)) }
}

private fun actionSentence(signal: DashboardSignal): String = when (signal.status.uppercase()) {
    "IMMEDIATE", "ACTIVE_SIGNAL" -> "지금 행동: 진입조건을 다시 확인한 뒤 분할로만 접근"
    "CONDITIONAL" -> "지금 행동: 기다리기. 진입구간에 들어오기 전에는 매수하지 않기"
    "WATCH" -> "지금 행동: 매수 금지. 조건이 바뀌는지만 관찰"
    else -> "지금 행동: 조건 확인 전에는 아무것도 하지 않기"
}

private fun directionKorean(value: String): String = when (value.uppercase()) {
    "LONG" -> "상승 전략"
    "SHORT" -> "하락 전략"
    "INVERSE" -> "인버스"
    "DEFENSIVE" -> "방어"
    else -> value
}

private fun statusKorean(value: String): String = when (value.uppercase()) {
    "IMMEDIATE", "ACTIVE_SIGNAL" -> "실행 가능"
    "CONDITIONAL" -> "조건 대기"
    "WATCH" -> "관찰만"
    else -> value
}

private fun friendlySlot(slot: String): String = when (slot) {
    "0750" -> "아침 07:50"
    "1130" -> "점심 11:30"
    "2050" -> "저녁 20:50"
    "2330" -> "밤 23:30"
    else -> slot.ifBlank { "-" }
}

private fun friendlyTimestamp(value: String): String = value
    .replace("T", " ")
    .replace("+09:00", "")
    .take(16)
    .ifBlank { "업데이트 시각 미확인" }

private fun rangeText(low: Double?, high: Double?, currency: String): String {
    if (low == null && high == null) return "조건 확인"
    if (low == null) return "~ ${priceText(high, currency)}"
    if (high == null) return "${priceText(low, currency)} ~"
    return "${priceText(low, currency)} ~ ${priceText(high, currency)}"
}

private fun priceText(value: Double?, currency: String): String {
    if (value == null) return "-"
    return when (currency.uppercase()) {
        "KRW" -> "%,.0f원".format(value)
        "USD" -> "$%,.2f".format(value)
        else -> "%,.2f %s".format(value, currency)
    }
}

private suspend fun loadLatestBriefing(): DashboardPayload = withContext(Dispatchers.IO) {
    val connection = (URL(LATEST_BRIEFING_URL).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 12000
        readTimeout = 12000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "UnifiedTradingCoach-Android")
    }
    try {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("HTTP $code")
        val root = JSONObject(body)
        val rows = root.optJSONArray("signals")
        val signals = buildList {
            if (rows != null) {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    add(
                        DashboardSignal(
                            name = row.optString("name", row.optString("ticker", "-")),
                            ticker = row.optString("ticker", "-"),
                            direction = row.optString("direction", ""),
                            status = row.optString("status", ""),
                            score = row.optInt("score", 0),
                            confidence = row.optInt("confidence", 0),
                            currency = row.optString("currency", ""),
                            currentPrice = row.optNullableDouble("currentPrice"),
                            entryLow = row.optNullableDouble("entryLow"),
                            entryHigh = row.optNullableDouble("entryHigh"),
                            stopLoss = row.optNullableDouble("stopLoss"),
                            target1 = row.optNullableDouble("target1"),
                            target2 = row.optNullableDouble("target2"),
                            reason = row.optString("reason", ""),
                            risk = row.optString("risk", ""),
                        )
                    )
                }
            }
        }
        DashboardPayload(
            generatedAtKst = root.optString("generatedAtKst", ""),
            briefingSlot = root.optString("briefingSlot", ""),
            signals = signals,
        )
    } finally {
        connection.disconnect()
    }
}

private fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return runCatching { getDouble(key) }.getOrNull()
}
