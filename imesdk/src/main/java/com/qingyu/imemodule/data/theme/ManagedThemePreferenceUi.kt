
package com.qingyu.imemodule.data.theme

import android.content.Context
import androidx.annotation.StringRes
import com.qingyu.imemodule.prefs.ManagedPreferenceUi
import com.qingyu.imemodule.view.preference.ThemeSelectPreference

class ManagedThemePreferenceUi(
    @param:StringRes
    val title: Int,
    key: String,
    val defaultValue: Theme,
    @param:StringRes
    val summary: Int? = null,
    enableUiOn: (() -> Boolean)? = null
) : ManagedPreferenceUi<ThemeSelectPreference>(key, enableUiOn) {
    override fun createUi(context: Context) = ThemeSelectPreference(context, defaultValue).apply {
        key = this@ManagedThemePreferenceUi.key
        isIconSpaceReserved = false
        isSingleLineTitle = false
        if (this@ManagedThemePreferenceUi.summary != null)
            setSummary(this@ManagedThemePreferenceUi.summary)
        setTitle(this@ManagedThemePreferenceUi.title)
    }
}