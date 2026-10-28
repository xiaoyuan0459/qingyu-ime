
package com.qingyu.imemodule.view.preference

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.qingyu.imemodule.R
import com.qingyu.imemodule.prefs.restore

class ImeSwitchPreference(context: Context) : SwitchPreferenceCompat(context) {

    init {
        widgetLayoutResource = R.layout.preference_widget_ime_switch
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.setOnLongClickListener {
            AlertDialog.Builder(context)
                .setTitle(title ?: "Preference")
                .setMessage(R.string.whether_reset_switch_preference)
                .setNegativeButton(com.qingyu.imemodule.R.string.cancel) { _, _ -> }
                .setPositiveButton(R.string.reset) { _, _ -> restore() }
                .show()
            true
        }
    }
}
