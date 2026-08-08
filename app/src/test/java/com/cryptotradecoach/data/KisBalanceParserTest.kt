package com.cryptotradecoach.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class KisBalanceParserTest {
    @Test
    fun parsesHoldingsAccountSummaryAndContinuationWithoutPrecisionLoss() {
        val page = KisBalanceParser.parsePage(
            JSONObject(
                """
                {
                  "rt_cd": "0",
                  "output1": [
                    {
                      "pdno": "069500",
                      "prdt_name": "KODEX 200",
                      "hldg_qty": "12.5000",
                      "ord_psbl_qty": "10.5000",
                      "pchs_avg_pric": "35,123.45",
                      "prpr": "36,000",
                      "pchs_amt": "439043.125",
                      "evlu_amt": "9007199254740993",
                      "evlu_pfls_amt": "10957.875",
                      "evlu_pfls_rt": "2.495"
                    },
                    {
                      "pdno": "000000",
                      "prdt_name": "매도 완료",
                      "hldg_qty": "0"
                    }
                  ],
                  "output2": [
                    {
                      "dnca_tot_amt": "1,500,000",
                      "pchs_amt_smtl_amt": "5,000,000",
                      "evlu_amt_smtl_amt": "5,250,000",
                      "evlu_pfls_smtl_amt": "250,000",
                      "nass_amt": "6,750,000"
                    }
                  ],
                  "ctx_area_fk100": "next-fk",
                  "ctx_area_nk100": "next-nk"
                }
                """.trimIndent()
            )
        )

        assertEquals(1, page.holdings.size)
        assertEquals("069500", page.holdings.single().ticker)
        assertEquals(BigDecimal("12.5000"), page.holdings.single().quantity)
        assertEquals(BigDecimal("9007199254740993"), page.holdings.single().evaluationAmount)
        assertEquals(BigDecimal("6750000"), page.accountSummary?.netAssetAmount)
        assertEquals(BigDecimal("1500000"), page.accountSummary?.cashBalance)
        assertEquals("next-fk", page.nextFk100)
        assertEquals("next-nk", page.nextNk100)
    }

    @Test
    fun acceptsObjectFormForAccountSummary() {
        val page = KisBalanceParser.parsePage(
            JSONObject(
                """
                {
                  "rt_cd": "0",
                  "output1": [],
                  "output2": {
                    "dnca_tot_amt": "1000",
                    "nass_amt": "2000"
                  }
                }
                """.trimIndent()
            )
        )

        assertEquals(BigDecimal("1000"), page.accountSummary?.cashBalance)
        assertEquals(BigDecimal("2000"), page.accountSummary?.netAssetAmount)
    }

    @Test
    fun classifiesExpiredTokenAsAuthenticationFailure() {
        val error = parseFailure(
            """
            {
              "rt_cd": "1",
              "msg_cd": "EGW00123",
              "msg1": "기간이 만료된 token 입니다."
            }
            """.trimIndent()
        )

        assertTrue(error.authenticationFailure)
        assertFalse(error.retryable)
        assertTrue(error.message.orEmpty().contains("EGW00123"))
    }

    @Test
    fun classifiesRateLimitAsRetryable() {
        val error = parseFailure(
            """
            {
              "rt_cd": "1",
              "msg_cd": "EGW00201",
              "msg1": "초당 거래건수를 초과하였습니다."
            }
            """.trimIndent()
        )

        assertFalse(error.authenticationFailure)
        assertTrue(error.retryable)
        assertTrue(error.message.orEmpty().contains("EGW00201"))
    }

    @Test
    fun parsesOfficialAbsoluteTokenExpiryAsKoreaTime() {
        val expiresAt = KisTokenExpiryParser.parseExpiryMillis(
            response = JSONObject()
                .put("access_token_token_expired", "2026-08-08 13:30:00")
                .put("expires_in", 1),
            nowMillis = 0L,
        )

        assertEquals(Instant.parse("2026-08-08T04:30:00Z").toEpochMilli(), expiresAt)
    }

    @Test
    fun fallsBackToExpiresInWhenAbsoluteTokenExpiryIsMissing() {
        val expiresAt = KisTokenExpiryParser.parseExpiryMillis(
            response = JSONObject().put("expires_in", 3600),
            nowMillis = 10_000L,
        )

        assertEquals(3_610_000L, expiresAt)
    }

    private fun parseFailure(json: String): KisApiException {
        try {
            KisBalanceParser.parsePage(JSONObject(json))
            fail("KisApiException이 발생해야 합니다.")
        } catch (error: KisApiException) {
            return error
        }
        error("unreachable")
    }
}
