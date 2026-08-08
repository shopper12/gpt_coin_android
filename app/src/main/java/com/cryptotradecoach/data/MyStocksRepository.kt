package com.cryptotradecoach.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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
                remove(KEY_KIS_HOLDINGS)
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
                            quantity = item.optApiDecimal("quantity"),
                            orderableQuantity = item.optApiDecimal("orderableQuantity"),
                            averagePrice = item.optApiDecimal("averagePrice"),
                            currentPrice = item.optApiDecimal("currentPrice"),
                            purchaseAmount = item.optApiDecimal("purchaseAmount"),
                            evaluationAmount = item.optApiDecimal("evaluationAmount"),
                            profitLossAmount = item.optApiDecimal("profitLossAmount"),
                            profitLossRate = item.optApiDecimal("profitLossRate"),
                        )
                    )
                }
            }
            val accountSummary = root.optJSONObject("accountSummary")?.let { summary ->
                KisAccountSummary(
                    cashBalance = summary.optApiDecimal("cashBalance"),
                    purchaseAmount = summary.optApiDecimal("purchaseAmount"),
                    evaluationAmount = summary.optApiDecimal("evaluationAmount"),
                    profitLossAmount = summary.optApiDecimal("profitLossAmount"),
                    netAssetAmount = summary.optApiDecimal("netAssetAmount"),
                )
            }
            KisHoldingsSnapshot(
                holdings = holdings,
                accountSummary = accountSummary,
                fetchedAt = root.optLong("fetchedAt", 0L),
            )
        }.getOrDefault(KisHoldingsSnapshot())
    }

    suspend fun refreshKisHoldings(): KisHoldingsSnapshot = withContext(Dispatchers.IO) {
        val credentials = loadCredentials()
        require(credentials.isConfigured) { "한투 설정에서 App Key, App Secret, 10자리 계좌번호를 먼저 저장하세요." }
        var authenticationRetried = false
        var result: KisHoldingsSnapshot? = null
        while (result == null) {
            try {
                val token = accessToken(credentials, forceRefresh = authenticationRetried)
                val snapshot = fetchAllHoldings(credentials, token)
                saveHoldings(snapshot)
                result = snapshot
            } catch (error: KisApiException) {
                if (!authenticationRetried && error.authenticationFailure) {
                    clearAccessToken()
                    authenticationRetried = true
                    continue
                }
                throw error
            }
        }
        checkNotNull(result)
    }

    private fun fetchAllHoldings(
        credentials: KisCredentials,
        token: String,
    ): KisHoldingsSnapshot {
        val holdings = mutableListOf<KisHolding>()
        var accountSummary: KisAccountSummary? = null
        var fk100 = ""
        var nk100 = ""
        var trCont = ""
        var page = 0
        while (page < MAX_BALANCE_PAGES) {
            page += 1
            val (response, parsed) = fetchParsedBalancePageWithRetry(
                credentials = credentials,
                accessToken = token,
                fk100 = fk100,
                nk100 = nk100,
                trCont = trCont,
            )
            holdings += parsed.holdings
            accountSummary = parsed.accountSummary ?: accountSummary
            val hasNextPage = response.trCont in setOf("M", "F") &&
                parsed.nextFk100.isNotBlank() &&
                parsed.nextNk100.isNotBlank()
            if (!hasNextPage) break
            if (page >= MAX_BALANCE_PAGES) {
                throw KisApiException(
                    messageCode = "CLIENT_PAGE_LIMIT",
                    httpStatus = null,
                    authenticationFailure = false,
                    retryable = false,
                    detail = "잔고 연속조회가 ${MAX_BALANCE_PAGES}페이지를 초과해 중단됐습니다.",
                )
            }
            fk100 = parsed.nextFk100
            nk100 = parsed.nextNk100
            trCont = "N"
            Thread.sleep(if (credentials.mockTrading) MOCK_REQUEST_INTERVAL_MS else REAL_REQUEST_INTERVAL_MS)
        }
        return KisHoldingsSnapshot(
            holdings = holdings.distinctBy { it.ticker }.sortedByDescending { it.evaluationAmount },
            accountSummary = accountSummary,
            fetchedAt = System.currentTimeMillis(),
        )
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
                    .put("quantity", item.quantity.toPlainString())
                    .put("orderableQuantity", item.orderableQuantity.toPlainString())
                    .put("averagePrice", item.averagePrice.toPlainString())
                    .put("currentPrice", item.currentPrice.toPlainString())
                    .put("purchaseAmount", item.purchaseAmount.toPlainString())
                    .put("evaluationAmount", item.evaluationAmount.toPlainString())
                    .put("profitLossAmount", item.profitLossAmount.toPlainString())
                    .put("profitLossRate", item.profitLossRate.toPlainString())
            )
        }
        val payload = JSONObject()
            .put("fetchedAt", snapshot.fetchedAt)
            .put("holdings", rows)
        snapshot.accountSummary?.let { summary ->
            payload.put(
                "accountSummary",
                JSONObject()
                    .put("cashBalance", summary.cashBalance.toPlainString())
                    .put("purchaseAmount", summary.purchaseAmount.toPlainString())
                    .put("evaluationAmount", summary.evaluationAmount.toPlainString())
                    .put("profitLossAmount", summary.profitLossAmount.toPlainString())
                    .put("netAssetAmount", summary.netAssetAmount.toPlainString())
            )
        }
        return securePrefs.edit().putString(KEY_KIS_HOLDINGS, payload.toString()).commit()
    }

    private fun accessToken(credentials: KisCredentials, forceRefresh: Boolean = false): String {
        val cached = securePrefs.getString(KEY_KIS_ACCESS_TOKEN, "").orEmpty()
        val expiresAt = securePrefs.getLong(KEY_KIS_TOKEN_EXPIRES_AT, 0L)
        if (!forceRefresh && cached.isNotBlank() && expiresAt > System.currentTimeMillis() + TOKEN_EXPIRY_MARGIN_MS) {
            return cached
        }
        val baseUrl = if (credentials.mockTrading) MOCK_BASE_URL else REAL_BASE_URL
        val payload = JSONObject()
            .put("grant_type", "client_credentials")
            .put("appkey", credentials.appKey)
            .put("appsecret", credentials.appSecret)
        val response = postJsonWithRetry("$baseUrl/oauth2/tokenP", payload, credentials.mockTrading)
        val token = response.optString("access_token").trim()
        if (token.isBlank()) {
            throw KisApiException.fromResponse(
                body = response,
                fallback = "한국투자증권 접근토큰 발급에 실패했습니다.",
            )
        }
        val expiresAtMillis = KisTokenExpiryParser.parseExpiryMillis(
            response = response,
            defaultLifetimeSeconds = DEFAULT_TOKEN_LIFETIME_SECONDS,
        )
        securePrefs.edit()
            .putString(KEY_KIS_ACCESS_TOKEN, token)
            .putLong(KEY_KIS_TOKEN_EXPIRES_AT, expiresAtMillis)
            .commit()
        return token
    }

    private fun clearAccessToken() {
        securePrefs.edit()
            .remove(KEY_KIS_ACCESS_TOKEN)
            .remove(KEY_KIS_TOKEN_EXPIRES_AT)
            .commit()
    }

    private fun fetchParsedBalancePageWithRetry(
        credentials: KisCredentials,
        accessToken: String,
        fk100: String,
        nk100: String,
        trCont: String,
    ): Pair<KisHttpResponse, KisBalancePage> {
        var retryCount = 0
        while (true) {
            try {
                val response = fetchBalancePage(credentials, accessToken, fk100, nk100, trCont)
                return response to KisBalanceParser.parsePage(response.body)
            } catch (error: IOException) {
                val retryable = when (error) {
                    is KisApiException -> error.retryable
                    else -> true
                }
                if (!retryable || retryCount >= MAX_API_RETRIES) throw error
                Thread.sleep(retryDelayMillis(credentials.mockTrading, retryCount))
                retryCount += 1
            }
        }
    }

    private fun retryDelayMillis(mockTrading: Boolean, retryCount: Int): Long {
        val base = if (mockTrading) 750L else 250L
        return (base * (1L shl retryCount)).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun postJsonWithRetry(
        url: String,
        payload: JSONObject,
        mockTrading: Boolean,
    ): JSONObject {
        var retryCount = 0
        while (true) {
            try {
                return postJson(url, payload)
            } catch (error: IOException) {
                val retryable = when (error) {
                    is KisApiException -> error.retryable
                    else -> true
                }
                if (!retryable || retryCount >= MAX_API_RETRIES) throw error
                Thread.sleep(retryDelayMillis(mockTrading, retryCount))
                retryCount += 1
            }
        }
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
            if (code !in 200..299) {
                throw KisApiException.fromResponse(
                    body = body,
                    httpStatus = code,
                    fallback = "잔고조회 HTTP $code",
                )
            }
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
                throw KisApiException.fromResponse(
                    body = body,
                    httpStatus = code,
                    fallback = "접근토큰 HTTP $code",
                )
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
        private const val MAX_API_RETRIES = 2
        private const val REAL_REQUEST_INTERVAL_MS = 100L
        private const val MOCK_REQUEST_INTERVAL_MS = 500L
        private const val MAX_RETRY_DELAY_MS = 2_000L
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
