package com.cryptotradecoach.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class WatchlistItem(
    val ticker: String,
    val name: String,
    val market: String = "",
    val assetClass: String = "",
    val currency: String = "",
    val direction: String = "LONG",
    val referencePrice: Double? = null,
    val currentPrice: Double? = null,
    val sourceRecommendationId: String = "",
    val addedAt: Long = System.currentTimeMillis(),
) {
    val key: String
        get() = ticker.trim().uppercase(Locale.US)

    fun normalized(): WatchlistItem {
        val normalizedTicker = key
        return copy(
            ticker = normalizedTicker,
            name = name.trim().ifBlank { normalizedTicker },
            market = market.trim().uppercase(Locale.US),
            assetClass = assetClass.trim().uppercase(Locale.US),
            currency = currency.trim().uppercase(Locale.US),
            direction = direction.trim().uppercase(Locale.US).ifBlank { "LONG" },
        )
    }
}

data class KisHolding(
    val ticker: String,
    val name: String,
    val quantity: Double,
    val orderableQuantity: Double,
    val averagePrice: Double,
    val currentPrice: Double,
    val purchaseAmount: Double,
    val evaluationAmount: Double,
    val profitLossAmount: Double,
    val profitLossRate: Double,
)

data class KisHoldingsSnapshot(
    val holdings: List<KisHolding> = emptyList(),
    val fetchedAt: Long = 0L,
)

data class KisCredentials(
    val appKey: String = "",
    val appSecret: String = "",
    val accountNumber: String = "",
    val mockTrading: Boolean = false,
) {
    val normalizedAccount: String
        get() = accountNumber.filter(Char::isDigit)
    val cano: String
        get() = normalizedAccount.take(8)
    val productCode: String
        get() = normalizedAccount.drop(8).take(2)
    val isConfigured: Boolean
        get() = appKey.isNotBlank() && appSecret.isNotBlank() && normalizedAccount.length == 10
}

class MyStocksRepository(context: Context) {
    private val appContext = context.applicationContext
    private val securePrefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun loadWatchlist(): List<WatchlistItem> {
        val rows = runCatching {
            JSONArray(securePrefs.getString(KEY_WATCHLIST, "[]").orEmpty().ifBlank { "[]" })
        }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                val ticker = item.optString("ticker").trim()
                if (ticker.isBlank()) continue
                add(
                    WatchlistItem(
                        ticker = ticker,
                        name = item.optString("name", ticker),
                        market = item.optString("market"),
                        assetClass = item.optString("assetClass"),
                        currency = item.optString("currency"),
                        direction = item.optString("direction", "LONG"),
                        referencePrice = item.optNullableDouble("referencePrice"),
                        currentPrice = item.optNullableDouble("currentPrice"),
                        sourceRecommendationId = item.optString("sourceRecommendationId"),
                        addedAt = item.optLong("addedAt", 0L),
                    ).normalized()
                )
            }
        }.distinctBy { it.key }.sortedByDescending { it.addedAt }
    }

    fun addWatchlist(item: WatchlistItem): Boolean {
        val normalized = item.normalized()
        require(normalized.ticker.isNotBlank()) { "종목코드를 입력하세요." }
        val rows = loadWatchlist().associateBy { it.key }.toMutableMap()
        val previous = rows[normalized.key]
        rows[normalized.key] = normalized.copy(addedAt = previous?.addedAt ?: normalized.addedAt)
        return saveWatchlist(rows.values.sortedByDescending { it.addedAt })
    }

    fun removeWatchlist(ticker: String): Boolean {
        val target = ticker.trim().uppercase(Locale.US)
        return saveWatchlist(loadWatchlist().filterNot { it.key == target })
    }

    fun loadCredentials(): KisCredentials {
        return KisCredentials(
            appKey = securePrefs.getString(KEY_KIS_APP_KEY, "").orEmpty(),
            appSecret = securePrefs.getString(KEY_KIS_APP_SECRET, "").orEmpty(),
            accountNumber = securePrefs.getString(KEY_KIS_ACCOUNT, "").orEmpty(),
            mockTrading = securePrefs.getBoolean(KEY_KIS_MOCK, false),
        )
    }

    fun saveCredentials(credentials: KisCredentials): Boolean {
        val normalized = credentials.copy(
            appKey = credentials.appKey.trim(),
            appSecret = credentials.appSecret.trim(),
            accountNumber = credentials.normalizedAccount,
        )
        require(normalized.appKey.isNotBlank()) { "한국투자증권 App Key를 입력하세요." }
        require(normalized.appSecret.isNotBlank()) { "한국투자증권 App Secret을 입력하세요." }
        require(normalized.normalizedAccount.length == 10) { "계좌번호는 앞 8자리와 상품코드 2자리를 합친 10자리여야 합니다." }
        val previous = loadCredentials()
        val changed = previous != normalized
        return securePrefs.edit().apply {
            putString(KEY_KIS_APP_KEY, normalized.appKey)
            putString(KEY_KIS_APP_SECRET, normalized.appSecret)
            putString(KEY_KIS_ACCOUNT, normalized.normalizedAccount)
            putBoolean(KEY_KIS_MOCK, normalized.mockTrading)
            if (changed) {
                remove(KEY_KIS_ACCESS_TOKEN)
                remove(KEY_KIS_TOKEN_EXPIRES_AT)
            }
        }.commit()
    }

    fun loadCachedHoldings(): KisHoldingsSnapshot {
        val text = securePrefs.getString(KEY_KIS_HOLDINGS, "").orEmpty()
        if (text.isBlank()) return KisHoldingsSnapshot()
        return runCatching {
            val root = JSONObject(text)
            val rows = root.optJSONArray("holdings") ?: JSONArray()
            val holdings = buildList {
                for (index in 0 until rows.length()) {
                    val item = rows.optJSONObject(index) ?: continue
                    val ticker = item.optString("ticker").trim()
                    if (ticker.isBlank()) continue
                    add(
                        KisHolding(
                            ticker = ticker,
                            name = item.optString("name", ticker),
                            quantity = item.optDouble("quantity", 0.0),
                            orderableQuantity = item.optDouble("orderableQuantity", 0.0),
                            averagePrice = item.optDouble("averagePrice", 0.0),
                            currentPrice = item.optDouble("currentPrice", 0.0),
                            purchaseAmount = item.optDouble("purchaseAmount", 0.0),
                            evaluationAmount = item.optDouble("evaluationAmount", 0.0),
                            profitLossAmount = item.optDouble("profitLossAmount", 0.0),
                            profitLossRate = item.optDouble("profitLossRate", 0.0),
                        )
                    )
                }
            }
            KisHoldingsSnapshot(holdings = holdings, fetchedAt = root.optLong("fetchedAt", 0L))
        }.getOrDefault(KisHoldingsSnapshot())
    }

    suspend fun refreshKisHoldings(): KisHoldingsSnapshot = withContext(Dispatchers.IO) {
        val credentials = loadCredentials()
        require(credentials.isConfigured) { "한투 설정에서 App Key, App Secret, 10자리 계좌번호를 먼저 저장하세요." }
        val token = accessToken(credentials)
        val holdings = mutableListOf<KisHolding>()
        var fk100 = ""
        var nk100 = ""
        var trCont = ""
        var page = 0
        while (page < MAX_BALANCE_PAGES) {
            page += 1
            val response = fetchBalancePage(credentials, token, fk100, nk100, trCont)
            val root = response.body
            if (root.optString("rt_cd") != "0") {
                error(root.optString("msg1", "한국투자증권 잔고조회가 실패했습니다."))
            }
            val rows = root.optJSONArray("output1") ?: JSONArray()
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                val quantity = item.optApiDouble("hldg_qty")
                if (quantity <= 0.0) continue
                holdings += KisHolding(
                    ticker = item.optString("pdno").trim(),
                    name = item.optString("prdt_name", item.optString("pdno")).trim(),
                    quantity = quantity,
                    orderableQuantity = item.optApiDouble("ord_psbl_qty"),
                    averagePrice = item.optApiDouble("pchs_avg_pric"),
                    currentPrice = item.optApiDouble("prpr"),
                    purchaseAmount = item.optApiDouble("pchs_amt"),
                    evaluationAmount = item.optApiDouble("evlu_amt"),
                    profitLossAmount = item.optApiDouble("evlu_pfls_amt"),
                    profitLossRate = item.optApiDouble("evlu_pfls_rt"),
                )
            }
            val nextFk = root.optString("ctx_area_fk100")
            val nextNk = root.optString("ctx_area_nk100")
            if (response.trCont !in setOf("M", "F") || nextFk.isBlank() || nextNk.isBlank()) break
            fk100 = nextFk
            nk100 = nextNk
            trCont = "N"
            Thread.sleep(120)
        }
        val snapshot = KisHoldingsSnapshot(
            holdings = holdings.distinctBy { it.ticker }.sortedByDescending { it.evaluationAmount },
            fetchedAt = System.currentTimeMillis(),
        )
        saveHoldings(snapshot)
        snapshot
    }

    private fun saveWatchlist(items: List<WatchlistItem>): Boolean {
        val rows = JSONArray()
        items.forEach { item ->
            rows.put(
                JSONObject()
                    .put("ticker", item.ticker)
                    .put("name", item.name)
                    .put("market", item.market)
                    .put("assetClass", item.assetClass)
                    .put("currency", item.currency)
                    .put("direction", item.direction)
                    .put("referencePrice", item.referencePrice ?: JSONObject.NULL)
                    .put("currentPrice", item.currentPrice ?: JSONObject.NULL)
                    .put("sourceRecommendationId", item.sourceRecommendationId)
                    .put("addedAt", item.addedAt)
            )
        }
        return securePrefs.edit().putString(KEY_WATCHLIST, rows.toString()).commit()
    }

    private fun saveHoldings(snapshot: KisHoldingsSnapshot): Boolean {
        val rows = JSONArray()
        snapshot.holdings.forEach { item ->
            rows.put(
                JSONObject()
                    .put("ticker", item.ticker)
                    .put("name", item.name)
                    .put("quantity", item.quantity)
                    .put("orderableQuantity", item.orderableQuantity)
                    .put("averagePrice", item.averagePrice)
                    .put("currentPrice", item.currentPrice)
                    .put("purchaseAmount", item.purchaseAmount)
                    .put("evaluationAmount", item.evaluationAmount)
                    .put("profitLossAmount", item.profitLossAmount)
                    .put("profitLossRate", item.profitLossRate)
            )
        }
        val payload = JSONObject().put("fetchedAt", snapshot.fetchedAt).put("holdings", rows)
        return securePrefs.edit().putString(KEY_KIS_HOLDINGS, payload.toString()).commit()
    }

    private fun accessToken(credentials: KisCredentials): String {
        val cached = securePrefs.getString(KEY_KIS_ACCESS_TOKEN, "").orEmpty()
        val expiresAt = securePrefs.getLong(KEY_KIS_TOKEN_EXPIRES_AT, 0L)
        if (cached.isNotBlank() && expiresAt > System.currentTimeMillis() + TOKEN_EXPIRY_MARGIN_MS) {
            return cached
        }
        val baseUrl = if (credentials.mockTrading) MOCK_BASE_URL else REAL_BASE_URL
        val payload = JSONObject()
            .put("grant_type", "client_credentials")
            .put("appkey", credentials.appKey)
            .put("appsecret", credentials.appSecret)
        val response = postJson("$baseUrl/oauth2/tokenP", payload)
        val token = response.optString("access_token").trim()
        if (token.isBlank()) {
            error(response.optString("error_description", response.optString("msg1", "한국투자증권 접근토큰 발급에 실패했습니다.")))
        }
        val expiresInSeconds = response.optLong("expires_in", DEFAULT_TOKEN_LIFETIME_SECONDS)
        securePrefs.edit()
            .putString(KEY_KIS_ACCESS_TOKEN, token)
            .putLong(KEY_KIS_TOKEN_EXPIRES_AT, System.currentTimeMillis() + expiresInSeconds * 1_000L)
            .commit()
        return token
    }

    private fun fetchBalancePage(
        credentials: KisCredentials,
        accessToken: String,
        fk100: String,
        nk100: String,
        trCont: String,
    ): KisHttpResponse {
        val baseUrl = if (credentials.mockTrading) MOCK_BASE_URL else REAL_BASE_URL
        val query = linkedMapOf(
            "CANO" to credentials.cano,
            "ACNT_PRDT_CD" to credentials.productCode,
            "AFHR_FLPR_YN" to "N",
            "OFL_YN" to "",
            "INQR_DVSN" to "02",
            "UNPR_DVSN" to "01",
            "FUND_STTL_ICLD_YN" to "N",
            "FNCG_AMT_AUTO_RDPT_YN" to "N",
            "PRCS_DVSN" to "00",
            "CTX_AREA_FK100" to fk100,
            "CTX_AREA_NK100" to nk100,
        ).entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val connection = URL("$baseUrl/uapi/domestic-stock/v1/trading/inquire-balance?$query")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.useCaches = false
        connection.connectTimeout = 12_000
        connection.readTimeout = 25_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("authorization", "Bearer $accessToken")
        connection.setRequestProperty("appkey", credentials.appKey)
        connection.setRequestProperty("appsecret", credentials.appSecret)
        connection.setRequestProperty("tr_id", if (credentials.mockTrading) "VTTC8434R" else "TTTC8434R")
        connection.setRequestProperty("custtype", "P")
        if (trCont.isNotBlank()) connection.setRequestProperty("tr_cont", trCont)
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            val body = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("msg1", "잔고조회 HTTP $code") }
            if (code !in 200..299) error(body.optString("msg1", "잔고조회 HTTP $code"))
            KisHttpResponse(body = body, trCont = connection.getHeaderField("tr_cont").orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(url: String, payload: JSONObject): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.useCaches = false
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.setRequestProperty("Accept", "application/json")
        return try {
            connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(payload.toString()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            val body = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (code !in 200..299) {
                error(body.optString("error_description", body.optString("msg1", "접근토큰 HTTP $code")))
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    private data class KisHttpResponse(
        val body: JSONObject,
        val trCont: String,
    )

    private companion object {
        private const val SECURE_PREFS_NAME = "my_stocks_secure"
        private const val KEY_WATCHLIST = "watchlist_json"
        private const val KEY_KIS_APP_KEY = "kis_app_key"
        private const val KEY_KIS_APP_SECRET = "kis_app_secret"
        private const val KEY_KIS_ACCOUNT = "kis_account"
        private const val KEY_KIS_MOCK = "kis_mock"
        private const val KEY_KIS_ACCESS_TOKEN = "kis_access_token"
        private const val KEY_KIS_TOKEN_EXPIRES_AT = "kis_token_expires_at"
        private const val KEY_KIS_HOLDINGS = "kis_holdings_json"
        private const val REAL_BASE_URL = "https://openapi.koreainvestment.com:9443"
        private const val MOCK_BASE_URL = "https://openapivts.koreainvestment.com:29443"
        private const val DEFAULT_TOKEN_LIFETIME_SECONDS = 86_400L
        private const val TOKEN_EXPIRY_MARGIN_MS = 120_000L
        private const val MAX_BALANCE_PAGES = 10

        private fun encode(value: String): String {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        }
    }
}

private fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val value = optDouble(key, Double.NaN)
    return if (value.isFinite()) value else null
}

private fun JSONObject.optApiDouble(key: String): Double {
    return optString(key).replace(",", "").trim().toDoubleOrNull() ?: optDouble(key, 0.0)
}
