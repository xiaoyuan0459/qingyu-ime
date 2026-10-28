package com.qingyu.imemodule.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.entity.keyboard.SoftKey
import com.qingyu.imemodule.entity.keyboard.SoftKeyboard
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.behavior.KeyboardSymbolSlideUpMod
import com.qingyu.imemodule.prefs.behavior.PopupMenuMode
import com.qingyu.imemodule.singleton.EnvironmentSingleton
import com.qingyu.imemodule.utils.DevicesUtils
import com.qingyu.imemodule.view.popup.PopupComponent
import com.qingyu.imemodule.view.popup.PopupComponent.Companion.get
import java.util.LinkedList
import java.util.Queue
import kotlin.math.abs
import kotlin.math.absoluteValue

/**
 * 键盘根布局
 *
 * 由于之前键盘体验问题，当前基于Android内置键盘[android.inputmethodservice.KeyboardView]进行调整开发。
 */
open class BaseKeyboardView(mContext: Context?) : View(mContext) {
    private val popupComponent: PopupComponent = get()
    protected var mSoftKeyboard: SoftKeyboard? = null
    private var mCurrentKey: SoftKey? = null
    private var mGestureDetector: GestureDetector? = null
    protected var mLongPressKey = false
    private var mVoiceLongPressed = false
    private var mAbortKey = false
    private var mHandler: Handler? = null
    protected var mDrawPending = false
    protected var mService: InputView? = null
    private val accessibility = KeyboardAccessibilityHelper(this,
        { if (mService == null) emptyList() else mSoftKeyboard?.mKeyRows?.flatten().orEmpty()
            .filter { it.code != InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL } },
        { key, longPress ->
            val input = mService
            if (input == null) false else {
                DevicesUtils.tryPlayKeyDown(key.code)
                DevicesUtils.tryVibrate(this, key.code)
                if (longPress && key.code == KeyEvent.KEYCODE_SPACE) input.requestVoiceInput()
                else input.responseKeyEvent(if (longPress) SoftKey(label = key.labelSmall) else key)
                invalidateKey()
                true
            }
        }, { mService?.hasSelection == true }, { code -> mService?.isTextEditActionEnabled(code) != false })
    init { androidx.core.view.ViewCompat.setAccessibilityDelegate(this, accessibility) }
    override fun dispatchHoverEvent(event: MotionEvent) = accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
    override fun dispatchKeyEvent(event: KeyEvent) = accessibility.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        accessibility.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }
    fun setResponseKeyEvent(service: InputView) {
        mService = service
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        initGestureDetector()
        if (mHandler == null) {
            mHandler = object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    when (msg.what) {
                        MSG_REPEAT -> {
                            if (repeatKey()) {
                                val repeat = Message.obtain(this, MSG_REPEAT)
                                sendMessageDelayed(repeat, REPEAT_INTERVAL)
                            }
                        }

                        MSG_LONGPRESS -> openPopupIfRequired()
                    }
                }
            }
        }
    }

    private fun initGestureDetector() {
        if (mGestureDetector == null) {
            mGestureDetector = GestureDetector(context, object : SimpleOnGestureListener() {
                override fun onScroll(downEvent: MotionEvent?, currentEvent: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    if(mLongPressKey && mCurrentKey?.getkeyLabel()?.isNotBlank() == true){
                        popupComponent.changeFocus(currentEvent.x - downEvent!!.x, currentEvent.y - downEvent.y)
                    } else {
                        dispatchGestureEvent(downEvent, currentEvent, distanceX, distanceY)
                    }
                    return true
                }
                override fun onDown(e: MotionEvent): Boolean {
                    currentDistanceY = 0f
                    return super.onDown(e)
                }
            })
            mGestureDetector!!.setIsLongpressEnabled(false)
        }
    }

    fun invalidateKey() {
        mDrawPending = true
        invalidate()
    }

    /**
     * 局部重绘：只标脏给定按键所在的矩形。
     *
     * 键面状态（按下高亮）变化不影响其它键，但原先一律走整块重绘，每次触摸都要
     * 重新生成全部按键的绘制命令。这里只让受影响的键参与重绘；键盘级的全局变化
     * （Shift 大小写、主题、切换布局）仍走 [invalidateKey]。
     */
    protected fun invalidateKeyRegion(vararg keys: SoftKey?) {
        // 已经有整块重绘挂起时，局部标脏没有意义
        if (mDrawPending) return
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        var any = false
        for (k in keys) {
            if (k == null) continue
            any = true
            if (k.mLeft < left) left = k.mLeft
            if (k.mTop < top) top = k.mTop
            if (k.mRight > right) right = k.mRight
            if (k.mBottom > bottom) bottom = k.mBottom
        }
        if (!any) return
        // 键帽圆角与抗锯齿会越过键矩形边界，外扩几像素避免边缘残影
        val rect = Rect(left - 3, top - 3, right + 3, bottom + 3)
        mDirtyKeyRect.union(rect)
        invalidate(rect)
    }

    /** 待重绘的按键区域，由 [onBufferDraw] 消费后清空 */
    protected val mDirtyKeyRect = Rect()

    protected fun invalidateAccessibilityState() = accessibility.invalidateRoot()

    open fun onBufferDraw() {}
    private fun openPopupIfRequired() {
        if (mAbortKey || !isShown) return
        if(mCurrentKey != null) {
            val softKey = mCurrentKey!!
            val keyboardSymbol = ThemeManager.prefs.keyboardSymbol.getValue()
            if (softKey.code == KeyEvent.KEYCODE_SPACE && AppPrefs.getInstance().voice.voiceOnSpace.getValue()) {
                mVoiceLongPressed = true
                mLongPressKey = true
                // Confirm before the voice panel hides and cancels this cached keyboard.
                DevicesUtils.tryVibrateLongPress(this)
                mService?.requestVoiceInput()
                return
            } else if (softKey.getkeyLabel().isNotBlank() && softKey.code != InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI ) {
                val keyLabel = if (InputModeSwitcher.isLower) softKey.keyLabel.lowercase() else softKey.keyLabel
                val designPreset = setOf("，", "。", ",", ".")
                val smallLabel = if(designPreset.any { it == keyLabel } || !keyboardSymbol) "" else softKey.getmKeyLabelSmall()
                val bounds = Rect(softKey.mLeft, softKey.mTop, softKey.mRight, softKey.mBottom)
                popupComponent.showKeyboard(keyLabel, smallLabel, bounds)
                mLongPressKey = true
            } else if (softKey.code == InputModeSwitcher.USER_KEYCODE_LANG ||
                softKey.code == InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI ||
                    softKey.code == KeyEvent.KEYCODE_SHIFT_LEFT ||
                softKey.code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION ||
                softKey.code == KeyEvent.KEYCODE_DEL || softKey.code == KeyEvent.KEYCODE_ENTER){
                val bounds = Rect(softKey.mLeft, softKey.mTop, softKey.mRight, softKey.mBottom)
                popupComponent.showKeyboardMenu(softKey, bounds, currentDistanceY)
                mLongPressKey = true
            } else {
                mLongPressKey = true
                mAbortKey = true
                dismissPreview()
            }
            if (mLongPressKey && !mAbortKey) DevicesUtils.tryVibrateLongPress(this)
        }
    }

    private var motionEventQueue: Queue<MotionEvent> = LinkedList()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(me: MotionEvent): Boolean {
        var result = false
        if (mGestureDetector!!.onTouchEvent(me)) {
            return true
        }
        when (val action = me.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val actionIndex = me.actionIndex
                val x = me.getX(actionIndex)
                val y = me.getY(actionIndex)
                val now = me.eventTime
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, me.metaState)
                motionEventQueue.offer(down)
                result = onModifiedTouchEvent(me)
                val keyIndex = getKeyIndices(x.toInt(), y.toInt())
                if(keyIndex != null) {
                    DevicesUtils.tryPlayKeyDown(keyIndex.code)
                    DevicesUtils.tryVibrate(this, keyIndex.code)
                }
                showPreview(keyIndex)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val now = me.eventTime
                val act = if(action == MotionEvent.ACTION_CANCEL)MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
                while (!motionEventQueue.isEmpty()) {
                    val first = motionEventQueue.poll()
                    if(first!= null) {
                        val release = MotionEvent.obtain(now, now, act, first.x, first.y, me.metaState)
                        result = onModifiedTouchEvent(release)
                        release.recycle()
                        first.recycle()
                    }
                }
                dismissPreview(commit = action != MotionEvent.ACTION_CANCEL)
            }
            else -> {
                result = onModifiedTouchEvent(me)
            }
        }
        return result
    }

    private fun onModifiedTouchEvent(me: MotionEvent): Boolean {
        when (me.action) {
            MotionEvent.ACTION_DOWN -> {
                mCurrentKey = getKeyIndices(me.x.toInt(), me.y.toInt())
                mAbortKey = false
                mLongPressKey = false
                mVoiceLongPressed = false
                if(mCurrentKey != null){
                    if (mCurrentKey!!.repeatable()) {
                        val msg = mHandler!!.obtainMessage(MSG_REPEAT)
                        mHandler!!.sendMessageDelayed(msg, REPEAT_START_DELAY)
                    }
                    val msg = mHandler!!.obtainMessage(MSG_LONGPRESS)
                    mHandler!!.sendMessageDelayed(msg, AppPrefs.getInstance().keyboardSetting.longPressTimeout.getValue().toLong())
                }
            }
            MotionEvent.ACTION_UP -> {
                mCurrentKey?.onReleased()
                mCurrentKey = getKeyIndices(me.x.toInt(), me.y.toInt())
                removeMessages()
                if (!mAbortKey && !mLongPressKey && mCurrentKey != null) {
                    mService?.responseKeyEvent(mCurrentKey!!)
                }
                currentDistanceX = 0F
                currentDistanceY = 0F
            }
            MotionEvent.ACTION_CANCEL -> {
                removeMessages()
                mAbortKey = true
                mCurrentKey?.onReleased()
                currentDistanceX = 0F
                currentDistanceY = 0F
            }
        }
        return true
    }

    private var lastEventX:Float = -1f
    private var lastEventY:Float = -1f
    private var currentDistanceY:Float = 0f
    private var currentDistanceX:Float = 0f
    private var lastEventActionIndex:Int = 0
    // 处理手势滑动
    private fun dispatchGestureEvent(downEvent: MotionEvent?, currentEvent: MotionEvent, distanceX: Float, distanceY: Float) : Boolean {
        var result = false
        val currentX = currentEvent.x
        val currentY = currentEvent.y
        currentDistanceX = distanceX
        currentDistanceY = distanceY
        val keyLableSmall = mCurrentKey?.getmKeyLabelSmall()
        if(currentEvent.pointerCount > 1) return false    // 避免多指触控导致上屏
        if(lastEventX < 0 || lastEventActionIndex != currentEvent.actionIndex) {   // 避免多指触控导致符号上屏
            lastEventX = currentX
            lastEventY = currentY
            lastEventActionIndex = currentEvent.actionIndex
            return false
        }
        val relDiffX = abs(currentX - lastEventX)
        val relDiffY = abs(currentY - lastEventY)
        val isVertical = relDiffX * 1.5 < relDiffY  //横向、竖向滑动距离接近时，优先触发左右滑动
        val symbolSlideUp = EnvironmentSingleton.instance.heightForCandidatesArea / when(ThemeManager.prefs.symbolSlideUpMod.getValue()){
            KeyboardSymbolSlideUpMod.SHORT -> 3;KeyboardSymbolSlideUpMod.MEDIUM -> 2;else -> 1
        }
        val spaceSwipeMoveCursorSpeed = AppPrefs.getInstance().keyboardSetting.spaceSwipeMoveCursorSpeed.getValue()
        if (!isVertical && relDiffX > spaceSwipeMoveCursorSpeed) {  // 左右滑动
            val isSwipeKey = mCurrentKey?.code == KeyEvent.KEYCODE_SPACE || mCurrentKey?.code == KeyEvent.KEYCODE_0
            if(mCurrentKey?.code == KeyEvent.KEYCODE_DEL && distanceX > 20){// 左滑删除
                removeMessages()
                mAbortKey = true
                mService?.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_CLEAR))
            } else if (isSwipeKey && AppPrefs.getInstance().keyboardSetting.spaceSwipeMoveCursor.getValue()) {  // 左右滑动
                removeMessages()
                lastEventX = currentX
                lastEventY = currentY
                mAbortKey = true
                mService!!.responseKeyEvent(SoftKey(code = if (distanceX > 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT))
                result = true
            }
        } else if(keyLableSmall?.isNotBlank() == true){
            if (isVertical && distanceY > 0 && relDiffY > symbolSlideUp && ThemeManager.prefs.keyboardSymbol.getValue()){   // 向上滑动
                lastEventX = currentX
                lastEventY = currentY
                lastEventActionIndex = currentEvent.actionIndex
                mLongPressKey = true
                removeMessages()
                mService?.responseLongKeyEvent(Pair(PopupMenuMode.Text, keyLableSmall))
                result = true
            }
        } else {  // 菜单
            if (isVertical && relDiffY > symbolSlideUp * 2) {   // 向上滑动
                lastEventX = currentX
                lastEventY = currentY
                lastEventActionIndex = currentEvent.actionIndex
                mLongPressKey = true
                popupComponent.onGestureEvent(distanceY)
            } else {
                if(downEvent != null) popupComponent.changeFocus(currentEvent.x - downEvent.x, currentEvent.y - downEvent.y)
            }
        }
        return result
    }

    private fun repeatKey(): Boolean {
        if (!mAbortKey && mCurrentKey != null && mCurrentKey!!.repeatable()) {
            if(mCurrentKey!!.code == KeyEvent.KEYCODE_DEL && mLongPressKey) {
                    if (currentDistanceX.absoluteValue >= currentDistanceY.absoluteValue ) {
                        if (currentDistanceX >= -2) {
                            mService?.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_DEL))
                        } else {
                            mService?.responseLongKeyEvent(Pair(PopupMenuMode.Revertl,  ""))
                        }
                    } else {
                        if (currentDistanceY > 0) {
                            mService?.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_DEL))
                        } else if (currentDistanceY < 0) {
                            mService?.responseLongKeyEvent(Pair(PopupMenuMode.Revertl,  ""))
                        }
                    }
            } else {
                mService?.responseKeyEvent(
                    if (mCurrentKey!!.code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION) {
                        SoftKey(
                            if (currentDistanceX.absoluteValue >= currentDistanceY.absoluteValue) {
                                if (currentDistanceX > 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                            } else {
                                if (currentDistanceY < 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                            }
                        )
                    } else mCurrentKey!!
                )
            }
            return true
        }
        return false
    }

    private fun removeMessages() {
        if (mHandler != null) {
            mHandler!!.removeMessages(MSG_REPEAT)
            mHandler!!.removeMessages(MSG_LONGPRESS)
            mHandler!!.removeMessages(MSG_SHOW_PREVIEW)
        }
    }

    public override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closing()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Cached keyboard views stay attached when a tool panel replaces them.
        if (visibility != VISIBLE && mSoftKeyboard != null) closing()
    }

    /**
     * 显示短按气泡
     */
    private fun showPreview(key: SoftKey?) {
        val released = mCurrentKey
        mCurrentKey?.onReleased()
        if (key != null) {
            key.onPressed()
            showBalloonText(key)
        } else {
            popupComponent.dismissPopup()
        }
        // 这里只有按下/抬起的高亮变了，键面其余内容不受影响
        invalidateKeyRegion(released, key)
    }

    /**
     * 隐藏短按气泡
     */
    private fun dismissPreview(commit: Boolean = true) {
        if (mLongPressKey) {
            if (commit && !mAbortKey && !mVoiceLongPressed) mService?.responseLongKeyEvent(popupComponent.triggerFocused())
            mLongPressKey = false
            mVoiceLongPressed = false
        }
        if (mCurrentKey != null) {
            val released = mCurrentKey
            mCurrentKey!!.onReleased()
            invalidateKeyRegion(released)
        }
        popupComponent.dismissPopup()
        lastEventX = -1f
    }

    open fun closing() {
        removeMessages()
        mAbortKey = true
        dismissPreview(commit = false)
        mSoftKeyboard?.mKeyRows?.flatten()?.forEach { it.onReleased() }
        mCurrentKey = null
        while (motionEventQueue.isNotEmpty()) motionEventQueue.poll()?.recycle()
        currentDistanceX = 0f
        currentDistanceY = 0f
    }

    private fun showBalloonText(key: SoftKey) {
        val keyboardBalloonShow = AppPrefs.getInstance().keyboardSetting.keyboardBalloonShow.getValue()
        if (keyboardBalloonShow && !TextUtils.isEmpty(key.getkeyLabel())) {
            val bounds = Rect(key.mLeft, key.mTop, key.mRight, key.mBottom)
            popupComponent.showPopup(key.getkeyLabel(), bounds)
        }
    }

    fun getKeyIndices(x: Int, y: Int): SoftKey? {
        return mSoftKeyboard?.mapToKey(x, y)
    }

    open fun setSoftKeyboard(softSkb: SoftKeyboard) {
        mSoftKeyboard = softSkb
        accessibility.invalidateRoot()
    }

    fun getSoftKeyboard(): SoftKeyboard {
        return mSoftKeyboard!!
    }

    companion object {
        private const val MSG_SHOW_PREVIEW = 1
        private const val MSG_REPEAT = 3
        private const val MSG_LONGPRESS = 4
        private const val REPEAT_INTERVAL = 50L // ~20 keys per second
        private const val REPEAT_START_DELAY = 400L
    }
}
