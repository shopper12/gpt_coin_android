package com.cryptotradecoach.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class KisHolding(
    val ticker: String,
    val name: String,
    val quantity: BigDecimal,
    val orderableQuantity: BigDecimal,
    val averagePrice: BigDecimal,
    val currentPrice: BigDecimal,
    val purchaseAmount: BigDecimal,
    val evaluationAmount: BigDecimal,
    val profitLossAmount: BigDecimal,
    val profitLossRate: BigDecimal,
)

data class KisAccountSummary(
    val cashBalance: BigDecimal = BigDecimal.ZERO,
    val purchaseAmount: BigDecimal = BigDecimal.ZERO,
    val evaluationAmount: BigDecimal = BigDecimal.ZERO,
    val profitLossAmount: BigDecimal = BigDecimal.ZERO,
    val netAssetAmount: BigDecimal = BigDecimal.ZERO,
)

data class KisHoldingsSnapshot(
    val holdings: List<KisHolding> = emptyList(),
    val accountSummary: KisAccountSummary? = null,
    val fetchedAt: Long = 0L,
)

internal data class KisBalancePage(
    val holdings: List<KisHolding>,
    val accountSummary: KisAccountSummary?,
    val nextFk100: String,
    val nextNk100: String,
)

internal class KisApiException(
    val messageCode: String,
    val httpStatus: Int?,
    val authenticationFailure: Boolean,
    val retryable: Boolean,
    detail: String,
) : IOException(buildDisplayMessage(messageCode, httpStatus, detail)) {
    companion object {
        private val AUTH_CODES = setOf("EGW00121", "EGW00122", "EGW00123", "EGW00124")
        private val RATE_LIMIT_CODES = setOf("EGW00201", "EGW00202")

        fun fromResponse(
            body: JSONObject,
            httpStatus: Int? = null,
            fallback: String,
        ): KisApiException {
            val code = body.optString("msg_cd").trim()
                .ifBlank { body.optString("error_code").trim() }
            val detail = sequenceOf(
                body.optString("msg1"),
                body.optString("error_description"),
                body.optString("message"),
            ).map { it.trim() }.firstOrNull { it.isNotBlank() } ?: fallback
            val normalized = detail.lowercase()
            val authenticationFailure = httpStatus == 401 ||
                code in AUTH_CODES ||
                (normalized.contains("token") || normalized.contains("토큰")) &&
                (
                    normalized.contains("expired") ||
                        normalized.contains("invalid") ||
                        normalized.contains("만료") ||
                        normalized.contains("유효하지")
                    )
            val rateLimited = httpStatus == 429 ||
                code in RATE_LIMIT_CODES ||
                normalized.contains("rate limit") ||
                normalized.contains("초당 거래건수")
            val retryable = rateLimited || (httpStatus != null && httpStatus >= 500)
            return KisApiException(
                messageCode = code,
                httpStatus = httpStatus,
                authenticationFailure = authenticationFailure,
                retryable = retryable,
                detail = detail,
            )
        }

        private fun buildDisplayMessage(code: String, status: Int?, detail: String): String {
            val prefix = when {
                code.isNotBlank() -> "[$code]"
                status != null -> "[HTTP $status]"
                else -> ""
            }
            return listOf(prefix, detail.ifBlank { "한국투자증권 API 요청이 실패했습니다." })
                .filter(String::isNotBlank)
                .joinToString(" ")
        }
    }
}

internal object KisBalanceParser {
    fun parsePage(root: JSONObject): KisBalancePage {
        if (root.optString("rt_cd") != "0") {
            throw KisApiException.fromResponse(
                body = root,
                fallback = "한국투자증권 잔고조회가 실패했습니다.",
            )
        }

        val holdings = buildList {
            val rows = root.optJSONArray("output1") ?: JSONArray()
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                val ticker = item.optString("pdno").trim()
                val quantity = item.optApiDecimal("hldg_qty")
                if (ticker.isBlank() || quantity.signum() <= 0) continue
                add(
                    KisHolding(
                        ticker = ticker,
                        name = item.optKisProductName(ticker),
                        quantity = quantity,
                        orderableQuantity = item.optApiDecimal("ord_psbl_qty"),
                        averagePrice = item.optApiDecimal("pchs_avg_pric"),
                        currentPrice = item.optApiDecimal("prpr"),
                        purchaseAmount = item.optApiDecimal("pchs_amt"),
                        evaluationAmount = item.optApiDecimal("evlu_amt"),
                        profitLossAmount = item.optApiDecimal("evlu_pfls_amt"),
                        profitLossRate = item.optApiDecimal("evlu_pfls_rt"),
                    )
                )
            }
        }

        return KisBalancePage(
            holdings = holdings,
            accountSummary = parseAccountSummary(root),
            nextFk100 = root.optString("ctx_area_fk100").trim(),
            nextNk100 = root.optString("ctx_area_nk100").trim(),
        )
    }

    private fun parseAccountSummary(root: JSONObject): KisAccountSummary? {
        val output = root.optJSONArray("output2")?.optJSONObject(0)
            ?: root.optJSONObject("output2")
            ?: return null
        return KisAccountSummary(
            cashBalance = output.optApiDecimal("dnca_tot_amt"),
            purchaseAmount = output.optApiDecimal("pchs_amt_smtl_amt"),
            evaluationAmount = output.optApiDecimal("evlu_amt_smtl_amt"),
            profitLossAmount = output.optApiDecimal("evlu_pfls_smtl_amt"),
            netAssetAmount = output.optApiDecimal("nass_amt"),
        )
    }
}

internal object KisTokenExpiryParser {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val timeZone = ZoneId.of("Asia/Seoul")

    fun parseExpiryMillis(
        response: JSONObject,
        nowMillis: Long = System.currentTimeMillis(),
        defaultLifetimeSeconds: Long = 86_400L,
    ): Long {
        val absoluteExpiry = response.optString("access_token_token_expired").trim()
        val parsed = runCatching {
            LocalDateTime.parse(absoluteExpiry, formatter)
                .atZone(timeZone)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
        if (parsed != null) return parsed
        val expiresInSeconds = response.optLong("expires_in", defaultLifetimeSeconds)
        return nowMillis + expiresInSeconds * 1_000L
    }
}

internal fun JSONObject.optApiDecimal(key: String): BigDecimal {
    if (!has(key) || isNull(key)) return BigDecimal.ZERO
    return optString(key)
        .replace(",", "")
        .trim()
        .toBigDecimalOrNull()
        ?: BigDecimal.ZERO
}

private fun JSONObject.optKisProductName(ticker: String): String {
    val candidates = sequenceOf(
        optString("prdt_name"),
        optString("prdt_abrv_name"),
        optString("hts_kor_isnm"),
        optString("item_name"),
    ).map { it.trim() }
        .filter { it.isNotBlank() }
        .filterNot { it == ticker || it.all(Char::isDigit) }
    return candidates.firstOrNull() ?: ticker
}
