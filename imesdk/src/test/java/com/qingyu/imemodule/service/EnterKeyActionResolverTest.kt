package com.qingyu.imemodule.service

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回车键分歧点：开关只在「应用自己没声明动作」时接管，搜索/前往这类显式动作必须原样交给应用。
 */
class EnterKeyActionResolverTest {

    private val textNoAction = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

    @Test
    fun enterToSendTakesOverMultilineChatFieldThatOnlyWantsNewline() {
        val action = EnterKeyActionResolver.resolve(
            enterToSend = true,
            inputType = textNoAction,
            imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
            actionLabel = null,
            actionId = EditorInfo.IME_ACTION_UNSPECIFIED,
        )
        assertEquals(EnterKeyAction.PerformAction(EditorInfo.IME_ACTION_SEND), action)
    }

    @Test
    fun enterToSendOffKeepsRawEnterForTheSameField() {
        val action = EnterKeyActionResolver.resolve(
            enterToSend = false,
            inputType = textNoAction,
            imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
            actionLabel = null,
            actionId = EditorInfo.IME_ACTION_UNSPECIFIED,
        )
        assertEquals(EnterKeyAction.RawEnter, action)
    }

    @Test
    fun undeclaredActionStillSendsWhenSwitchIsOn() {
        val action = EnterKeyActionResolver.resolve(
            enterToSend = true,
            inputType = textNoAction,
            imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED,
            actionLabel = null,
            actionId = EditorInfo.IME_ACTION_UNSPECIFIED,
        )
        assertEquals(EnterKeyAction.PerformAction(EditorInfo.IME_ACTION_SEND), action)
    }

    @Test
    fun explicitSearchActionIsNotHijackedBySwitch() {
        val action = EnterKeyActionResolver.resolve(
            enterToSend = true,
            inputType = textNoAction,
            imeOptions = EditorInfo.IME_ACTION_SEARCH,
            actionLabel = null,
            actionId = EditorInfo.IME_ACTION_UNSPECIFIED,
        )
        assertEquals(EnterKeyAction.PerformAction(EditorInfo.IME_ACTION_SEARCH), action)
    }

    @Test
    fun actionLabelWinsOverImeOptions() {
        val action = EnterKeyActionResolver.resolve(
            enterToSend = true,
            inputType = textNoAction,
            imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED,
            actionLabel = "前往",
            actionId = EditorInfo.IME_ACTION_GO,
        )
        assertEquals(EnterKeyAction.PerformAction(EditorInfo.IME_ACTION_GO), action)
    }

    @Test
    fun switchDoesNotApplyOutsideTextFields() {
        val action = EnterKeyActionResolver.resolve(
            enterToSend = true,
            inputType = InputType.TYPE_CLASS_NUMBER,
            imeOptions = EditorInfo.IME_ACTION_NONE,
            actionLabel = null,
            actionId = EditorInfo.IME_ACTION_UNSPECIFIED,
        )
        assertEquals(EnterKeyAction.RawEnter, action)
    }

    @Test
    fun declaredDoneActionIsRespectedWithSwitchOn() {
        val on = EnterKeyActionResolver.resolve(
            enterToSend = true,
            inputType = textNoAction,
            imeOptions = EditorInfo.IME_ACTION_DONE,
            actionLabel = null,
            actionId = EditorInfo.IME_ACTION_UNSPECIFIED,
        )
        assertEquals(EnterKeyAction.PerformAction(EditorInfo.IME_ACTION_DONE), on)
        val off = EnterKeyActionResolver.resolve(
            enterToSend = false,
            inputType = textNoAction,
            imeOptions = EditorInfo.IME_ACTION_DONE,
            actionLabel = null,
            actionId = EditorInfo.IME_ACTION_UNSPECIFIED,
        )
        assertEquals(EnterKeyAction.PerformAction(EditorInfo.IME_ACTION_DONE), off)
    }
}
