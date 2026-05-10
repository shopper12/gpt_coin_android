package com.cryptotradecoach.data

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

interface MarketDataSource {
    suspend fun fetchTickers(): List<Ticker>
}

class UpbitMarketDataSource : MarketDataSource {
    override suspend fun fetchTickers(): List<Ticker> {
        val markets = fetchKrwMarkets()
        if (markets.isEmpty()) return emptyList()
        return fetchTickerFor(markets)
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
}
