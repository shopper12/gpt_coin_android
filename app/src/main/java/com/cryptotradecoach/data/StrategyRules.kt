package com.cryptotradecoach.data

import org.json.JSONObject
import kotlin.math.max

data class StrategyRules(
    val version: String,
    val minimumScore: Double,
    val maxResults: Int,
    val validForMinutes: Int,
    val entryBandPct: Double,
    val compressionBreakout: CompressionBreakoutRules,
    val sweepReclaim: SweepReclaimRules,
    val trendPullback: TrendPullbackRules,
    val bearDecouplingBounce: BearDecouplingBounceRules,
    val prePumpRotation: PrePumpRotationRules,
    val scoring: ScoringRules,
    val risk: RiskRules,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("version", version)
            .put("minimumScore", minimumScore)
            .put("maxResults", maxResults)
            .put("validForMinutes", validForMinutes)
            .put("entryBandPct", entryBandPct)
            .put("compressionBreakout", compressionBreakout.toJson())
            .put("sweepReclaim", sweepReclaim.toJson())
            .put("trendPullback", trendPullback.toJson())
            .put("bearDecouplingBounce", bearDecouplingBounce.toJson())
            .put("prePumpRotation", prePumpRotation.toJson())
            .put("scoring", scoring.toJson())
            .put("risk", risk.toJson())
    }

    companion object {
        val DEFAULT = StrategyRules(
            version = "v3-conservative-filters",
            minimumScore = 72.0,
            maxResults = 3,
            validForMinutes = 20,
            entryBandPct = 0.1,
            compressionBreakout = CompressionBreakoutRules(
                enabled = true,
                rangeCompressionRatio = 0.75,
                maxDistanceTo15mHighPct = 1.5,
                minVolumeAcceleration = 1.5,
                minFiveMinuteVolumeRatio = 1.3,
            ),
            sweepReclaim = SweepReclaimRules(
                enabled = true,
                fiveMinuteLookback = 12,
                fifteenMinuteLookback = 8,
                requireVolumeAboveAverage = true,
            ),
            trendPullback = TrendPullbackRules(
                enabled = false,
                higherTimeframeMaPeriod = 120,
                fifteenMinuteMaPeriod = 20,
                min15mMaMultiplier = 0.997,
                minPriorLowMultiplier = 0.99,
                pullbackLookback = 10,
                reclaimLookback = 6,
            ),
            bearDecouplingBounce = BearDecouplingBounceRules(
                enabled = false,
                btcWeakBelowPct = -1.0,
                altStrongAbovePct = 2.0,
                maxTradeValueRank = 30,
                minFourHourVolumeMultiple = 2.5,
                maxPreviousFourHourVolumeMultiple = 3.0,
                maxPriceOver240mMa20Pct = 5.0,
                maxBearishUpperWickPct = 55.0,
                decouplingScoreCap = 24.0,
                wickPenalty = 12.0,
            ),
            prePumpRotation = PrePumpRotationRules(
                enabled = true,
                minChange24hPct = -4.0,
                maxChange24hPct = 8.5,
                maxChange30mPct = 3.2,
                maxChange5mPct = 1.8,
                maxTradeValueRank = 25,
                maxChangeRank = 35,
                minRotation30mPct = 0.7,
                minVolumeAcceleration = 1.45,
                minFiveMinuteVolumeRatio = 1.6,
                minFifteenMinuteVolumeRatio = 1.35,
                maxRangePct = 4.2,
                minRangePosition = 0.55,
                minHighProximityMultiplier = 0.992,
                minCloseStairCount = 2,
            ),
            scoring = ScoringRules(
                overheat24hBasePct = 10.0,
                overheat24hWeight = 1.8,
                overheat30mBasePct = 2.8,
                overheat30mWeight = 3.0,
                overheat5mBasePct = 1.2,
                overheat5mWeight = 8.0,
                overheatMax = 45.0,
                hardBlockBtc24hBelowPct = -4.0,
                hardBlock30mPumpPct = 5.0,
                hardBlock5mPumpPct = 2.2,
                hardBlockRedUpperWickPct = 55.0,
            ),
            risk = RiskRules(
                defaultStopMultiplier = 0.990,
                minimumRiskPct = 0.15,
                minimumExpectedReturnPct = 0.2,
            ),
        )

        fun fromJson(text: String): StrategyRules {
            val root = JSONObject(text)
            return StrategyRules(
                version = root.optString("version", DEFAULT.version).ifBlank { DEFAULT.version },
                minimumScore = root.optDouble("minimumScore", DEFAULT.minimumScore).coerceIn(0.0, 100.0),
                maxResults = root.optInt("maxResults", DEFAULT.maxResults).coerceIn(1, 20),
                validForMinutes = max(1, root.optInt("validForMinutes", DEFAULT.validForMinutes)),
                entryBandPct = root.optDouble("entryBandPct", DEFAULT.entryBandPct).coerceIn(0.0, 5.0),
                compressionBreakout = CompressionBreakoutRules.fromJson(root.optJSONObject("compressionBreakout")),
                sweepReclaim = SweepReclaimRules.fromJson(root.optJSONObject("sweepReclaim")),
                trendPullback = TrendPullbackRules.fromJson(root.optJSONObject("trendPullback")),
                bearDecouplingBounce = BearDecouplingBounceRules.fromJson(root.optJSONObject("bearDecouplingBounce")),
                prePumpRotation = PrePumpRotationRules.fromJson(root.optJSONObject("prePumpRotation")),
                scoring = ScoringRules.fromJson(root.optJSONObject("scoring")),
                risk = RiskRules.fromJson(root.optJSONObject("risk")),
            )
        }
    }
}

data class CompressionBreakoutRules(
    val enabled: Boolean,
    val rangeCompressionRatio: Double,
    val maxDistanceTo15mHighPct: Double,
    val minVolumeAcceleration: Double,
    val minFiveMinuteVolumeRatio: Double,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("rangeCompressionRatio", rangeCompressionRatio)
        .put("maxDistanceTo15mHighPct", maxDistanceTo15mHighPct)
        .put("minVolumeAcceleration", minVolumeAcceleration)
        .put("minFiveMinuteVolumeRatio", minFiveMinuteVolumeRatio)

    companion object {
        fun fromJson(json: JSONObject?): CompressionBreakoutRules {
            val d = StrategyRules.DEFAULT.compressionBreakout
            return CompressionBreakoutRules(
                enabled = json?.optBoolean("enabled", d.enabled) ?: d.enabled,
                rangeCompressionRatio = json?.optDouble("rangeCompressionRatio", d.rangeCompressionRatio) ?: d.rangeCompressionRatio,
                maxDistanceTo15mHighPct = json?.optDouble("maxDistanceTo15mHighPct", d.maxDistanceTo15mHighPct) ?: d.maxDistanceTo15mHighPct,
                minVolumeAcceleration = json?.optDouble("minVolumeAcceleration", d.minVolumeAcceleration) ?: d.minVolumeAcceleration,
                minFiveMinuteVolumeRatio = json?.optDouble("minFiveMinuteVolumeRatio", d.minFiveMinuteVolumeRatio) ?: d.minFiveMinuteVolumeRatio,
            )
        }
    }
}

data class SweepReclaimRules(
    val enabled: Boolean,
    val fiveMinuteLookback: Int,
    val fifteenMinuteLookback: Int,
    val requireVolumeAboveAverage: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("fiveMinuteLookback", fiveMinuteLookback)
        .put("fifteenMinuteLookback", fifteenMinuteLookback)
        .put("requireVolumeAboveAverage", requireVolumeAboveAverage)

    companion object {
        fun fromJson(json: JSONObject?): SweepReclaimRules {
            val d = StrategyRules.DEFAULT.sweepReclaim
            return SweepReclaimRules(
                enabled = json?.optBoolean("enabled", d.enabled) ?: d.enabled,
                fiveMinuteLookback = json?.optInt("fiveMinuteLookback", d.fiveMinuteLookback) ?: d.fiveMinuteLookback,
                fifteenMinuteLookback = json?.optInt("fifteenMinuteLookback", d.fifteenMinuteLookback) ?: d.fifteenMinuteLookback,
                requireVolumeAboveAverage = json?.optBoolean("requireVolumeAboveAverage", d.requireVolumeAboveAverage) ?: d.requireVolumeAboveAverage,
            )
        }
    }
}

data class TrendPullbackRules(
    val enabled: Boolean,
    val higherTimeframeMaPeriod: Int,
    val fifteenMinuteMaPeriod: Int,
    val min15mMaMultiplier: Double,
    val minPriorLowMultiplier: Double,
    val pullbackLookback: Int,
    val reclaimLookback: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("higherTimeframeMaPeriod", higherTimeframeMaPeriod)
        .put("fifteenMinuteMaPeriod", fifteenMinuteMaPeriod)
        .put("min15mMaMultiplier", min15mMaMultiplier)
        .put("minPriorLowMultiplier", minPriorLowMultiplier)
        .put("pullbackLookback", pullbackLookback)
        .put("reclaimLookback", reclaimLookback)

    companion object {
        fun fromJson(json: JSONObject?): TrendPullbackRules {
            val d = StrategyRules.DEFAULT.trendPullback
            return TrendPullbackRules(
                enabled = json?.optBoolean("enabled", d.enabled) ?: d.enabled,
                higherTimeframeMaPeriod = json?.optInt("higherTimeframeMaPeriod", d.higherTimeframeMaPeriod) ?: d.higherTimeframeMaPeriod,
                fifteenMinuteMaPeriod = json?.optInt("fifteenMinuteMaPeriod", d.fifteenMinuteMaPeriod) ?: d.fifteenMinuteMaPeriod,
                min15mMaMultiplier = json?.optDouble("min15mMaMultiplier", d.min15mMaMultiplier) ?: d.min15mMaMultiplier,
                minPriorLowMultiplier = json?.optDouble("minPriorLowMultiplier", d.minPriorLowMultiplier) ?: d.minPriorLowMultiplier,
                pullbackLookback = json?.optInt("pullbackLookback", d.pullbackLookback) ?: d.pullbackLookback,
                reclaimLookback = json?.optInt("reclaimLookback", d.reclaimLookback) ?: d.reclaimLookback,
            )
        }
    }
}

data class BearDecouplingBounceRules(
    val enabled: Boolean,
    val btcWeakBelowPct: Double,
    val altStrongAbovePct: Double,
    val maxTradeValueRank: Int,
    val minFourHourVolumeMultiple: Double,
    val maxPreviousFourHourVolumeMultiple: Double,
    val maxPriceOver240mMa20Pct: Double,
    val maxBearishUpperWickPct: Double,
    val decouplingScoreCap: Double,
    val wickPenalty: Double,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("btcWeakBelowPct", btcWeakBelowPct)
        .put("altStrongAbovePct", altStrongAbovePct)
        .put("maxTradeValueRank", maxTradeValueRank)
        .put("minFourHourVolumeMultiple", minFourHourVolumeMultiple)
        .put("maxPreviousFourHourVolumeMultiple", maxPreviousFourHourVolumeMultiple)
        .put("maxPriceOver240mMa20Pct", maxPriceOver240mMa20Pct)
        .put("maxBearishUpperWickPct", maxBearishUpperWickPct)
        .put("decouplingScoreCap", decouplingScoreCap)
        .put("wickPenalty", wickPenalty)

    companion object {
        fun fromJson(json: JSONObject?): BearDecouplingBounceRules {
            val d = StrategyRules.DEFAULT.bearDecouplingBounce
            return BearDecouplingBounceRules(
                enabled = json?.optBoolean("enabled", d.enabled) ?: d.enabled,
                btcWeakBelowPct = json?.optDouble("btcWeakBelowPct", d.btcWeakBelowPct) ?: d.btcWeakBelowPct,
                altStrongAbovePct = json?.optDouble("altStrongAbovePct", d.altStrongAbovePct) ?: d.altStrongAbovePct,
                maxTradeValueRank = json?.optInt("maxTradeValueRank", d.maxTradeValueRank) ?: d.maxTradeValueRank,
                minFourHourVolumeMultiple = json?.optDouble("minFourHourVolumeMultiple", d.minFourHourVolumeMultiple) ?: d.minFourHourVolumeMultiple,
                maxPreviousFourHourVolumeMultiple = json?.optDouble("maxPreviousFourHourVolumeMultiple", d.maxPreviousFourHourVolumeMultiple) ?: d.maxPreviousFourHourVolumeMultiple,
                maxPriceOver240mMa20Pct = json?.optDouble("maxPriceOver240mMa20Pct", d.maxPriceOver240mMa20Pct) ?: d.maxPriceOver240mMa20Pct,
                maxBearishUpperWickPct = json?.optDouble("maxBearishUpperWickPct", d.maxBearishUpperWickPct) ?: d.maxBearishUpperWickPct,
                decouplingScoreCap = json?.optDouble("decouplingScoreCap", d.decouplingScoreCap) ?: d.decouplingScoreCap,
                wickPenalty = json?.optDouble("wickPenalty", d.wickPenalty) ?: d.wickPenalty,
            )
        }
    }
}

data class PrePumpRotationRules(
    val enabled: Boolean,
    val minChange24hPct: Double,
    val maxChange24hPct: Double,
    val maxChange30mPct: Double,
    val maxChange5mPct: Double,
    val maxTradeValueRank: Int,
    val maxChangeRank: Int,
    val minRotation30mPct: Double,
    val minVolumeAcceleration: Double,
    val minFiveMinuteVolumeRatio: Double,
    val minFifteenMinuteVolumeRatio: Double,
    val maxRangePct: Double,
    val minRangePosition: Double,
    val minHighProximityMultiplier: Double,
    val minCloseStairCount: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("minChange24hPct", minChange24hPct)
        .put("maxChange24hPct", maxChange24hPct)
        .put("maxChange30mPct", maxChange30mPct)
        .put("maxChange5mPct", maxChange5mPct)
        .put("maxTradeValueRank", maxTradeValueRank)
        .put("maxChangeRank", maxChangeRank)
        .put("minRotation30mPct", minRotation30mPct)
        .put("minVolumeAcceleration", minVolumeAcceleration)
        .put("minFiveMinuteVolumeRatio", minFiveMinuteVolumeRatio)
        .put("minFifteenMinuteVolumeRatio", minFifteenMinuteVolumeRatio)
        .put("maxRangePct", maxRangePct)
        .put("minRangePosition", minRangePosition)
        .put("minHighProximityMultiplier", minHighProximityMultiplier)
        .put("minCloseStairCount", minCloseStairCount)

    companion object {
        fun fromJson(json: JSONObject?): PrePumpRotationRules {
            val d = StrategyRules.DEFAULT.prePumpRotation
            return PrePumpRotationRules(
                enabled = json?.optBoolean("enabled", d.enabled) ?: d.enabled,
                minChange24hPct = json?.optDouble("minChange24hPct", d.minChange24hPct) ?: d.minChange24hPct,
                maxChange24hPct = json?.optDouble("maxChange24hPct", d.maxChange24hPct) ?: d.maxChange24hPct,
                maxChange30mPct = json?.optDouble("maxChange30mPct", d.maxChange30mPct) ?: d.maxChange30mPct,
                maxChange5mPct = json?.optDouble("maxChange5mPct", d.maxChange5mPct) ?: d.maxChange5mPct,
                maxTradeValueRank = json?.optInt("maxTradeValueRank", d.maxTradeValueRank) ?: d.maxTradeValueRank,
                maxChangeRank = json?.optInt("maxChangeRank", d.maxChangeRank) ?: d.maxChangeRank,
                minRotation30mPct = json?.optDouble("minRotation30mPct", d.minRotation30mPct) ?: d.minRotation30mPct,
                minVolumeAcceleration = json?.optDouble("minVolumeAcceleration", d.minVolumeAcceleration) ?: d.minVolumeAcceleration,
                minFiveMinuteVolumeRatio = json?.optDouble("minFiveMinuteVolumeRatio", d.minFiveMinuteVolumeRatio) ?: d.minFiveMinuteVolumeRatio,
                minFifteenMinuteVolumeRatio = json?.optDouble("minFifteenMinuteVolumeRatio", d.minFifteenMinuteVolumeRatio) ?: d.minFifteenMinuteVolumeRatio,
                maxRangePct = json?.optDouble("maxRangePct", d.maxRangePct) ?: d.maxRangePct,
                minRangePosition = json?.optDouble("minRangePosition", d.minRangePosition) ?: d.minRangePosition,
                minHighProximityMultiplier = json?.optDouble("minHighProximityMultiplier", d.minHighProximityMultiplier) ?: d.minHighProximityMultiplier,
                minCloseStairCount = json?.optInt("minCloseStairCount", d.minCloseStairCount) ?: d.minCloseStairCount,
            )
        }
    }
}

data class ScoringRules(
    val overheat24hBasePct: Double,
    val overheat24hWeight: Double,
    val overheat30mBasePct: Double,
    val overheat30mWeight: Double,
    val overheat5mBasePct: Double,
    val overheat5mWeight: Double,
    val overheatMax: Double,
    val hardBlockBtc24hBelowPct: Double,
    val hardBlock30mPumpPct: Double,
    val hardBlock5mPumpPct: Double,
    val hardBlockRedUpperWickPct: Double,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("overheat24hBasePct", overheat24hBasePct)
        .put("overheat24hWeight", overheat24hWeight)
        .put("overheat30mBasePct", overheat30mBasePct)
        .put("overheat30mWeight", overheat30mWeight)
        .put("overheat5mBasePct", overheat5mBasePct)
        .put("overheat5mWeight", overheat5mWeight)
        .put("overheatMax", overheatMax)
        .put("hardBlockBtc24hBelowPct", hardBlockBtc24hBelowPct)
        .put("hardBlock30mPumpPct", hardBlock30mPumpPct)
        .put("hardBlock5mPumpPct", hardBlock5mPumpPct)
        .put("hardBlockRedUpperWickPct", hardBlockRedUpperWickPct)

    companion object {
        fun fromJson(json: JSONObject?): ScoringRules {
            val d = StrategyRules.DEFAULT.scoring
            return ScoringRules(
                overheat24hBasePct = json?.optDouble("overheat24hBasePct", d.overheat24hBasePct) ?: d.overheat24hBasePct,
                overheat24hWeight = json?.optDouble("overheat24hWeight", d.overheat24hWeight) ?: d.overheat24hWeight,
                overheat30mBasePct = json?.optDouble("overheat30mBasePct", d.overheat30mBasePct) ?: d.overheat30mBasePct,
                overheat30mWeight = json?.optDouble("overheat30mWeight", d.overheat30mWeight) ?: d.overheat30mWeight,
                overheat5mBasePct = json?.optDouble("overheat5mBasePct", d.overheat5mBasePct) ?: d.overheat5mBasePct,
                overheat5mWeight = json?.optDouble("overheat5mWeight", d.overheat5mWeight) ?: d.overheat5mWeight,
                overheatMax = json?.optDouble("overheatMax", d.overheatMax) ?: d.overheatMax,
                hardBlockBtc24hBelowPct = json?.optDouble("hardBlockBtc24hBelowPct", d.hardBlockBtc24hBelowPct) ?: d.hardBlockBtc24hBelowPct,
                hardBlock30mPumpPct = json?.optDouble("hardBlock30mPumpPct", d.hardBlock30mPumpPct) ?: d.hardBlock30mPumpPct,
                hardBlock5mPumpPct = json?.optDouble("hardBlock5mPumpPct", d.hardBlock5mPumpPct) ?: d.hardBlock5mPumpPct,
                hardBlockRedUpperWickPct = json?.optDouble("hardBlockRedUpperWickPct", d.hardBlockRedUpperWickPct) ?: d.hardBlockRedUpperWickPct,
            )
        }
    }
}

data class RiskRules(
    val defaultStopMultiplier: Double,
    val minimumRiskPct: Double,
    val minimumExpectedReturnPct: Double,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("defaultStopMultiplier", defaultStopMultiplier)
        .put("minimumRiskPct", minimumRiskPct)
        .put("minimumExpectedReturnPct", minimumExpectedReturnPct)

    companion object {
        fun fromJson(json: JSONObject?): RiskRules {
            val d = StrategyRules.DEFAULT.risk
            return RiskRules(
                defaultStopMultiplier = json?.optDouble("defaultStopMultiplier", d.defaultStopMultiplier) ?: d.defaultStopMultiplier,
                minimumRiskPct = json?.optDouble("minimumRiskPct", d.minimumRiskPct) ?: d.minimumRiskPct,
                minimumExpectedReturnPct = json?.optDouble("minimumExpectedReturnPct", d.minimumExpectedReturnPct) ?: d.minimumExpectedReturnPct,
            )
        }
    }
}