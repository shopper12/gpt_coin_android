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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class StockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                StockMenuScreen(
                    onBack = { finish() },
                    onWorkflow = { openUrl(UNIFIED_WORKFLOW_URL) },
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
        private const val UNIFIED_WORKFLOW_URL = "https://github.com/shopper12/gpt_coin_android/actions/workflows/unified-strategy-monitor.yml"
        private const val DOCS_URL = "https://github.com/shopper12/gpt_coin_android/blob/main/docs/unified-trading-app.md"
        private const val STOCK_REPO_URL = "https://github.com/shopper12/stock_scanner"
    }
}

@Composable
private fun StockMenuScreen(
    onBack: () -> Unit,
    onWorkflow: () -> Unit,
    onDocs: () -> Unit,
    onStockRepo: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("← 홈") }
                OutlinedButton(onClick = onWorkflow) { Text("통합 모니터") }
            }
        }
        item {
            Text("Stock Strategy", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("stock_scanner의 추천전략을 앱 안에서 별도 메뉴로 분리했습니다.", style = MaterialTheme.typography.bodyMedium)
        }
        item { StockStrategyCard("KR_SHORT_STOCK", "한국 단기 일반계좌", "한국 개별주 단기 후보를 점수화합니다. 거래대금, 섹터 강도, 과열·리스크 필터, 진입·손절·목표가를 같이 검증합니다.") }
        item { StockStrategyCard("KR_RETIREMENT_ETF", "퇴직연금 ETF", "퇴직연금 계좌에서 접근 가능한 한국 ETF 중심으로 장기·방어·분할매수 후보를 관리합니다.") }
        item { StockStrategyCard("US_LONG_ETF", "미국 장기 ETF", "미국 단기 매매가 아니라 장기 ETF 분할매수 비중과 DCA 백테스트를 관리합니다.") }
        item { StockStrategyCard("FX_CONVERSION", "USD/KRW 환전 판단", "달러 환전 타이밍을 20/60/120일 평균, DXY, 미국 10년물, VIX 등과 함께 판단합니다.") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("백테스트/전략 진화", fontWeight = FontWeight.Bold)
                    Text("Unified strategy monitor가 코인 Upbit 백테스트와 주식 KR_SHORT 백테스트를 같이 돌리고, 검증된 룰만 다음 실행 후보로 올립니다.")
                    Text("주식 룰 기준 파일: rules/stock-strategy-rules.json")
                    Text("코인 룰 기준 파일: rules/strategy-rules.json")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onWorkflow) { Text("실행/결과 보기") }
                        OutlinedButton(onClick = onDocs) { Text("문서") }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("통합 상태", fontWeight = FontWeight.Bold)
                    Text("모바일 앱의 주식 메뉴는 gpt_coin_android에 들어왔고, stock_scanner 원본 레포는 전략 소스/히스토리 참조로만 남깁니다.")
                    OutlinedButton(onClick = onStockRepo) { Text("원본 stock_scanner 확인") }
                }
            }
        }
    }
}

@Composable
private fun StockStrategyCard(key: String, title: String, description: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$key · $title", fontWeight = FontWeight.Bold)
            Text(description)
        }
    }
}
