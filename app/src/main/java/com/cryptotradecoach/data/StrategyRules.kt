package com.cryptotradecoach.data

import org.json.JSONObject
import kotlin.math.max

data class StrategyRules(
    val version: String,
    val minimumScore: Double,
    val maxResults: Int,
    val validForMinutes: Int,
    val entryBandPct: Double,
    val candidateSelection: CandidateSelectionRules,
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
            .put("candidateSelection", candidateSelection.toJson())
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
            version = "v5-locked-profit-priority",
            minimumScore = 74.0,
            maxResults = 3,
            validForMinutes = 240,
            entryBandPct = 0.10,
            candidateSelection = CandidateSelectionRules(
                maxCandleTargets = 55,
                topTradeValueCount = 70,
                topChangeRateCount = 35,
                volumeBuildupCount = 35,
                quietAccumulationCount = 25,
                medianTradeValueMultiplier = 1.05,
                minBuildupChangeRatePct = -2.0,
                maxBuildupChangeRatePct = 7.5,
                maxQuietAbsChangeRatePct = 1.8,
            ),
            compressionBreakout = CompressionBreakoutRules(
                enabled = true,
                rangeCompressionRatio = 0.72,
                maxDistanceTo15mHighPct = 1.25,
                minVolumeAcceleration = 1.48,
                minFiveMinuteVolumeRatio = 1.35,
            ),
            sweepReclaim = SweepReclaimRules(
                enabled = true,
                fiveMinuteLookback = 18,
                fifteenMinuteLookback = 10,
                requireVolumeAboveAverage = true,
            ),
            trendPullback = TrendPullbackRules(
                enabled = true,
                higherTimeframeMaPeriod = 80,
                fifteenMinuteMaPeriod = 20,
                min15mMaMultiplier = 1.000,
                minPriorLowMultiplier = 0.990,
                pullbackLookback = 10,
                reclaimLookback = 5,
            ),
            bearDecouplingBounce = BearDecouplingBounceRules(
                enabled = true,
                btcWeakBelowPct = -0.7,
                altStrongAbovePct = 1.8,
                maxTradeValueRank = 35,
                minFourHourVolumeMultiple = 1.9,
                maxPreviousFourHourVolumeMultiple = 3.4,
                maxPriceOver240mMa20Pct = 6.5,
                maxBearishUpperWickPct = 55.0,
                decouplingScoreCap = 24.0,
                wickPenalty = 12.0,
            ),
            prePumpRotation = PrePumpRotationRules(
                enabled = true,
                minChange24hPct = -2.5,
                maxChange24hPct = 9.0,
                maxChange30mPct = 3.2,
                maxChange5mPct = 1.8,
                maxTradeValueRank = 55,
                maxChangeRank = 45,
                minRotation30mPct = 0.45,
                minVolumeAcceleration = 1.38,
                minFiveMinuteVolumeRatio = 1.40,
                minFifteenMinuteVolumeRatio = 1.25,
                maxRangePct = 5.2,
                minRangePosition = 0.58,
                minHighProximityMultiplier = 0.990,
                minCloseStairCount = 2,
            ),
            scoring = ScoringRules(
                overheat24hBasePct = 9.0,
                overheat24hWeight = 2.0,
                overheat30mBasePct = 3.0,
                overheat30mWeight = 3.0,
                overheat5mBasePct = 1.5,
                overheat5mWeight = 7.0,
                overheatMax = 50.0,
                hardBlockBtc24hBelowPct = -4.0,
                hardBlock30mPumpPct = 4.8,
                hardBlock5mPumpPct = 2.2,
                hardBlockRedUpperWickPct = 55.0,
            ),
            risk = RiskRules(
                defaultStopMultiplier = 0.986,
                minimumRiskPct = 0.32,
                minimumExpectedReturnPct = 0.90,
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
                candidateSelection = CandidateSelectionRules.fromJson(root.optJSONObject("candidateSelection")),
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

data class CandidateSelectionRules(
    val maxCandleTargets: Int,
    val topTradeValueCount: Int,
    val topChangeRateCount: Int,
    val volumeBuildupCount: Int,
    val quietAccumulationCount: Int,
    val medianTradeValueMultiplier: Double,
    val minBuildupChangeRatePct: Double,
    val maxBuildupChangeRatePct: Double,
    val maxQuietAbsChangeRatePct: Double,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("maxCandleTargets", maxCandleTargets)
        .put("topTradeValueCount", topTradeValueCount)
        .put("topChangeRateCount", topChangeRateCount)
        .put("volumeBuildupCount", volumeBuildupCount)
        .put("quietAccumulationCount", quietAccumulationCount)
        .put("medianTradeValueMultiplier", medianTradeValueMultiplier)
        .put("minBuildupChangeRatePct", minBuildupChangeRatePct)
        .put("maxBuildupChangeRatePct", maxBuildupChangeRatePct)
        .put("maxQuietAbsChangeRatePct", maxQuietAbsChangeRatePct)

    companion object {
        fun fromJson(json: JSONObject?): CandidateSelectionRules {
            val d = StrategyRules.DEFAULT.candidateSelection
            return CandidateSelectionRules(
                maxCandleTargets = json?.optInt("maxCandleTargets", d.maxCandleTargets)?.coerceIn(10, 80) ?: d.maxCandleTargets,
                topTradeValueCount = json?.optInt("topTradeValueCount", d.topTradeValueCount)?.coerceIn(10, 100) ?: d.topTradeValueCount,
                topChangeRateCount = json?.optInt("topChangeRateCount", d.topChangeRateCount)?.coerceIn(5, 80) ?: d.topChangeRateCount,
                volumeBuildupCount = json?.optInt("volumeBuildupCount", d.volumeBuildupCount)?.coerceIn(0, 80) ?: d.volumeBuildupCount,
                quietAccumulationCount = json?.optInt("quietAccumulationCount", d.quietAccumulationCount)?.coerceIn(0, 80) ?: d.quietAccumulationCount,
                medianTradeValueMultiplier = json?.optDouble("medianTradeValueMultiplier", d.medianTradeValueMultiplier)?.coerceIn(0.5, 3.0) ?: d.medianTradeValueMultiplier,
                minBuildupChangeRatePct = json?.optDouble("minBuildupChangeRatePct", d.minBuildupChangeRatePct)?.coerceIn(-10.0, 5.0) ?: d.minBuildupChangeRatePct,
                maxBuildupChangeRatePct = json?.optDouble("maxBuildupChangeRatePct", d.maxBuildupChangeRatePct)?.coerceIn(0.0, 15.0) ?: d.maxBuildupChangeRatePct,
                maxQuietAbsChangeRatePct = json?.optDouble("maxQuietAbsChangeRatePct", d.maxQuietAbsChangeRatePct)?.coerceIn(0.1, 5.0) ?: d.maxQuietAbsChangeRatePct,
            )
        }
    }
}

data class CompressionBreakoutRules(val enabled: Boolean, val rangeCompressionRatio: Double, val maxDistanceTo15mHighPct: Double, val minVolumeAcceleration: Double, val minFiveMinuteVolumeRatio: Double) {
    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("rangeCompressionRatio", rangeCompressionRatio).put("maxDistanceTo15mHighPct", maxDistanceTo15mHighPct).put("minVolumeAcceleration", minVolumeAcceleration).put("minFiveMinuteVolumeRatio", minFiveMinuteVolumeRatio)
    companion object { fun fromJson(json: JSONObject?): CompressionBreakoutRules { val d = StrategyRules.DEFAULT.compressionBreakout; return CompressionBreakoutRules(json?.optBoolean("enabled", d.enabled) ?: d.enabled, json?.optDouble("rangeCompressionRatio", d.rangeCompressionRatio) ?: d.rangeCompressionRatio, json?.optDouble("maxDistanceTo15mHighPct", d.maxDistanceTo15mHighPct) ?: d.maxDistanceTo15mHighPct, json?.optDouble("minVolumeAcceleration", d.minVolumeAcceleration) ?: d.minVolumeAcceleration, json?.optDouble("minFiveMinuteVolumeRatio", d.minFiveMinuteVolumeRatio) ?: d.minFiveMinuteVolumeRatio) } }
}

data class SweepReclaimRules(val enabled: Boolean, val fiveMinuteLookback: Int, val fifteenMinuteLookback: Int, val requireVolumeAboveAverage: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("fiveMinuteLookback", fiveMinuteLookback).put("fifteenMinuteLookback", fifteenMinuteLookback).put("requireVolumeAboveAverage", requireVolumeAboveAverage)
    companion object { fun fromJson(json: JSONObject?): SweepReclaimRules { val d = StrategyRules.DEFAULT.sweepReclaim; return SweepReclaimRules(json?.optBoolean("enabled", d.enabled) ?: d.enabled, json?.optInt("fiveMinuteLookback", d.fiveMinuteLookback) ?: d.fiveMinuteLookback, json?.optInt("fifteenMinuteLookback", d.fifteenMinuteLookback) ?: d.fifteenMinuteLookback, json?.optBoolean("requireVolumeAboveAverage", d.requireVolumeAboveAverage) ?: d.requireVolumeAboveAverage) } }
}

data class TrendPullbackRules(val enabled: Boolean, val higherTimeframeMaPeriod: Int, val fifteenMinuteMaPeriod: Int, val min15mMaMultiplier: Double, val minPriorLowMultiplier: Double, val pullbackLookback: Int, val reclaimLookback: Int) {
    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("higherTimeframeMaPeriod", higherTimeframeMaPeriod).put("fifteenMinuteMaPeriod", fifteenMinuteMaPeriod).put("min15mMaMultiplier", min15mMaMultiplier).put("minPriorLowMultiplier", minPriorLowMultiplier).put("pullbackLookback", pullbackLookback).put("reclaimLookback", reclaimLookback)
    companion object { fun fromJson(json: JSONObject?): TrendPullbackRules { val d = StrategyRules.DEFAULT.trendPullback; return TrendPullbackRules(json?.optBoolean("enabled", d.enabled) ?: d.enabled, json?.optInt("higherTimeframeMaPeriod", d.higherTimeframeMaPeriod) ?: d.higherTimeframeMaPeriod, json?.optInt("fifteenMinuteMaPeriod", d.fifteenMinuteMaPeriod) ?: d.fifteenMinuteMaPeriod, json?.optDouble("min15mMaMultiplier", d.min15mMaMultiplier) ?: d.min15mMaMultiplier, json?.optDouble("minPriorLowMultiplier", d.minPriorLowMultiplier) ?: d.minPriorLowMultiplier, json?.optInt("pullbackLookback", d.pullbackLookback) ?: d.pullbackLookback, json?.optInt("reclaimLookback", d.reclaimLookback) ?: d.reclaimLookback) } }
}

data class BearDecouplingBounceRules(val enabled: Boolean, val btcWeakBelowPct: Double, val altStrongAbovePct: Double, val maxTradeValueRank: Int, val minFourHourVolumeMultiple: Double, val maxPreviousFourHourVolumeMultiple: Double, val maxPriceOver240mMa20Pct: Double, val maxBearishUpperWickPct: Double, val decouplingScoreCap: Double, val wickPenalty: Double) {
    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("btcWeakBelowPct", btcWeakBelowPct).put("altStrongAbovePct", altStrongAbovePct).put("maxTradeValueRank", maxTradeValueRank).put("minFourHourVolumeMultiple", minFourHourVolumeMultiple).put("maxPreviousFourHourVolumeMultiple", maxPreviousFourHourVolumeMultiple).put("maxPriceOver240mMa20Pct", maxPriceOver240mMa20Pct).put("maxBearishUpperWickPct", maxBearishUpperWickPct).put("decouplingScoreCap", decouplingScoreCap).put("wickPenalty", wickPenalty)
    companion object { fun fromJson(json: JSONObject?): BearDecouplingBounceRules { val d = StrategyRules.DEFAULT.bearDecouplingBounce; return BearDecouplingBounceRules(json?.optBoolean("enabled", d.enabled) ?: d.enabled, json?.optDouble("btcWeakBelowPct", d.btcWeakBelowPct) ?: d.btcWeakBelowPct, json?.optDouble("altStrongAbovePct", d.altStrongAbovePct) ?: d.altStrongAbovePct, json?.optInt("maxTradeValueRank", d.maxTradeValueRank) ?: d.maxTradeValueRank, json?.optDouble("minFourHourVolumeMultiple", d.minFourHourVolumeMultiple) ?: d.minFourHourVolumeMultiple, json?.optDouble("maxPreviousFourHourVolumeMultiple", d.maxPreviousFourHourVolumeMultiple) ?: d.maxPreviousFourHourVolumeMultiple, json?.optDouble("maxPriceOver240mMa20Pct", d.maxPriceOver240mMa20Pct) ?: d.maxPriceOver240mMa20Pct, json?.optDouble("maxBearishUpperWickPct", d.maxBearishUpperWickPct) ?: d.maxBearishUpperWickPct, json?.optDouble("decouplingScoreCap", d.decouplingScoreCap) ?: d.decouplingScoreCap, json?.optDouble("wickPenalty", d.wickPenalty) ?: d.wickPenalty) } }
}

data class PrePumpRotationRules(val enabled: Boolean, val minChange24hPct: Double, val maxChange24hPct: Double, val maxChange30mPct: Double, val maxChange5mPct: Double, val maxTradeValueRank: Int, val maxChangeRank: Int, val minRotation30mPct: Double, val minVolumeAcceleration: Double, val minFiveMinuteVolumeRatio: Double, val minFifteenMinuteVolumeRatio: Double, val maxRangePct: Double, val minRangePosition: Double, val minHighProximityMultiplier: Double, val minCloseStairCount: Int) {
    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("minChange24hPct", minChange24hPct).put("maxChange24hPct", maxChange24hPct).put("maxChange30mPct", maxChange30mPct).put("maxChange5mPct", maxChange5mPct).put("maxTradeValueRank", maxTradeValueRank).put("maxChangeRank", maxChangeRank).put("minRotation30mPct", minRotation30mPct).put("minVolumeAcceleration", minVolumeAcceleration).put("minFiveMinuteVolumeRatio", minFiveMinuteVolumeRatio).put("minFifteenMinuteVolumeRatio", minFifteenMinuteVolumeRatio).put("maxRangePct", maxRangePct).put("minRangePosition", minRangePosition).put("minHighProximityMultiplier", minHighProximityMultiplier).put("minCloseStairCount", minCloseStairCount)
    companion object { fun fromJson(json: JSONObject?): PrePumpRotationRules { val d = StrategyRules.DEFAULT.prePumpRotation; return PrePumpRotationRules(json?.optBoolean("enabled", d.enabled) ?: d.enabled, json?.optDouble("minChange24hPct", d.minChange24hPct) ?: d.minChange24hPct, json?.optDouble("maxChange24hPct", d.maxChange24hPct) ?: d.maxChange24hPct, json?.optDouble("maxChange30mPct", d.maxChange30mPct) ?: d.maxChange30mPct, json?.optDouble("maxChange5mPct", d.maxChange5mPct) ?: d.maxChange5mPct, json?.optInt("maxTradeValueRank", d.maxTradeValueRank) ?: d.maxTradeValueRank, json?.optInt("maxChangeRank", d.maxChangeRank) ?: d.maxChangeRank, json?.optDouble("minRotation30mPct", d.minRotation30mPct) ?: d.minRotation30mPct, json?.optDouble("minVolumeAcceleration", d.minVolumeAcceleration) ?: d.minVolumeAcceleration, json?.optDouble("minFiveMinuteVolumeRatio", d.minFiveMinuteVolumeRatio) ?: d.minFiveMinuteVolumeRatio, json?.optDouble("minFifteenMinuteVolumeRatio", d.minFifteenMinuteVolumeRatio) ?: d.minFifteenMinuteVolumeRatio, json?.optDouble("maxRangePct", d.maxRangePct) ?: d.maxRangePct, json?.optDouble("minRangePosition", d.minRangePosition) ?: d.minRangePosition, json?.optDouble("minHighProximityMultiplier", d.minHighProximityMultiplier) ?: d.minHighProximityMultiplier, json?.optInt("minCloseStairCount", d.minCloseStairCount) ?: d.minCloseStairCount) } }
}

data class ScoringRules(val overheat24hBasePct: Double, val overheat24hWeight: Double, val overheat30mBasePct: Double, val overheat30mWeight: Double, val overheat5mBasePct: Double, val overheat5mWeight: Double, val overheatMax: Double, val hardBlockBtc24hBelowPct: Double, val hardBlock30mPumpPct: Double, val hardBlock5mPumpPct: Double, val hardBlockRedUpperWickPct: Double) {
    fun toJson(): JSONObject = JSONObject().put("overheat24hBasePct", overheat24hBasePct).put("overheat24hWeight", overheat24hWeight).put("overheat30mBasePct", overheat30mBasePct).put("overheat30mWeight", overheat30mWeight).put("overheat5mBasePct", overheat5mBasePct).put("overheat5mWeight", overheat5mWeight).put("overheatMax", overheatMax).put("hardBlockBtc24hBelowPct", hardBlockBtc24hBelowPct).put("hardBlock30mPumpPct", hardBlock30mPumpPct).put("hardBlock5mPumpPct", hardBlock5mPumpPct).put("hardBlockRedUpperWickPct", hardBlockRedUpperWickPct)
    companion object { fun fromJson(json: JSONObject?): ScoringRules { val d = StrategyRules.DEFAULT.scoring; return ScoringRules(json?.optDouble("overheat24hBasePct", d.overheat24hBasePct) ?: d.overheat24hBasePct, json?.optDouble("overheat24hWeight", d.overheat24hWeight) ?: d.overheat24hWeight, json?.optDouble("overheat30mBasePct", d.overheat30mBasePct) ?: d.overheat30mBasePct, json?.optDouble("overheat30mWeight", d.overheat30mWeight) ?: d.overheat30mWeight, json?.optDouble("overheat5mBasePct", d.overheat5mBasePct) ?: d.overheat5mBasePct, json?.optDouble("overheat5mWeight", d.overheat5mWeight) ?: d.overheat5mWeight, json?.optDouble("overheatMax", d.overheatMax) ?: d.overheatMax, json?.optDouble("hardBlockBtc24hBelowPct", d.hardBlockBtc24hBelowPct) ?: d.hardBlockBtc24hBelowPct, json?.optDouble("hardBlock30mPumpPct", d.hardBlock30mPumpPct) ?: d.hardBlock30mPumpPct, json?.optDouble("hardBlock5mPumpPct", d.hardBlock5mPumpPct) ?: d.hardBlock5mPumpPct, json?.optDouble("hardBlockRedUpperWickPct", d.hardBlockRedUpperWickPct) ?: d.hardBlockRedUpperWickPct) } }
}

data class RiskRules(val defaultStopMultiplier: Double, val minimumRiskPct: Double, val minimumExpectedReturnPct: Double) {
    fun toJson(): JSONObject = JSONObject().put("defaultStopMultiplier", defaultStopMultiplier).put("minimumRiskPct", minimumRiskPct).put("minimumExpectedReturnPct", minimumExpectedReturnPct)
    companion object { fun fromJson(json: JSONObject?): RiskRules { val d = StrategyRules.DEFAULT.risk; return RiskRules(json?.optDouble("defaultStopMultiplier", d.defaultStopMultiplier) ?: d.defaultStopMultiplier, json?.optDouble("minimumRiskPct", d.minimumRiskPct) ?: d.minimumRiskPct, json?.optDouble("minimumExpectedReturnPct", d.minimumExpectedReturnPct) ?: d.minimumExpectedReturnPct) } }
}
