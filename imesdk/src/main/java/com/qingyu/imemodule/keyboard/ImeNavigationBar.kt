package com.qingyu.imemodule.keyboard

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Keeps keyboard content above the navigation bar, including transient gesture navigation. */
internal object ImeNavigationBar {
    fun install(window: Window?, inputView: View, onBottomInsetChanged: (Int) -> Unit) {
        window?.let { win ->
            WindowCompat.setDecorFitsSystemWindows(win, false)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                win.navigationBarColor = Color.TRANSPARENT
            }
            WindowCompat.getInsetsController(win, win.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                show(WindowInsetsCompat.Type.navigationBars())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                win.isNavigationBarContrastEnforced = false
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(inputView) { _, insets ->
            // Visible navigation-bar insets can be zero during hiding/animation. Query the
            // current window's stable area rather than retaining an old orientation's value.
            val navigationBottom = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            ).bottom
            val gestureBottom = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom
            onBottomInsetChanged(maxOf(navigationBottom, gestureBottom))
            insets
        }
    }
}
