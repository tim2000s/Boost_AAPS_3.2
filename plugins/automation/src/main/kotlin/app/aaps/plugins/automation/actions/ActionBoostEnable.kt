package app.aaps.plugins.automation.actions

import androidx.annotation.DrawableRes
import dagger.android.HasAndroidInjector
import app.aaps.plugins.automation.R
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.database.entities.UserEntry
import app.aaps.database.entities.UserEntry.Sources
import app.aaps.core.interfaces.*
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.sharedPreferences.SP
import javax.inject.Inject

class ActionBoostEnable(injector: HasAndroidInjector) : Action(injector) {

    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var sp: SP

    override fun friendlyName(): Int = R.string.enableBoost
    override fun shortDescription(): String = rh.gs(R.string.enableBoost)
    @DrawableRes override fun icon(): Int = R.drawable.ic_boost_enabled

    override fun doAction(callback: Callback) {
        val currentBoostStatus:Boolean = sp.getBoolean(R.string.key_enable_Boost, true)
        if (!currentBoostStatus) {
            uel.log(UserEntry.Action.BOOST_ENABLED, Sources.Automation, title)
            sp.putBoolean(R.string.key_enable_Boost, true)
            callback.result(PumpEnactResult(injector).success(true).comment(R.string.boost_enabled)).run()
        } else {
            callback.result(PumpEnactResult(injector).success(true).comment(R.string.boost_alreadyenabled)).run()
        }
    }

    override fun isValid(): Boolean = true

    override fun hasDialog(): Boolean = false
}