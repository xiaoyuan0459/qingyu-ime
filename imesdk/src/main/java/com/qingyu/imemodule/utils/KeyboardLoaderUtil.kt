package com.qingyu.imemodule.utils

import android.view.KeyEvent
import com.qingyu.imemodule.application.CustomConstant
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.database.ImeDataRepository
import com.qingyu.imemodule.entity.keyboard.SoftKey
import com.qingyu.imemodule.entity.keyboard.SoftKeyToggle
import com.qingyu.imemodule.entity.keyboard.SoftKeyboard
import com.qingyu.imemodule.entity.keyboard.ToggleState
import com.qingyu.imemodule.keyboard.KeyPreset
import com.qingyu.imemodule.keyboard.KeyboardData
import com.qingyu.imemodule.manager.InputModeSwitcher
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.behavior.DoublePinyinSchemaMode
import com.qingyu.imemodule.singleton.EnvironmentSingleton
import com.qingyu.imemodule.keyboard.doubleAbcMnemonicPreset
import com.qingyu.imemodule.keyboard.doubleFlyMnemonicPreset
import com.qingyu.imemodule.keyboard.doubleMSMnemonicPreset
import com.qingyu.imemodule.keyboard.doubleNaturalMnemonicPreset
import com.qingyu.imemodule.keyboard.doubleSogouMnemonicPreset
import com.qingyu.imemodule.keyboard.doubleZiguangMnemonicPreset
import com.qingyu.imemodule.keyboard.lx17MnemonicPreset
import com.qingyu.imemodule.prefs.behavior.SkbStyleMode
import java.util.LinkedList

/**
 * 键盘加载类  包括中文9键  中文26键 英文26键
 */
class KeyboardLoaderUtil private constructor() {
    private var rimeValue: String? = null
    private var mSkbValue: Int = 0
    private var numberLine: Boolean = false
    private var skbStyleMode: SkbStyleMode = SkbStyleMode.Qingyu
    fun clearKeyboardMap() {
        mSoftKeyboardMap.clear()
    }

    private var cachedT9ShowSplit: Boolean? = null

    /**
     * 设置页在应用进程改的布局类偏好，输入法进程看不到通知，只能每次弹出时对一次值，
     * 变了就让键盘缓存失效，避免改动要等输入法进程重启才生效。
     */
    fun syncLayoutPrefs() {
        val showSplit = AppPrefs.getInstance().keyboardSetting.t9ShowSplit.getValue()
        if (cachedT9ShowSplit != showSplit) {
            cachedT9ShowSplit = showSplit
            clearKeyboardMap()
        }
    }

    private fun loadBaseSkb(skbValue: Int): SoftKeyboard {
        skbStyleMode = ThemeManager.prefs.skbStyleMode.getValue()
        mSkbValue = skbValue
        // shift键状态
        // 直输状态
        val shiftToggleStates = LinkedList<ToggleState>()
        shiftToggleStates.add(ToggleState(0))
        shiftToggleStates.add(ToggleState(1))
        shiftToggleStates.add(ToggleState(2))
        // 拼写模式
        shiftToggleStates.add(ToggleState(3))
        shiftToggleStates.add(ToggleState(4))
        shiftToggleStates.add(ToggleState(5))

        val softKeyboard: SoftKeyboard?
        numberLine = AppPrefs.getInstance().keyboardSetting.abcNumberLine.getValue()
        val rows: MutableList<List<SoftKey>> = LinkedList()
        if (numberLine) {
            val qwertyKeys = createNumberLineKeys(arrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0))
            rows.add(qwertyKeys.asList())
        }
        when(skbValue){
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN -> {  // 1000  拼音全键
                rimeValue = AppPrefs.getInstance().internal.pinyinModeRime.getValue()
                var keyBeans = mutableListOf<SoftKey>()
                val keys = when (rimeValue) {
                    CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.mspy,
                    CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.sogou,
                    CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.ziguang -> arrayListOf(
                        arrayOf(45, 51, 33, 46, 48, 53, 49, 37, 43, 44),
                        arrayOf(29, 47, 32, 34, 35, 36, 38, 39, 40),
                        arrayOf(74, 54, 52, 31, 50, 30, 42, 41, KeyEvent.KEYCODE_DEL),)
                    else -> {
                        KeyboardData.layoutQwertyCn[skbStyleMode]!!
                    }
                }
                var qwertyKeys = createQwertyPYKeys(keys[0])
                keyBeans.addAll(qwertyKeys)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                qwertyKeys = createQwertyPYKeys(keys[1])
                qwertyKeys.first().apply {
                    mLeftF = 0.06f
                }
                keyBeans.addAll(qwertyKeys)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                qwertyKeys = createQwertyPYKeys(keys[2])
                qwertyKeys.first().apply {
                    widthF = 0.147f
                }
                qwertyKeys.last().apply {
                    widthF = 0.147f
                }
                // 第 3 行首键统一为 Shift（对齐参考图）。
                // 原本只有 Google 风格是 Shift；Yuyan/Samsung 风格此处是
                // 75(KEYCODE_APOSTROPHE) 且标签为「分词」。
                // 统一后全键盘不再有独立的「分词」键位，需要分词符时可从符号键盘取。
                val softKeyToggle = createKeyToggle(KeyEvent.KEYCODE_SHIFT_LEFT)
                softKeyToggle.widthF = 0.147f
                softKeyToggle.setToggleStates(shiftToggleStates)
                qwertyKeys[0] = softKeyToggle
                keyBeans.addAll(qwertyKeys)
                rows.add(keyBeans)
                keyBeans = lastRows(skbValue)
                rows.add(keyBeans)
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN -> {  // 2000  T9键键
                var keyBeans: MutableList<SoftKey> = LinkedList()
                val keys = KeyboardData.layoutT9Cn[skbStyleMode]!!
                var t9Key = createT9Keys(keys[0])
                t9Key.first().apply {
                    widthF = 0.18f
                    heightF = 0.75f
                }
                t9Key.last().widthF = 0.18f
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                t9Key = createT9Keys(keys[1])
                t9Key.first().mLeftF = 0.185f
                t9Key.last().widthF = 0.18f
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                t9Key = createT9Keys(keys[2])
                t9Key.first().mLeftF = 0.185f
                t9Key.last().apply {
                    widthF = 0.18f
                    stateId = 7
                }
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = lastRows(skbValue)
                rows.add(keyBeans)

            }
            InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING -> {// 3000 手写键盘
                val env = EnvironmentSingleton.instance
                // 全屏手写只有画布长高：键行保持常规键盘的像素行高并锚在底部（对齐 Flyme 搜狗参考图）
                val fullHw = env.handwritingFullScreenActive && !env.isLandscape && !env.keyboardModeFloat
                var keyBeans: MutableList<SoftKey> = LinkedList()
                val keys = KeyboardData.layoutHandwritingCn[skbStyleMode]!!
                if (fullHw) {
                    // 底部两行按像素自下往上锚定（导航栏留白由 relayout 实时扣除），
                    // 画布比预估高时键行也不会掉到屏幕外
                    val rowH = env.handwritingKeyRowHeight
                    keyBeans.add(SoftKey(code = InputModeSwitcher.USER_KEYCODE_HW_SIDE_SETTING, label = "设置▶"))
                    ImeDataRepository.sideSymbols()
                        .map { it.symbolValue }.take(hwSideSymbolCount).forEach {
                            keyBeans.add(SoftKey(label = it).apply { functionalCap = true })
                        }
                    keyBeans.add(createHandwritingKey(keys[1][0])) // 删除键
                    anchorBottom(keyBeans, rowH, rowH)
                    assignWidths(keyBeans, listOf(0.150f, 0.175f, 0.175f, 0.175f, 0.175f, 0.150f))
                    rows.add(keyBeans)
                    keyBeans = lastRows(skbValue)
                    keyBeans.add(2, handWritingFullScreenToggleKey())
                    anchorBottom(keyBeans, rowH, 0)
                    assignWidths(keyBeans, listOf(0.148f, 0.116f, 0.175f, 0.237f, 0.175f, 0.148f))
                    rows.add(keyBeans)
                } else {
                    val rowF = 0.25f
                    var handwritingKey = createHandwritingKey(keys[1][0]) // 删除键，右列上
                    handwritingKey.mLeftF = 0.815f
                    handwritingKey.heightF = rowF
                    keyBeans.add(handwritingKey)
                    rows.add(keyBeans)
                    keyBeans = LinkedList()
                    handwritingKey = createHandwritingKey(keys[0][0]) // 符号占位，右列下
                    handwritingKey.mLeftF = 0.815f
                    handwritingKey.heightF = 2f * rowF
                    keyBeans.add(handwritingKey)
                    rows.add(keyBeans)
                    keyBeans = lastRows(skbValue)
                    keyBeans.add(2, handWritingFullScreenToggleKey())
                    assignWidths(keyBeans, listOf(0.147f, 0.116f, 0.175f, 0.225f, 0.175f, 0.147f))
                    rows.add(keyBeans)
                }
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC -> {// 4000 英文全键
                var keyBeans: MutableList<SoftKey> = LinkedList()
                val keys = KeyboardData.layoutQwertyEn[skbStyleMode]!!
                var qwertyKeys = createQwertyKeys(keys[0])
                keyBeans.addAll(qwertyKeys)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                qwertyKeys = createQwertyKeys(keys[1])
                qwertyKeys.first().apply {
                    mLeftF = 0.06f
                }
                keyBeans.addAll(qwertyKeys)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                val softKeyToggle = createKeyToggle(KeyEvent.KEYCODE_SHIFT_LEFT)
                softKeyToggle.widthF = 0.147f
                softKeyToggle.setToggleStates(shiftToggleStates)
                keyBeans.add(softKeyToggle)
                keyBeans.addAll(createQwertyKeys(keys[2]))
                keyBeans.last().apply {
                    widthF = 0.147f
                }
                rows.add(keyBeans)
                keyBeans = lastRows(skbValue)
                keyBeans[keyBeans.size -2].stateId = 1
                rows.add(keyBeans)
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER -> {  // 5000 数字键盘
                var keyBeans: MutableList<SoftKey> = LinkedList()
                val keys =  KeyboardData.layoutT9Number[skbStyleMode]!!
                var t9Keys = createT9NumberKeys(keys[0])
                t9Keys.first().apply {
                    widthF = 0.18f
                    heightF = 0.75f
                }
                t9Keys.last().widthF = 0.18f
                keyBeans.addAll(t9Keys)
                rows.add(keyBeans)

                keyBeans = LinkedList()
                t9Keys = createT9NumberKeys(keys[1])
                t9Keys.first().mLeftF = 0.185f
                t9Keys.last().widthF = 0.18f
                keyBeans.addAll(t9Keys)
                rows.add(keyBeans)

                keyBeans = LinkedList()
                t9Keys = createT9NumberKeys(keys[2])
                t9Keys.first().mLeftF = 0.185f
                t9Keys.last().widthF = 0.18f
                keyBeans.addAll(t9Keys)
                rows.add(keyBeans)
                keyBeans = lastRows(skbValue)
                rows.add(keyBeans)
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_LX17 -> {     // 6000 乱序17键盘
                var keyBeans: MutableList<SoftKey> = LinkedList()
                if(AppPrefs.getInstance().keyboardSetting.lx17WithLeftPrefix.getValue()) {
                    val keys = KeyboardData.layoutLX17CnWithLeftPrefix[skbStyleMode]!!
                    var lX17Keys = createLX17Keys(keys[0])
                    lX17Keys.first().apply {
                        widthF = 0.1457f
                        heightF = 0.75f
                    }
                    lX17Keys[1].mLeftF = 0.1457f
                    keyBeans.addAll(lX17Keys)
                    rows.add(keyBeans)
                    keyBeans = LinkedList()
                    lX17Keys = createLX17Keys(keys[1])
                    lX17Keys.first().mLeftF = 0.1457f
                    keyBeans.addAll(lX17Keys)
                    rows.add(keyBeans)
                    keyBeans = LinkedList()
                    lX17Keys = createLX17Keys(keys[2])
                    lX17Keys.first().mLeftF = 0.1457f
                    keyBeans.addAll(lX17Keys)
                    rows.add(keyBeans)
                    keyBeans = lastRows(skbValue)
                    rows.add(keyBeans)
                } else {
                    val keys =  KeyboardData.layoutLX17Cn[skbStyleMode]!!
                    var lX17Keys = createLX17Keys(keys[0], 0.165f)
                    keyBeans.addAll(lX17Keys)
                    rows.add(keyBeans)
                    keyBeans = LinkedList()
                    lX17Keys = createLX17Keys(keys[1], 0.165f)
                    keyBeans.addAll(lX17Keys)
                    rows.add(keyBeans)
                    keyBeans = LinkedList()
                    lX17Keys = createLX17Keys(keys[2], 0.165f)
                    keyBeans.addAll(lX17Keys)
                    rows.add(keyBeans)
                    keyBeans = lastRows(skbValue)
                    rows.add(keyBeans)
                }
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_STROKE -> {  // 7000  笔画键盘
                var keyBeans: MutableList<SoftKey> = LinkedList()
                val keys =  KeyboardData.layoutStrokeCn[skbStyleMode]!!
                var t9Key = createT9Keys(keys[0])
                t9Key.first().apply {
                    widthF = 0.18f
                    heightF = 0.75f
                }
                t9Key.last().widthF = 0.18f
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                t9Key = createT9Keys(keys[1])
                t9Key.first().mLeftF = 0.185f
                t9Key.last().widthF = 0.39f   // 撤掉通配键后，重输吃掉腾出的 0.21
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                t9Key = createT9Keys(keys[2])
                t9Key.first().mLeftF = 0.185f
                t9Key.last().widthF = 0.18f
                keyBeans.addAll(t9Key)
                rows.add(keyBeans)
                keyBeans = lastRows(skbValue)
                rows.add(keyBeans)
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_TEXTEDIT -> {     // 8000 文本编辑键盘
                var keyBeans: MutableList<SoftKey> = LinkedList()
                val keys =  KeyboardData.layoutTextEdit[skbStyleMode]!!
                var editKeys = createTextEditKeys(keys[0])
                editKeys[0].heightF = 0.75f
                editKeys[2].heightF = 0.75f
                keyBeans.addAll(editKeys)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                editKeys = createTextEditKeys(keys[1])
                editKeys[0].mLeftF = 0.25f
                editKeys[1].mLeftF = 0.75f
                keyBeans.addAll(editKeys)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                editKeys = createTextEditKeys(keys[2])
                editKeys[0].mLeftF = 0.25f
                editKeys[1].mLeftF = 0.75f
                keyBeans.addAll(editKeys)
                rows.add(keyBeans)
                keyBeans = LinkedList()
                editKeys = createTextEditKeys(keys[3])
                keyBeans.addAll(editKeys)
                rows.add(keyBeans)
            }
        }
        val numberLineSkb = when(skbStyleMode){
            SkbStyleMode.Qingyu -> numberLine
            SkbStyleMode.Samsung -> numberLine
            SkbStyleMode.Google -> numberLine
        }
        if (skbValue == InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC &&
            (InputModeSwitcher.isEmailEditor || InputModeSwitcher.isUrlEditor)) {
            rows.flatten().firstOrNull { it.code == InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI ||
                it.code == InputModeSwitcher.USER_KEYCODE_LEFT_COMMA }?.apply {
                code = 0
                label = if (InputModeSwitcher.isEmailEditor) "@" else "/"
                labelSmall = ""
            }
        }
        softKeyboard = getSoftKeyboard(rows, numberLineSkb)
        mSoftKeyboardMap[skbValue] = softKeyboard
        return softKeyboard
    }

    // 键盘最后一行（各键盘统一，数字键盘稍微不同）
    /** 全/半屏切换键：展开态显示「半/全屏」，收起态显示「全/半屏」 */
    private fun handWritingFullScreenToggleKey(): SoftKey {
        val expanded = AppPrefs.getInstance().handwriting.fullScreen.getValue()
        return SoftKey(code = InputModeSwitcher.USER_KEYCODE_HW_FULLSCREEN,
            label = if (expanded) "半" else "全", labelSmall = if (expanded) "/全屏" else "/半屏")
    }

    /** 自画布底部向上锚定行高，避免画布实际高度与预估不一致时整行掉到屏幕外 */
    private fun anchorBottom(keys: List<SoftKey>, rowHeight: Int, bottomOffset: Int) {
        keys.forEach { it.heightPx = rowHeight; it.bottomOffsetPx = bottomOffset }
    }

    /** 键数与参考排布不一致（其它键盘风格）时保留各自预设宽度 */
    private fun assignWidths(keys: List<SoftKey>, widths: List<Float>) {
        if (keys.size != widths.size) return
        keys.forEachIndexed { i, key -> key.widthF = widths[i] }
    }

    private fun lastRows(skbValue: Int): MutableList<SoftKey> {
        val enterToggleStates = listOf(ToggleState("去往", 2), ToggleState("搜索", 3), ToggleState("发送", 4),
            ToggleState("下一个", 5), ToggleState("完成", 6), ToggleState("上一个", 7))
        val softKeyToggle = createKeyToggle(KeyEvent.KEYCODE_ENTER)
        softKeyToggle.widthF = 0.18f
        softKeyToggle.stateId = 0
        softKeyToggle.setToggleStates(enterToggleStates)
        val keyBeans = mutableListOf<SoftKey>()
        val t9Keys = when(skbValue){
            InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN, InputModeSwitcher.MASK_SKB_LAYOUT_HANDWRITING, InputModeSwitcher.MASK_SKB_LAYOUT_STROKE ->{
                if(skbStyleMode == SkbStyleMode.Google){
                    createT9Keys(arrayOf(InputModeSwitcher.USER_KEYCODE_NUMBER, InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI, InputModeSwitcher.USER_KEYCODE_LANG,
                        KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD))
                } else if(skbStyleMode == SkbStyleMode.Samsung){
                    createT9Keys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_LANG,
                        KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_NUMBER))
                } else {
                    createT9Keys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_NUMBER,
                            KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LANG))
                }
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_ABC -> {
                if(skbStyleMode == SkbStyleMode.Google){
                    createQwertyKeys(arrayOf(InputModeSwitcher.USER_KEYCODE_NUMBER, InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI, InputModeSwitcher.USER_KEYCODE_LANG,
                        KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD))
                } else if (skbStyleMode == SkbStyleMode.Samsung) {
                    createQwertyKeys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_LANG,
                        InputModeSwitcher.USER_KEYCODE_LEFT_COMMA, KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD, InputModeSwitcher.USER_KEYCODE_NUMBER))
                } else {
                    createQwertyKeys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_NUMBER,
                            InputModeSwitcher.USER_KEYCODE_LEFT_COMMA, KeyEvent.KEYCODE_SPACE,
                            InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD, InputModeSwitcher.USER_KEYCODE_LANG))
                }
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER -> {
                createT9NumberKeys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_RETURN, 7, KeyEvent.KEYCODE_SPACE))
            }
            InputModeSwitcher.MASK_SKB_LAYOUT_LX17 -> {
                if(skbStyleMode == SkbStyleMode.Google){
                    createT9Keys(arrayOf(InputModeSwitcher.USER_KEYCODE_NUMBER, InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI, InputModeSwitcher.USER_KEYCODE_LANG,
                        KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD))
                } else if (skbStyleMode == SkbStyleMode.Samsung) {
                    createLX17Keys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_LANG,
                        InputModeSwitcher.USER_KEYCODE_LEFT_COMMA, KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_NUMBER))
                } else {
                    createLX17Keys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_NUMBER,
                            InputModeSwitcher.USER_KEYCODE_LEFT_COMMA, KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LANG))
                }
            }
            else -> { //0x1000 InputModeSwitcherManager.MASK_SKB_LAYOUT_QWERTY_PINYIN
                if(skbStyleMode == SkbStyleMode.Google){
                    createQwertyPYKeys(arrayOf(InputModeSwitcher.USER_KEYCODE_NUMBER, InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI, InputModeSwitcher.USER_KEYCODE_LANG,
                        KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD))
                } else if (skbStyleMode == SkbStyleMode.Samsung) {
                    createQwertyPYKeys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_LANG,
                        InputModeSwitcher.USER_KEYCODE_LEFT_COMMA, KeyEvent.KEYCODE_SPACE, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD, InputModeSwitcher.USER_KEYCODE_NUMBER))
                } else {
                    createQwertyPYKeys(arrayOf(InputModeSwitcher.USER_KEYCODE_SYMBOL, InputModeSwitcher.USER_KEYCODE_NUMBER,
                            InputModeSwitcher.USER_KEYCODE_LEFT_COMMA, KeyEvent.KEYCODE_SPACE,
                            InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD, InputModeSwitcher.USER_KEYCODE_LANG))
                }
            }
        }
        if (skbStyleMode == SkbStyleMode.Google) {
            t9Keys[2].stateId = 2
            when (t9Keys.size) {
                6 -> {
                    t9Keys[0].widthF = 0.185f;t9Keys[1].widthF = 0.1f
                    t9Keys[2].widthF = 0.1f;t9Keys[3].widthF = 0.23f
                    t9Keys[4].widthF = 0.1f;t9Keys[5].widthF = 0.1f
                    softKeyToggle.widthF = 0.185f
                }
                5 -> {
                    t9Keys[0].widthF = 0.185f;t9Keys[1].widthF = 0.1f
                    t9Keys[2].widthF = 0.1f;t9Keys[3].widthF = 0.33f
                    t9Keys[4].widthF = 0.1f;softKeyToggle.widthF = 0.185f
                }
                else -> {
                    softKeyToggle.widthF = 0.18f
                    t9Keys[0].widthF = 0.18f;t9Keys[1].widthF = 0.21f
                    t9Keys[2].widthF = 0.21f;t9Keys[3].widthF = 0.21f
                }
            }
        } else if (skbStyleMode == SkbStyleMode.Samsung) {
            if(skbValue == 0x4000)t9Keys[1].stateId = 1
            when (t9Keys.size) {
                6 -> {
                    t9Keys[0].widthF = 0.1457f;t9Keys[1].widthF = 0.1457f
                    t9Keys[2].widthF = 0.099f;t9Keys[3].widthF = 0.2f
                    t9Keys[4].widthF = 0.099f;t9Keys[5].widthF = 0.1457f
                    softKeyToggle.widthF = 0.1457f
                }
                5 -> {
                    t9Keys[0].widthF = 0.16f;t9Keys[1].widthF = 0.099f
                    t9Keys[2].widthF = 0.099f;t9Keys[3].widthF = 0.38f
                    t9Keys[4].widthF = 0.099f;softKeyToggle.widthF = 0.16f
                }
                else -> {
                    softKeyToggle.widthF = 0.18f
                    t9Keys[0].widthF = 0.18f;t9Keys[1].widthF = 0.21f
                    t9Keys[2].widthF = 0.21f;t9Keys[3].widthF = 0.21f
                }
            }
        } else {
            when (t9Keys.size) {
                6 -> {
                    // 符 | 数字 | 逗号 | 空格 | 句号 | 中英 + 回车
                    // 键宽比例按参考输入法底行实测：标点键收窄、123/中英加宽、空格 0.257
                    softKeyToggle.widthF = 0.150f
                    t9Keys[0].widthF = 0.150f;t9Keys[1].widthF = 0.1185f
                    t9Keys[2].widthF = 0.100f;t9Keys[3].widthF = 0.2574f
                    t9Keys[4].widthF = 0.100f;t9Keys[5].widthF = 0.1185f
                }
                5 -> {
                    softKeyToggle.widthF = 0.147f
                    t9Keys[0].widthF = 0.147f;t9Keys[1].widthF = 0.099f
                    t9Keys[2].widthF = 0.099f;t9Keys[3].widthF = 0.396f
                    t9Keys[4].widthF = 0.099f
                }
                else -> {
                    t9Keys[0].widthF = 0.18f;t9Keys[1].widthF = 0.147f
                    t9Keys[2].widthF = 0.336f;t9Keys[3].widthF = 0.147f
                }
            }
        }
        keyBeans.addAll(t9Keys)
        keyBeans.add(softKeyToggle)
        return keyBeans
    }

    fun changeSKBNumberRow() {
        for (skbValue in mSoftKeyboardMap.keys) {
            loadBaseSkb(skbValue)
        }
    }

    fun getSoftKeyboard(skbValue: Int): SoftKeyboard {
        val symbols = ImeDataRepository.sideSymbols() + ImeDataRepository.sideSymbols("number")
        if (loadedSideSymbols != symbols) {
            loadedSideSymbols = symbols
            clearKeyboardMap()
        }
        var softKeyboard = mSoftKeyboardMap[skbValue]
        if (softKeyboard == null) {
            softKeyboard = loadBaseSkb(skbValue)
        }
        return softKeyboard
    }

    fun createPreviewKeyboard(skbValue: Int): SoftKeyboard = KeyboardLoaderUtil().getSoftKeyboard(skbValue)

    private var loadedSideSymbols = emptyList<com.qingyu.imemodule.database.entry.SideSymbol>()

    /** 生成键盘布局，主要用于计算键盘边界 */
    private fun getSoftKeyboard(rows: List<List<SoftKey>>, isNumberRow: Boolean): SoftKeyboard {
        var lastKeyBottom = 0f
        var lastKeyRight: Float
        var lastKeyTop: Float
        for (rowBean in rows) {
            lastKeyTop = lastKeyBottom  // 新行top为上一行bottom
            lastKeyRight = 0.005f // 新行x从0.005开始
            for (keyBean in rowBean) {
                var keyXPos = keyBean.mLeftF
                var keyYPos = keyBean.mTopF
                val keyWidth = keyBean.widthF
                val keyHeight = keyBean.heightF
                if(keyXPos == -1f || keyYPos == -1f || isNumberRow) {
                    if (keyXPos == -1f) keyXPos = lastKeyRight
                    if (keyYPos == -1f) keyYPos = lastKeyTop
                    if (isNumberRow) {
                        keyBean.setKeyDimensions(keyXPos, keyYPos/1.2f, keyHeight/1.2f)
                    } else {
                        keyBean.setKeyDimensions(keyXPos, keyYPos)
                    }
                }
                keyBean.setSkbCoreSize(EnvironmentSingleton.instance.skbWidth, EnvironmentSingleton.instance.skbHeight)
                lastKeyRight = keyXPos + keyWidth
                lastKeyTop = keyYPos
                lastKeyBottom = keyYPos + keyHeight
            }
        }
        return SoftKeyboard(rows)
    }

    private fun createT9Keys(codes: Array<Int>): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset =  if(mSkbValue == 0x7000) KeyPreset.strokeKeyPreset else KeyPreset.t9PYKeyPreset
        // 「拼音九键显示分词」关闭时，九键的 1 槽位只显示数字，分词符仍可通过空格键分隔输入。
        val hideSplit = mSkbValue == InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN &&
            !AppPrefs.getInstance().keyboardSetting.t9ShowSplit.getValue()
        for(code in codes){
            val labels = if (hideSplit && code == KeyEvent.KEYCODE_APOSTROPHE) arrayOf("1") else keyPreset[code]
            softKeys.add(SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1)?: "").apply {
                widthF = 0.21f
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createT9NumberKeys(codes: Array<Int>): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset = KeyPreset.t9NumberKeyPreset
        for(code in codes){
            val labels = keyPreset[code]
            softKeys.add(SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1) ?: "").apply {
                widthF = 0.21f
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createQwertyPYKeys(codes: Array<Int>): Array<SoftKey> {
        val keyMnemonicPreset = when (rimeValue) {
            CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.flypy -> doubleFlyMnemonicPreset
            CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.abc -> doubleAbcMnemonicPreset
            CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.mspy -> doubleMSMnemonicPreset
            CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.natural -> doubleNaturalMnemonicPreset
            CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.sogou -> doubleSogouMnemonicPreset
            CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY + DoublePinyinSchemaMode.ziguang -> doubleZiguangMnemonicPreset
            else -> emptyMap()
        }
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset = if(numberLine)KeyPreset.qwertyPYKeyPreset else KeyPreset.qwertyPYKeyNumberPreset
        for(code in codes){
            val labels = keyPreset[code]
            softKeys.add(SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1) ?: "", keyMnemonic = keyMnemonicPreset[code] ?: "").apply {
                widthF = 0.099f
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createQwertyKeys(codes: Array<Int>): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset = if(numberLine)KeyPreset.qwertyKeyPreset else KeyPreset.qwertyKeyNumberPreset
        for(code in codes){
            val labels = keyPreset[code]
            softKeys.add(SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1) ?: "", keyMnemonic = labels?.getOrNull(2) ?: "").apply {
                widthF = 0.099f
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createHandwritingKey(code: Int): SoftKey {
        val keyPreset = if(numberLine)KeyPreset.qwertyPYKeyPreset else KeyPreset.qwertyPYKeyNumberPreset
        val labels = keyPreset[code]
        return SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1) ?: "").apply {
            widthF = 0.18f
        }
    }

    private fun createNumberLineKeys(codes: Array<Int>): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        for(code in codes) {
            val softKey = SoftKey(label = code.toString()).apply {
                widthF = 0.099f
                heightF = 0.2f
            }
            softKeys.add(softKey)
        }
        return softKeys.toTypedArray()
    }

    private fun createLX17Keys(codes: Array<Int>, width: Float = 0.142f): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset = if(numberLine)KeyPreset.lx17PYKeyPreset else KeyPreset.lx17PYKeyNumberPreset
        for(code in codes){
            val labels = keyPreset[code]
            softKeys.add(SoftKey(code = code, label = labels?.getOrNull(0) ?: "", labelSmall = labels?.getOrNull(1) ?: "", keyMnemonic= lx17MnemonicPreset[code] ?: "").apply {
                widthF = width
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createTextEditKeys(codes: Array<Int>): Array<SoftKey> {
        val softKeys = mutableListOf<SoftKey>()
        val keyPreset = KeyPreset.textEditKeyPreset
        for(code in codes){
            val labels = keyPreset[code]
            softKeys.add(SoftKey(code = code, label = labels?.getOrNull(0) ?: "").apply {
                widthF = 0.2475f
            })
        }
        return softKeys.toTypedArray()
    }

    private fun createKeyToggle(code: Int): SoftKeyToggle {
        return SoftKeyToggle(code)
    }

    companion object {
        private var mInstance: KeyboardLoaderUtil? = null
        private val mSoftKeyboardMap = HashMap<Int, SoftKeyboard?>() //缓存所有可用键盘
        /** 全屏手写符号行放得下的侧边符号个数（参考图：设置 + 4 个符号 + 删除） */
        private const val hwSideSymbolCount = 4
        @JvmStatic
        val instance: KeyboardLoaderUtil
            get() {
                if (null == mInstance) mInstance = KeyboardLoaderUtil()
                return mInstance!!
            }
    }
}
