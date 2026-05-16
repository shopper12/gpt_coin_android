package com.cryptotradecoach.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class GitHubSyncSettings(
    val owner: String = DEFAULT_OWNER,
    val repo: String = DEFAULT_REPO,
    val branch: String = DEFAULT_BRANCH,
    val rulesPath: String = DEFAULT_RULES_PATH,
    val reportPath: String = DEFAULT_REPORT_PATH,
    val token: String = "",
) {
    val isConfigured: Boolean
        get() = normalized().let { it.owner.isNotBlank() && it.repo.isNotBlank() && it.branch.isNotBlank() }

    fun normalized(): GitHubSyncSettings {
        return copy(
            owner = owner.trim().ifBlank { DEFAULT_OWNER },
            repo = repo.trim().ifBlank { DEFAULT_REPO },
            branch = branch.trim().ifBlank { DEFAULT_BRANCH },
            rulesPath = rulesPath.trim().ifBlank { DEFAULT_RULES_PATH },
            reportPath = reportPath.trim().ifBlank { DEFAULT_REPORT_PATH },
        )
    }

    companion object {
        const val DEFAULT_OWNER = "shopper12"
        const val DEFAULT_REPO = "gpt_coin_android"
        const val DEFAULT_BRANCH = "fix/runtime-phone-error"
        const val DEFAULT_RULES_PATH = "rules/strategy-rules.json"
        const val DEFAULT_REPORT_PATH = "reports/latest.json"
    }
}

typealias GitHubSettings = GitHubSyncSettings

class SettingsRepository private constructor(context: Context) {
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

    fun load(): GitHubSyncSettings {
        migrateLegacyDefaultsIfNeeded()
        return GitHubSyncSettings(
            owner = publicPrefs.getString(KEY_OWNER, GitHubSyncSettings.DEFAULT_OWNER).orEmpty().ifBlank { GitHubSyncSettings.DEFAULT_OWNER },
            repo = publicPrefs.getString(KEY_REPO, GitHubSyncSettings.DEFAULT_REPO).orEmpty().ifBlank { GitHubSyncSettings.DEFAULT_REPO },
            branch = publicPrefs.getString(KEY_BRANCH, GitHubSyncSettings.DEFAULT_BRANCH).orEmpty().ifBlank { GitHubSyncSettings.DEFAULT_BRANCH },
            rulesPath = publicPrefs.getString(KEY_RULES_PATH, GitHubSyncSettings.DEFAULT_RULES_PATH).orEmpty().ifBlank { GitHubSyncSettings.DEFAULT_RULES_PATH },
            reportPath = publicPrefs.getString(KEY_REPORT_PATH, GitHubSyncSettings.DEFAULT_REPORT_PATH).orEmpty().ifBlank { GitHubSyncSettings.DEFAULT_REPORT_PATH },
            token = securePrefs.getString(KEY_TOKEN, "").orEmpty(),
        )
    }

    fun save(settings: GitHubSyncSettings): Boolean {
        val normalized = settings.normalized()
        val publicSaved = publicPrefs.edit()
            .putString(KEY_OWNER, normalized.owner)
            .putString(KEY_REPO, normalized.repo)
            .putString(KEY_BRANCH, normalized.branch)
            .putString(KEY_RULES_PATH, normalized.rulesPath)
            .putString(KEY_REPORT_PATH, normalized.reportPath)
            .commit()
        val secureSaved = securePrefs.edit()
            .putString(KEY_TOKEN, normalized.token)
            .commit()
        return publicSaved && secureSaved
    }

    private fun migrateLegacyDefaultsIfNeeded() {
        if (publicPrefs.getBoolean(KEY_DEFAULTS_MIGRATED, false)) return
        val owner = publicPrefs.getString(KEY_OWNER, GitHubSyncSettings.DEFAULT_OWNER).orEmpty().ifBlank { GitHubSyncSettings.DEFAULT_OWNER }
        val repo = publicPrefs.getString(KEY_REPO, GitHubSyncSettings.DEFAULT_REPO).orEmpty().ifBlank { GitHubSyncSettings.DEFAULT_REPO }
        val branch = publicPrefs.getString(KEY_BRANCH, "").orEmpty()
        val shouldReplaceLegacyMain = owner == GitHubSyncSettings.DEFAULT_OWNER &&
            repo == GitHubSyncSettings.DEFAULT_REPO &&
            (branch.isBlank() || branch == LEGACY_DEFAULT_BRANCH)

        publicPrefs.edit().apply {
            if (shouldReplaceLegacyMain) {
                putString(KEY_BRANCH, GitHubSyncSettings.DEFAULT_BRANCH)
            }
            if (publicPrefs.getString(KEY_RULES_PATH, "").orEmpty().isBlank()) {
                putString(KEY_RULES_PATH, GitHubSyncSettings.DEFAULT_RULES_PATH)
            }
            if (publicPrefs.getString(KEY_REPORT_PATH, "").orEmpty().isBlank()) {
                putString(KEY_REPORT_PATH, GitHubSyncSettings.DEFAULT_REPORT_PATH)
            }
            putBoolean(KEY_DEFAULTS_MIGRATED, true)
        }.commit()
    }

    companion object {
        private const val LEGACY_DEFAULT_BRANCH = "main"
        private const val PREFS_NAME = "github_sync_settings"
        private const val SECURE_PREFS_NAME = "github_sync_secure"
        private const val KEY_OWNER = "owner"
        private const val KEY_REPO = "repo"
        private const val KEY_BRANCH = "branch"
        private const val KEY_TOKEN = "token"
        private const val KEY_RULES_PATH = "rules_path"
        private const val KEY_REPORT_PATH = "report_path"
        private const val KEY_DEFAULTS_MIGRATED = "defaults_migrated"

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
        }
    }
}

typealias GitHubSettingsStore = SettingsRepository
