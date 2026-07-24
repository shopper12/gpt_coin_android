package com.cryptotradecoach.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
        val token = settings.normalized().token
        val releaseJson = fetchLatestReleaseJson(token)
        val asset = findOfficialApkAsset(releaseJson)
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
        val token = settings.normalized().token
        val releaseJson = fetchLatestReleaseJson(token)
        val asset = findOfficialApkAsset(releaseJson)
        val apiUrl = asset.optString("url")
        val browserUrl = asset.optString("browser_download_url")
        val downloadUrl = browserUrl.takeIf { it.isNotBlank() }
            ?: apiUrl.takeIf { it.isNotBlank() }
            ?: throw IOException("Official unified APK asset URL not found in $RELEASE_TAG")
        val fileName = asset.optString("name")
        if (fileName != OFFICIAL_APK_ASSET_NAME) {
            throw IOException("Refusing unexpected APK asset: $fileName")
        }
        val outDir = File(context.cacheDir, "updates").also { it.mkdirs() }
        val outFile = File(outDir, OFFICIAL_APK_ASSET_NAME)
        downloadBinary(URL(downloadUrl), token, outFile, preferGitHubAssetApi = downloadUrl == apiUrl)
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
        context.startActivity(Intent.createChooser(intent, "Install Unified Trading Coach").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun fetchLatestReleaseJson(token: String): JSONObject {
        val releaseUrl = URL("https://api.github.com/repos/$OFFICIAL_RELEASE_OWNER/$OFFICIAL_RELEASE_REPO/releases/tags/$RELEASE_TAG")
        return fetchJson(releaseUrl, token)
    }

    private fun findOfficialApkAsset(releaseJson: JSONObject): JSONObject {
        val assets = releaseJson.optJSONArray("assets") ?: throw IOException("Release $RELEASE_TAG has no assets")
        val names = mutableListOf<String>()
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            names += name
            if (name == OFFICIAL_APK_ASSET_NAME) return asset
        }
        throw IOException(
            "Official APK $OFFICIAL_APK_ASSET_NAME not found in $RELEASE_TAG. assets=${names.joinToString()}",
        )
    }

    private fun parseVersionCode(body: String, assetName: String): Int {
        Regex("versionCode:\\s*(\\d+)").find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("v(\\d+)(?:-[^.]+)?\\.apk$").find(assetName)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return 0
    }

    private fun parseVersionName(body: String, assetName: String): String {
        Regex("versionName:\\s*([^\\s]+)").find(body)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("v(\\d+)(?:-[^.]+)?\\.apk$").find(assetName)?.groupValues?.getOrNull(1)?.let { return "1.0.$it" }
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

    private fun downloadBinary(
        url: URL,
        token: String,
        outFile: File,
        preferGitHubAssetApi: Boolean,
        redirectDepth: Int = 0,
    ) {
        if (redirectDepth > MAX_REDIRECTS) throw IOException("APK download failed: too many redirects")
        val accept = if (preferGitHubAssetApi) {
            "application/octet-stream"
        } else {
            "application/vnd.android.package-archive,application/octet-stream,*/*"
        }
        val connection = openConnection(url, token, accept = accept, followRedirects = false)
        try {
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("APK download redirect without Location")
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
        validateOfficialPackageMetadata(file)
    }

    private fun validateOfficialPackageMetadata(file: File) {
        val packageManager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val archiveInfo = packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("Downloaded APK package metadata cannot be read")
        if (archiveInfo.packageName != OFFICIAL_PACKAGE_NAME) {
            throw IOException(
                "Refusing APK package ${archiveInfo.packageName}; expected $OFFICIAL_PACKAGE_NAME",
            )
        }

        val applicationInfo = archiveInfo.applicationInfo
            ?: throw IOException("Downloaded APK application metadata is missing")
        applicationInfo.sourceDir = file.absolutePath
        applicationInfo.publicSourceDir = file.absolutePath
        val appLabel = runCatching { packageManager.getApplicationLabel(applicationInfo).toString().trim() }
            .getOrDefault("")
        if (appLabel != OFFICIAL_APP_LABEL) {
            throw IOException("Refusing APK app label '$appLabel'; expected '$OFFICIAL_APP_LABEL'")
        }

        if (context.packageName == OFFICIAL_PACKAGE_NAME) {
            @Suppress("DEPRECATION")
            val installedInfo = packageManager.getPackageInfo(context.packageName, flags)
            val installedCertificates = signingCertificateDigests(installedInfo, includeHistory = true)
            val archiveCertificates = signingCertificateDigests(archiveInfo, includeHistory = false)
            if (
                installedCertificates.isNotEmpty() &&
                archiveCertificates.isNotEmpty() &&
                installedCertificates.intersect(archiveCertificates).isEmpty()
            ) {
                throw IOException(
                    "APK signing key does not match the installed app. Uninstall the old debug/Crypto build once, then install the official unified APK.",
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun signingCertificateDigests(packageInfo: PackageInfo, includeHistory: Boolean): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: return emptySet()
            if (includeHistory && !signingInfo.hasMultipleSigners()) {
                signingInfo.signingCertificateHistory
            } else {
                signingInfo.apkContentsSigners
            }
        } else {
            packageInfo.signatures
        }
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }.toSet()
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
            setRequestProperty("User-Agent", "UnifiedTradingCoach")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
    }

    companion object {
        private const val OFFICIAL_RELEASE_OWNER = "shopper12"
        private const val OFFICIAL_RELEASE_REPO = "gpt_coin_android"
        private const val RELEASE_TAG = "latest-phone-apk"
        private const val OFFICIAL_APK_ASSET_NAME = "unified-trading-coach-release.apk"
        private const val OFFICIAL_PACKAGE_NAME = "com.cryptotradecoach"
        private const val OFFICIAL_APP_LABEL = "Unified Trading Coach"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val MIN_APK_BYTES = 1024L * 1024L
        private const val MAX_REDIRECTS = 5
    }
}
