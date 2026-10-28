package com.qingyu.imemodule.service

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * 回车键该做什么。
 *
 * 输入法的回车不能一律发 KEYCODE_ENTER：聊天、短信这类输入框常把回车声明成
 * 「换行」，而用户装了「回车键发送消息」就是想让回车直接发出去。这里把判定从
 * `ImeService` 里拆成纯函数，方便在 JVM 上覆盖各分支。
 */
sealed interface EnterKeyAction {
    /** 直接按下并抬起回车，等价于物理键盘回车（换行/默认行为）。 */
    data object RawEnter : EnterKeyAction

    /** 让输入框执行它自己声明的动作，例如搜索、前往、发送。 */
    data class PerformAction(val actionId: Int) : EnterKeyAction
}

object EnterKeyActionResolver {

    private val NO_ACTION = setOf(EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE)

    /**
     * @param enterToSend 用户在设置里打开了「回车键发送消息」
     * @param inputType   输入框的 inputType
     * @param imeOptions  输入框的 imeOptions（含 action 位与 IME_FLAG_NO_ENTER_ACTION）
     * @param actionLabel 输入框给出的动作文案，非空说明应用自己声明了动作
     * @param actionId    EditorInfo.actionId
     */
    fun resolve(
        enterToSend: Boolean,
        inputType: Int,
        imeOptions: Int,
        actionLabel: CharSequence?,
        actionId: Int,
    ): EnterKeyAction {
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val isTextInput = inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT
        // 只在「应用没声明动作」时接管，搜索/前往这类显式动作交给应用自己处理。
        val allowsOverride = isTextInput &&
            action in NO_ACTION &&
            actionId == EditorInfo.IME_ACTION_UNSPECIFIED &&
            actionLabel.isNullOrEmpty()

        if (enterToSend && allowsOverride) {
            return EnterKeyAction.PerformAction(EditorInfo.IME_ACTION_SEND)
        }
        if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL ||
            (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        ) {
            return EnterKeyAction.RawEnter
        }
        if (!actionLabel.isNullOrEmpty() && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
            return EnterKeyAction.PerformAction(actionId)
        }
        return if (action in NO_ACTION) {
            EnterKeyAction.RawEnter
        } else {
            EnterKeyAction.PerformAction(action)
        }
    }
}
