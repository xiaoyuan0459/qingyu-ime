
package com.qingyu.imemodule.view.preference

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import com.qingyu.imemodule.data.theme.Theme
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.ui.fragment.theme.ResponsiveThemeListView
import com.qingyu.imemodule.ui.fragment.theme.SimpleThemeListAdapter

class ThemeSelectPreference(context: Context, private val defaultTheme: Theme) :
    Preference(context) {

    init {
        setDefaultValue(defaultTheme.name)
    }

    private val currentThemeName
        get() = getPersistedString(defaultTheme.name)

    override fun onClick() {
        val view = ResponsiveThemeListView(context).apply {
            // force AlertDialog's customPanel to grow
            minimumHeight = Int.MAX_VALUE
        }
        val allThemes = ThemeManager.getAllThemes()
        val adapter = SimpleThemeListAdapter(allThemes).apply {
            selected = allThemes.indexOfFirst { it.name == currentThemeName }
        }
        view.adapter = adapter
        AlertDialog.Builder(context)
            .setTitle(title)
            .setPositiveButton(com.qingyu.imemodule.R.string.ok) { _, _ ->
                adapter.selectedTheme?.let {
                    if (callChangeListener(it.name)) {
                        persistString(it.name)
                        notifyChanged()
                    }
                }
            }
            .setNegativeButton(com.qingyu.imemodule.R.string.cancel, null)
            .setView(view)
            .show()
    }

}