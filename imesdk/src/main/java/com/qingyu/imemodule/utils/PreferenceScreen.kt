package com.qingyu.imemodule.utils

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import com.qingyu.imemodule.view.preference.ImePreferenceCategory
import splitties.resources.drawable
import splitties.resources.styledColor

fun PreferenceScreen.addCategory(title: String, block: ImePreferenceCategory.() -> Unit) {
    val category = ImePreferenceCategory(context).apply {
        setTitle(title)
        isIconSpaceReserved = false
        // 页内首个分组不再画顶部分隔线：应用栏底部已经有一条通栏线
        topDividerVisible = (0 until preferenceCount)
            .map(::getPreference)
            .any { it is ImePreferenceCategory }
    }
    addPreference(category)
    category.block()
}

fun PreferenceScreen.addCategory(@StringRes title: Int, block: ImePreferenceCategory.() -> Unit) {
    val ctx = context
    addCategory(ctx.getString(title), block)
}

fun PreferenceGroup.addPreference(
    title: String,
    summary: String? = null,
    @DrawableRes icon: Int? = null,
    showArrow: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    addPreference(Preference(context).apply {
        isSingleLineTitle = false
        setTitle(title)
        isCopyingEnabled = true
        setSummary(summary)
        if (icon == null) {
            isIconSpaceReserved = false
        } else {
            setIcon(context.drawable(icon)?.apply {
                setTint(context.styledColor(android.R.attr.colorControlNormal))
            })
        }
        if (showArrow) {
            setWidgetLayoutResource(com.qingyu.imemodule.R.layout.preference_arrow)
        }
        onClick?.also {
            setOnPreferenceClickListener { _ ->
                it.invoke()
                true
            }
        }
    })
}

fun PreferenceGroup.addPreference(
    @StringRes title: Int,
    summary: String,
    @DrawableRes icon: Int? = null,
    showArrow: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val ctx = context
    addPreference(ctx.getString(title), summary, icon, showArrow, onClick)
}

fun PreferenceGroup.addPreference(
    @StringRes title: Int,
    @StringRes summary: Int? = null,
    @DrawableRes icon: Int? = null,
    showArrow: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val ctx = context
    addPreference(ctx.getString(title), summary?.let(ctx::getString), icon, showArrow, onClick)
}
