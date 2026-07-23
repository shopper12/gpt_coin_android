package com.cryptotradecoach.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GlobalMarketSignalWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val root = fetchLatestSignals()
            val rows = root.optJSONArray("signals")
            val currentIds = mutableSetOf<String>()
            val parsed = buildList {
                if (rows != null) {
                    for (index in 0 until rows.length()) {
                        val item = rows.optJSONObject(index) ?: continue
                        val signal = GlobalMarketSignal.from(item) ?: continue
                        currentIds += signal.id
                        add(signal)
                    }
                }
            }

            val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val initialized = preferences.getBoolean(KEY_INITIALIZED, false)
            val seen = preferences.getStringSet(KEY_SEEN_IDS, emptySet()).orEmpty().toMutableSet()
            val unseen = if (initialized) {
                parsed.filter { it.id !in seen }
            } else {
                parsed.take(FIRST_RUN_ALERT_LIMIT)
            }

            val notifier = SignalNotificationHelper(applicationContext)
            notifier.ensureChannels()
            unseen.take(MAX_ALERTS_PER_RUN).forEach { signal ->
                notifier.notifyGlobalMarketSignal(
                    notificationId = NOTIFICATION_BASE + (signal.id.hashCode() and 0x0FFFFFFF) % 100_000,
                    ticker = signal.ticker,
                    name = signal.name,
                    direction = signal.direction,
                    score = signal.score,
                    currentPrice = signal.currentPrice,
                    currency = signal.currency,
                    entryLow = signal.entryLow,
                    entryHigh = signal.entryHigh,
                    stopLoss = signal.stopLoss,
                    target1 = signal.target1,
                    target2 = signal.target2,
                    reason = signal.reason,
                )
            }

            seen += currentIds
            preferences.edit()
                .putBoolean(KEY_INITIALIZED, true)
                .putStringSet(KEY_SEEN_IDS, seen.toList().takeLast(MAX_STORED_IDS).toSet())
                .putString(KEY_LAST_REPORT_TIME, root.optString("generatedAtKst"))
                .apply()
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }

    private fun fetchLatestSignals(): JSONObject {
        val connection = URL(LATEST_SIGNALS_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "UnifiedTradingCoach-Android")
        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("global market signal HTTP $code")
            JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private data class GlobalMarketSignal(
        val id: String,
        val ticker: String,
        val name: String,
        val direction: String,
        val score: Double,
        val currentPrice: Double,
        val currency: String,
        val entryLow: Double,
        val entryHigh: Double,
        val stopLoss: Double,
        val target1: Double,
        val target2: Double,
        val reason: String,
    ) {
        companion object {
            fun from(item: JSONObject): GlobalMarketSignal? {
                val id = item.optString("id").trim()
                val ticker = item.optString("ticker").trim()
                if (id.isBlank() || ticker.isBlank()) return null
                return GlobalMarketSignal(
                    id = id,
                    ticker = ticker,
                    name = item.optString("name", ticker),
                    direction = item.optString("direction", "LONG"),
                    score = item.optDouble("score", 0.0),
                    currentPrice = item.optDouble("currentPrice", 0.0),
                    currency = item.optString("currency"),
                    entryLow = item.optDouble("entryLow", 0.0),
                    entryHigh = item.optDouble("entryHigh", 0.0),
                    stopLoss = item.optDouble("stopLoss", 0.0),
                    target1 = item.optDouble("target1", 0.0),
                    target2 = item.optDouble("target2", 0.0),
                    reason = item.optString("reason"),
                )
            }
        }
    }

    private companion object {
        private const val PREFERENCES = "global_market_signal_alerts"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_SEEN_IDS = "seen_ids"
        private const val KEY_LAST_REPORT_TIME = "last_report_time"
        private const val FIRST_RUN_ALERT_LIMIT = 3
        private const val MAX_ALERTS_PER_RUN = 5
        private const val MAX_STORED_IDS = 500
        private const val NOTIFICATION_BASE = 120_000
        private const val LATEST_SIGNALS_URL =
            "https://raw.githubusercontent.com/shopper12/gpt_coin_android/main/reports/global_market_signals_latest.json"
    }
}
