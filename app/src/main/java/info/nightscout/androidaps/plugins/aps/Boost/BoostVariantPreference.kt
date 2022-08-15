package info.nightscout.androidaps.plugins.aps.Boost

import android.content.Context
import android.util.AttributeSet
import androidx.preference.DropDownPreference
import dagger.android.HasAndroidInjector
class BoostVariantPreference(context: Context, attrs: AttributeSet?)
    : DropDownPreference(context, attrs) {

    constructor(context: Context) : this(context, null)

    init {
        (context.applicationContext as HasAndroidInjector).androidInjector().inject(this)

        entryValues = arrayOf("default", "3.6.5-capped")
        setEntries(arrayOf("default (3.6.5)", "3.6.5-capped"))
        //setDefaultValue("default")
    }
}
