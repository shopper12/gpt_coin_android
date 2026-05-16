package com.cryptotradecoach.data

import org.json.JSONObject
import kotlin.math.max

data class StrategyRules(
    val version: String,
    val minimumScore: Double,
    val maxResults: Int,
    val validForMinutes: Int,
    val entryBandPct: Double,
    val momentumBreakout: MomentumBreakoutRules,
    val volumeExpansion: VolumeExpansionRules,
    val pullbackRebound: PullbackReboundRules,
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
            .put("momentumBreakout", momentumBreakout.toJson())
            .put("volumeExpansion", volumeExpansion.toJson())
            .put("pullbackRebound", pullbackRebound.toJson())
            .put("scoring", scoring.toJson())
            .put("risk", risk.toJson())
    }

    companion object {
        val DEFAULT = StrategyRules(
            version = "v1",
            minimumScore = 60.0,
            maxResults = 5,
            validForMinutes = 30,
            entryBandPct = 0.2,
            momentumBreakout = MomentumBreakoutRules(
                overheated24hPct = 20.0,
                overheatedVolumeAcceleration = 2.5,
                minChange30mPct = -0.1,
                minChange5mPct = -0.05,
                minSnapshotMomentumPct = -0.05,
                watchHigh15mDistancePct = 1.2,
                watchChange5mPct = 2.8,
                minNearHigh = 0.972,
                maxChangeRank = 10,
                minVolumeAcceleration = 1.2,
            ),
            volumeExpansion = VolumeExpansionRules(
                minVolumeAcceleration = 1.25,
                maxAbsChange24hPct = 12.0,
                minRangePct = 0.25,
                maxRangePct = 4.5,
            ),
            pullbackRebound = PullbackReboundRules(
                minChange24hPct = -6.0,
                maxChange24hPct = 1.0,
                minChange30mPct = -1.5,
                minChange5mPct = 0.0,
                high15mPriceMultiplier = 0.985,
                minVolumeAcceleration = 1.05,
            ),
            scoring = ScoringRules(
                change30mWeight = 5.0,
                change5mWeight = 6.0,
                snapshotMomentumWeight = 8.0,
                immediateMomentumMax = 25.0,
                volumeAccelerationBase = 1.0,
                volumeAccelerationWeight = 12.0,
                volumeAccelerationMax = 20.0,
                nearHighBase = 0.965,
                nearHighWeight = 420.0,
                nearHighMax = 18.0,
                entryProximityMax = 20.0,
                postSpikeDistanceBonusLimitPct = 0.8,
                postSpikeDistanceBonus = 2.0,
                rangePenaltyBasePct = 4.0,
                changeRankTopScore = 15.0,
                changeRankTopLimit = 10,
                changeRankMidScore = 10.0,
                changeRankMidLimit = 20,
                changeRankLowScore = 5.0,
                changeRankLowLimit = 40,
                riskRewardBase = 4.0,
                riskRewardRangeCapPct = 6.0,
                liquidityLogDivisor = 3.0,
                liquidityOffset = 4.5,
                overheat24hBasePct = 16.0,
                overheat24hWeight = 1.2,
                overheat30mBasePct = 4.0,
                overheat30mWeight = 2.0,
                overheat5mBasePct = 2.5,
                overheat5mWeight = 4.0,
                overheatMax = 25.0,
                staleSnapshotWarningMinutes = 3,
                staleSnapshotErrorMinutes = 10,
                staleSnapshotWarningPenalty = 6.0,
                staleSnapshotErrorPenalty = 15.0,
                postSpikeNearHighLimit = 0.975,
                postSpikeChange30mLimitPct = 1.5,
                postSpikeNearHighPenalty = 8.0,
                postSpikeChange5mBasePct = 1.8,
                postSpikeChange5mWeight = 7.0,
                postSpikeMax = 20.0,
            ),
            risk = RiskRules(
                volumeExpansionStopMultiplier = 0.992,
                defaultStopMultiplier = 0.990,
                wideRangeThresholdPct = 2.0,
                wideRangeTarget1Multiplier = 1.015,
                defaultTarget1Multiplier = 1.012,
                target2BaseMultiplier = 1.03,
                target2RangeDivisor = 200.0,
                target2RangeCap = 0.02,
                trailingStopMultiplier = 0.988,
                minimumRiskPct = 0.1,
                minimumExpectedReturnPct = 0.1,
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
                momentumBreakout = MomentumBreakoutRules.fromJson(root.optJSONObject("momentumBreakout")),
                volumeExpansion = VolumeExpansionRules.fromJson(root.optJSONObject("volumeExpansion")),
                pullbackRebound = PullbackReboundRules.fromJson(root.optJSONObject("pullbackRebound")),
                scoring = ScoringRules.fromJson(root.optJSONObject("scoring")),
                risk = RiskRules.fromJson(root.optJSONObject("risk")),
            )
        }
    }
}

data class MomentumBreakoutRules(
    val overheated24hPct: Double,
    val overheatedVolumeAcceleration: Double,
    val minChange30mPct: Double,
    val minChange5mPct: Double,
    val minSnapshotMomentumPct: Double,
    val watchHigh15mDistancePct: Double,
    val watchChange5mPct: Double,
    val minNearHigh: Double,
    val maxChangeRank: Int,
    val minVolumeAcceleration: Double,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("overheated24hPct", overheated24hPct)
        .put("overheatedVolumeAcceleration", overheatedVolumeAcceleration)
        .put("minChange30mPct", minChange30mPct)
        .put("minChange5mPct", minChange5mPct)
        .put("minSnapshotMomentumPct", minSnapshotMomentumPct)
        .put("watchHigh15mDistancePct", watchHigh15mDistancePct)
        .put("watchChange5mPct", watchChange5mPct)
        .put("minNearHigh", minNearHigh)
        .put("maxChangeRank", maxChangeRank)
        .put("minVolumeAcceleration", minVolumeAcceleration)

    companion object {
        fun fromJson(json: JSONObject?): MomentumBreakoutRules {
            val default = StrategyRules.DEFAULT.momentumBreakout
            return MomentumBreakoutRules(
                overheated24hPct = json?.optDouble("overheated24hPct", default.overheated24hPct) ?: default.overheated24hPct,
                overheatedVolumeAcceleration = json?.optDouble("overheatedVolumeAcceleration", default.overheatedVolumeAcceleration) ?: default.overheatedVolumeAcceleration,
                minChange30mPct = json?.optDouble("minChange30mPct", default.minChange30mPct) ?: default.minChange30mPct,
                minChange5mPct = json?.optDouble("minChange5mPct", default.minChange5mPct) ?: default.minChange5mPct,
                minSnapshotMomentumPct = json?.optDouble("minSnapshotMomentumPct", default.minSnapshotMomentumPct) ?: default.minSnapshotMomentumPct,
                watchHigh15mDistancePct = json?.optDouble("watchHigh15mDistancePct", default.watchHigh15mDistancePct) ?: default.watchHigh15mDistancePct,
                watchChange5mPct = json?.optDouble("watchChange5mPct", default.watchChange5mPct) ?: default.watchChange5mPct,
                minNearHigh = json?.optDouble("minNearHigh", default.minNearHigh) ?: default.minNearHigh,
                maxChangeRank = json?.optInt("maxChangeRank", default.maxChangeRank) ?: default.maxChangeRank,
                minVolumeAcceleration = json?.optDouble("minVolumeAcceleration", default.minVolumeAcceleration) ?: default.minVolumeAcceleration,
            )
        }
    }
}

data class VolumeExpansionRules(
    val minVolumeAcceleration: Double,
    val maxAbsChange24hPct: Double,
    val minRangePct: Double,
    val maxRangePct: Double,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("minVolumeAcceleration", minVolumeAcceleration)
        .put("maxAbsChange24hPct", maxAbsChange24hPct)
        .put("minRangePct", minRangePct)
        .put("maxRangePct", maxRangePct)

    companion object {
        fun fromJson(json: JSONObject?): VolumeExpansionRules {
            val default = StrategyRules.DEFAULT.volumeExpansion
            return VolumeExpansionRules(
                minVolumeAcceleration = json?.optDouble("minVolumeAcceleration", default.minVolumeAcceleration) ?: default.minVolumeAcceleration,
                maxAbsChange24hPct = json?.optDouble("maxAbsChange24hPct", default.maxAbsChange24hPct) ?: default.maxAbsChange24hPct,
                minRangePct = json?.optDouble("minRangePct", default.minRangePct) ?: default.minRangePct,
                maxRangePct = json?.optDouble("maxRangePct", default.maxRangePct) ?: default.maxRangePct,
            )
        }
    }
}

data class PullbackReboundRules(
    val minChange24hPct: Double,
    val maxChange24hPct: Double,
    val minChange30mPct: Double,
    val minChange5mPct: Double,
    val high15mPriceMultiplier: Double,
    val minVolumeAcceleration: Double,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("minChange24hPct", minChange24hPct)
        .put("maxChange24hPct", maxChange24hPct)
        .put("minChange30mPct", minChange30mPct)
        .put("minChange5mPct", minChange5mPct)
        .put("high15mPriceMultiplier", high15mPriceMultiplier)
        .put("minVolumeAcceleration", minVolumeAcceleration)

    companion object {
        fun fromJson(json: JSONObject?): PullbackReboundRules {
            val default = StrategyRules.DEFAULT.pullbackRebound
            return PullbackReboundRules(
                minChange24hPct = json?.optDouble("minChange24hPct", default.minChange24hPct) ?: default.minChange24hPct,
                maxChange24hPct = json?.optDouble("maxChange24hPct", default.maxChange24hPct) ?: default.maxChange24hPct,
                minChange30mPct = json?.optDouble("minChange30mPct", default.minChange30mPct) ?: default.minChange30mPct,
                minChange5mPct = json?.optDouble("minChange5mPct", default.minChange5mPct) ?: default.minChange5mPct,
                high15mPriceMultiplier = json?.optDouble("high15mPriceMultiplier", default.high15mPriceMultiplier) ?: default.high15mPriceMultiplier,
                minVolumeAcceleration = json?.optDouble("minVolumeAcceleration", default.minVolumeAcceleration) ?: default.minVolumeAcceleration,
            )
        }
    }
}

data class ScoringRules(
    val change30mWeight: Double,
    val change5mWeight: Double,
    val snapshotMomentumWeight: Double,
    val immediateMomentumMax: Double,
    val volumeAccelerationBase: Double,
    val volumeAccelerationWeight: Double,
    val volumeAccelerationMax: Double,
    val nearHighBase: Double,
    val nearHighWeight: Double,
    val nearHighMax: Double,
    val entryProximityMax: Double,
    val postSpikeDistanceBonusLimitPct: Double,
    val postSpikeDistanceBonus: Double,
    val rangePenaltyBasePct: Double,
    val changeRankTopScore: Double,
    val changeRankTopLimit: Int,
    val changeRankMidScore: Double,
    val changeRankMidLimit: Int,
    val changeRankLowScore: Double,
    val changeRankLowLimit: Int,
    val riskRewardBase: Double,
    val riskRewardRangeCapPct: Double,
    val liquidityLogDivisor: Double,
    val liquidityOffset: Double,
    val overheat24hBasePct: Double,
    val overheat24hWeight: Double,
    val overheat30mBasePct: Double,
    val overheat30mWeight: Double,
    val overheat5mBasePct: Double,
    val overheat5mWeight: Double,
    val overheatMax: Double,
    val staleSnapshotWarningMinutes: Int,
    val staleSnapshotErrorMinutes: Int,
    val staleSnapshotWarningPenalty: Double,
    val staleSnapshotErrorPenalty: Double,
    val postSpikeNearHighLimit: Double,
    val postSpikeChange30mLimitPct: Double,
    val postSpikeNearHighPenalty: Double,
    val postSpikeChange5mBasePct: Double,
    val postSpikeChange5mWeight: Double,
    val postSpikeMax: Double,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("change30mWeight", change30mWeight)
        .put("change5mWeight", change5mWeight)
        .put("snapshotMomentumWeight", snapshotMomentumWeight)
        .put("immediateMomentumMax", immediateMomentumMax)
        .put("volumeAccelerationBase", volumeAccelerationBase)
        .put("volumeAccelerationWeight", volumeAccelerationWeight)
        .put("volumeAccelerationMax", volumeAccelerationMax)
        .put("nearHighBase", nearHighBase)
        .put("nearHighWeight", nearHighWeight)
        .put("nearHighMax", nearHighMax)
        .put("entryProximityMax", entryProximityMax)
        .put("postSpikeDistanceBonusLimitPct", postSpikeDistanceBonusLimitPct)
        .put("postSpikeDistanceBonus", postSpikeDistanceBonus)
        .put("rangePenaltyBasePct", rangePenaltyBasePct)
        .put("changeRankTopScore", changeRankTopScore)
        .put("changeRankTopLimit", changeRankTopLimit)
        .put("changeRankMidScore", changeRankMidScore)
        .put("changeRankMidLimit", changeRankMidLimit)
        .put("changeRankLowScore", changeRankLowScore)
        .put("changeRankLowLimit", changeRankLowLimit)
        .put("riskRewardBase", riskRewardBase)
        .put("riskRewardRangeCapPct", riskRewardRangeCapPct)
        .put("liquidityLogDivisor", liquidityLogDivisor)
        .put("liquidityOffset", liquidityOffset)
        .put("overheat24hBasePct", overheat24hBasePct)
        .put("overheat24hWeight", overheat24hWeight)
        .put("overheat30mBasePct", overheat30mBasePct)
        .put("overheat30mWeight", overheat30mWeight)
        .put("overheat5mBasePct", overheat5mBasePct)
        .put("overheat5mWeight", overheat5mWeight)
        .put("overheatMax", overheatMax)
        .put("staleSnapshotWarningMinutes", staleSnapshotWarningMinutes)
        .put("staleSnapshotErrorMinutes", staleSnapshotErrorMinutes)
        .put("staleSnapshotWarningPenalty", staleSnapshotWarningPenalty)
        .put("staleSnapshotErrorPenalty", staleSnapshotErrorPenalty)
        .put("postSpikeNearHighLimit", postSpikeNearHighLimit)
        .put("postSpikeChange30mLimitPct", postSpikeChange30mLimitPct)
        .put("postSpikeNearHighPenalty", postSpikeNearHighPenalty)
        .put("postSpikeChange5mBasePct", postSpikeChange5mBasePct)
        .put("postSpikeChange5mWeight", postSpikeChange5mWeight)
        .put("postSpikeMax", postSpikeMax)

    companion object {
        fun fromJson(json: JSONObject?): ScoringRules {
            val d = StrategyRules.DEFAULT.scoring
            return ScoringRules(
                change30mWeight = json?.optDouble("change30mWeight", d.change30mWeight) ?: d.change30mWeight,
                change5mWeight = json?.optDouble("change5mWeight", d.change5mWeight) ?: d.change5mWeight,
                snapshotMomentumWeight = json?.optDouble("snapshotMomentumWeight", d.snapshotMomentumWeight) ?: d.snapshotMomentumWeight,
                immediateMomentumMax = json?.optDouble("immediateMomentumMax", d.immediateMomentumMax) ?: d.immediateMomentumMax,
                volumeAccelerationBase = json?.optDouble("volumeAccelerationBase", d.volumeAccelerationBase) ?: d.volumeAccelerationBase,
                volumeAccelerationWeight = json?.optDouble("volumeAccelerationWeight", d.volumeAccelerationWeight) ?: d.volumeAccelerationWeight,
                volumeAccelerationMax = json?.optDouble("volumeAccelerationMax", d.volumeAccelerationMax) ?: d.volumeAccelerationMax,
                nearHighBase = json?.optDouble("nearHighBase", d.nearHighBase) ?: d.nearHighBase,
                nearHighWeight = json?.optDouble("nearHighWeight", d.nearHighWeight) ?: d.nearHighWeight,
                nearHighMax = json?.optDouble("nearHighMax", d.nearHighMax) ?: d.nearHighMax,
                entryProximityMax = json?.optDouble("entryProximityMax", d.entryProximityMax) ?: d.entryProximityMax,
                postSpikeDistanceBonusLimitPct = json?.optDouble("postSpikeDistanceBonusLimitPct", d.postSpikeDistanceBonusLimitPct) ?: d.postSpikeDistanceBonusLimitPct,
                postSpikeDistanceBonus = json?.optDouble("postSpikeDistanceBonus", d.postSpikeDistanceBonus) ?: d.postSpikeDistanceBonus,
                rangePenaltyBasePct = json?.optDouble("rangePenaltyBasePct", d.rangePenaltyBasePct) ?: d.rangePenaltyBasePct,
                changeRankTopScore = json?.optDouble("changeRankTopScore", d.changeRankTopScore) ?: d.changeRankTopScore,
                changeRankTopLimit = json?.optInt("changeRankTopLimit", d.changeRankTopLimit) ?: d.changeRankTopLimit,
                changeRankMidScore = json?.optDouble("changeRankMidScore", d.changeRankMidScore) ?: d.changeRankMidScore,
                changeRankMidLimit = json?.optInt("changeRankMidLimit", d.changeRankMidLimit) ?: d.changeRankMidLimit,
                changeRankLowScore = json?.optDouble("changeRankLowScore", d.changeRankLowScore) ?: d.changeRankLowScore,
                changeRankLowLimit = json?.optInt("changeRankLowLimit", d.changeRankLowLimit) ?: d.changeRankLowLimit,
                riskRewardBase = json?.optDouble("riskRewardBase", d.riskRewardBase) ?: d.riskRewardBase,
                riskRewardRangeCapPct = json?.optDouble("riskRewardRangeCapPct", d.riskRewardRangeCapPct) ?: d.riskRewardRangeCapPct,
                liquidityLogDivisor = json?.optDouble("liquidityLogDivisor", d.liquidityLogDivisor) ?: d.liquidityLogDivisor,
                liquidityOffset = json?.optDouble("liquidityOffset", d.liquidityOffset) ?: d.liquidityOffset,
                overheat24hBasePct = json?.optDouble("overheat24hBasePct", d.overheat24hBasePct) ?: d.overheat24hBasePct,
                overheat24hWeight = json?.optDouble("overheat24hWeight", d.overheat24hWeight) ?: d.overheat24hWeight,
                overheat30mBasePct = json?.optDouble("overheat30mBasePct", d.overheat30mBasePct) ?: d.overheat30mBasePct,
                overheat30mWeight = json?.optDouble("overheat30mWeight", d.overheat30mWeight) ?: d.overheat30mWeight,
                overheat5mBasePct = json?.optDouble("overheat5mBasePct", d.overheat5mBasePct) ?: d.overheat5mBasePct,
                overheat5mWeight = json?.optDouble("overheat5mWeight", d.overheat5mWeight) ?: d.overheat5mWeight,
                overheatMax = json?.optDouble("overheatMax", d.overheatMax) ?: d.overheatMax,
                staleSnapshotWarningMinutes = json?.optInt("staleSnapshotWarningMinutes", d.staleSnapshotWarningMinutes) ?: d.staleSnapshotWarningMinutes,
                staleSnapshotErrorMinutes = json?.optInt("staleSnapshotErrorMinutes", d.staleSnapshotErrorMinutes) ?: d.staleSnapshotErrorMinutes,
                staleSnapshotWarningPenalty = json?.optDouble("staleSnapshotWarningPenalty", d.staleSnapshotWarningPenalty) ?: d.staleSnapshotWarningPenalty,
                staleSnapshotErrorPenalty = json?.optDouble("staleSnapshotErrorPenalty", d.staleSnapshotErrorPenalty) ?: d.staleSnapshotErrorPenalty,
                postSpikeNearHighLimit = json?.optDouble("postSpikeNearHighLimit", d.postSpikeNearHighLimit) ?: d.postSpikeNearHighLimit,
                postSpikeChange30mLimitPct = json?.optDouble("postSpikeChange30mLimitPct", d.postSpikeChange30mLimitPct) ?: d.postSpikeChange30mLimitPct,
                postSpikeNearHighPenalty = json?.optDouble("postSpikeNearHighPenalty", d.postSpikeNearHighPenalty) ?: d.postSpikeNearHighPenalty,
                postSpikeChange5mBasePct = json?.optDouble("postSpikeChange5mBasePct", d.postSpikeChange5mBasePct) ?: d.postSpikeChange5mBasePct,
                postSpikeChange5mWeight = json?.optDouble("postSpikeChange5mWeight", d.postSpikeChange5mWeight) ?: d.postSpikeChange5mWeight,
                postSpikeMax = json?.optDouble("postSpikeMax", d.postSpikeMax) ?: d.postSpikeMax,
            )
        }
    }
}

data class RiskRules(
    val volumeExpansionStopMultiplier: Double,
    val defaultStopMultiplier: Double,
    val wideRangeThresholdPct: Double,
    val wideRangeTarget1Multiplier: Double,
    val defaultTarget1Multiplier: Double,
    val target2BaseMultiplier: Double,
    val target2RangeDivisor: Double,
    val target2RangeCap: Double,
    val trailingStopMultiplier: Double,
    val minimumRiskPct: Double,
    val minimumExpectedReturnPct: Double,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("volumeExpansionStopMultiplier", volumeExpansionStopMultiplier)
        .put("defaultStopMultiplier", defaultStopMultiplier)
        .put("wideRangeThresholdPct", wideRangeThresholdPct)
        .put("wideRangeTarget1Multiplier", wideRangeTarget1Multiplier)
        .put("defaultTarget1Multiplier", defaultTarget1Multiplier)
        .put("target2BaseMultiplier", target2BaseMultiplier)
        .put("target2RangeDivisor", target2RangeDivisor)
        .put("target2RangeCap", target2RangeCap)
        .put("trailingStopMultiplier", trailingStopMultiplier)
        .put("minimumRiskPct", minimumRiskPct)
        .put("minimumExpectedReturnPct", minimumExpectedReturnPct)

    companion object {
        fun fromJson(json: JSONObject?): RiskRules {
            val d = StrategyRules.DEFAULT.risk
            return RiskRules(
                volumeExpansionStopMultiplier = json?.optDouble("volumeExpansionStopMultiplier", d.volumeExpansionStopMultiplier) ?: d.volumeExpansionStopMultiplier,
                defaultStopMultiplier = json?.optDouble("defaultStopMultiplier", d.defaultStopMultiplier) ?: d.defaultStopMultiplier,
                wideRangeThresholdPct = json?.optDouble("wideRangeThresholdPct", d.wideRangeThresholdPct) ?: d.wideRangeThresholdPct,
                wideRangeTarget1Multiplier = json?.optDouble("wideRangeTarget1Multiplier", d.wideRangeTarget1Multiplier) ?: d.wideRangeTarget1Multiplier,
                defaultTarget1Multiplier = json?.optDouble("defaultTarget1Multiplier", d.defaultTarget1Multiplier) ?: d.defaultTarget1Multiplier,
                target2BaseMultiplier = json?.optDouble("target2BaseMultiplier", d.target2BaseMultiplier) ?: d.target2BaseMultiplier,
                target2RangeDivisor = json?.optDouble("target2RangeDivisor", d.target2RangeDivisor) ?: d.target2RangeDivisor,
                target2RangeCap = json?.optDouble("target2RangeCap", d.target2RangeCap) ?: d.target2RangeCap,
                trailingStopMultiplier = json?.optDouble("trailingStopMultiplier", d.trailingStopMultiplier) ?: d.trailingStopMultiplier,
                minimumRiskPct = json?.optDouble("minimumRiskPct", d.minimumRiskPct) ?: d.minimumRiskPct,
                minimumExpectedReturnPct = json?.optDouble("minimumExpectedReturnPct", d.minimumExpectedReturnPct) ?: d.minimumExpectedReturnPct,
            )
        }
    }
}
