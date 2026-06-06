package com.cryptotradecoach.domain

enum class BtcRegime {
    BULL,
    NEUTRAL,
    BEAR,
    CRASH;

    fun isRisky(): Boolean = this == BEAR || this == CRASH
}

object BtcRegimeDetector {
    fun detect(btcChange24h: Double, btcChange1h: Double = 0.0): BtcRegime {
        val shortTermDrop = btcChange1h < -2.0
        return when {
            btcChange24h >= 2.0 && !shortTermDrop -> BtcRegime.BULL
            btcChange24h >= -2.0 && !shortTermDrop -> BtcRegime.NEUTRAL
            btcChange24h >= -5.0 -> BtcRegime.BEAR
            else -> BtcRegime.CRASH
        }
    }

    fun minimumScoreDelta(regime: BtcRegime): Double = when (regime) {
        BtcRegime.BULL -> 0.0
        BtcRegime.NEUTRAL -> 0.0
        BtcRegime.BEAR -> 15.0
        BtcRegime.CRASH -> 25.0
    }

    fun isStrategyAllowed(strategyTypeName: String, regime: BtcRegime): Boolean = when (regime) {
        BtcRegime.BULL,
        BtcRegime.NEUTRAL -> true
        BtcRegime.BEAR -> strategyTypeName in setOf(
            "BEAR_DECOUPLING_BOUNCE",
            "BTC_SHORT_REGIME",
            "SWEEP_RECLAIM",
        )
        BtcRegime.CRASH -> strategyTypeName == "BTC_SHORT_REGIME"
    }
}
