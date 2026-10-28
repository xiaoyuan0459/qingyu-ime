

package com.qingyu.imemodule.view.widget

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** Adds the navigation-bar inset without discarding the view's design-time padding. */
fun View.applyNavBarInsetsBottomPadding() {
    (this as? ViewGroup)?.clipToPadding = false
    val initialBottomPadding = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val navigationBar = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
        view.updatePadding(bottom = initialBottomPadding + navigationBar.bottom)
        windowInsets
    }
    ViewCompat.requestApplyInsets(this)
}
