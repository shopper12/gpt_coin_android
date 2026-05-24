package com.cryptotradecoach.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class AppUpdateRepository(private val context: Context) {
    fun downloadLatestReleaseApk(settings: GitHubSyncSettings): File {
        val normalized = settings.normalized()
        if (!normalized.isConfigured) throw IOException("GitHub settings are incomplete")
        if (normalized.token.isBlank()) throw IOException("GitHub token is missing")

        val releaseUrl = URL("https://api.github.com/repos/${normalized.owner}/${normalized.repo}/releases/tags/$RELEASE_TAG")
        val releaseJson = fetchJson(releaseUrl, normalized.token)
        val assets = releaseJson.optJSONArray("assets") ?: throw IOException("Release has no assets")
        var assetUrl: String? = null
        var assetName: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (name == APK_ASSET_NAME || name.endsWith(".apk")) {
                assetUrl = asset.optString("url")
                assetName = name
                break
            }
        }
        val downloadUrl = assetUrl?.takeIf { it.isNotBlank() } ?: throw IOException("APK asset not found in $RELEASE_TAG")
        val fileName = assetName?.takeIf { it.isNotBlank() } ?: APK_ASSET_NAME
        val outDir = File(context.cacheDir, "updates").also { it.mkdirs() }
        val outFile = File(outDir, fileName)
        downloadBinary(URL(downloadUrl), normalized.token, outFile)
        if (!outFile.exists() || outFile.length() <= 0L) throw IOException("Downloaded APK is empty")
        return outFile
    }

    fun canRequestPackageInstalls(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    }

    fun openUnknownAppSourcesSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun openApkInstaller(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun fetchJson(url: URL, token: String): JSONObject {
        val connection = openConnection(url, token, accept = "application/vnd.github+json")
        return try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("GitHub release lookup failed: HTTP $status")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadBinary(url: URL, token: String, outFile: File) {
        val connection = openConnection(url, token, accept = "application/octet-stream")
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("APK download failed: HTTP $status")
            connection.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: URL, token: String, accept: String): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Accept", accept)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "CryptoTradeCoach")
            setRequestProperty("Authorization", "Bearer $token")
        }
    }

    companion object {
        private const val RELEASE_TAG = "latest-phone-apk"
        private const val APK_ASSET_NAME = "crypto-trade-coach-release.apk"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
