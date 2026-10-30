package com.qingyu.inputmethod

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout

/** Synthetic editor surface; no existing app or user text is accessed. */
class NativeTypingHostActivity : Activity() {
    lateinit var first: EditText
    lateinit var password: EditText
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 27) setTurnScreenOn(true)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        first = EditText(this).apply { hint = "Synthetic text field"; inputType = InputType.TYPE_CLASS_TEXT }
        password = EditText(this).apply {
            hint = "Synthetic password field"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 100, 24, 24)
            addView(first, LinearLayout.LayoutParams(-1, 160))
            addView(password, LinearLayout.LayoutParams(-1, 160))
        })
    }
}

class NativeSettingsHostActivity : com.qingyu.imemodule.ui.activity.SettingsActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
