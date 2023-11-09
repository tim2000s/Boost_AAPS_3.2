package app.aaps.plugins.aps.Boost

import app.aaps.core.interfaces.aps.BoostDefaults
import app.aaps.core.interfaces.db.GlucoseUnit
import app.aaps.core.interfaces.iob.GlucoseStatus
import app.aaps.core.interfaces.iob.IobTotal
import app.aaps.core.interfaces.iob.MealData
import app.aaps.core.interfaces.logging.ScriptLogger
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.SafeParse
import app.aaps.core.main.extensions.convertedToAbsolute
import app.aaps.core.main.extensions.getPassedDurationToTimeInMinutes
import app.aaps.core.main.extensions.plannedRemainingMinutes
import app.aaps.core.main.profile.ProfileSealed
import app.aaps.plugins.aps.utils.ScriptReader
import dagger.android.HasAndroidInjector
import app.aaps.core.interfaces.stats.IsfCalculator
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSSMB.DetermineBasalAdapterSMBJS
import org.joda.time.format.ISODateTimeFormat
import javax.inject.Inject

class DetermineBasalAdapterBoostJS internal constructor(scriptReader: ScriptReader, injector: HasAndroidInjector)
    : DetermineBasalAdapterSMBJS(scriptReader, injector) {

    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var isfCalculator: IsfCalculator
    @Inject lateinit var jsLogger: ScriptLogger

    override val jsFolder = "Boost"
    override val useLoopVariants = true
    override val jsAdditionalScript = """
var getIsfByProfile = function (bg, profile, useCap) {
    if (useCap) {
        var cap = profile.dynISFBgCap;
        if (bg > cap) bg = (cap + (bg - cap)/3);
    }
    var sens_BG = Math.log((bg / profile.insulinDivisor) + 1);
    var scaler = Math.log((profile.normalTarget / profile.insulinDivisor) + 1) / sens_BG;
    return profile.sensNormalTarget * (1 - (1 - scaler) * profile.dynISFvelocity);
}"""

    @Suppress("SpellCheckingInspection")
    override fun setData(
        profile: Profile,
        maxIob: Double,
        maxBasal: Double,
        minBg: Double,
        maxBg: Double,
        targetBg: Double,
        basalRate: Double,
        iobArray: Array<IobTotal>,
        glucoseStatus: GlucoseStatus,
        mealData: MealData,
        autosensDataRatio: Double,
        tempTargetSet: Boolean,
        microBolusAllowed: Boolean,
        uamAllowed: Boolean,
        advancedFiltering: Boolean,
        flatBGsDetected: Boolean
    ) {
        val pump = activePlugin.activePump
        val pumpBolusStep = pump.pumpDescription.bolusStep
        val normalTarget = SafeParse.stringToDouble(sp.getString(app.aaps.core.utils.R.string.key_dynamic_isf_normalTarget, "99"))

        this.profile.put("max_iob", maxIob)
        //mProfile.put("dia", profile.getDia());
        this.profile.put("type", "current")
        this.profile.put("max_daily_basal", profile.getMaxDailyBasal())
        this.profile.put("max_basal", maxBasal)
        this.profile.put("carb_ratio", profile.getIc())
        this.profile.put("sens", profile.getIsfMgdl())
        this.profile.put("max_daily_safety_multiplier", sp.getInt(R.string.key_openapsama_max_daily_safety_multiplier, 3))
        this.profile.put("current_basal_safety_multiplier", sp.getDouble(R.string.key_openapsama_current_basal_safety_multiplier, 4.0))
        this.profile.put("lgsThreshold", profileUtil.convertToMgdlDetect(sp.getDouble(R.string.key_lgs_threshold, 65.0)))


        //mProfile.put("high_temptarget_raises_sensitivity", SP.getBoolean(R.string.key_high_temptarget_raises_sensitivity, BoostDefaults.high_temptarget_raises_sensitivity));
//**********************************************************************************************************************************************
        //this.profile.put("high_temptarget_raises_sensitivity", false)
        //mProfile.put("low_temptarget_lowers_sensitivity", SP.getBoolean(R.string.key_low_temptarget_lowers_sensitivity, BoostDefaults.low_temptarget_lowers_sensitivity));
        this.profile.put("high_temptarget_raises_sensitivity",sp.getBoolean(R.string.key_high_temptarget_raises_sensitivity, BoostDefaults.high_temptarget_raises_sensitivity))
        this.profile.put("low_temptarget_lowers_sensitivity",sp.getBoolean(R.string.key_low_temptarget_lowers_sensitivity, BoostDefaults.low_temptarget_lowers_sensitivity))
        this.profile.put("enableBoostPercentScale", sp.getBoolean(R.string.key_enable_boost_percent_scale, false))
        this.profile.put("enableCircadianISF", sp.getBoolean(R.string.key_enableCircadianISF, false))
        //this.profile.put("low_temptarget_lowers_sensitivity", false)
//**********************************************************************************************************************************************
        this.profile.put("sensitivity_raises_target", sp.getBoolean(R.string.key_sensitivity_raises_target, BoostDefaults.sensitivity_raises_target))
        this.profile.put("resistance_lowers_target", sp.getBoolean(R.string.key_resistance_lowers_target, BoostDefaults.resistance_lowers_target))
        this.profile.put("adv_target_adjustments", BoostDefaults.adv_target_adjustments)
        this.profile.put("exercise_mode", BoostDefaults.exercise_mode)
        this.profile.put("half_basal_exercise_target", BoostDefaults.half_basal_exercise_target)
        this.profile.put("maxCOB", BoostDefaults.maxCOB)
        this.profile.put("skip_neutral_temps", pump.setNeutralTempAtFullHour())
        // min_5m_carbimpact is not used within SMB determinebasal
        //if (mealData.usedMinCarbsImpact > 0) {
        //    mProfile.put("min_5m_carbimpact", mealData.usedMinCarbsImpact);
        //} else {
        //    mProfile.put("min_5m_carbimpact", SP.getDouble(R.string.key_openapsama_min_5m_carbimpact, SMBDefaults.min_5m_carbimpact));
        //}
        this.profile.put("remainingCarbsCap", BoostDefaults.remainingCarbsCap)
        this.profile.put("enableUAM", uamAllowed)
        this.profile.put("A52_risk_enable", BoostDefaults.A52_risk_enable)
        val smbEnabled = sp.getBoolean(R.string.key_use_smb, false)
        this.profile.put("SMBInterval", sp.getInt(R.string.key_smb_interval, BoostDefaults.SMBInterval))
        this.profile.put("enableSMB_with_COB", smbEnabled && sp.getBoolean(R.string.key_enableSMB_with_COB, false))
        this.profile.put("enableSMB_with_temptarget", smbEnabled && sp.getBoolean(R.string.key_enableSMB_with_temptarget, false))
        this.profile.put("allowSMB_with_high_temptarget", smbEnabled && sp.getBoolean(R.string.key_allowSMB_with_high_temptarget, false))
        val allowBoostWithHighTemporaryTarget = sp.getBoolean(R.string.key_allowBoost_with_high_temptarget, false)
        this.profile.put("allowBoost_with_high_temptarget", smbEnabled && allowBoostWithHighTemporaryTarget)
        this.profile.put("enableSMB_always", smbEnabled && sp.getBoolean(R.string.key_enableSMB_always, false) && advancedFiltering)
        this.profile.put("enableSMB_after_carbs", smbEnabled && sp.getBoolean(R.string.key_enableSMB_after_carbs, false) && advancedFiltering)
        this.profile.put("maxSMBBasalMinutes", sp.getInt(R.string.key_smb_max_minutes, BoostDefaults.maxSMBBasalMinutes))
        this.profile.put("maxUAMSMBBasalMinutes", sp.getInt(R.string.key_uam_smb_max_minutes, BoostDefaults.maxUAMSMBBasalMinutes))

        //set the min SMB amount to be the amount set by the pump.
        this.profile.put("bolus_increment", pumpBolusStep)
        this.profile.put("carbsReqThreshold", sp.getInt(R.string.key_carbsReqThreshold, BoostDefaults.carbsReqThreshold))
        this.profile.put("current_basal", basalRate)
        this.profile.put("temptargetSet", tempTargetSet)
        this.profile.put("autosens_max", SafeParse.stringToDouble(sp.getString(app.aaps.core.utils.R.string.key_openapsama_autosens_max, "1.2")))
        this.profile.put("autosens_min", SafeParse.stringToDouble(sp.getString(app.aaps.core.utils.R.string.key_openapsama_autosens_min, "0.8")))
//**********************************************************************************************************************************************
        //this.profile.put("scale_min",SafeParse.stringToDouble(sp.getString(R.string.key_scale_min,"70")))
        //this.profile.put("scale_max",SafeParse.stringToDouble(sp.getString(R.string.key_scale_max,"20")))
        //this.profile.put("scale_50",SafeParse.stringToDouble(sp.getString(R.string.key_scale_50,"2")))
//MP: Boost_boluscap start
        this.profile.put("boost_bolus",SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_boost_bolus,"2.5")))
        this.profile.put("boost_percent_scale",SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_boost_scale_factor,"200")))


        val now = System.currentTimeMillis()

        this.profile.put("boost_maxIOB",  SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_boost_max_iob, "1.0")))
        this.profile.put("Boost_InsulinReq",  SafeParse.stringToDouble(sp.getString(R.string.key_boost_insulinreq,"50.0")))
        this.profile.put("boost_scale",  SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_boost_scale, "1.0")))
        //this.profile.put("Boost_eventualBG",SafeParse.stringToDouble(sp.getString(R.string.key_Boost_eventualBG,"155")))
        //this.profile.put("W2_IOB_threshold",SafeParse.stringToDouble(sp.getString(R.string.key_w2_iob_threshold,"20")))
        //this.profile.put("Boost_hyperBG",SafeParse.stringToDouble(sp.getString(R.string.key_Boost_hyperBG,"220")));
//**********************************************************************************************************************************************
        if (profileFunction.getUnits() == GlucoseUnit.MMOL) {
            this.profile.put("out_units", "mmol/L")
        }
        val tb = iobCobCalculator.getTempBasalIncludingConvertedExtended(now)
        currentTemp.put("temp", "absolute")
        currentTemp.put("duration", tb?.plannedRemainingMinutes ?: 0)
        currentTemp.put("rate", tb?.convertedToAbsolute(now, profile) ?: 0.0)
        // as we have non default temps longer than 30 mintues
        if (tb != null) currentTemp.put("minutesrunning", tb.getPassedDurationToTimeInMinutes(now))

        iobData = iobCobCalculator.convertToJSONArray(iobArray)
        mGlucoseStatus.put("glucose", glucoseStatus.glucose)
        mGlucoseStatus.put("noise", glucoseStatus.noise)
        if (sp.getBoolean(R.string.key_always_use_shortavg, false)) {
            mGlucoseStatus.put("delta", glucoseStatus.shortAvgDelta)
        } else {
            mGlucoseStatus.put("delta", glucoseStatus.delta)
        }

        mGlucoseStatus.put("short_avgdelta", glucoseStatus.shortAvgDelta)
        mGlucoseStatus.put("long_avgdelta", glucoseStatus.longAvgDelta)
        mGlucoseStatus.put("date", glucoseStatus.date)
        this.mealData.put("carbs", mealData.carbs)
        this.mealData.put("mealCOB", mealData.mealCOB)
        this.mealData.put("slopeFromMaxDeviation", mealData.slopeFromMaxDeviation)
        this.mealData.put("slopeFromMinDeviation", mealData.slopeFromMinDeviation)
        this.mealData.put("lastBolusTime", mealData.lastBolusTime)
        this.mealData.put("lastCarbTime", mealData.lastCarbTime)


        val insulin = activePlugin.activeInsulin
        val insulinPeak = when {
            insulin.peak < 30   -> 30
            insulin.peak > 75   -> 75
            else                -> insulin.peak
        }
        val insulinDivisor = when {
            insulinPeak < 60    -> (90 - insulinPeak) + 30
            else                -> (90 - insulinPeak) + 40
        }

        this.profile.put("insulinType", insulin.friendlyName)
        this.profile.put("insulinPeak", insulinPeak)

        val inactivity_steps = SafeParse.stringToDouble(sp.getString(R.string.key_inactivity_steps,"400"))
        val inactivity_pct = SafeParse.stringToDouble(sp.getString(R.string.key_inactivity_pct_inc,"130"))
        val sleep_in_steps = SafeParse.stringToDouble(sp.getString(R.string.key_sleep_in_steps,"250"))
        val activity_steps_5 = SafeParse.stringToDouble(sp.getString(R.string.key_activity_steps_5,"420"))
        val activity_steps_30 = SafeParse.stringToDouble(sp.getString(R.string.key_activity_steps_30,"1200"))
        val activity_steps_60 = SafeParse.stringToDouble(sp.getString(R.string.key_activity_hour_steps,"1800"))
        val activity_pct = SafeParse.stringToDouble(sp.getString(R.string.key_activity_pct_inc,"80"))

        val midnight = MidnightTime.calc(now)
        val startHour = sp.getString(R.string.key_openapsama_boost_start, "22:00")
        var boostStart = midnight + org.joda.time.LocalTime.parse(startHour, ISODateTimeFormat.timeElementParser()).millisOfDay
        val endHour = sp.getString(R.string.key_openapsama_boost_end, "7:00")
        var boostEnd = midnight + org.joda.time.LocalTime.parse(endHour, ISODateTimeFormat.timeElementParser()).millisOfDay
        val sleepInMillis = (3600000.0 * SafeParse.stringToDouble(sp.getString(R.string.key_sleep_in_hrs, "2"))).toLong()

        if (boostStart > boostEnd) {
            if (now > boostEnd) boostEnd += 86400000
            else boostStart -= 86400000
        }

        var boostActive = now in boostStart..<boostEnd

        if (boostActive && tempTargetSet && !allowBoostWithHighTemporaryTarget && targetBg > normalTarget) {
            boostActive = false
            jsLogger.debug("Boost disabled due to high temptarget of $targetBg")
        }

        val recentSteps5Minutes = StepService.getRecentStepCount5Min()
        // val recentSteps10Minutes = StepService.getRecentStepCount10Min() // unused
        val recentSteps15Minutes = StepService.getRecentStepCount15Min()
        val recentSteps30Minutes = StepService.getRecentStepCount30Min()
        val recentSteps60Minutes = StepService.getRecentStepCount60Min()

        val activityBgTarget = 150.0
        var activityMinBg = minBg
        var activityMaxBg = maxBg
        var activityTargetBg = targetBg
        var profileSwitch = if (profile is ProfileSealed.EPS) profile.value.originalPercentage else 100

        if (boostActive && now in boostStart..<( boostStart + sleepInMillis ) && recentSteps60Minutes < sleep_in_steps) {
            boostActive = false
            jsLogger.debug("Boost disabled due to lie-in")
        }

        if (boostActive) {

            val activity = recentSteps5Minutes > activity_steps_5
                || recentSteps30Minutes > activity_steps_30
                || recentSteps60Minutes > activity_steps_60
                || (recentSteps5Minutes < activity_steps_5 && recentSteps15Minutes > activity_steps_5)

            if (activity) {

                if (profileSwitch == 100) {
                    profileSwitch = activity_pct.toInt()
                    jsLogger.debug("Profile changed to $activity_pct% due to activity")
                }
                if (!tempTargetSet) {
                    activityMinBg = activityBgTarget
                    activityMaxBg = activityBgTarget
                    activityTargetBg = activityBgTarget
                    jsLogger.debugUnits("TargetBG changed to %.2f due to activity", activityTargetBg)
                }
            } else if (profileSwitch == 100 && recentSteps60Minutes < inactivity_steps) {
                profileSwitch = inactivity_pct.toInt()
                jsLogger.debug("Profile changed to $inactivity_pct% due to inactivity")
            }
        }

        this.profile.put("boostActive", boostActive)
        this.profile.put("profileSwitch", profileSwitch)
        this.profile.put("recentSteps5Minutes", recentSteps5Minutes)
        this.profile.put("recentSteps15Minutes", recentSteps15Minutes)
        this.profile.put("recentSteps30Minutes", recentSteps30Minutes)
        this.profile.put("recentSteps60Minutes", recentSteps60Minutes)
        this.profile.put("min_bg", activityMinBg)
        this.profile.put("max_bg", activityMaxBg)
        this.profile.put("target_bg", activityTargetBg)

        val profileScale = profileSwitch.toDouble() / 100.0
        if (profileSwitch != 100)
        {
            val adjustedBasal = basalRate * profileScale
            this.profile.put("current_basal", adjustedBasal )

            jsLogger.debug("Basal adjusted to %.2f", adjustedBasal)
        }

        val isf = isfCalculator.calculateAndSetToProfile(
            profile.getIsfMgdl() * profileScale,
            profileSwitch,
            targetBg,
            insulinDivisor, glucoseStatus, tempTargetSet, this.profile)

        autosensData.put("ratio", isf.ratio)
        this.profile.put("normalTarget", normalTarget)

        this.microBolusAllowed = microBolusAllowed
        smbAlwaysAllowed = advancedFiltering
        currentTime = now
        this.flatBGsDetected = flatBGsDetected
    }

    init {
        injector.androidInjector().inject(this)
    }
}