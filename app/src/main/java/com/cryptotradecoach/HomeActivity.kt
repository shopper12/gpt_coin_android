package com.cryptotradecoach

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cryptotradecoach.data.WorkflowDispatchRepository
import kotlinx.coroutines.launch

class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val workflowRepository = WorkflowDispatchRepository(this)
        setContent {
            MaterialTheme {
                UnifiedHomeScreen(
                    onCoin = { startActivity(Intent(this, MainActivity::class.java)) },
                    onStock = { startActivity(Intent(this, StockActivity::class.java)) },
                    onRecommendationHistory = { startActivity(Intent(this, RecommendationHistoryActivity::class.java)) },
                    onBtcMonitor = { startActivity(Intent(this, BacktestMonitorActivity::class.java)) },
                    onWorkflow = { workflowRepository.dispatchUnifiedStrategyMonitor() },
                )
            }
        }
    }
}

@Composable
private fun UnifiedHomeScreen(
    onCoin: () -> Unit,
    onStock: () -> Unit,
    onRecommendationHistory: () -> Unit,
    onBtcMonitor: () -> Unit,
    onWorkflow: suspend () -> String,
) {
    val scope = rememberCoroutineScope()
    var workflowRunning by remember { mutableStateOf(false) }
    var workflowMessage by remember { mutableStateOf<String?>(null) }

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

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Unified Trading Coach", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("앱은 하나로 통합하고, 코인·주식 전략과 백테스트 모니터는 메뉴를 분리해서 운용합니다.", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            HomeCard(
                title = "코인 메뉴",
                description = "Upbit KRW 코인 스캔, ACTIVE 신호, 차트, 성과 추적, 룰 편집, 코인 백테스트 진화 로그를 봅니다.",
                button = "코인 전략 열기",
                onClick = onCoin,
            )
        }
        item {
            HomeCard(
                title = "주식 메뉴 + ICT",
                description = "한국 단기 후보·성과·백테스트와 단일 종목의 BOS/CHOCH, 유동성 스윕, FVG, 프리미엄·디스카운트 분석을 봅니다.",
                button = "주식 전략 열기",
                onClick = onStock,
            )
        }
        item {
            HomeCard(
                title = "과거 추천·현재 수익률",
                description = "종목·현재가·추천가·추천가부터 수익률·간략차트를 한 줄 표로 확인합니다.",
                button = "추천 이력 열기",
                onClick = onRecommendationHistory,
            )
        }
        item {
            HomeCard(
                title = "BTC 24시간 백테스트 모니터",
                description = "매시간 Binance 5분봉 진입 조건과 일 1회 최적화 백테스트 결과를 앱에서 확인합니다.",
                button = "BTC 모니터 열기",
                onClick = onBtcMonitor,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("통합 백테스트/전략 진화", fontWeight = FontWeight.Bold)
                    Text("버튼을 누르면 GitHub 웹페이지를 열지 않고 코인·주식 자가검증 workflow를 즉시 요청합니다.")
                    OutlinedButton(
                        onClick = { runWorkflow() },
                        enabled = !workflowRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (workflowRunning) "실행 요청 중" else "코인·주식 자가검증 실행")
                    }
                    workflowMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun HomeCard(title: String, description: String, button: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(description)
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(button) }
        }
    }
}
