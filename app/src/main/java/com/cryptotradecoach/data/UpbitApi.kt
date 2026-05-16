package com.cryptotradecoach.data

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

interface MarketDataSource {
    suspend fun fetchTickers(): List<Ticker>
    suspend fun fetchMarketCandidates(limit: Int = 80): List<MarketCandidate>
}

class UpbitMarketDataSource : MarketDataSource {
    override suspend fun fetchTickers(): List<Ticker> {
        val markets = fetchKrwMarkets()
        if (markets.isEmpty()) return emptyList()
        return fetchTickerFor(markets)
    }

    override suspend fun fetchMarketCandidates(limit: Int): List<MarketCandidate> {
        val tickers = fetchTickers()
        val changeRanks = tickers
            .sortedByDescending { it.signedChangeRate }
            .mapIndexed { index, ticker -> ticker.market to index + 1 }
            .toMap()
        val tradeValueRanks = tickers
            .sortedByDescending { it.accTradePrice24h }
            .mapIndexed { index, ticker -> ticker.market to index + 1 }
            .toMap()

        val enriched = tickers.mapNotNull { ticker ->
            val oneMinuteCandles = runCatching { fetchCandles(ticker.market, unit = 1, count = 35) }.getOrDefault(emptyList())
            val fiveMinuteCandles = runCatching { fetchCandles(ticker.market, unit = 5, count = 30) }.getOrDefault(emptyList())
            val fifteenMinuteCandles = runCatching { fetchCandles(ticker.market, unit = 15, count = 20) }.getOrDefault(emptyList())
            if (oneMinuteCandles.size < 6 || fiveMinuteCandles.size < 2 || fifteenMinuteCandles.size < 2) return@mapNotNull null

            val changeRate30m = percentChange(oneMinuteCandles.takeLast(30).first().openingPrice, ticker.tradePrice)
            val changeRate5m = percentChange(oneMinuteCandles.takeLast(5).first().openingPrice, ticker.tradePrice)
            val recentTradeValue = oneMinuteCandles.takeLast(5).sumOf { it.candleAccTradePrice }
            val previousTradeValue = oneMinuteCandles.dropLast(5).takeLast(20).sumOf { it.candleAccTradePrice } / 4.0
            val volumeAcceleration = if (previousTradeValue > 0.0) recentTradeValue / previousTradeValue else 1.0

            CandidateEnvelope(
                ticker = ticker,
                oneMinuteCandles = oneMinuteCandles,
                fiveMinuteCandles = fiveMinuteCandles,
                fifteenMinuteCandles = fifteenMinuteCandles,
                rankByChangeRate = changeRanks[ticker.market] ?: Int.MAX_VALUE,
                rankByTradeValue = tradeValueRanks[ticker.market] ?: Int.MAX_VALUE,
                changeRate30m = changeRate30m,
                changeRate5m = changeRate5m,
                volumeAcceleration = volumeAcceleration,
            )
        }

        val selectedMarkets = buildSet {
            addAll(enriched.sortedBy { it.rankByTradeValue }.take(50).map { it.ticker.market })
            addAll(enriched.sortedBy { it.rankByChangeRate }.take(20).map { it.ticker.market })
            addAll(enriched.sortedByDescending { it.changeRate30m }.take(20).map { it.ticker.market })
            addAll(enriched.sortedByDescending { it.volumeAcceleration }.take(20).map { it.ticker.market })
        }

        return enriched
            .filter { envelope ->
                envelope.ticker.market in selectedMarkets &&
                    (envelope.ticker.accTradePrice24h >= MIN_ACC_TRADE_PRICE_24H || envelope.rankByChangeRate <= 10)
            }
            .sortedWith(
                compareBy<CandidateEnvelope> { it.rankByTradeValue }
                    .thenBy { it.rankByChangeRate },
            )
            .take(limit)
            .map { envelope ->
                MarketCandidate(
                    ticker = envelope.ticker,
                    oneMinuteCandles = envelope.oneMinuteCandles,
                    fiveMinuteCandles = envelope.fiveMinuteCandles,
                    fifteenMinuteCandles = envelope.fifteenMinuteCandles,
                    rankByChangeRate = envelope.rankByChangeRate,
                    rankByTradeValue = envelope.rankByTradeValue,
                    changeRate30m = envelope.changeRate30m,
                    changeRate5m = envelope.changeRate5m,
                    volumeAcceleration = envelope.volumeAcceleration,
                )
            }
    }

    private fun fetchKrwMarkets(): List<String> {
        val url = URL("https://api.upbit.com/v1/market/all?isDetails=false")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
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
    }

    private fun fetchTickerFor(markets: List<String>): List<Ticker> {
        val chunks = markets.chunked(100)
        val tickers = mutableListOf<Ticker>()
        for (chunk in chunks) {
            val url = URL("https://api.upbit.com/v1/ticker?markets=${chunk.joinToString(",")}")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
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
        }
        return tickers
    }

    private fun fetchCandles(market: String, unit: Int, count: Int): List<Candle> {
        val url = URL("https://api.upbit.com/v1/candles/minutes/$unit?market=$market&count=$count")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        connection.inputStream.bufferedReader().use { reader ->
            val body = reader.readText()
            val array = JSONArray(body)
            val candles = mutableListOf<Candle>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                candles += Candle(
                    market = market,
                    openingPrice = item.optDouble("opening_price", 0.0),
                    highPrice = item.optDouble("high_price", 0.0),
                    lowPrice = item.optDouble("low_price", 0.0),
                    tradePrice = item.optDouble("trade_price", 0.0),
                    candleAccTradeVolume = item.optDouble("candle_acc_trade_volume", 0.0),
                    candleAccTradePrice = item.optDouble("candle_acc_trade_price", 0.0),
                    timestamp = item.optLong("timestamp", 0L),
                )
            }
            return candles.sortedBy { it.timestamp }
        }
    }

    private fun percentChange(from: Double, to: Double): Double {
        if (from <= 0.0) return 0.0
        return ((to - from) / from) * 100.0
    }

    private data class CandidateEnvelope(
        val ticker: Ticker,
        val oneMinuteCandles: List<Candle>,
        val fiveMinuteCandles: List<Candle>,
        val fifteenMinuteCandles: List<Candle>,
        val rankByChangeRate: Int,
        val rankByTradeValue: Int,
        val changeRate30m: Double,
        val changeRate5m: Double,
        val volumeAcceleration: Double,
    )

    companion object {
        private const val MIN_ACC_TRADE_PRICE_24H = 500_000_000.0
    }
}
