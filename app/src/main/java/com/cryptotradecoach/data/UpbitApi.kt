package com.cryptotradecoach.data

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

interface MarketDataSource {
    suspend fun fetchTickers(): List<Ticker>
    suspend fun fetchMarketCandidates(limit: Int = 40): List<MarketCandidate>
}

class UpbitMarketDataSource : MarketDataSource {
    @Volatile
    var lastError: String? = null
        private set

    private val candleCache = mutableMapOf<String, CachedCandles>()
    private var lastRequestAt: Long = 0L

    override suspend fun fetchTickers(): List<Ticker> {
        lastError = null
        val markets = fetchKrwMarkets()
        if (markets.isEmpty()) return emptyList()
        return fetchTickerFor(markets)
    }

    fun selectCandleTargets(tickers: List<Ticker>, limit: Int = MAX_CANDLE_TARGETS): List<Ticker> {
        val boundedLimit = limit.coerceIn(10, MAX_CANDLE_TARGETS)
        val byTradeValue = tickers.sortedByDescending { it.accTradePrice24h }.take(40)
        val byChangeRate = tickers.sortedByDescending { it.signedChangeRate }.take(15)
        val tradeRanks = byTradeValue.mapIndexed { index, ticker -> ticker.market to index + 1 }.toMap()
        val changeRanks = byChangeRate.mapIndexed { index, ticker -> ticker.market to index + 1 }.toMap()
        return (byTradeValue + byChangeRate)
            .distinctBy { it.market }
            .sortedWith(
                compareBy<Ticker> {
                    minOf(tradeRanks[it.market] ?: Int.MAX_VALUE, changeRanks[it.market] ?: Int.MAX_VALUE)
                }.thenByDescending { it.accTradePrice24h },
            )
            .take(boundedLimit)
    }

    fun fetchCandleData(tickers: List<Ticker>): CandleData {
        val out = mutableMapOf<String, Map<Int, List<Candle>>>()
        tickers.take(MAX_CANDLE_TARGETS).forEach { ticker ->
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

    override suspend fun fetchMarketCandidates(limit: Int): List<MarketCandidate> {
        val boundedLimit = limit.coerceIn(10, MAX_CANDLE_TARGETS)
        val tickers = fetchTickers()
        val changeRanks = tickers
            .sortedByDescending { it.signedChangeRate }
            .mapIndexed { index, ticker -> ticker.market to index + 1 }
            .toMap()
        val tradeValueRanks = tickers
            .sortedByDescending { it.accTradePrice24h }
            .mapIndexed { index, ticker -> ticker.market to index + 1 }
            .toMap()

        val selectedTickers = selectCandleTargets(tickers, boundedLimit)

        return selectedTickers.mapNotNull { ticker ->
            val fiveMinuteCandles = runCatching { fetchMinuteCandles(ticker.market, unit = 5, count = 60) }.getOrDefault(emptyList())
            val fifteenMinuteCandles = runCatching { fetchMinuteCandles(ticker.market, unit = 15, count = 60) }.getOrDefault(emptyList())
            val fourHourCandles = runCatching { fetchMinuteCandles(ticker.market, unit = 240, count = 140) }.getOrDefault(emptyList())
            if (fiveMinuteCandles.size < 25 || fifteenMinuteCandles.size < 25) return@mapNotNull null

            val changeRate30m = percentChange(fiveMinuteCandles.takeLast(6).first().open, ticker.tradePrice)
            val changeRate5m = percentChange(fiveMinuteCandles.last().open, ticker.tradePrice)
            val recentTradeValue = fiveMinuteCandles.takeLast(3).sumOf { it.tradePrice }
            val previousTradeValue = fiveMinuteCandles.dropLast(3).takeLast(20).sumOf { it.tradePrice } / 6.67
            val volumeAcceleration = if (previousTradeValue > 0.0) recentTradeValue / previousTradeValue else 1.0

            MarketCandidate(
                ticker = ticker,
                oneMinuteCandles = fiveMinuteCandles,
                fiveMinuteCandles = fiveMinuteCandles,
                fifteenMinuteCandles = fifteenMinuteCandles,
                fourHourCandles = fourHourCandles,
                rankByChangeRate = changeRanks[ticker.market] ?: Int.MAX_VALUE,
                rankByTradeValue = tradeValueRanks[ticker.market] ?: Int.MAX_VALUE,
                changeRate30m = changeRate30m,
                changeRate5m = changeRate5m,
                volumeAcceleration = volumeAcceleration,
            )
        }
    }

    private fun fetchKrwMarkets(): List<String> {
        throttleRequest()
        val url = URL("https://api.upbit.com/v1/market/all?isDetails=false")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                lastError = "Upbit market list failed: HTTP $statusCode"
                return emptyList()
            }
            connection.inputStream.bufferedReader().use { reader ->
                val body = reader.readText()
                val array = JSONArray(body)
                val out = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val market = item.optString("market")
                    if (market.startsWith("KRW-")) out += market
                }
                return out
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchTickerFor(markets: List<String>): List<Ticker> {
        val chunks = markets.chunked(100)
        val tickers = mutableListOf<Ticker>()
        for (chunk in chunks) {
            throttleRequest()
            val url = URL("https://api.upbit.com/v1/ticker?markets=${chunk.joinToString(",")}")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            try {
                val statusCode = connection.responseCode
                if (statusCode !in 200..299) {
                    lastError = "Upbit ticker failed: HTTP $statusCode"
                    continue
                }
                connection.inputStream.bufferedReader().use { reader ->
                    val body = reader.readText()
                    val array = JSONArray(body)
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        tickers += Ticker(
                            market = item.optString("market"),
                            tradePrice = item.optDouble("trade_price", 0.0),
                            signedChangeRate = item.optDouble("signed_change_rate", 0.0),
                            accTradePrice24h = item.optDouble("acc_trade_price_24h", 0.0),
                            accTradeVolume24h = item.optDouble("acc_trade_volume_24h", 0.0),
                        )
                    }
                }
            } finally {
                connection.disconnect()
            }
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
        throttleRequest()
        val url = URL("https://api.upbit.com/v1/candles/minutes/$unit?market=$market&count=$count")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            val statusCode = connection.responseCode
            if (statusCode == 429) {
                lastError = "Upbit candle rate limited: HTTP 429 market=$market unit=$unit"
                return emptyList()
            }
            if (statusCode !in 200..299) {
                lastError = "Upbit candle failed: HTTP $statusCode market=$market unit=$unit"
                return emptyList()
            }
            connection.inputStream.bufferedReader().use { reader ->
                val body = reader.readText()
                if (body.isBlank()) {
                    lastError = "Upbit candle empty response market=$market unit=$unit"
                    return emptyList()
                }
                val array = JSONArray(body)
                if (array.length() == 0) {
                    lastError = "Upbit candle empty array market=$market unit=$unit"
                    return emptyList()
                }
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
                candleCache[cacheKey] = CachedCandles(now, sorted)
                return sorted
            }
        } finally {
            connection.disconnect()
        }
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
        240 -> 120
        else -> 50
    }

    companion object {
        private const val CANDLE_CACHE_MS = 90_000L
        private const val MAX_CANDLE_TARGETS = 20
        private const val MIN_REQUEST_INTERVAL_MS = 130L
        private val SUPPORTED_UNITS = setOf(5, 15, 240)
    }
}