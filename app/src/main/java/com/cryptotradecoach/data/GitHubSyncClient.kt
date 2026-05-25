package com.cryptotradecoach.data

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GitHubSyncException(
    val statusCode: Int,
    val syncPoint: String,
    val endpoint: String,
    val branch: String,
    val path: String,
) : RuntimeException("$syncPoint failed with HTTP $statusCode")

class GitHubSyncClient {
    fun downloadText(settings: GitHubSyncSettings, path: String): String? {
        val normalized = settings.normalized()
        if (!normalized.isConfigured || normalized.token.isBlank() || path.isBlank()) return null
        val encodedPath = path.trim('/').split("/").joinToString("/") { encodePathSegment(it) }
        val url = URL("https://api.github.com/repos/${normalized.owner}/${normalized.repo}/contents/$encodedPath?ref=${encodePathSegment(normalized.branch)}")
        val connection = openConnection(url, normalized.token, "GET")
        return connection.useJsonResponse(
            syncPoint = "download",
            endpoint = url.toString(),
            branch = normalized.branch,
            path = path,
        ) { json ->
            val encodedContent = json.optString("content").replace("\n", "")
            if (encodedContent.isBlank()) return@useJsonResponse null
            String(Base64.decode(encodedContent, Base64.DEFAULT), Charsets.UTF_8)
        }
    }

    fun uploadText(settings: GitHubSyncSettings, path: String, content: String, message: String): Boolean {
        val normalized = settings.normalized()
        if (!normalized.isConfigured || normalized.token.isBlank() || path.isBlank()) return false
        val sha = existingSha(normalized, path)
        val encodedPath = path.trim('/').split("/").joinToString("/") { encodePathSegment(it) }
        val url = URL("https://api.github.com/repos/${normalized.owner}/${normalized.repo}/contents/$encodedPath")
        val body = JSONObject()
            .put("message", message)
            .put("branch", normalized.branch)
            .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        if (sha != null) body.put("sha", sha)

        val connection = openConnection(url, normalized.token, "PUT")
        connection.doOutput = true
        connection.outputStream.use { stream ->
            stream.write(body.toString().toByteArray(Charsets.UTF_8))
        }
        val statusCode = connection.responseCode
        connection.disconnect()
        if (statusCode !in 200..299) {
            throw GitHubSyncException(
                statusCode = statusCode,
                syncPoint = "upload",
                endpoint = url.toString(),
                branch = normalized.branch,
                path = path,
            )
        }
        return true
    }

    private fun existingSha(settings: GitHubSyncSettings, path: String): String? {
        val encodedPath = path.trim('/').split("/").joinToString("/") { encodePathSegment(it) }
        val url = URL("https://api.github.com/repos/${settings.owner}/${settings.repo}/contents/$encodedPath?ref=${encodePathSegment(settings.branch)}")
        val connection = openConnection(url, settings.token, "GET")
        return connection.useJsonResponse(
            syncPoint = "lookup",
            endpoint = url.toString(),
            branch = settings.branch,
            path = path,
            allowNotFound = true,
        ) { json ->
            json.optString("sha").takeIf { it.isNotBlank() }
        }
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

    private fun <T> HttpURLConnection.useJsonResponse(
        syncPoint: String,
        endpoint: String,
        branch: String,
        path: String,
        allowNotFound: Boolean = false,
        block: (JSONObject) -> T,
    ): T? {
        return try {
            val statusCode = responseCode
            if (allowNotFound && statusCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (statusCode !in 200..299) {
                throw GitHubSyncException(
                    statusCode = statusCode,
                    syncPoint = syncPoint,
                    endpoint = endpoint,
                    branch = branch,
                    path = path,
                )
            }
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
