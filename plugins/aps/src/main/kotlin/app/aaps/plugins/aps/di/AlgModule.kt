package app.aaps.plugins.aps.di

import app.aaps.plugins.aps.Boost.DetermineBasalAdapterBoostJS
import app.aaps.plugins.aps.EN.DetermineBasalAdapterENJS
import app.aaps.plugins.aps.logger.LoggerCallback
import app.aaps.plugins.aps.openAPSAMA.DetermineBasalAdapterAMAJS
import app.aaps.plugins.aps.openAPSAMA.DetermineBasalResultAMA
import app.aaps.plugins.aps.openAPSSMB.DetermineBasalAdapterSMBJS
import app.aaps.plugins.aps.openAPSSMB.DetermineBasalResultSMB
import app.aaps.plugins.aps.openAPSSMBDynamicISF.DetermineBasalAdapterSMBDynamicISFJS
import dagger.Module
import dagger.android.ContributesAndroidInjector

@Module
@Suppress("unused")
abstract class AlgModule {

    @ContributesAndroidInjector abstract fun loggerCallbackInjector(): LoggerCallback
    @ContributesAndroidInjector abstract fun determineBasalResultSMBInjector(): DetermineBasalResultSMB
    @ContributesAndroidInjector abstract fun determineBasalResultAMAInjector(): DetermineBasalResultAMA
    @ContributesAndroidInjector abstract fun determineBasalAdapterAMAJSInjector(): DetermineBasalAdapterAMAJS
    @ContributesAndroidInjector abstract fun determineBasalAdapterSMBJSInjector(): DetermineBasalAdapterSMBJS
    @ContributesAndroidInjector abstract fun determineBasalAdapterSMBAutoISFJSInjector(): DetermineBasalAdapterSMBDynamicISFJS
    @ContributesAndroidInjector abstract fun determineBasalAdapterBoostJSInjector(): DetermineBasalAdapterBoostJS
    @ContributesAndroidInjector abstract fun determineBasalAdapterENSInjector(): DetermineBasalAdapterENJS
}