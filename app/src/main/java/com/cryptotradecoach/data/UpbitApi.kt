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
        val topTickers = fetchTickers()
            .sortedByDescending { it.accTradePrice24h }
            .take(limit)

        return topTickers.map { ticker ->
            MarketCandidate(
                ticker = ticker,
                oneMinuteCandles = fetchCandles(ticker.market, unit = 1, count = 30),
                fiveMinuteCandles = fetchCandles(ticker.market, unit = 5, count = 30),
                fifteenMinuteCandles = fetchCandles(ticker.market, unit = 15, count = 30),
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
}
