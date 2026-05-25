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

        val tradeRanks = byTradeValue.mapIndexed { index, ticker -> ticker.market to index + 1 }.toMap()
        val changeRanks = byChangeRate.mapIndexed { index, ticker -> ticker.market to index + 1 }.toMap()

        return (byTradeValue + byChangeRate + byVolumeBuildup + byQuietAccumulation)
            .distinctBy { it.market }
            .sortedWith(
                compareBy<Ticker> {
                    minOf(
                        tradeRanks[it.market] ?: Int.MAX_VALUE,
                        changeRanks[it.market] ?: Int.MAX_VALUE,
                    )
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

        val selectedTickers = selectCandleTargets(tickers, boundedLimit, selectionRules)

        return selectedTickers.mapNotNull { ticker ->
            ticker.toMarketCandidate(changeRanks, tradeValueRanks)
        }
    }

    suspend fun fetchManualMarketCandidate(rawSymbol: String): MarketCandidate? {
        val market = normalizeMarket(rawSymbol)
        lastError = null
        val allTickers = fetchTickers()
        val ticker = allTickers.firstOrNull { it.market == market }
            ?: fetchTickerFor(listOf(market)).firstOrNull()
            ?: run {
                lastError = "Manual search failed: unsupported Upbit KRW market $market"
                return null
            }
        val changeRanks = allTickers
            .sortedByDescending { it.signedChangeRate }
            .mapIndexed { index, row -> row.market to index + 1 }
            .toMap()
        val tradeValueRanks = allTickers
            .sortedByDescending { it.accTradePrice24h }
            .mapIndexed { index, row -> row.market to index + 1 }
            .toMap()
        return ticker.toMarketCandidate(changeRanks, tradeValueRanks)
    }

    private fun Ticker.toMarketCandidate(
        changeRanks: Map<String, Int>,
        tradeValueRanks: Map<String, Int>,
    ): MarketCandidate? {
        val oneMinuteCandles = runCatching { fetchMinuteCandles(market, unit = 1, count = 60) }.getOrDefault(emptyList())
        val fiveMinuteCandles = runCatching { fetchMinuteCandles(market, unit = 5, count = 60) }.getOrDefault(emptyList())
        val fifteenMinuteCandles = runCatching { fetchMinuteCandles(market, unit = 15, count = 50) }.getOrDefault(emptyList())
        val fourHourCandles = runCatching { fetchMinuteCandles(market, unit = 240, count = 120) }.getOrDefault(emptyList())
        if (fiveMinuteCandles.size < 25 || fifteenMinuteCandles.size < 25) {
            lastError = "Manual search failed: insufficient candle data for $market"
            return null
        }
        val changeRate30m = percentChange(fiveMinuteCandles.takeLast(6).first().open, tradePrice)
        val changeRate5m = percentChange(fiveMinuteCandles.last().open, tradePrice)
        val recentTradeValue = fiveMinuteCandles.takeLast(3).sumOf { it.tradePrice }
        val previousTradeValue = fiveMinuteCandles.dropLast(3).takeLast(20).sumOf { it.tradePrice } / 6.67
        val volumeAcceleration = if (previousTradeValue > 0.0) recentTradeValue / previousTradeValue else 1.0
        return MarketCandidate(
            ticker = this,
            oneMinuteCandles = oneMinuteCandles,
            fiveMinuteCandles = fiveMinuteCandles,
            fifteenMinuteCandles = fifteenMinuteCandles,
            fourHourCandles = fourHourCandles,
            btcChangeRate24h = btcChangeRate24h,
            rankByChangeRate = changeRanks[market] ?: Int.MAX_VALUE,
            rankByTradeValue = tradeValueRanks[market] ?: Int.MAX_VALUE,
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
                val array = fetchJsonArray("https://api.upbit.com/v1/ticker?markets=${chunk.joinToString(",")}")
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
                out
            }.orEmpty()
            tickers += chunkTickers
        }
        return tickers
    }

    fun fetchMinuteCandles(market: String, unit: Int, count: Int): List<Candle> {
        require(unit in SUPPORTED_UNITS) { "Unsupported Upbit candle unit: $unit" }
        val cacheKey = "$market:$unit"
        val now = System.currentTimeMillis()
        candleCache[cacheKey]?.let { cached ->
            if (now - cached.fetchedAt < CANDLE_CACHE_MS) return cached.candles
        }

        return withRetry("Upbit candle market=$market unit=$unit") {
            val array = fetchJsonArray("https://api.upbit.com/v1/candles/minutes/$unit?market=$market&count=$count")
            if (array.length() == 0) throw IOException("empty candle array market=$market unit=$unit")
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
            val sorted = candles.sortedBy { it.timestamp }
            candleCache[cacheKey] = CachedCandles(System.currentTimeMillis(), sorted)
            sorted
        }.orEmpty()
    }

    private fun fetchJsonArray(rawUrl: String): JSONArray {
        throttleRequest()
        val connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            val statusCode = connection.responseCode
            if (statusCode != HttpURLConnection.HTTP_OK) {
                val errorBody = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }
                    .getOrNull()
                    .orEmpty()
                    .take(160)
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

    private fun throttleRequest() {
        val now = System.currentTimeMillis()
        val waitMs = MIN_REQUEST_INTERVAL_MS - (now - lastRequestAt)
        if (waitMs > 0) {
            Thread.sleep(waitMs)
        }
        lastRequestAt = System.currentTimeMillis()
    }

    private fun percentChange(from: Double, to: Double): Double {
        if (from <= 0.0) return 0.0
        return ((to - from) / from) * 100.0
    }

    private data class CachedCandles(
        val fetchedAt: Long,
        val candles: List<Candle>,
    )

    private fun countForUnit(unit: Int): Int = when (unit) {
        1 -> 60
        5 -> 60
        15 -> 50
        240 -> 120
        else -> 50
    }

    companion object {
        private const val CANDLE_CACHE_MS = 25_000L
        private const val MAX_CANDLE_TARGETS = 80
        private const val MIN_REQUEST_INTERVAL_MS = 130L
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_BACKOFF_MS = 500L
        private val SUPPORTED_UNITS = setOf(1, 5, 15, 240)
    }
}