package com.cryptotradecoach.data

import java.net.HttpURLConnection
import java.net.URL

class PublicTextReader {
    fun read(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "CryptoTradeCoach")
        }
        return try {
            val status = connection.responseCode
            if (status !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
