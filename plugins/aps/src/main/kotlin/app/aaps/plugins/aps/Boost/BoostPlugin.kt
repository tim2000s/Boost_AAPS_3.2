package app.aaps.plugins.aps.Boost

import android.content.Context
import app.aaps.annotations.OpenForTesting
import dagger.android.HasAndroidInjector
import app.aaps.core.interfaces.aps.DetermineBasalAdapter
import app.aaps.core.interfaces.aps.DynamicISFPlugin
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginType
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.plugins.aps.openAPSSMB.OpenAPSSMBPlugin
import app.aaps.plugins.aps.utils.ScriptReader
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
    bgQualityCheck: BgQualityCheck
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
), DynamicISFPlugin {
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

    override fun provideDetermineBasalAdapter(): DetermineBasalAdapter = DetermineBasalAdapterBoostJS(ScriptReader(context), injector)
}