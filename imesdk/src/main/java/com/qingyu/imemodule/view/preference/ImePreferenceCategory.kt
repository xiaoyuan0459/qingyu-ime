
package com.qingyu.imemodule.view.preference

import android.content.Context
import android.view.View
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import com.qingyu.imemodule.R

/**
 * 分组之间用一条内缩发丝线隔开；页面首个分组顶部那条要藏掉，
 * 否则会和应用栏底部的通栏线叠成双线。
 */
class ImePreferenceCategory(context: Context) : PreferenceCategory(context) {

    var topDividerVisible = true
        set(value) {
            if (field == value) return
            field = value
            notifyChanged()
        }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.findViewById(R.id.pref_group_divider)?.visibility =
            if (topDividerVisible) View.VISIBLE else View.GONE
    }
}
