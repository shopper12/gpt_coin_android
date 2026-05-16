package com.cryptotradecoach.data

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GitHubSettings(
    val owner: String = "",
    val repo: String = "",
    val branch: String = "main",
    val token: String = "",
    val rulesPath: String = "rules/strategy-rules.json",
    val reportPath: String = "reports/latest.json",
) {
    val isConfigured: Boolean
        get() = owner.isNotBlank() && repo.isNotBlank() && branch.isNotBlank()
}

class GitHubSyncClient {
    fun downloadText(settings: GitHubSettings, path: String): String? {
        if (!settings.isConfigured || path.isBlank()) return null
        val encodedPath = path.trim('/').split("/").joinToString("/") { encodePathSegment(it) }
        val url = URL("https://api.github.com/repos/${settings.owner}/${settings.repo}/contents/$encodedPath?ref=${encodePathSegment(settings.branch)}")
        val connection = openConnection(url, settings.token, "GET")
        return connection.useJsonResponse { json ->
            val encodedContent = json.optString("content").replace("\n", "")
            if (encodedContent.isBlank()) return@useJsonResponse null
            String(Base64.decode(encodedContent, Base64.DEFAULT), Charsets.UTF_8)
        }
    }

    fun uploadText(settings: GitHubSettings, path: String, content: String, message: String): Boolean {
        if (!settings.isConfigured || settings.token.isBlank() || path.isBlank()) return false
        val sha = existingSha(settings, path)
        val encodedPath = path.trim('/').split("/").joinToString("/") { encodePathSegment(it) }
        val url = URL("https://api.github.com/repos/${settings.owner}/${settings.repo}/contents/$encodedPath")
        val body = JSONObject()
            .put("message", message)
            .put("branch", settings.branch)
            .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        if (sha != null) body.put("sha", sha)

        val connection = openConnection(url, settings.token, "PUT")
        connection.doOutput = true
        connection.outputStream.use { stream ->
            stream.write(body.toString().toByteArray(Charsets.UTF_8))
        }
        return connection.responseCode in 200..299
    }

    private fun existingSha(settings: GitHubSettings, path: String): String? {
        return runCatching {
            val encodedPath = path.trim('/').split("/").joinToString("/") { encodePathSegment(it) }
            val url = URL("https://api.github.com/repos/${settings.owner}/${settings.repo}/contents/$encodedPath?ref=${encodePathSegment(settings.branch)}")
            val connection = openConnection(url, settings.token, "GET")
            connection.useJsonResponse { json -> json.optString("sha").takeIf { it.isNotBlank() } }
        }.getOrNull()
    }

    private fun openConnection(url: URL, token: String, method: String): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "CryptoTradeCoach")
            setRequestProperty("Content-Type", "application/json")
            if (token.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
    }

    private fun <T> HttpURLConnection.useJsonResponse(block: (JSONObject) -> T): T? {
        return try {
            if (responseCode !in 200..299) return null
            inputStream.bufferedReader().use { reader ->
                block(JSONObject(reader.readText()))
            }
        } finally {
            disconnect()
        }
    }

    private fun encodePathSegment(value: String): String {
        return java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
