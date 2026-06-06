package com.cryptotradecoach.data

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

interface MarketDataSource {
    suspend fun fetchTickers(): List<Ticker>
    suspend fun fetchMarketCandidates(limit: Int = 40, rules: StrategyRules = StrategyRules.DEFAULT): List<MarketCandidate>
}

class UpbitMarketDataSource : MarketDataSource {
    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var btcChangeRate24h: Double = 0.0
        private set

    private val candleCache = mutableMapOf<String, CachedCandles>()
    private var lastRequestAt: Long = 0L

    override suspend fun fetchTickers(): List<Ticker> {
        lastError = null
        val markets = fetchKrwMarkets()
        if (markets.isEmpty()) {
            btcChangeRate24h = 0.0
            return emptyList()
        }
        val tickers = fetchTickerFor(markets)
        btcChangeRate24h = tickers
            .firstOrNull { it.market == "KRW-BTC" }
            ?.signedChangeRate
            ?.times(100.0)
            ?: 0.0
        return tickers
    }

    fun selectCandleTargets(
        tickers: List<Ticker>,
        limit: Int = MAX_CANDLE_TARGETS,
        rules: CandidateSelectionRules = StrategyRules.DEFAULT.candidateSelection,
    ): List<Ticker> {
        val boundedLimit = limit.coerceIn(10, rules.maxCandleTargets.coerceIn(10, MAX_CANDLE_TARGETS))
        if (tickers.isEmpty()) return emptyList()

        val byTradeValue = tickers.sortedByDescending { it.accTradePrice24h }.take(rules.topTradeValueCount)
        val byChangeRate = tickers.sortedByDescending { it.signedChangeRate }.take(rules.topChangeRateCount)
        val tradeValueMarkets = byTradeValue.map { it.market }.toSet()

        val medianTradePrice = tickers.map { it.accTradePrice24h }.sorted()
            .let { values -> if (values.isEmpty()) 0.0 else values[values.size / 2] }

        val byVolumeBuildup = tickers
            .filter { ticker ->
                ticker.accTradePrice24h > medianTradePrice * rules.medianTradeValueMultiplier &&
                    ticker.signedChangeRate * 100.0 in rules.minBuildupChangeRatePct..rules.maxBuildupChangeRatePct &&
                    ticker.market !in tradeValueMarkets
            }
            .sortedByDescending { it.accTradePrice24h }
            .take(rules.volumeBuildupCount)
        val buildupMarkets = byVolumeBuildup.map { it.market }.toSet()

        val byQuietAccumulation = tickers
            .filter { ticker ->
                abs(ticker.signedChangeRate * 100.0) < rules.maxQuietAbsChangeRatePct &&
                    ticker.accTradeVolume24h > 0.0 &&
                    ticker.market !in tradeValueMarkets &&
                    ticker.market !in buildupMarkets
            }
            .sortedByDescending { it.accTradeVolume24h }
            .take(rules.quietAccumulationCount)

        val byForcedMajorAlts = tickers
            .filter { it.market in FORCE_WATCH_MARKETS }
            .sortedByDescending { it.accTradePrice24h }

        val tradeRanks = byTradeValue.mapIndexed { index, ticker -> ticker.market to index + 1 }.toMap()
        val changeRanks = byChangeRate.mapIndexed { index, ticker -> ticker.market to index + 1 }.toMap()

        return (byForcedMajorAlts + byTradeValue + byChangeRate + byVolumeBuildup + byQuietAccumulation)
            .distinctBy { it.market }
            .sortedWith(
                compareBy<Ticker> {
                    when {
                        it.market in FORCE_WATCH_MARKETS -> 0
                        else -> minOf(
                            tradeRanks[it.market] ?: Int.MAX_VALUE,
                            changeRanks[it.market] ?: Int.MAX_VALUE,
                        )
                    }
                }.thenByDescending { it.accTradePrice24h },
            )
            .take(boundedLimit)
    }

    fun fetchCandleData(tickers: List<Ticker>, rules: CandidateSelectionRules = StrategyRules.DEFAULT.candidateSelection): CandleData {
        val out = mutableMapOf<String, Map<Int, List<Candle>>>()
        tickers.take(rules.maxCandleTargets.coerceIn(10, MAX_CANDLE_TARGETS)).forEach { ticker ->
            val byUnit = SUPPORTED_UNITS.associateWith { unit ->
                runCatching { fetchMinuteCandles(ticker.market, unit, countForUnit(unit)) }
                    .onFailure { error -> lastError = "Upbit candle $unit ${ticker.market} failed: ${error.message ?: error::class.java.simpleName}" }
                    .getOrDefault(emptyList())
            }
            if (byUnit.values.any { it.isNotEmpty() }) {
                out[ticker.market] = byUnit
            }
        }
        return out
    }

    override suspend fun fetchMarketCandidates(limit: Int, rules: StrategyRules): List<MarketCandidate> {
        val selectionRules = rules.candidateSelection
        val boundedLimit = limit.coerceIn(10, selectionRules.maxCandleTargets.coerceIn(10, MAX_CANDLE_TARGETS))
        val tickers = fetchTickers()
        val changeRanks = tickers
            .sortedByDescending { it.signedChangeRate }
            .mapIndexed { index, ticker -> ticker.market to index + 1 }
            .toMap()
        val tradeValueRanks = tickers
            .sortedByDescending { it.accTradePrice24h }
            .mapIndexed { index, ticker -> ticker.market to index + 1 }
            .toMap()

        val selectedTickers = if (isHardRiskOff(rules)) {
            lastError = marketRiskOffMessage(rules, "신규 알트 후보 차단. KRW-BTC 위험회피 신호만 평가합니다.")
            tickers.filter { it.market == "KRW-BTC" }
        } else {
            if (isSoftRiskOff()) {
                lastError = "전체 코인 시장 약세 경고: BTC 24h ${btcChangeRate24h.one()}%. 알트 신호는 보수적으로 봐야 합니다."
            }
            selectCandleTargets(tickers, boundedLimit, selectionRules)
        }

        return selectedTickers.mapNotNull { ticker -> ticker.toMarketCandidate(changeRanks, tradeValueRanks) }
    }

    suspend fun fetchManualMarketCandidate(rawSymbol: String): MarketCandidate? {
        val market = normalizeMarket(rawSymbol)
        lastError = null
        val allTickers = fetchTickers()
        if (market != "KRW-BTC" && btcChangeRate24h <= StrategyRules.DEFAULT.scoring.hardBlockBtc24hBelowPct) {
            lastError = "전체 코인 시장 약세: BTC 24h ${btcChangeRate24h.one()}% <= ${StrategyRules.DEFAULT.scoring.hardBlockBtc24hBelowPct.one()}%. 수동 알트 분석을 차단합니다. BTC 또는 현금관망만 봅니다."
            return null
        }
        val ticker = allTickers.firstOrNull { it.market == market }
            ?: fetchTickerFor(listOf(market)).firstOrNull()
            ?: run {
                lastError = "Manual search failed: unsupported Upbit KRW market $market"
                return null
            }
        val changeRanks = allTickers.sortedByDescending { it.signedChangeRate }.mapIndexed { index, row -> row.market to index + 1 }.toMap()
        val tradeValueRanks = allTickers.sortedByDescending { it.accTradePrice24h }.mapIndexed { index, row -> row.market to index + 1 }.toMap()
        return ticker.toMarketCandidate(changeRanks, tradeValueRanks)
    }

    suspend fun fetchManualMarketCandidateFast(rawSymbol: String, rules: StrategyRules = StrategyRules.DEFAULT): MarketCandidate? {
        val market = normalizeMarket(rawSymbol)
        lastError = null
        if (market.isBlank() || market == "KRW-") {
            lastError = "Manual search failed: blank market"
            return null
        }

        val btcTicker = fetchTickerForFast(listOf("KRW-BTC")).firstOrNull()
        btcChangeRate24h = btcTicker?.signedChangeRate?.times(100.0) ?: 0.0
        if (market != "KRW-BTC" && btcChangeRate24h <= rules.scoring.hardBlockBtc24hBelowPct) {
            lastError = "전체 코인 시장 약세: BTC 24h ${btcChangeRate24h.one()}% <= ${rules.scoring.hardBlockBtc24hBelowPct.one()}%. 수동 알트 분석을 차단합니다. BTC 또는 현금관망만 봅니다."
            return null
        }

        val ticker = if (market == "KRW-BTC") {
            btcTicker ?: fetchTickerForFast(listOf(market)).firstOrNull()
        } else {
            fetchTickerForFast(listOf(market)).firstOrNull()
        } ?: run {
            lastError = "Manual search failed: unsupported or unreachable Upbit KRW market $market"
            return null
        }

        return ticker.toManualMarketCandidate()
    }

    private fun Ticker.toMarketCandidate(
        changeRanks: Map<String, Int>,
        tradeValueRanks: Map<String, Int>,
    ): MarketCandidate? {
        val oneMinuteCandles = runCatching { fetchMinuteCandles(market, unit = 1, count = 60) }.getOrDefault(emptyList())
        val rawFiveMinuteCandles = runCatching { fetchMinuteCandles(market, unit = 5, count = 60) }.getOrDefault(emptyList())
        val rawFifteenMinuteCandles = runCatching { fetchMinuteCandles(market, unit = 15, count = 50) }.getOrDefault(emptyList())
        val fourHourCandles = runCatching { fetchMinuteCandles(market, unit = 240, count = 120) }.getOrDefault(emptyList())
        return buildMarketCandidate(
            ticker = this,
            oneMinuteCandles = oneMinuteCandles,
            rawFiveMinuteCandles = rawFiveMinuteCandles,
            rawFifteenMinuteCandles = rawFifteenMinuteCandles,
            fourHourCandles = fourHourCandles,
            rankByChange = changeRanks[market] ?: Int.MAX_VALUE,
            rankByValue = tradeValueRanks[market] ?: Int.MAX_VALUE,
        )
    }

    private fun Ticker.toManualMarketCandidate(): MarketCandidate? {
        val rawFiveMinuteCandles = fetchMinuteCandlesFast(market, unit = 5, count = 60)
        val rawFifteenMinuteCandles = fetchMinuteCandlesFast(market, unit = 15, count = 50)
        val oneMinuteCandles = if (rawFiveMinuteCandles.size < 25 || rawFifteenMinuteCandles.size < 25) {
            fetchMinuteCandlesFast(market, unit = 1, count = 60)
        } else {
            emptyList()
        }
        val fourHourCandles = fetchMinuteCandlesFast(market, unit = 240, count = 40)
        return buildMarketCandidate(
            ticker = this,
            oneMinuteCandles = oneMinuteCandles,
            rawFiveMinuteCandles = rawFiveMinuteCandles,
            rawFifteenMinuteCandles = rawFifteenMinuteCandles,
            fourHourCandles = fourHourCandles,
            rankByChange = 1,
            rankByValue = 1,
        )
    }

    private fun buildMarketCandidate(
        ticker: Ticker,
        oneMinuteCandles: List<Candle>,
        rawFiveMinuteCandles: List<Candle>,
        rawFifteenMinuteCandles: List<Candle>,
        fourHourCandles: List<Candle>,
        rankByChange: Int,
        rankByValue: Int,
    ): MarketCandidate? {
        if (rawFiveMinuteCandles.size < 25 || rawFifteenMinuteCandles.size < 25) {
            if (oneMinuteCandles.size < 3) {
                lastError = "Candidate skipped: insufficient candle data for ${ticker.market}"
                return null
            }
        }
        val effectiveFiveMinuteCandles = if (rawFiveMinuteCandles.size >= 25) rawFiveMinuteCandles else oneMinuteCandles
        val effectiveFifteenMinuteCandles = if (rawFifteenMinuteCandles.size >= 25) rawFifteenMinuteCandles else oneMinuteCandles
        val changeRate30m = effectiveFiveMinuteCandles.takeLast(6).firstOrNull()?.let { percentChange(it.open, ticker.tradePrice) }
            ?: percentChange(effectiveFiveMinuteCandles.first().open, ticker.tradePrice)
        val changeRate5m = effectiveFiveMinuteCandles.lastOrNull()?.let { percentChange(it.open, ticker.tradePrice) } ?: 0.0
        val recentTradeValue = effectiveFiveMinuteCandles.takeLast(3).sumOf { it.tradePrice }
        val previousTradeValue = effectiveFiveMinuteCandles.dropLast(3).takeLast(20).sumOf { it.tradePrice } / 6.67
        val fallbackBaseValue = effectiveFiveMinuteCandles.dropLast(1).takeLast(12).sumOf { it.tradePrice }.takeIf { it > 0.0 }
        val volumeAcceleration = when {
            previousTradeValue > 0.0 -> recentTradeValue / previousTradeValue
            fallbackBaseValue != null -> recentTradeValue / (fallbackBaseValue / 4.0)
            else -> 1.0
        }
        return MarketCandidate(
            ticker = ticker,
            oneMinuteCandles = oneMinuteCandles,
            fiveMinuteCandles = effectiveFiveMinuteCandles,
            fifteenMinuteCandles = effectiveFifteenMinuteCandles,
            fourHourCandles = fourHourCandles,
            btcChangeRate24h = btcChangeRate24h,
            rankByChangeRate = rankByChange,
            rankByTradeValue = rankByValue,
            changeRate30m = changeRate30m,
            changeRate5m = changeRate5m,
            volumeAcceleration = volumeAcceleration,
        )
    }

    private fun normalizeMarket(rawSymbol: String): String {
        val upper = rawSymbol.trim().uppercase().replace("/", "-")
        return when {
            upper.startsWith("KRW-") -> upper
            upper.isBlank() -> upper
            else -> "KRW-$upper"
        }
    }

    private fun fetchKrwMarkets(): List<String> {
        return withRetry("Upbit market list") {
            val array = fetchJsonArray("https://api.upbit.com/v1/market/all?isDetails=false")
            val out = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val market = item.optString("market")
                if (market.startsWith("KRW-")) out += market
            }
            out
        }.orEmpty()
    }

    private fun fetchTickerFor(markets: List<String>): List<Ticker> {
        val chunks = markets.chunked(100)
        val tickers = mutableListOf<Ticker>()
        for (chunk in chunks) {
            val chunkTickers = withRetry("Upbit ticker ${chunk.firstOrNull().orEmpty()}..") {
                parseTickerArray(fetchJsonArray("https://api.upbit.com/v1/ticker?markets=${chunk.joinToString(",")}"))
            }.orEmpty()
            tickers += chunkTickers
        }
        return tickers
    }

    private fun fetchTickerForFast(markets: List<String>): List<Ticker> {
        val targets = markets.distinct().filter { it.startsWith("KRW-") }
        if (targets.isEmpty()) return emptyList()
        return runCatching {
            parseTickerArray(fetchJsonArray(rawUrl = "https://api.upbit.com/v1/ticker?markets=${targets.joinToString(",")}", connectTimeoutMs = MANUAL_CONNECT_TIMEOUT_MS, readTimeoutMs = MANUAL_READ_TIMEOUT_MS))
        }.onFailure { error ->
            lastError = "Manual ticker ${targets.joinToString(",")} failed: ${error.message ?: error::class.java.simpleName}"
        }.getOrDefault(emptyList())
    }

    private fun parseTickerArray(array: JSONArray): List<Ticker> {
        val out = mutableListOf<Ticker>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            out += Ticker(
                market = item.optString("market"),
                tradePrice = item.optDouble("trade_price", 0.0),
                signedChangeRate = item.optDouble("signed_change_rate", 0.0),
                accTradePrice24h = item.optDouble("acc_trade_price_24h", 0.0),
                accTradeVolume24h = item.optDouble("acc_trade_volume_24h", 0.0),
                highPrice24h = item.optDouble("high_price", 0.0),
                lowPrice24h = item.optDouble("low_price", 0.0),
                prevClosingPrice = item.optDouble("prev_closing_price", 0.0),
            )
        }
        return out
    }

    fun fetchMinuteCandles(market: String, unit: Int, count: Int): List<Candle> {
        require(unit in SUPPORTED_UNITS) { "Unsupported Upbit candle unit: $unit" }
        val cacheKey = "$market:$unit"
        val now = System.currentTimeMillis()
        candleCache[cacheKey]?.let { cached -> if (now - cached.fetchedAt < CANDLE_CACHE_MS) return cached.candles }
        return withRetry("Upbit candle market=$market unit=$unit") {
            val array = fetchJsonArray("https://api.upbit.com/v1/candles/minutes/$unit?market=$market&count=$count")
            if (array.length() == 0) throw IOException("empty candle array market=$market unit=$unit")
            val sorted = parseCandles(market, unit, array)
            candleCache[cacheKey] = CachedCandles(System.currentTimeMillis(), sorted)
            sorted
        }.orEmpty()
    }

    private fun fetchMinuteCandlesFast(market: String, unit: Int, count: Int): List<Candle> {
        require(unit in SUPPORTED_UNITS) { "Unsupported Upbit candle unit: $unit" }
        val cacheKey = "$market:$unit"
        val now = System.currentTimeMillis()
        candleCache[cacheKey]?.let { cached -> if (now - cached.fetchedAt < CANDLE_CACHE_MS) return cached.candles }
        return runCatching {
            val array = fetchJsonArray(rawUrl = "https://api.upbit.com/v1/candles/minutes/$unit?market=$market&count=$count", connectTimeoutMs = MANUAL_CONNECT_TIMEOUT_MS, readTimeoutMs = MANUAL_READ_TIMEOUT_MS)
            if (array.length() == 0) throw IOException("empty candle array market=$market unit=$unit")
            val sorted = parseCandles(market, unit, array)
            candleCache[cacheKey] = CachedCandles(System.currentTimeMillis(), sorted)
            sorted
        }.onFailure { error ->
            lastError = "Manual candle $unit $market failed: ${error.message ?: error::class.java.simpleName}"
        }.getOrDefault(emptyList())
    }

    private fun parseCandles(market: String, unit: Int, array: JSONArray): List<Candle> {
        val candles = mutableListOf<Candle>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            candles += Candle(
                market = market,
                unit = unit,
                timestamp = item.optLong("timestamp", 0L),
                open = item.optDouble("opening_price", 0.0),
                high = item.optDouble("high_price", 0.0),
                low = item.optDouble("low_price", 0.0),
                close = item.optDouble("trade_price", 0.0),
                volume = item.optDouble("candle_acc_trade_volume", 0.0),
                tradePrice = item.optDouble("candle_acc_trade_price", 0.0),
            )
        }
        return candles.sortedBy { it.timestamp }
    }

    private fun fetchJsonArray(rawUrl: String, connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS, readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS): JSONArray {
        throttleRequest()
        val connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
        }
        try {
            val statusCode = connection.responseCode
            if (statusCode != HttpURLConnection.HTTP_OK) {
                val errorBody = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty().take(160)
                throw IOException("HTTP $statusCode ${errorBody.ifBlank { rawUrl }}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) throw IOException("empty response $rawUrl")
            return JSONArray(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun <T> withRetry(label: String, block: () -> T): T? {
        var backoffMs = INITIAL_RETRY_BACKOFF_MS
        repeat(MAX_RETRIES) { attempt ->
            try {
                return block()
            } catch (error: IOException) {
                lastError = "$label failed attempt ${attempt + 1}/$MAX_RETRIES: ${error.message ?: error::class.java.simpleName}"
                if (attempt == MAX_RETRIES - 1) return null
                Thread.sleep(backoffMs)
                backoffMs *= 2
            } catch (error: RuntimeException) {
                lastError = "$label failed attempt ${attempt + 1}/$MAX_RETRIES: ${error.message ?: error::class.java.simpleName}"
                if (attempt == MAX_RETRIES - 1) return null
                Thread.sleep(backoffMs)
                backoffMs *= 2
            }
        }
        return null
    }

    private fun isHardRiskOff(rules: StrategyRules): Boolean = btcChangeRate24h <= rules.scoring.hardBlockBtc24hBelowPct
    private fun isSoftRiskOff(): Boolean = btcChangeRate24h <= SOFT_RISK_OFF_BTC_24H_PCT
    private fun marketRiskOffMessage(rules: StrategyRules, action: String): String = "전체 코인 시장 약세: BTC 24h ${btcChangeRate24h.one()}% <= ${rules.scoring.hardBlockBtc24hBelowPct.one()}%. $action"

    private fun throttleRequest() {
        val now = System.currentTimeMillis()
        val waitMs = MIN_REQUEST_INTERVAL_MS - (now - lastRequestAt)
        if (waitMs > 0) Thread.sleep(waitMs)
        lastRequestAt = System.currentTimeMillis()
    }

    private fun percentChange(from: Double, to: Double): Double {
        if (from <= 0.0) return 0.0
        return ((to - from) / from) * 100.0
    }

    private data class CachedCandles(val fetchedAt: Long, val candles: List<Candle>)

    private fun countForUnit(unit: Int): Int = when (unit) {
        1 -> 60
        5 -> 60
        15 -> 50
        60 -> 120
        240 -> 120
        else -> 50
    }

    private fun Double.one(): String = String.format(java.util.Locale.US, "%.1f", this)

    companion object {
        private const val CANDLE_CACHE_MS = 25_000L
        private const val MAX_CANDLE_TARGETS = 100
        private const val MIN_REQUEST_INTERVAL_MS = 130L
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_BACKOFF_MS = 500L
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        private const val DEFAULT_READ_TIMEOUT_MS = 10_000
        private const val MANUAL_CONNECT_TIMEOUT_MS = 2_500
        private const val MANUAL_READ_TIMEOUT_MS = 2_500
        private const val SOFT_RISK_OFF_BTC_24H_PCT = -1.5
        private val SUPPORTED_UNITS = setOf(1, 5, 15, 60, 240)
        private val FORCE_WATCH_MARKETS = setOf("KRW-XLM", "KRW-XRP", "KRW-ADA", "KRW-DOGE", "KRW-SOL", "KRW-LINK", "KRW-AVAX", "KRW-DOT", "KRW-SUI", "KRW-APT", "KRW-ONDO", "KRW-HBAR", "KRW-STX")
    }
}
