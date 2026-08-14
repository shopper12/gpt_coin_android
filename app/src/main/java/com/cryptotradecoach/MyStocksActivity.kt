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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cryptotradecoach.data.HoldingStrategyRepository
import com.cryptotradecoach.data.HoldingStrategySignal
import com.cryptotradecoach.data.KisCredentials
import com.cryptotradecoach.data.KisHolding
import com.cryptotradecoach.data.KisHoldingsSnapshot
import com.cryptotradecoach.data.MyStocksRepository
import com.cryptotradecoach.data.WatchlistItem
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MyStocksActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = MyStocksRepository(this)
        val strategyRepository = HoldingStrategyRepository()
        setContent {
            MaterialTheme {
                MyStocksScreen(
                    repository = repository,
                    strategyRepository = strategyRepository,
                    onBack = { finish() },
                    onRecommendations = {
                        startActivity(Intent(this, RecommendationHistoryActivity::class.java))
                    },
                )
            }
        }
    }
}

private enum class MyStocksTab(val label: String) {
    WATCHLIST("관심종목"),
    HOLDINGS("보유종목"),
    HOLDING_SIGNALS("보유전략"),
    KIS_SETTINGS("한투 설정"),
}

@Composable
private fun MyStocksScreen(
    repository: MyStocksRepository,
    strategyRepository: HoldingStrategyRepository,
    onBack: () -> Unit,
    onRecommendations: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(MyStocksTab.WATCHLIST) }
    var watchlist by remember { mutableStateOf(repository.loadWatchlist()) }
    var holdings by remember { mutableStateOf(repository.loadCachedHoldings()) }
    var credentials by remember { mutableStateOf(repository.loadCredentials()) }
    var strategySignals by remember { mutableStateOf<List<HoldingStrategySignal>>(emptyList()) }
    var strategyErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingHoldings by remember { mutableStateOf(false) }
    var analyzingStrategies by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun analyzeHoldings(targets: List<KisHolding> = holdings.holdings) {
        if (analyzingStrategies || targets.isEmpty()) return
        scope.launch {
            analyzingStrategies = true
            message = "보유종목 ${targets.size}개의 추세·모멘텀·ICT 전략 신호를 분석하는 중입니다."
            error = null
            runCatching { strategyRepository.analyzeHoldings(targets) }
                .onSuccess { result ->
                    strategySignals = result.signals
                    strategyErrors = result.errors
                    message = buildString {
                        append("보유전략 ${result.signals.size}개 분석 완료")
                        if (result.errors.isNotEmpty()) append(" / 실패 ${result.errors.size}개")
                    }
                }
                .onFailure {
                    error = it.message ?: it.javaClass.simpleName
                    message = null
                }
            analyzingStrategies = false
        }
    }

    fun refreshHoldings() {
        if (loadingHoldings) return
        scope.launch {
            loadingHoldings = true
            message = "한국투자증권 보유종목을 조회하는 중입니다."
            error = null
            runCatching { repository.refreshKisHoldings() }
                .onSuccess {
                    holdings = it
                    strategySignals = emptyList()
                    strategyErrors = emptyList()
                    message = "보유종목 ${it.holdings.size}개를 동기화했습니다."
                    if (tab == MyStocksTab.HOLDING_SIGNALS && it.holdings.isNotEmpty()) {
                        analyzeHoldings(it.holdings)
                    }
                }
                .onFailure {
                    error = it.message ?: it.javaClass.simpleName
                    message = null
                }
            loadingHoldings = false
        }
    }

    LaunchedEffect(Unit) {
        if (credentials.isConfigured) refreshHoldings()
    }

    LaunchedEffect(tab, holdings.fetchedAt) {
        if (
            tab == MyStocksTab.HOLDING_SIGNALS &&
            holdings.holdings.isNotEmpty() &&
            strategySignals.isEmpty() &&
            !loadingHoldings &&
            !analyzingStrategies
        ) {
            analyzeHoldings()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onBack) { Text("← 홈") }
                OutlinedButton(onClick = onRecommendations) { Text("추천 목록") }
            }
        }
        item {
            Text("내 종목", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("한국투자증권 실보유 종목을 읽기 전용으로 동기화하고, 각 보유종목의 추세·모멘텀·ICT 전략 신호를 별도로 계산합니다.")
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MyStocksTab.entries.forEach { item ->
                    FilterChip(
                        selected = tab == item,
                        onClick = { tab = item },
                        label = { Text(item.label) },
                    )
                }
            }
        }
        error?.let { item { MyStocksInfoCard("오류: $it") } }
        message?.let { item { MyStocksInfoCard(it) } }
        when (tab) {
            MyStocksTab.WATCHLIST -> {
                item {
                    AddWatchlistCard(
                        onAdd = { ticker, name ->
                            runCatching {
                                check(
                                    repository.addWatchlist(
                                        WatchlistItem(
                                            ticker = ticker,
                                            name = name,
                                            market = if (ticker.filter(Char::isDigit).length == 6) "KR" else "",
                                            assetClass = "MANUAL",
                                            currency = if (ticker.filter(Char::isDigit).length == 6) "KRW" else "",
                                        )
                                    )
                                ) { "관심종목을 저장하지 못했습니다." }
                            }.onSuccess {
                                watchlist = repository.loadWatchlist()
                                message = "${ticker.trim().uppercase(Locale.US)}을 관심종목에 추가했습니다."
                                error = null
                            }.onFailure {
                                error = it.message ?: it.javaClass.simpleName
                                message = null
                            }
                        },
                    )
                }
                if (watchlist.isEmpty()) {
                    item { MyStocksInfoCard("관심종목이 없습니다. 추천 목록에서 담거나 직접 종목코드를 입력하세요.") }
                } else {
                    items(watchlist, key = { it.key }) { item ->
                        WatchlistCard(
                            item = item,
                            onRemove = {
                                repository.removeWatchlist(item.ticker)
                                watchlist = repository.loadWatchlist()
                                message = "${item.ticker}을 관심종목에서 삭제했습니다."
                            },
                        )
                    }
                }
            }

            MyStocksTab.HOLDINGS -> {
                item {
                    HoldingsSummaryCard(
                        snapshot = holdings,
                        configured = credentials.isConfigured,
                        loading = loadingHoldings,
                        onRefresh = { refreshHoldings() },
                        onOpenSettings = { tab = MyStocksTab.KIS_SETTINGS },
                    )
                }
                if (holdings.holdings.isEmpty()) {
                    item {
                        MyStocksInfoCard(
                            if (credentials.isConfigured) {
                                "동기화된 보유종목이 없습니다. 조회 오류가 있으면 위 메시지와 한투 설정을 확인하세요."
                            } else {
                                "한투 설정을 저장하면 실제 보유종목을 자동으로 불러옵니다."
                            }
                        )
                    }
                } else {
                    items(holdings.holdings, key = { it.ticker }) { holding ->
                        HoldingCard(holding)
                    }
                }
            }

            MyStocksTab.HOLDING_SIGNALS -> {
                item {
                    HoldingStrategySummaryCard(
                        snapshot = holdings,
                        signalCount = strategySignals.size,
                        errorCount = strategyErrors.size,
                        configured = credentials.isConfigured,
                        loadingHoldings = loadingHoldings,
                        analyzing = analyzingStrategies,
                        onRefreshHoldings = { refreshHoldings() },
                        onAnalyze = { analyzeHoldings() },
                        onOpenSettings = { tab = MyStocksTab.KIS_SETTINGS },
                    )
                }
                if (strategyErrors.isNotEmpty()) {
                    item {
                        MyStocksInfoCard(
                            "분석 실패 ${strategyErrors.size}개: ${strategyErrors.take(3).joinToString(" / ")}"
                        )
                    }
                }
                if (holdings.holdings.isEmpty()) {
                    item {
                        MyStocksInfoCard(
                            if (credentials.isConfigured) "보유종목을 먼저 동기화하세요." else "한투 설정을 먼저 저장하세요."
                        )
                    }
                } else if (strategySignals.isEmpty()) {
                    item {
                        MyStocksInfoCard(
                            if (analyzingStrategies) "보유전략을 계산하고 있습니다." else "전략 결과가 없습니다. '전략 재분석'을 누르세요."
                        )
                    }
                } else {
                    val holdingMap = holdings.holdings.associateBy { it.ticker }
                    items(strategySignals, key = { it.ticker }) { signal ->
                        HoldingStrategyCard(signal = signal, holding = holdingMap[signal.ticker])
                    }
                }
            }

            MyStocksTab.KIS_SETTINGS -> {
                item {
                    KisSettingsCard(
                        initial = credentials,
                        onSave = { updated ->
                            runCatching {
                                check(repository.saveCredentials(updated)) {
                                    "한국투자증권 설정을 저장하지 못했습니다."
                                }
                            }
                                .onSuccess {
                                    credentials = repository.loadCredentials()
                                    holdings = repository.loadCachedHoldings()
                                    strategySignals = emptyList()
                                    strategyErrors = emptyList()
                                    message = "한국투자증권 설정을 암호화 저장했습니다."
                                    error = null
                                    tab = MyStocksTab.HOLDINGS
                                    refreshHoldings()
                                }
                                .onFailure {
                                    error = it.message ?: it.javaClass.simpleName
                                    message = null
                                }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddWatchlistCard(onAdd: (String, String) -> Unit) {
    var ticker by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("관심종목 직접 추가", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = ticker,
                onValueChange = { ticker = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("종목코드 · 예: 069500, AAPL") },
                singleLine = true,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("종목명(선택)") },
                singleLine = true,
            )
            Button(
                onClick = {
                    onAdd(ticker, name)
                    if (ticker.isNotBlank()) {
                        ticker = ""
                        name = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("관심종목 추가")
            }
        }
    }
}

@Composable
private fun WatchlistCard(item: WatchlistItem, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            KoreanStockIdentityLabel(ticker = item.ticker, preferredName = item.name)
            Text(
                listOf(item.market, item.assetClass, item.direction)
                    .filter(String::isNotBlank)
                    .joinToString(" · ")
                    .ifBlank { "직접 추가" }
            )
            if (item.referencePrice != null || item.currentPrice != null) {
                Text(
                    "추천가 ${formatMoney(item.referencePrice, item.currency)} / 현재가 ${
                        formatMoney(item.currentPrice, item.currency)
                    }"
                )
            }
            if (item.sourceRecommendationId.isNotBlank()) {
                Text("추천 목록에서 담은 종목", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onRemove) { Text("관심종목 삭제") }
        }
    }
}

@Composable
private fun HoldingsSummaryCard(
    snapshot: KisHoldingsSnapshot,
    configured: Boolean,
    loading: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val fallbackEvaluation = snapshot.holdings.fold(BigDecimal.ZERO) { total, item ->
        total + item.evaluationAmount
    }
    val fallbackProfitLoss = snapshot.holdings.fold(BigDecimal.ZERO) { total, item ->
        total + item.profitLossAmount
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("한국투자증권 보유종목", fontWeight = FontWeight.Bold)
            snapshot.accountSummary?.let { summary ->
                Text(
                    "종목 ${snapshot.holdings.size}개 / 순자산 ${formatWon(summary.netAssetAmount)} / " +
                        "예수금 ${formatWon(summary.cashBalance)}"
                )
                Text(
                    "평가금액 ${formatWon(summary.evaluationAmount)} / " +
                        "매입금액 ${formatWon(summary.purchaseAmount)}"
                )
                Text(
                    "평가손익 ${formatSignedWon(summary.profitLossAmount)}",
                    fontWeight = FontWeight.Bold,
                )
            } ?: Text(
                "종목 ${snapshot.holdings.size}개 / 평가금액 ${formatWon(fallbackEvaluation)} / " +
                    "평가손익 ${formatSignedWon(fallbackProfitLoss)}"
            )
            Text("마지막 동기화: ${formatTimestamp(snapshot.fetchedAt)}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRefresh, enabled = configured && !loading) {
                    Text(if (loading) "동기화 중" else "보유종목 새로고침")
                }
                OutlinedButton(onClick = onOpenSettings) { Text("한투 설정") }
            }
        }
    }
}

@Composable
private fun HoldingCard(item: KisHolding) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            KoreanStockIdentityLabel(ticker = item.ticker, preferredName = item.name)
            Text("보유 ${formatQuantity(item.quantity)}주 / 주문가능 ${formatQuantity(item.orderableQuantity)}주")
            Text("평균단가 ${formatWon(item.averagePrice)} / 현재가 ${formatWon(item.currentPrice)}")
            Text("평가금액 ${formatWon(item.evaluationAmount)} / 매입금액 ${formatWon(item.purchaseAmount)}")
            Text(
                "평가손익 ${formatSignedWon(item.profitLossAmount)} (${formatSignedPercent(item.profitLossRate)})",
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun HoldingStrategySummaryCard(
    snapshot: KisHoldingsSnapshot,
    signalCount: Int,
    errorCount: Int,
    configured: Boolean,
    loadingHoldings: Boolean,
    analyzing: Boolean,
    onRefreshHoldings: () -> Unit,
    onAnalyze: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("한국투자증권 보유전략", fontWeight = FontWeight.Bold)
            Text("한투에서 읽은 보유 ${snapshot.holdings.size}개 중 전략 ${signalCount}개 분석 / 실패 ${errorCount}개")
            Text("App Key·App Secret·계좌번호·보유수량·평균단가는 전략 서버로 보내지 않습니다. 전략 분석에는 6자리 종목코드만 사용합니다.", style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onRefreshHoldings,
                    enabled = configured && !loadingHoldings && !analyzing,
                ) {
                    Text(if (loadingHoldings) "잔고 동기화 중" else "잔고+전략 새로고침")
                }
                OutlinedButton(
                    onClick = onAnalyze,
                    enabled = snapshot.holdings.isNotEmpty() && !loadingHoldings && !analyzing,
                ) {
                    Text(if (analyzing) "전략 분석 중" else "전략 재분석")
                }
                OutlinedButton(onClick = onOpenSettings) { Text("한투 설정") }
            }
        }
    }
}

@Composable
private fun HoldingStrategyCard(signal: HoldingStrategySignal, holding: KisHolding?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            KoreanStockIdentityLabel(ticker = signal.ticker, preferredName = holding?.name ?: signal.name)
            Text("보유전략: ${signal.holdingSignal}", fontWeight = FontWeight.Bold)
            Text(signal.holdingSignalReason)
            if (holding != null) {
                Text(
                    "한투 현재가 ${formatWon(holding.currentPrice)} / 평균단가 ${formatWon(holding.averagePrice)} / " +
                        "보유수익률 ${formatSignedPercent(holding.profitLossRate)}"
                )
            }
            Text("전략 현재가 ${formatWon(signal.currentPrice)} / 기본판단 ${signal.baseAction}")
            Text("점수 ${formatScore(signal.score)} / 기준 ${formatScore(signal.threshold)} / ${signal.setup}")
            Text(
                "진입 ${formatWon(signal.entry)} / 손절 ${formatWon(signal.stopLoss)} / " +
                    "목표 ${formatWon(signal.target1)} → ${formatWon(signal.target2)}"
            )
            Text(
                "RSI ${formatScore(signal.rsi14)} / MA20 괴리 ${formatSignedPercent(signal.gapMa20Pct)} / " +
                    "20일 모멘텀 ${formatSignedPercent(signal.momentum20dPct)}"
            )
            Text("ICT: ${signal.ict.summary}", fontWeight = FontWeight.Bold)
            if (signal.actionReason.isNotBlank()) Text("판단근거: ${signal.actionReason}")
            if (signal.reason.isNotBlank()) Text("기본근거: ${signal.reason}")
            if (signal.failureCondition.isNotBlank()) Text("무효화: ${signal.failureCondition}")
        }
    }
}

@Composable
private fun KisSettingsCard(initial: KisCredentials, onSave: (KisCredentials) -> Unit) {
    var appKey by remember(initial.appKey) { mutableStateOf(initial.appKey) }
    var appSecret by remember(initial.appSecret) { mutableStateOf(initial.appSecret) }
    var accountNumber by remember(initial.accountNumber) { mutableStateOf(initial.accountNumber) }
    var mockTrading by remember(initial.mockTrading) { mutableStateOf(initial.mockTrading) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("한국투자증권 Open API 설정", fontWeight = FontWeight.Bold)
            Text("앱키·시크릿·계좌번호와 토큰은 이 기기의 Android Keystore 암호화 저장소에만 보관됩니다.")
            Text("잔고조회만 사용하며 주문 API는 호출하지 않습니다.", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = appKey,
                onValueChange = { appKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("App Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            OutlinedTextField(
                value = appSecret,
                onValueChange = { appSecret = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("App Secret") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            OutlinedTextField(
                value = accountNumber,
                onValueChange = { accountNumber = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("계좌번호 10자리(앞 8자리 + 상품코드 2자리)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = mockTrading, onCheckedChange = { mockTrading = it })
                Text(if (mockTrading) "모의투자 계좌" else "실전투자 계좌", modifier = Modifier.padding(top = 12.dp))
            }
            Button(
                onClick = {
                    onSave(
                        KisCredentials(
                            appKey = appKey,
                            appSecret = appSecret,
                            accountNumber = accountNumber,
                            mockTrading = mockTrading,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("암호화 저장 후 잔고 동기화")
            }
        }
    }
}

@Composable
private fun MyStocksInfoCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(12.dp))
    }
}

private fun formatMoney(value: Double?, currency: String): String {
    if (value == null) return "미확인"
    return if (currency == "KRW") formatWon(value) else "%,.2f %s".format(Locale.US, value, currency)
}

private fun formatWon(value: Double): String = "%,.0f원".format(Locale.US, value)
private fun formatWon(value: BigDecimal): String {
    val rounded = value.setScale(0, RoundingMode.HALF_UP)
    return "${NumberFormat.getIntegerInstance(Locale.KOREA).format(rounded)}원"
}

private fun formatSignedWon(value: BigDecimal): String {
    val prefix = if (value.signum() > 0) "+" else ""
    return "$prefix${formatWon(value)}"
}

private fun formatSignedPercent(value: BigDecimal): String {
    val prefix = if (value.signum() > 0) "+" else ""
    return "$prefix${value.setScale(2, RoundingMode.HALF_UP).toPlainString()}%"
}

private fun formatSignedPercent(value: Double): String = String.format(Locale.US, "%+.2f%%", value)
private fun formatScore(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun formatQuantity(value: BigDecimal): String {
    return NumberFormat.getNumberInstance(Locale.KOREA).apply {
        maximumFractionDigits = 8
        isGroupingUsed = true
    }.format(value.stripTrailingZeros())
}

private fun formatTimestamp(value: Long): String {
    if (value <= 0L) return "아직 없음"
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.KOREA)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(value))
}
