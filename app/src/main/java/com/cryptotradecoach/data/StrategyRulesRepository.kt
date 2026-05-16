package com.cryptotradecoach.data

import android.content.Context
import android.util.Log
import com.cryptotradecoach.service.ScannerStateStore
import java.io.File

class StrategyRulesRepository private constructor(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val gitHubSyncClient: GitHubSyncClient,
) {
    private val rulesFile: File
        get() = File(context.filesDir, "rules/strategy-rules.json")

    fun loadLastKnownGood(): StrategyRules {
        val saved = runCatching {
            if (rulesFile.exists()) StrategyRules.fromJson(rulesFile.readText()) else null
        }.getOrNull()
        return saved ?: StrategyRules.DEFAULT.also { persistLastKnownGood(it) }
    }

    fun refreshFromGitHub(): StrategyRules {
        val current = loadLastKnownGood()
        val settings = settingsRepository.load().normalized()
        if (!settings.isConfigured) return current
        if (settings.token.isBlank()) {
            ScannerStateStore.setLastError("GitHub token is missing")
            return current
        }
        val downloaded = runCatching {
            gitHubSyncClient.downloadText(settings, settings.rulesPath)
                ?.let { StrategyRules.fromJson(it) }
        }.onFailure { error ->
            val message = if (error is GitHubSyncException) {
                "Rules download failed at ${error.syncPoint}: HTTP ${error.statusCode}; endpoint=${error.endpoint}; branch=${error.branch}; path=${error.path}"
            } else {
                "Rules download failed at ${settings.rulesPath}: ${error::class.java.simpleName}"
            }
            ScannerStateStore.setLastError(message)
            Log.w(TAG, "$message; keeping last-known-good rules.")
        }.getOrNull()
        return if (downloaded != null) {
            persistLastKnownGood(downloaded)
            downloaded
        } else {
            current
        }
    }

    private fun persistLastKnownGood(rules: StrategyRules) {
        runCatching {
            rulesFile.parentFile?.mkdirs()
            rulesFile.writeText(rules.toJson().toString(2))
        }.onFailure {
            Log.w(TAG, "Failed to persist last-known-good rules.")
        }
    }

    companion object {
        private const val TAG = "StrategyRulesRepo"

        @Volatile
        private var instance: StrategyRulesRepository? = null

        fun getInstance(context: Context): StrategyRulesRepository {
            return instance ?: synchronized(this) {
                instance ?: StrategyRulesRepository(
                    context.applicationContext,
                    SettingsRepository.getInstance(context),
                    GitHubSyncClient(),
                ).also { instance = it }
            }
        }
    }
}
