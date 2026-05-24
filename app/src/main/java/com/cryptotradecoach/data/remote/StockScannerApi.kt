package com.cryptotradecoach.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val BASE_URL = "https://stock-scanner-api-5sk6.onrender.com"

data class StockScannerSnapshot(
    val createdAtKst: String,
    val mode: String,
    val quoteOkRate: Double,
    val quoteOk: Int,
    val total: Int,
    val krShortStocks: List<KrShortStock>,
)

data class KrShortStock(
    val code: String,
    val name: String,
    val sector: String,
    val strategyType: String,
    val currentPrice: Double,
    val priceBasis: String,
    val priceTimestamp: String,
    val quoteSource: String,
    val score: Double,
    val entry: Double,
    val stopLoss: Double,
    val target1: Double,
    val target2: Double,
    val riskPct: Double,
    val reason: String,
    val failureCondition: String,
)

class StockScannerApi(
    private val baseUrl: String = BASE_URL,
) {
    suspend fun fetchLatest(): StockScannerSnapshot = withContext(Dispatchers.IO) {
        val text = getText("$baseUrl/api/latest")
        parseLatest(JSONObject(text))
    }

    private fun getText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CryptoTradeCoach-Android")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: $body")
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun parseLatest(json: JSONObject): StockScannerSnapshot {
        val quality = json.optJSONObject("data_quality") ?: JSONObject()
        val rows = json.optJSONArray("kr_short_stocks")
        val stocks = buildList {
            if (rows != null) {
                for (i in 0 until rows.length()) {
                    val item = rows.optJSONObject(i) ?: continue
                    add(parseKrShortStock(item))
                }
            }
        }
        return StockScannerSnapshot(
            createdAtKst = json.optString("created_at_kst", "-"),
            mode = json.optString("mode", "unknown"),
            quoteOkRate = quality.optDouble("kr_short_quote_ok_rate", 0.0),
            quoteOk = quality.optInt("kr_short_quote_ok", 0),
            total = quality.optInt("kr_short_total", stocks.size),
            krShortStocks = stocks,
        )
    }

    private fun parseKrShortStock(item: JSONObject): KrShortStock = KrShortStock(
        code = item.optString("code", ""),
        name = item.optString("name", ""),
        sector = item.optString("sector", "기타"),
        strategyType = item.optString("strategy_type", ""),
        currentPrice = item.optDouble("current_price", 0.0),
        priceBasis = item.optString("price_basis", "unknown"),
        priceTimestamp = item.optString("price_timestamp", "unknown"),
        quoteSource = item.optString("quote_source", item.optString("data_source", "unknown")),
        score = item.optDouble("score", 0.0),
        entry = item.optDouble("entry", 0.0),
        stopLoss = item.optDouble("stop_loss", 0.0),
        target1 = item.optDouble("target1", 0.0),
        target2 = item.optDouble("target2", 0.0),
        riskPct = item.optDouble("risk_pct", 0.0),
        reason = item.optString("reason", ""),
        failureCondition = item.optString("failure_condition", ""),
    )
}
