package com.cryptotradecoach

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import com.cryptotradecoach.data.WorkflowDispatchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class BacktestMonitorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val workflowRepository = WorkflowDispatchRepository(this)
        setContent {
            MaterialTheme {
                BtcMonitorScreen(
                    onBack = { finish() },
                    onRunMonitor = { workflowRepository.dispatchBinanceBtcMonitor() },
                    onRunBacktest = { workflowRepository.dispatchBinanceBtcBacktest() },
                )
            }
        }
    }
}

private data class BtcMonitorReport(
    val ok: Boolean,
    val generatedAtKst: String,
    val symbol: String,
    val market: String,
    val interval: String,
    val signal: String,
    val summary: String,
    val currentPrice: Double?,
    val lastCandleKst: String,
    val recentCloses: List<Double>,
    val rsi: Double?,
    val adx: Double?,
    val vwap: Double?,
    val volumeMultiple: Double?,
    val contextActive: Boolean,
    val baseScore: Int,
    val baseRequired: Int,
    val confirmationScore: Int,
    val confirmationRequired: Int,
    val aboveVwap: Boolean,
    val volumeOk: Boolean,
    val backtestTrades: Int,
    val backtestWinRate: Double?,
    val backtestProfitFactor: Double?,
    val backtestNetPnlPct: Double?,
)

@Composable
private fun BtcMonitorScreen(
    onBack: () -> Unit,
    onRunMonitor: suspend () -> String,
    onRunBacktest: suspend () -> String,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var actionRunning by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<BtcMonitorReport?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            runCatching { loadBtcMonitorReport() }
                .onSuccess { report = it; message = "최신 BTC 모니터 결과를 불러왔습니다." }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            loading = false
        }
    }

    fun runAction(action: suspend () -> String) {
        if (actionRunning) return
        scope.launch {
            actionRunning = true
            error = null
            runCatching { action() }
                .onSuccess { message = it }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            actionRunning = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("← 홈") }
            Button(onClick = { refresh() }, enabled = !loading) { Text(if (loading) "불러오는 중" else "결과 새로고침") }
        }
        Text("BTC 24시간 백테스트 모니터", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("시간당 라이브 5분봉 모니터와 일 1회 최적화 백테스트 결과를 같은 화면에서 확인합니다.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runAction(onRunMonitor) }, enabled = !actionRunning) { Text("지금 모니터 실행") }
            OutlinedButton(onClick = { runAction(onRunBacktest) }, enabled = !actionRunning) { Text("백테스트 갱신") }
        }
        message?.let { InfoMonitorCard(it) }
        error?.let { InfoMonitorCard("오류: $it") }
        val current = report
        if (current == null) {
            InfoMonitorCard("모니터 결과를 아직 읽지 못했습니다.")
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${current.symbol} ${current.market}/${current.interval}", fontWeight = FontWeight.Bold)
                    Text("신호: ${current.signal}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(current.summary)
                    Text("현재가 ${current.currentPrice?.let { "%,.2f".format(it) } ?: "미확인"} / 마지막 캔들 ${current.lastCandleKst.ifBlank { "미확인" }}")
                    Text("리포트 생성 ${current.generatedAtKst.ifBlank { "첫 실행 대기" }}")
                    BtcSparkline(current.recentCloses)
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("현재 진입 조건", fontWeight = FontWeight.Bold)
                    Text("상위시간 문맥 ${yesNo(current.contextActive)} / 기본 setup ${current.baseScore}/${current.baseRequired} / 확인 ${current.confirmationScore}/${current.confirmationRequired}")
                    Text("VWAP 상단 ${yesNo(current.aboveVwap)} / 거래량 ${yesNo(current.volumeOk)}")
                    Text("RSI ${number(current.rsi)} / ADX ${number(current.adx)} / VWAP ${price(current.vwap)} / 거래량배수 ${number(current.volumeMultiple)}x")
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("최적화 백테스트", fontWeight = FontWeight.Bold)
                    Text("거래 ${current.backtestTrades}건 / 승률 ${percent(current.backtestWinRate)} / PF ${number(current.backtestProfitFactor)} / 누적 ${percent(current.backtestNetPnlPct)}")
                    Text("백테스트는 일 1회, 라이브 조건 모니터는 매시간 실행됩니다.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun BtcSparkline(values: List<Double>) {
    if (values.size < 2) {
        Text("간략차트: 첫 모니터 실행 후 표시됩니다.")
        return
    }
    val minValue = values.minOrNull() ?: return
    val maxValue = values.maxOrNull() ?: return
    val range = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
    val lineColor = if (values.last() >= values.first()) Color(0xFF2E7D32) else Color(0xFFC62828)
    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp)) {
        val step = size.width / (values.size - 1)
        for (index in 1 until values.size) {
            val x1 = (index - 1) * step
            val x2 = index * step
            val y1 = size.height - ((values[index - 1] - minValue) / range).toFloat() * size.height
            val y2 = size.height - ((values[index] - minValue) / range).toFloat() * size.height
            drawLine(lineColor, Offset(x1, y1), Offset(x2, y2), strokeWidth = 4f)
        }
    }
}

@Composable
private fun InfoMonitorCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) { Text(text, modifier = Modifier.padding(12.dp)) }
}

private suspend fun loadBtcMonitorReport(): BtcMonitorReport = withContext(Dispatchers.IO) {
    val connection = URL(MONITOR_URL).openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("Accept", "application/json")
    try {
        if (connection.responseCode !in 200..299) error("BTC monitor HTTP ${connection.responseCode}")
        parseBtcMonitor(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
    } finally {
        connection.disconnect()
    }
}

private fun parseBtcMonitor(json: JSONObject): BtcMonitorReport {
    val components = json.optJSONObject("components") ?: JSONObject()
    val metrics = json.optJSONObject("metrics") ?: JSONObject()
    val backtest = json.optJSONObject("backtest_summary") ?: JSONObject()
    val recent = json.optJSONArray("recent_closes")
    val closes = buildList {
        if (recent != null) {
            for (index in 0 until recent.length()) {
                val value = recent.optDouble(index, Double.NaN)
                if (value.isFinite()) add(value)
            }
        }
    }
    return BtcMonitorReport(
        ok = json.optBoolean("ok", false),
        generatedAtKst = json.optString("generated_at_kst"),
        symbol = json.optString("symbol", "BTCUSDT"),
        market = json.optString("market", "futures"),
        interval = json.optString("interval", "5m"),
        signal = json.optString("signal", "UNKNOWN"),
        summary = json.optString("summary", "결과 없음"),
        currentPrice = json.optNullableDouble("current_price"),
        lastCandleKst = json.optString("last_candle_kst"),
        recentCloses = closes,
        rsi = metrics.optNullableDouble("rsi"),
        adx = metrics.optNullableDouble("adx"),
        vwap = metrics.optNullableDouble("vwap"),
        volumeMultiple = metrics.optNullableDouble("volume_multiple"),
        contextActive = components.optBoolean("context_active", false),
        baseScore = components.optInt("base_score", 0),
        baseRequired = components.optInt("base_required", 0),
        confirmationScore = components.optInt("confirmation_score", 0),
        confirmationRequired = components.optInt("confirmation_required", 0),
        aboveVwap = components.optBoolean("above_vwap", false),
        volumeOk = components.optBoolean("volume_ok", false),
        backtestTrades = backtest.optInt("trades", 0),
        backtestWinRate = backtest.optNullableDouble("win_rate_pct"),
        backtestProfitFactor = backtest.optNullableDouble("profit_factor"),
        backtestNetPnlPct = backtest.optNullableDouble("net_pnl_pct"),
    )
}

private fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val value = optDouble(key, Double.NaN)
    return if (value.isFinite()) value else null
}

private fun yesNo(value: Boolean): String = if (value) "충족" else "미충족"
private fun number(value: Double?): String = value?.let { "%.2f".format(it) } ?: "-"
private fun price(value: Double?): String = value?.let { "%,.2f".format(it) } ?: "-"
private fun percent(value: Double?): String = value?.let { "%+.2f%%".format(it) } ?: "-"

private const val MONITOR_URL = "https://raw.githubusercontent.com/shopper12/gpt_coin_android/main/reports/binance_btc_monitor_latest.json"
