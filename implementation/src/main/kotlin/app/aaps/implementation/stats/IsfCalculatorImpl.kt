package app.aaps.implementation.stats

import app.aaps.core.interfaces.aps.SMBDefaults
import app.aaps.core.interfaces.logging.ScriptLogger
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.stats.IsfCalculation
import app.aaps.core.interfaces.stats.IsfCalculator
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.core.main.profile.ProfileSealed
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

@Singleton
class IsfCalculatorImpl @Inject constructor(
    private val tddCalculator: TddCalculator,
    private val sp: SP,
    private val profileUtil: ProfileUtil,
    private val jsLogger: ScriptLogger,
) : IsfCalculator {

    override fun calculateAndSetToProfile(profile : Profile, insulinDivisor: Int, glucose: Double, isTempTarget: Boolean, profileJson: JSONObject?) : IsfCalculation {

        val autosensMax = SafeParse.stringToDouble(sp.getString(app.aaps.core.utils.R.string.key_openapsama_autosens_max, "1.2"))
        val autosensMin = SafeParse.stringToDouble(sp.getString(app.aaps.core.utils.R.string.key_openapsama_autosens_min, "0.7"))
        val dynIsfVelocity = SafeParse.stringToDouble(sp.getString(app.aaps.core.utils.R.string.key_dynamic_isf_velocity, "100")) / 100.0
        val bgCap = profileUtil.convertToMgdlDetect(SafeParse.stringToDouble(sp.getString(app.aaps.core.utils.R.string.key_dynamic_isf_bg_cap, "210")))
        val bgNormalTarget = SafeParse.stringToDouble(sp.getString(app.aaps.core.utils.R.string.key_dynamic_isf_normalTarget, "99"))

        val highTemptargetRaisesSensitivity = sp.getBoolean(app.aaps.core.utils.R.string.key_high_temptarget_raises_sensitivity, false)
        val lowTemptargetLowersSensitivity = sp.getBoolean(app.aaps.core.utils.R.string.key_low_temptarget_lowers_sensitivity, false)
        val halfBasalTarget = SMBDefaults.half_basal_exercise_target
        val obeyProfile = sp.getBoolean(app.aaps.core.utils.R.string.key_dynamic_isf_obey_profile, true)

        val useDynIsf = sp.getBoolean(app.aaps.core.utils.R.string.key_dynamic_isf_enable, false)
        val useTDD = sp.getBoolean(app.aaps.core.utils.R.string.key_dynamic_isf_use_tdd, false)
        val adjustSens = sp.getBoolean(app.aaps.core.utils.R.string.key_dynamic_isf_adjust_sensitivity, false)

        val globalScale =
            if (obeyProfile) (100.0 / if (profile is ProfileSealed.EPS) profile.value.originalPercentage else 100)
            else 1.0

        val sensBase = profile.getIsfMgdl()
        var sensNormalTarget = sensBase * globalScale
        var variableSensitivity = sensNormalTarget
        var ratio = 1.0
        val bgCurrent =
            if (useDynIsf && glucose > bgCap) bgCap + ((glucose - bgCap) / 3)
            else glucose

        jsLogger.debug("------------------ ISF Calculation ------------------")

        val result =
            if (!useDynIsf) {
                jsLogger.debug("Dynamic ISF is disabled")

                IsfCalculation(
                    glucose,
                    glucose,
                    Round.roundTo(sensNormalTarget, 0.1),
                    Round.roundTo(variableSensitivity, 0.1),
                    Round.roundTo(ratio / globalScale, 0.1),
                    insulinDivisor,
                    dynIsfVelocity
                )
            }
            else {
                if (useTDD) {
                    jsLogger.debug("Dynamic ISF uses TDD")
                    val tdd7D = tddCalculator.averageTDD(tddCalculator.calculate(7, false))?.totalAmount
                    if (tdd7D != null) {

                        val tddLast24H = tddCalculator.calculateDaily(-24, 0)?.totalAmount ?: 0.0

                        val tdd1D = tddCalculator.averageTDD(tddCalculator.calculate(1, false))?.totalAmount
                        val tddLast4H = tddCalculator.calculateDaily(-4, 0)?.totalAmount ?: 0.0
                        val tddLast8to4H = tddCalculator.calculateDaily(-8, -4)?.totalAmount ?: 0.0
                        val tddWeightedFromLast8H = ((1.4 * tddLast4H) + (0.6 * tddLast8to4H)) * 3
                        var tdd =
                            if (tdd1D != null) (tddWeightedFromLast8H * 0.33) + (tdd7D * 0.34) + (tdd1D * 0.33)
                            else tddWeightedFromLast8H

                        jsLogger.debug("TDD: ${Round.roundTo(tdd, 0.01)}")
                        jsLogger.debug("tdd1D: ${ if (tdd1D == null) null else Round.roundTo(tdd1D, 0.01)}")
                        jsLogger.debug("tddLast4H: ${Round.roundTo(tddLast4H, 0.01)}")
                        jsLogger.debug("tddLast8to4H: ${Round.roundTo(tddLast8to4H, 0.01)}")
                        jsLogger.debug("tddWeightedFromLast8H: ${Round.roundTo(tddWeightedFromLast8H, 0.01)}")

                        val dynISFadjust = SafeParse.stringToDouble(sp.getString(app.aaps.core.utils.R.string.key_dynamic_isf_tdd_adjust, "100")) / 100.0
                        tdd *= dynISFadjust

                        sensNormalTarget = 1800 / (tdd * (ln((bgNormalTarget / insulinDivisor) + 1))) * globalScale

                        if (adjustSens) {
                            ratio = Math.max(Math.min(tddLast24H / tdd7D, autosensMax), autosensMin)
                            sensNormalTarget /= ratio
                        }
                    }
                    else jsLogger.debug("tdd7D not found, falling back to profile sensNormalTarget of $sensNormalTarget")
                }

                val bgTarget = profile.getTargetMgdl();
                if (isTempTarget && ((highTemptargetRaisesSensitivity && bgTarget > bgNormalTarget) || (lowTemptargetLowersSensitivity && bgTarget < bgNormalTarget))) {
                    val c = halfBasalTarget - bgNormalTarget
                    ratio = c / (c + bgTarget - bgNormalTarget)
                    ratio = Math.max(Math.min(ratio, autosensMax), autosensMin)
                    sensNormalTarget /= ratio
                    jsLogger.debug("Adjusted ISF by /$ratio due to tempTarget of $bgTarget")
                }

                val sbg = ln((bgCurrent / insulinDivisor) + 1)
                val scaler = ln((bgNormalTarget / insulinDivisor) + 1) / sbg
                variableSensitivity = sensNormalTarget * (1 - (1 - scaler) * dynIsfVelocity)

                if (ratio == 1.0 && adjustSens && !useTDD) {
                    ratio = variableSensitivity / sensNormalTarget
                    jsLogger.debug("Adjusted sensitivity.ratio to $ratio due to settings")
                }

                ratio /= globalScale

                IsfCalculation(
                    glucose,
                    bgCurrent,
                    Round.roundTo(sensNormalTarget, 0.1),
                    Round.roundTo(variableSensitivity, 0.1),
                    Round.roundTo(ratio, 0.1),
                    insulinDivisor,
                    dynIsfVelocity
                )
            }

        jsLogger.debug("")
        jsLogger.debug("Final values:")
        jsLogger.debug("sensNormalTarget: ${result.isfNormalTarget}")
        jsLogger.debug("variableSensitivity: ${result.isf}")
        jsLogger.debug("ratio: ${result.ratio}")
        jsLogger.debug("dynISFvelocity: ${result.velocity}")
        jsLogger.debug("dynISFBgCapped: ${result.bgCapped}")
        jsLogger.debug("insulinDivisor: ${result.insulinDivisor}")
        jsLogger.debug("---------------------------------------------------------")

        profileJson?.let { p ->
            p.put("dynISFBgCap", bgCap )
            p.put("dynISFBgCapped", result.bgCapped)
            p.put("dynISFvelocity", result.velocity)
            p.put("sensNormalTarget", result.isfNormalTarget)
            p.put("variable_sens", result.isf)
            p.put("insulinDivisor", result.insulinDivisor)
        }
        return result
    }
}