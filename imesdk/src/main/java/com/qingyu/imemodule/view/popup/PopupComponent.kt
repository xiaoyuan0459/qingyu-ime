
package com.qingyu.imemodule.view.popup

import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import com.qingyu.imemodule.application.Launcher
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.entity.keyboard.SoftKey
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.behavior.PopupMenuMode
import com.qingyu.imemodule.singleton.EnvironmentSingleton
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import java.util.LinkedList

class PopupComponent private constructor(){
    private var showingEntryUi:PopupEntryUi? = null
    private val freeEntryUi = LinkedList<PopupEntryUi>()

    private var showingContainerUi:PopupContainerUi? = null

    private val popupRadius by lazy {
        ThemeManager.prefs.keyRadius.getValue().toFloat()
    }

    val root by lazy {
        Launcher.instance.context.frameLayout {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            isClickable = false
            isFocusable = false
        }
    }

    companion object{
        private var instance:PopupComponent?=null
            get() {
                if (field ==null) field = PopupComponent()
                return field
            }
        fun get():PopupComponent{
            return instance!!
        }
    }

    fun showPopup(content: String, bounds: Rect) {
        val popup = (showingEntryUi ?: freeEntryUi.poll()
            ?: PopupEntryUi(Launcher.instance.context)).apply {
            lastShowTime = System.currentTimeMillis()
            setBackground(ThemeManager.activeTheme, popupRadius)
            setText(content)
        }
        root.apply {
            val bottomMargin = EnvironmentSingleton.instance.skbAreaHeight - (bounds.top + bounds.bottom)/2
            val leftMargin = EnvironmentSingleton.instance.leftMarginWidth + bounds.left
            val existing = popup.root.layoutParams as? FrameLayout.LayoutParams
            // 气泡视图常驻父子层级，只切可见性：每键 remove/add 会多一次 attach 与整屏遍历。
            if (popup.root.parent !== this) {
                add(popup.root, lParams(bounds.width(), bounds.height()) {
                    this.bottomMargin = bottomMargin
                    this.leftMargin = leftMargin
                })
            } else {
                if (popup.root.visibility != View.VISIBLE) popup.root.visibility = View.VISIBLE
                if (existing == null || existing.width != bounds.width() ||
                    existing.height != bounds.height() || existing.bottomMargin != bottomMargin ||
                    existing.leftMargin != leftMargin) {
                    popup.root.layoutParams = lParams(bounds.width(), bounds.height()) {
                        this.bottomMargin = bottomMargin
                        this.leftMargin = leftMargin
                    }
                }
            }
        }
        showingEntryUi = popup
    }

    fun showKeyboard(label: String, labelSmall: String, bounds: Rect) {
        showingEntryUi?.setText("") ?: showPopup("", bounds)
        var labels =  (PopupSmallPreset[labelSmall] ?: emptyArray<String>()).plus(PopupPreset[label] ?: emptyArray())
        if(labels.isEmpty())labels = PopupPreset[labelSmall] ?: emptyArray()
        if(labels.isNotEmpty()) {
            reallyShowKeyboard(labels, bounds)
        } else {
            dismissPopup()
        }
    }

    fun showKeyboardMenu(mCurrentKey: SoftKey, bounds: Rect, distanceY: Float) {
        val key = when(mCurrentKey.code) {
            InputModeSwitcher.USER_KEYCODE_LANG ->  Pair(PopupMenuMode.SwitchIME, "🌐")
            InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI ->  Pair(PopupMenuMode.EMOJI, "😆")
            KeyEvent.KEYCODE_SHIFT_LEFT -> {
                Pair(PopupMenuMode.EnglishCell, if(AppPrefs.getInstance().input.abcSearchEnglishCell.getValue()) "直输模式" else "拼写模式")
            }
            KeyEvent.KEYCODE_DEL -> Pair(PopupMenuMode.Clear,  "🔙 上滑清空")
            InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION -> Pair(PopupMenuMode.Move,  "")
            else ->  Pair(PopupMenuMode.Enter,  "↩️ 换行")
        }
        showingEntryUi?.setText("") ?: showPopup("", bounds)
        reallyMenuKeyboard(key, bounds, mCurrentKey.code != KeyEvent.KEYCODE_DEL)
    }

    fun onGestureEvent(distance: Float) {
        showingContainerUi?.onGestureEvent(distance)
    }

    private fun reallyMenuKeyboard(key: Pair<PopupMenuMode, String>, bounds: Rect, isSelect: Boolean,) {
        val popupWidth = EnvironmentSingleton.instance.skbWidth.div(10) * key.second.length / 2
        val keyboardUi = PopupKeyboardMenuUi(bounds, { dismissPopup() }, popupRadius, popupWidth, isSelect, key)
        root.apply {
            add(keyboardUi.root, lParams {
                bottomMargin = EnvironmentSingleton.instance.skbAreaHeight - (bounds.top + bounds.bottom)/2
                leftMargin = EnvironmentSingleton.instance.leftMarginWidth + bounds.left + keyboardUi.offsetX
            })
        }
        dismissPopup()
        showingContainerUi= keyboardUi
    }

    private fun reallyShowKeyboard(keys: Array<String>, bounds: Rect) {
        val popupWidth = EnvironmentSingleton.instance.skbWidth.div(10)
        val keyboardUi = PopupKeyboardUi(bounds, { dismissPopup() }, popupRadius, popupWidth, keys)
        root.apply {
            add(keyboardUi.root, lParams {
                bottomMargin = EnvironmentSingleton.instance.skbAreaHeight - (bounds.top + bounds.bottom)/2
                leftMargin = EnvironmentSingleton.instance.leftMarginWidth + bounds.left + keyboardUi.offsetX
            })
        }
        dismissPopup()
        showingContainerUi= keyboardUi
    }

    fun changeFocus(x: Float, y: Float): Boolean {
        return showingContainerUi?.changeFocus(x, y) ?: false
    }

    fun triggerFocused(): Pair<PopupMenuMode, String> {
        return showingContainerUi?.onTrigger()?:Pair(PopupMenuMode.None, "")
    }

    fun dismissPopup() {
        dismissPopupContainer()
        showingEntryUi?.also {
            showingEntryUi = null
            it.root.visibility = View.GONE
            freeEntryUi.add(it)
        }
    }

    private fun dismissPopupContainer() {
        showingContainerUi?.also {
            showingContainerUi = null
            root.removeView(it.root)
        }
    }
}
