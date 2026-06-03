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
    data class ReleaseApkInfo(
        val versionCode: Int,
        val versionName: String,
        val assetName: String,
        val assetUrl: String,
        val releaseBody: String,
        val currentVersionCode: Int,
    ) {
        val hasUpdate: Boolean get() = versionCode > currentVersionCode
    }

    fun checkLatestRelease(settings: GitHubSyncSettings): ReleaseApkInfo? {
        val normalized = settings.normalized()
        if (!normalized.isConfigured) return null
        val releaseJson = fetchLatestReleaseJson(normalized)
        val asset = findApkAsset(releaseJson)
        val body = releaseJson.optString("body")
        val assetName = asset.optString("name")
        return ReleaseApkInfo(
            versionCode = parseVersionCode(body, assetName),
            versionName = parseVersionName(body, assetName),
            assetName = assetName,
            assetUrl = asset.optString("browser_download_url").ifBlank { asset.optString("url") },
            releaseBody = body,
            currentVersionCode = currentVersionCode(),
        )
    }

    fun downloadLatestReleaseApk(settings: GitHubSyncSettings): File {
        val normalized = settings.normalized()
        if (!normalized.isConfigured) throw IOException("GitHub settings are incomplete: owner/repo/branch required")

        val releaseJson = fetchLatestReleaseJson(normalized)
        val asset = findApkAsset(releaseJson)
        val apiUrl = asset.optString("url")
        val browserUrl = asset.optString("browser_download_url")
        val downloadUrl = browserUrl.takeIf { it.isNotBlank() } ?: apiUrl.takeIf { it.isNotBlank() } ?: throw IOException("APK asset URL not found in $RELEASE_TAG")
        val fileName = asset.optString("name").takeIf { it.isNotBlank() } ?: APK_ASSET_FALLBACK_NAME
        val outDir = File(context.cacheDir, "updates").also { it.mkdirs() }
        val outFile = File(outDir, fileName)
        downloadBinary(URL(downloadUrl), normalized.token, outFile, preferGitHubAssetApi = downloadUrl == apiUrl)
        validateApk(outFile)
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
        validateApk(apkFile)
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
        context.startActivity(Intent.createChooser(intent, "Install APK").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }

    private fun fetchLatestReleaseJson(settings: GitHubSyncSettings): JSONObject {
        val releaseUrl = URL("https://api.github.com/repos/${settings.owner}/${settings.repo}/releases/tags/$RELEASE_TAG")
        return fetchJson(releaseUrl, settings.token)
    }

    private fun findApkAsset(releaseJson: JSONObject): JSONObject {
        val assets = releaseJson.optJSONArray("assets") ?: throw IOException("Release $RELEASE_TAG has no assets")
        val names = mutableListOf<String>()
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            names += name
            if (name.endsWith(".apk") && name.startsWith("crypto-trade-coach")) return asset
        }
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk")) return asset
        }
        throw IOException("APK asset not found in $RELEASE_TAG. assets=${names.joinToString()}")
    }

    private fun parseVersionCode(body: String, assetName: String): Int {
        Regex("versionCode:\\s*(\\d+)").find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("v(\\d+)\\.apk$").find(assetName)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return 0
    }

    private fun parseVersionName(body: String, assetName: String): String {
        Regex("versionName:\\s*([^\\s]+)").find(body)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("v(\\d+)\\.apk$").find(assetName)?.groupValues?.getOrNull(1)?.let { return "1.0.$it" }
        return "unknown"
    }

    private fun currentVersionCode(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }
    }

    private fun fetchJson(url: URL, token: String): JSONObject {
        val connection = openConnection(url, token, accept = "application/vnd.github+json")
        return try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = readErrorBody(connection)
                throw IOException("GitHub release lookup failed: HTTP $status ${body.take(180)}")
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadBinary(url: URL, token: String, outFile: File, preferGitHubAssetApi: Boolean, redirectDepth: Int = 0) {
        if (redirectDepth > MAX_REDIRECTS) throw IOException("APK download failed: too many redirects")
        val accept = if (preferGitHubAssetApi) "application/octet-stream" else "application/vnd.android.package-archive,application/octet-stream,*/*"
        val connection = openConnection(url, token, accept = accept, followRedirects = false)
        try {
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location") ?: throw IOException("APK download redirect without Location")
                connection.disconnect()
                downloadBinary(URL(location), "", outFile, preferGitHubAssetApi = false, redirectDepth = redirectDepth + 1)
                return
            }
            if (status !in 200..299) {
                val body = readErrorBody(connection)
                throw IOException("APK download failed: HTTP $status ${body.take(180)}")
            }
            connection.inputStream.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun validateApk(file: File) {
        if (!file.exists() || file.length() <= 0L) throw IOException("Downloaded APK is empty")
        if (file.length() < MIN_APK_BYTES) throw IOException("Downloaded APK is too small: ${file.length()} bytes")
        file.inputStream().use { input ->
            val header = ByteArray(2)
            val read = input.read(header)
            if (read != 2 || header[0] != 'P'.code.toByte() || header[1] != 'K'.code.toByte()) {
                throw IOException("Downloaded file is not a valid APK/ZIP. size=${file.length()} bytes")
            }
        }
    }

    private fun readErrorBody(connection: HttpURLConnection): String {
        return runCatching {
            (connection.errorStream ?: connection.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrDefault("")
    }

    private fun openConnection(
        url: URL,
        token: String,
        accept: String,
        followRedirects: Boolean = true,
    ): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = followRedirects
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Accept", accept)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "CryptoTradeCoach")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
    }

    companion object {
        private const val RELEASE_TAG = "latest-phone-apk"
        private const val APK_ASSET_FALLBACK_NAME = "crypto-trade-coach-release.apk"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val MIN_APK_BYTES = 1024L * 1024L
        private const val MAX_REDIRECTS = 5
    }
}
