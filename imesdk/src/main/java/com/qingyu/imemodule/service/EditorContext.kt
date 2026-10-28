package com.qingyu.imemodule.service

import android.text.InputType
import android.view.inputmethod.EditorInfo

/** Immutable editor semantics: Android may mutate the EditorInfo object on restartInput. */
data class EditorContext(
    val packageName: String?,
    val fieldId: Int,
    val inputType: Int,
    val noPersonalizedLearning: Boolean,
) {
    val isPassword: Boolean
        get() = when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            InputType.TYPE_CLASS_TEXT -> inputType and InputType.TYPE_MASK_VARIATION in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )
            else -> false
        }

    val allowsSuggestions: Boolean get() = !isPassword && !noPersonalizedLearning

    companion object {
        fun from(info: EditorInfo) = EditorContext(
            info.packageName, info.fieldId, info.inputType,
            info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0,
        )
    }
}
