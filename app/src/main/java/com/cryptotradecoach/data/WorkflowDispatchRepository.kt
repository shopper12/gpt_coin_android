package com.cryptotradecoach.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WorkflowDispatchRepository(context: Context) {
    private val settingsRepository = SettingsRepository.getInstance(context)
    private val client = GitHubSyncClient()

    suspend fun dispatchUnifiedStrategyMonitor(): String = withContext(Dispatchers.IO) {
        val settings = settingsRepository.load()
        client.dispatchWorkflow(
            settings = settings,
            workflowFile = UNIFIED_WORKFLOW_FILE,
            inputs = mapOf(
                "coin_days" to "30",
                "coin_max_markets" to "40",
                "stock_max_symbols" to "25",
                "apply_coin_rules" to "true",
                "apply_stock_rules" to "true",
            ),
        )
        "코인·주식 자가검증과 룰 진화 실행을 요청했습니다."
    }

    suspend fun dispatchRecommendationPriceRefresh(): String = withContext(Dispatchers.IO) {
        val settings = settingsRepository.load()
        client.dispatchWorkflow(
            settings = settings,
            workflowFile = RECOMMENDATION_PRICE_WORKFLOW_FILE,
        )
        "추천 이력 현재가·오늘 등락률 갱신을 요청했습니다. 완료 후 다시 불러오세요."
    }

    private companion object {
        const val UNIFIED_WORKFLOW_FILE = "unified-strategy-monitor.yml"
        const val RECOMMENDATION_PRICE_WORKFLOW_FILE = "update-recommendation-history-prices.yml"
    }
}
