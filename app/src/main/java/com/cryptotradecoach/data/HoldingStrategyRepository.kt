package com.cryptotradecoach.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class HoldingStrategySignal(
    val ticker: String,
    val name: String,
    val holdingSignal: String,
    val holdingSignalReason: String,
    val baseAction: String,
    val actionReason: String,
    val score: Double,
    val threshold: Double,
    val setup: String,
    val currentPrice: Double,
    val entry: Double,
    val stopLoss: Double,
    val target1: Double,
    val target2: Double,
    val riskPct: Double,
    val reason: String,
    val failureCondition: String,
    val rsi14: Double,
    val gapMa20Pct: Double,
    val momentum20dPct: Double,
    val ict: IctAnalysis,
)

data class HoldingStrategyBatch(
    val signals: List<HoldingStrategySignal>,
    val errors: List<String>,
)

class HoldingStrategyRepository {
    suspend fun analyzeHoldings(holdings: List<KisHolding>): HoldingStrategyBatch = coroutineScope {
        val targets = holdings
            .filter { it.ticker.filter(Char::isDigit).length == 6 }
            .distinctBy { it.ticker }
            .take(MAX_HOLDINGS)
        if (targets.isEmpty()) return@coroutineScope HoldingStrategyBatch(emptyList(), emptyList())

        val semaphore = Semaphore(MAX_CONCURRENT_ANALYSES)
        val attempts = targets.map { holding ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    runCatching { analyzeOne(holding) }
                        .fold(
                            onSuccess = { AnalysisAttempt(it, null) },
                            onFailure = {
                                AnalysisAttempt(
                                    signal = null,
                                    error = "${holding.name}(${holding.ticker}): ${(it.message ?: it.javaClass.simpleName).take(180)}",
                                )
                            },
                        )
                }
            }
        }.awaitAll()

        HoldingStrategyBatch(
            signals = attempts.mapNotNull { it.signal }.sortedWith(
                compareBy<HoldingStrategySignal> { signalPriority(it.holdingSignal) }
                    .thenByDescending { it.score }
            ),
            errors = attempts.mapNotNull { it.error },
        )
    }

    private fun analyzeOne(holding: KisHolding): HoldingStrategySignal {
        val code = holding.ticker.filter(Char::isDigit).padStart(6, '0')
        val base = JSONObject(
            httpJson(
                method = "POST",
                url = STOCK_STRATEGY_URL,
                body = JSONObject().put("query", code).toString(),
                readTimeoutMs = STRATEGY_TIMEOUT_MS,
            )
        )
        val resolvedCode = base.optString("code", code).filter(Char::isDigit).padStart(6, '0')
        val ict = fetchIctAnalysis(resolvedCode)
        val baseScore = base.optDouble("score", 0.0)
        val threshold = base.optDouble("threshold", 0.0)
        val adjustedScore = (baseScore + ict.scoreAdjustment).coerceIn(0.0, 100.0)
        val baseAction = base.optString("action", "관망")
        val currentPrice = base.optDouble("current_price", holding.currentPrice.toDouble())
        val stopLoss = base.optDouble("stop_loss", 0.0)
        val target1 = base.optDouble("target1", 0.0)
        val target2 = base.optDouble("target2", 0.0)
        val adjustedAction = adjustAction(baseAction, adjustedScore, threshold, ict)
        val holdingDecision = deriveHoldingSignal(
            action = adjustedAction,
            score = adjustedScore,
            threshold = threshold,
            currentPrice = currentPrice,
            stopLoss = stopLoss,
            target1 = target1,
            target2 = target2,
            ict = ict,
        )
        val metrics = base.optJSONObject("metrics") ?: JSONObject()
        val actionReason = buildString {
            append(base.optString("action_reason", "").trim())
            if (isNotEmpty()) append(' ')
            append("ICT ${ict.bias} 조정 ${formatSigned(ict.scoreAdjustment)} 반영.")
        }
        return HoldingStrategySignal(
            ticker = resolvedCode,
            name = base.optString("name", holding.name).ifBlank { holding.name },
            holdingSignal = holdingDecision.first,
            holdingSignalReason = holdingDecision.second,
            baseAction = adjustedAction,
            actionReason = actionReason,
            score = adjustedScore,
            threshold = threshold,
            setup = base.optString("setup", ""),
            currentPrice = currentPrice,
            entry = base.optDouble("entry", 0.0),
            stopLoss = stopLoss,
            target1 = target1,
            target2 = target2,
            riskPct = base.optDouble("risk_pct", 0.0),
            reason = base.optString("reason", ""),
            failureCondition = base.optString("failure_condition", ""),
            rsi14 = metrics.optDouble("rsi14", 0.0),
            gapMa20Pct = metrics.optDouble("gap_ma20_pct", 0.0),
            momentum20dPct = metrics.optDouble("momentum_20d_pct", 0.0),
            ict = ict,
        )
    }

    private fun fetchIctAnalysis(code: String): IctAnalysis {
        val json = JSONObject(
            httpJson(
                method = "GET",
                url = "$STOCK_CHART_URL?code=$code&days=160",
                body = null,
                readTimeoutMs = CHART_TIMEOUT_MS,
            )
        )
        val rows = json.optJSONArray("candles")
        val candles = buildList {
            if (rows != null) {
                for (index in 0 until rows.length()) {
                    val item = rows.optJSONObject(index) ?: continue
                    add(
                        IctCandle(
                            date = item.optString("date"),
                            open = item.optDouble("open", 0.0),
                            high = item.optDouble("high", 0.0),
                            low = item.optDouble("low", 0.0),
                            close = item.optDouble("close", 0.0),
                        )
                    )
                }
            }
        }
        return IctChartAnalyzer.analyze(candles)
    }

    private fun adjustAction(
        baseAction: String,
        score: Double,
        threshold: Double,
        ict: IctAnalysis,
    ): String {
        return when {
            ict.bias == "BEARISH" && baseAction.contains("매수") -> "조건부 대기"
            ict.bias == "BULLISH" && baseAction == "조건부 매수" && score >= threshold + 10.0 -> "매수 후보"
            else -> baseAction
        }
    }

    private fun deriveHoldingSignal(
        action: String,
        score: Double,
        threshold: Double,
        currentPrice: Double,
        stopLoss: Double,
        target1: Double,
        target2: Double,
        ict: IctAnalysis,
    ): Pair<String, String> {
        return when {
            currentPrice > 0.0 && stopLoss > 0.0 && currentPrice <= stopLoss ->
                "손절·축소 검토" to "현재가가 전략 무효화/손절 기준 이하입니다."
            currentPrice > 0.0 && target2 > 0.0 && currentPrice >= target2 ->
                "2차 목표 도달·익절관리" to "현재가가 2차 목표 이상이므로 수익 보호가 우선입니다."
            currentPrice > 0.0 && target1 > 0.0 && currentPrice >= target1 ->
                "1차 목표 도달·트레일링" to "1차 목표를 통과했으므로 일부 이익실현과 추적손절 구간입니다."
            ict.bias == "BEARISH" && score < threshold ->
                "비중축소·방어" to "ICT 약세 구조이고 종합점수가 기준 미만입니다."
            action.contains("매수") && ict.bias != "BEARISH" ->
                "추가매수 후보" to "기본 매수 조건과 ICT 구조가 충돌하지 않습니다."
            score >= threshold && ict.bias != "BEARISH" ->
                "보유" to "종합점수가 기준 이상이고 ICT가 약세로 확정되지 않았습니다."
            else ->
                "보유·관망" to "추가매수 확인 신호가 부족해 기존 보유분 관리가 우선입니다."
        }
    }

    private fun httpJson(
        method: String,
        url: String,
        body: String?,
        readTimeoutMs: Int,
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            useCaches = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "UnifiedTradingCoach-Holdings")
            if (method == "POST") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (body != null) {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IOException("HTTP $status: ${text.take(240)}")
            if (text.trimStart().startsWith("<")) throw IOException("주식 전략 서버가 JSON 대신 HTML을 반환했습니다.")
            text
        } finally {
            connection.disconnect()
        }
    }

    private data class AnalysisAttempt(
        val signal: HoldingStrategySignal?,
        val error: String?,
    )

    private companion object {
        private const val STOCK_API_BASE_URL = "https://stock-scanner-api-5sk6.onrender.com"
        private const val STOCK_STRATEGY_URL = "$STOCK_API_BASE_URL/api/kr-stock-strategy"
        private const val STOCK_CHART_URL = "$STOCK_API_BASE_URL/api/kr-stock-chart"
        private const val MAX_HOLDINGS = 40
        private const val MAX_CONCURRENT_ANALYSES = 3
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val STRATEGY_TIMEOUT_MS = 120_000
        private const val CHART_TIMEOUT_MS = 40_000

        private fun signalPriority(signal: String): Int {
            return when {
                signal.startsWith("손절") -> 0
                signal.startsWith("비중축소") -> 1
                signal.startsWith("추가매수") -> 2
                signal.startsWith("1차") || signal.startsWith("2차") -> 3
                signal == "보유" -> 4
                else -> 5
            }
        }

        private fun formatSigned(value: Double): String = String.format(java.util.Locale.US, "%+.1f", value)
    }
}
