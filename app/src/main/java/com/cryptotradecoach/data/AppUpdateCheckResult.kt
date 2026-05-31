package com.cryptotradecoach.data

data class AppUpdateCheckResult(
    val hasUpdate: Boolean,
    val currentVersionCode: Int,
    val latestVersionCode: Int,
    val latestVersionName: String,
    val assetName: String,
)
