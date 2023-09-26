package app.aaps.plugins.aps.Boost

import android.content.Context
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import app.aaps.annotations.OpenForTesting
import app.aaps.core.interfaces.aps.AutosensResult
import dagger.android.HasAndroidInjector
import app.aaps.core.interfaces.aps.BoostDefaults
import app.aaps.core.interfaces.aps.DetermineBasalAdapter
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginType
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.main.constraints.ConstraintObject
import app.aaps.core.main.extensions.target
import app.aaps.core.utils.MidnightUtils
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.openAPSSMB.DetermineBasalResultSMB
import app.aaps.plugins.aps.openAPSSMB.OpenAPSSMBPlugin
import app.aaps.plugins.aps.utils.ScriptReader
import app.aaps.database.ValueWrapper
import app.aaps.database.impl.AppRepository
import app.aaps.plugins.aps.R
import javax.inject.Inject
import javax.inject.Singleton

@OpenForTesting
@Singleton
class BoostPlugin @Inject constructor(
    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    rxBus: RxBus,
    constraintChecker: ConstraintsChecker,
    rh: ResourceHelper,
    profileFunction: ProfileFunction,
    context: Context,
    activePlugin: ActivePlugin,
    iobCobCalculator: IobCobCalculator,
    hardLimits: HardLimits,
    profiler: Profiler,
    sp: SP,
    dateUtil: DateUtil,
    repository: AppRepository,
    glucoseStatusProvider: GlucoseStatusProvider,
    bgQualityCheck: BgQualityCheck,
    val config : Config
) : OpenAPSSMBPlugin(
    injector,
    aapsLogger,
    rxBus,
    constraintChecker,
    rh,
    profileFunction,
    context,
    activePlugin,
    iobCobCalculator,
    hardLimits,
    profiler,
    sp,
    dateUtil,
    repository,
    glucoseStatusProvider,
    bgQualityCheck
) {
    init {
        pluginDescription
            .mainType(PluginType.APS)
            .fragmentClass(app.aaps.plugins.aps.OpenAPSFragment::class.java.name)
            .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
            .pluginName(R.string.Boost)
            .shortName(R.string.Boost_shortname)
            .preferencesId(R.xml.pref_boost)
            .description(R.string.description_Boost)
            .setDefault(false)
    }

    // last values
    override var lastAPSRun: Long = 0
    override var lastAPSResult: DetermineBasalResultSMB? = null
    override var lastDetermineBasalAdapter: DetermineBasalAdapter? = null
    override var lastAutosensResult = AutosensResult()

    override fun specialEnableCondition(): Boolean {
        return try {
            activePlugin.activePump.pumpDescription.isTempBasalCapable
        } catch (ignored: Exception) {
            // may fail during initialization
            true
        }
    }

    override fun specialShowInListCondition(): Boolean {
        val pump = activePlugin.activePump
        return pump.pumpDescription.isTempBasalCapable
    }

    override fun preprocessPreferences(preferenceFragment: PreferenceFragmentCompat) {
        super.preprocessPreferences(preferenceFragment)
        val smbAlwaysEnabled = sp.getBoolean(R.string.key_enableSMB_always, false)
        preferenceFragment.findPreference<SwitchPreference>(rh.gs(R.string.key_enableSMB_with_COB))?.isVisible = !smbAlwaysEnabled
        preferenceFragment.findPreference<SwitchPreference>(rh.gs(R.string.key_enableSMB_with_temptarget))?.isVisible = !smbAlwaysEnabled
        preferenceFragment.findPreference<SwitchPreference>(rh.gs(R.string.key_enableSMB_after_carbs))?.isVisible = !smbAlwaysEnabled
    }

    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
        lastAPSResult = null
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val profile = profileFunction.getProfile()
        val pump = activePlugin.activePump
        if (profile == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(app.aaps.core.ui.R.string.no_profile_set)))
            aapsLogger.debug(LTag.APS, rh.gs(app.aaps.core.ui.R.string.no_profile_set))
            return
        }
        if (!isEnabled(PluginType.APS)) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_disabled)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_disabled))
            return
        }
        if (glucoseStatus == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_no_glucose_data)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_no_glucose_data))
            return
        }

        val inputConstraints = ConstraintObject(0.0, aapsLogger) // fake. only for collecting all results
        val maxBasal = constraintChecker.getMaxBasalAllowed(profile).also {
            inputConstraints.copyReasons(it)
        }.value()
        var start = System.currentTimeMillis()
        var startPart = System.currentTimeMillis()
        profiler.log(LTag.APS, "getMealData()", startPart)
        val maxIob = constraintChecker.getMaxIOBAllowed().also { maxIOBAllowedConstraint ->
            inputConstraints.copyReasons(maxIOBAllowedConstraint)
        }.value()

        var minBg = hardLimits.verifyHardLimits(
            Round.roundTo(profile.getTargetLowMgdl(), 0.1),
            app.aaps.core.ui.R.string.profile_low_target,
            HardLimits.VERY_HARD_LIMIT_MIN_BG[0],
            HardLimits.VERY_HARD_LIMIT_MIN_BG[1])
        var maxBg = hardLimits.verifyHardLimits(
            Round.roundTo(profile.getTargetHighMgdl(), 0.1),
            app.aaps.core.ui.R.string.profile_high_target,
            HardLimits.VERY_HARD_LIMIT_MAX_BG[0],
            HardLimits.VERY_HARD_LIMIT_MAX_BG[1])
        var targetBg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.VERY_HARD_LIMIT_TARGET_BG[0], HardLimits.VERY_HARD_LIMIT_TARGET_BG[1])
        var isTempTarget = false
        val tempTarget = repository.getTemporaryTargetActiveAt(dateUtil.now()).blockingGet()
        if (tempTarget is ValueWrapper.Existing) {
            isTempTarget = true
            minBg =
                hardLimits.verifyHardLimits(
                    tempTarget.value.lowTarget,
                    app.aaps.core.ui.R.string.temp_target_low_target,
                    HardLimits.VERY_HARD_LIMIT_TEMP_MIN_BG[0].toDouble(),
                    HardLimits.VERY_HARD_LIMIT_TEMP_MIN_BG[1].toDouble()
                )
            maxBg =
                hardLimits.verifyHardLimits(
                    tempTarget.value.highTarget,
                    app.aaps.core.ui.R.string.temp_target_high_target,
                    HardLimits.VERY_HARD_LIMIT_TEMP_MAX_BG[0].toDouble(),
                    HardLimits.VERY_HARD_LIMIT_TEMP_MAX_BG[1].toDouble()
                )
            targetBg =
                hardLimits.verifyHardLimits(
                    tempTarget.value.target(),
                    app.aaps.core.ui.R.string.temp_target_value,
                    HardLimits.VERY_HARD_LIMIT_TEMP_TARGET_BG[0].toDouble(),
                    HardLimits.VERY_HARD_LIMIT_TEMP_TARGET_BG[1].toDouble()
                )
        }
        if (!hardLimits.checkHardLimits(profile.dia, app.aaps.core.ui.R.string.profile_dia, hardLimits.minDia(), hardLimits.maxDia())) return
        if (!hardLimits.checkHardLimits(profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()), app.aaps.core.ui.R.string.profile_carbs_ratio_value, hardLimits.minIC(), hardLimits.maxIC())) return
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl(), app.aaps.core.ui.R.string.profile_sensitivity_value, HardLimits.MIN_ISF, HardLimits.MAX_ISF)) return
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), app.aaps.core.ui.R.string.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return
        if (!hardLimits.checkHardLimits(pump.baseBasalRate, app.aaps.core.ui.R.string.current_basal_value, 0.01, hardLimits.maxBasal())) return
        startPart = System.currentTimeMillis()
        if (constraintChecker.isAutosensModeEnabled().value()) {
            val autosensData = iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSPlugin")
            if (autosensData == null) {
                rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openaps_no_as_data)))
                return
            }
            lastAutosensResult = autosensData.autosensResult
        } else {
            lastAutosensResult.sensResult = "autosens disabled"
        }
        val iobArray = iobCobCalculator.calculateIobArrayForSMB(lastAutosensResult, BoostDefaults.exercise_mode, BoostDefaults.half_basal_exercise_target, isTempTarget)
        profiler.log(LTag.APS, "calculateIobArrayInDia()", startPart)
        startPart = System.currentTimeMillis()
        val smbAllowed = ConstraintObject(!tempBasalFallback, aapsLogger).also {
            constraintChecker.isSMBModeEnabled(it)
            inputConstraints.copyReasons(it)
        }
        val advancedFiltering = ConstraintObject(!tempBasalFallback, aapsLogger).also {
            constraintChecker.isAdvancedFilteringEnabled(it)
            inputConstraints.copyReasons(it)
        }
        val uam = ConstraintObject(true, aapsLogger).also {
            constraintChecker.isUAMEnabled(it)
            inputConstraints.copyReasons(it)
        }
        val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT
        profiler.log(LTag.APS, "detectSensitivityAndCarbAbsorption()", startPart)
        profiler.log(LTag.APS, "SMB data gathering", start)
        start = System.currentTimeMillis()

        provideDetermineBasalAdapter().also { determineBasalAdapterBoostJS ->
            determineBasalAdapterBoostJS.setData(
                profile, maxIob, maxBasal, minBg, maxBg, targetBg,
                activePlugin.activePump.baseBasalRate,
                iobArray,
                glucoseStatus,
                iobCobCalculator.getMealDataWithWaitingForCalculationFinish(),
                lastAutosensResult.ratio,
                isTempTarget,
                smbAllowed.value(),
                uam.value(),
                advancedFiltering.value(),
                flatBGsDetected
            )
            val now = System.currentTimeMillis()
            val determineBasalResult = determineBasalAdapterBoostJS.invoke() as DetermineBasalResultSMB
            profiler.log(LTag.APS, "SMB calculation", start)
            if (determineBasalResult == null) {
                aapsLogger.error(LTag.APS, "SMB calculation returned null")
                lastDetermineBasalAdapter = null
                lastAPSResult = null
                lastAPSRun = 0
            } else {
                // TODO still needed with oref1?
                // Fix bug determine basal
                if (determineBasalResult.rate == 0.0 && determineBasalResult.duration == 0 && iobCobCalculator.getTempBasalIncludingConvertedExtended(dateUtil.now()) == null)
                    determineBasalResult.isTempBasalRequested = false
                determineBasalResult.iob = iobArray[0]
                determineBasalResult.json?.put("timestamp", dateUtil.toISOString(now))
                determineBasalResult.inputConstraints = inputConstraints
                lastDetermineBasalAdapter = determineBasalAdapterBoostJS
                lastAPSResult = determineBasalResult
                lastAPSRun = now
            }
        }
        rxBus.send(app.aaps.plugins.aps.events.EventOpenAPSUpdateGui())
    }

    override fun isSuperBolusEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        value.set(false)
        return value
    }

    override fun provideDetermineBasalAdapter(): DetermineBasalAdapter = DetermineBasalAdapterBoostJS(ScriptReader(context), injector)
}