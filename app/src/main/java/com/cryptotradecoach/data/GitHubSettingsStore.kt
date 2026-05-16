package com.cryptotradecoach.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class GitHubSettingsStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val publicPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

    fun load(): GitHubSettings {
        return GitHubSettings(
            owner = publicPrefs.getString(KEY_OWNER, "").orEmpty(),
            repo = publicPrefs.getString(KEY_REPO, "").orEmpty(),
            branch = publicPrefs.getString(KEY_BRANCH, "main").orEmpty().ifBlank { "main" },
            token = securePrefs.getString(KEY_TOKEN, "").orEmpty(),
            rulesPath = publicPrefs.getString(KEY_RULES_PATH, "rules/strategy-rules.json").orEmpty().ifBlank { "rules/strategy-rules.json" },
            reportPath = publicPrefs.getString(KEY_REPORT_PATH, "reports/latest.json").orEmpty().ifBlank { "reports/latest.json" },
        )
    }

    fun save(settings: GitHubSettings) {
        publicPrefs.edit()
            .putString(KEY_OWNER, settings.owner.trim())
            .putString(KEY_REPO, settings.repo.trim())
            .putString(KEY_BRANCH, settings.branch.trim().ifBlank { "main" })
            .putString(KEY_RULES_PATH, settings.rulesPath.trim().ifBlank { "rules/strategy-rules.json" })
            .putString(KEY_REPORT_PATH, settings.reportPath.trim().ifBlank { "reports/latest.json" })
            .apply()
        securePrefs.edit()
            .putString(KEY_TOKEN, settings.token)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "github_sync_settings"
        private const val SECURE_PREFS_NAME = "github_sync_secure"
        private const val KEY_OWNER = "owner"
        private const val KEY_REPO = "repo"
        private const val KEY_BRANCH = "branch"
        private const val KEY_TOKEN = "token"
        private const val KEY_RULES_PATH = "rules_path"
        private const val KEY_REPORT_PATH = "report_path"

        @Volatile
        private var instance: GitHubSettingsStore? = null

        fun getInstance(context: Context): GitHubSettingsStore {
            return instance ?: synchronized(this) {
                instance ?: GitHubSettingsStore(context).also { instance = it }
            }
        }
    }
}
