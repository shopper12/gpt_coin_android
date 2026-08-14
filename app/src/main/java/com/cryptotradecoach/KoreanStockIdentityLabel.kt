package com.cryptotradecoach

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

@Composable
internal fun KoreanStockIdentityLabel(
    ticker: String,
    preferredName: String,
    modifier: Modifier = Modifier,
    bold: Boolean = true,
    maxLines: Int = 1,
    resolveKoreanCode: Boolean = true,
) {
    val code = ticker.trim()
    val preferred = preferredHumanName(preferredName, code)
    var displayName by remember(code, preferredName) { mutableStateOf(preferred) }
    var resolutionFinished by remember(code, preferredName) {
        mutableStateOf(preferred != null || !resolveKoreanCode || !isSixDigitKrCode(code))
    }

    LaunchedEffect(code, preferredName, resolveKoreanCode) {
        if (resolveKoreanCode && isSixDigitKrCode(code) && displayName == null) {
            displayName = withContext(Dispatchers.IO) { KoreanStockNameResolver.resolve(code) }
        }
        resolutionFinished = true
    }

    val text = when {
        displayName != null -> "${displayName} ($code)"
        resolveKoreanCode && isSixDigitKrCode(code) && !resolutionFinished -> "종목명 조회 중 ($code)"
        resolveKoreanCode && isSixDigitKrCode(code) -> "종목명 미확인 ($code)"
        else -> code
    }
    Text(
        text = text,
        modifier = modifier,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = maxLines,
    )
}

private object KoreanStockNameResolver {
    private val cache = ConcurrentHashMap<String, String>()

    fun resolve(code: String): String? {
        if (!isSixDigitKrCode(code)) return null
        cache[code]?.let { return it }
        val connection = URL("https://m.stock.naver.com/api/stock/$code/basic")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.useCaches = true
        connection.connectTimeout = 6_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "UnifiedTradingCoach-Android")
        return try {
            if (connection.responseCode !in 200..299) return null
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(text)
            val name = sequenceOf(
                root.optString("stockName"),
                root.optString("itemName"),
                root.optString("name"),
            ).map { it.trim() }.firstOrNull { preferredHumanName(it, code) != null }
            preferredHumanName(name, code)?.also { cache[code] = it }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}

private fun preferredHumanName(value: String?, code: String): String? {
    val name = value.orEmpty().trim()
    if (name.isBlank()) return null
    if (name == code) return null
    if (name.all(Char::isDigit)) return null
    return name
}

private fun isSixDigitKrCode(value: String): Boolean =
    value.length == 6 && value.all(Char::isDigit)
