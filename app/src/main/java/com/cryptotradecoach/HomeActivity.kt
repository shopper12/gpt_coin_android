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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                UnifiedHomeScreen(
                    onCoin = { startActivity(Intent(this, MainActivity::class.java)) },
                    onStock = { startActivity(Intent(this, StockActivity::class.java)) },
                    onWorkflow = { openUrl(UNIFIED_WORKFLOW_URL) },
                )
            }
        }
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private companion object {
        private const val UNIFIED_WORKFLOW_URL = "https://github.com/shopper12/gpt_coin_android/actions/workflows/unified-strategy-monitor.yml"
    }
}

@Composable
private fun UnifiedHomeScreen(
    onCoin: () -> Unit,
    onStock: () -> Unit,
    onWorkflow: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Unified Trading Coach", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("코인·주식·백테스트 검증을 한 앱에서 분리해서 봅니다.", style = MaterialTheme.typography.bodyMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("코인 메뉴", fontWeight = FontWeight.Bold)
                Text("업비트 KRW 코인 스캔, ACTIVE 전략, 차트, 성과 추적, 룰 편집, 코인 백테스트 진화 로그를 봅니다.")
                Button(onClick = onCoin, modifier = Modifier.fillMaxWidth()) { Text("코인 전략 열기") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("주식 메뉴", fontWeight = FontWeight.Bold)
                Text("한국 단기 주식, 퇴직연금 ETF, 미국 장기 ETF, 환전 판단과 주식 백테스트 진화 상태를 봅니다.")
                Button(onClick = onStock, modifier = Modifier.fillMaxWidth()) { Text("주식 전략 열기") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("통합 백테스트/전략 진화", fontWeight = FontWeight.Bold)
                Text("GitHub Actions의 Unified strategy monitor가 코인과 주식 전략을 같은 루프에서 백테스트하고 룰 변경을 검증합니다.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onWorkflow) { Text("모니터 열기") }
                }
            }
        }
    }
}
