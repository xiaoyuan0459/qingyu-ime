package com.qingyu.imemodule.view

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.theme.Theme
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.database.ImeDataRepository
import com.qingyu.imemodule.database.entry.Phrase
import com.qingyu.imemodule.utils.HanziPinyin
import com.qingyu.imemodule.view.widget.ImeEditText
import com.qingyu.inputmethod.util.LX17PinYinUtils
import com.qingyu.inputmethod.util.T9PinYinUtils
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

class EditPhrasesView(context: Context) : LinearLayout(context){
    private var originalContent: String? = null
    private var draftGeneration = 0L
    private var saving = false

    private var mEtPhrases: ImeEditText
    private var mEtPhrasesQuickCode:ImeEditText
    private var mEtPhrasesTips:TextView
    init {
        orientation = VERTICAL
        mEtPhrasesTips = TextView(context).apply {
            setText(R.string.add_phrases_input_tips)
            setPadding(dp(5))
            textSize = 12f
        }
        mEtPhrasesQuickCode = ImeEditText(context).apply {
            setText(R.string.add_phrases_input_tips)
            setPadding(dp(5))
            background = null
            isCursorVisible = true
            isFocusable = true
            isFocusableInTouchMode = true
            inputType
            textSize = 12f
        }
       val llTipContainer = LinearLayout(context)
        llTipContainer.add(mEtPhrasesTips, lParams(weight = 0f))
        llTipContainer.add(mEtPhrasesQuickCode, lParams(weight = 1f))
        mEtPhrases = ImeEditText(context).apply {
            background = null
            isCursorVisible = true
            isFocusable = true
            isFocusableInTouchMode = true
            gravity = Gravity.CENTER_VERTICAL
            textSize = 16f
            minLines = 3
            setPadding(dp(10))
            setHint(R.string.add_phrases_input_hint)
        }
        add(llTipContainer, lParams(width = matchParent,height = wrapContent, weight = 0f))
        add(mEtPhrases, lParams(width = matchParent,height = wrapContent, weight = 1f))
    }

    fun handleAddPhrasesView() {
        mEtPhrases.requestFocus()
        mEtPhrases.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(editable: Editable) {
                val pinYinHeadChar  = HanziPinyin.initials(editable.toString().take(4))
                if(pinYinHeadChar.isNotBlank()) {
                    mEtPhrasesQuickCode.setText(pinYinHeadChar)
                    mEtPhrasesQuickCode.setSelection(pinYinHeadChar.length)
                }
            }
        })
    }

    fun clearPhrasesContent() {
        draftGeneration += 1
        saving = false
        originalContent = null
        mEtPhrases.setText("")
        mEtPhrasesQuickCode.setText("")
    }

    fun addPhrasesHandle(onSaved: () -> Unit) {
        if (saving) return
        val content = mEtPhrases.text.toString()
        if(content.isNotBlank()) {
            val pinYinHeadChar  = mEtPhrasesQuickCode.text.toString()
            val pinYinHeadT9 = pinYinHeadChar.map { T9PinYinUtils.pinyin2T9Key(it)}.joinToString("")
            val pinYinHeadLX17 = pinYinHeadChar.map { LX17PinYinUtils.pinyin2Lx17Key(it)}.joinToString("")
            val phrase =  Phrase(content = content, t9 = pinYinHeadT9, qwerty = pinYinHeadChar, lx17 = pinYinHeadLX17)
            val replacedContent = originalContent
            val generation = draftGeneration
            saving = true
            ImeDataRepository.mutate(onComplete = {
                if (generation == draftGeneration) {
                    saving = false
                    originalContent = content
                    if (isAttachedToWindow && mEtPhrases.text.toString() == content &&
                        mEtPhrasesQuickCode.text.toString() == pinYinHeadChar) onSaved()
                }
            }, onFailure = { if (generation == draftGeneration) saving = false }) {
                replacedContent?.let { phraseDao().deleteByContent(it) }
                phraseDao().insert(phrase)
            }
        }
    }

    fun setExtraData(extra:Phrase) {
        draftGeneration += 1
        saving = false
        originalContent = extra.content
        val content = extra.content
        val qwerty = extra.qwerty
        mEtPhrases.setText(content)
        mEtPhrases.setSelection(content.length)
        mEtPhrasesQuickCode.setText(qwerty)
        mEtPhrasesQuickCode.setSelection(qwerty.length)
    }

    fun commitText(text:String) {
        val currentView = if(mEtPhrasesQuickCode.isFocused)mEtPhrasesQuickCode else mEtPhrases
        currentView.commitText(text)
    }

    fun sendKeyEvent(keyCode: Int) {
        val currentView = if(mEtPhrasesQuickCode.isFocused)mEtPhrasesQuickCode else mEtPhrases
        when(keyCode){
            KeyEvent.KEYCODE_DEL ->{
                currentView.onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                currentView.onKeyUp(keyCode, KeyEvent(KeyEvent.ACTION_UP, keyCode))
            }
            else -> {
                val unicodeChar: Char = KeyEvent(KeyEvent.ACTION_DOWN, keyCode).unicodeChar.toChar()
                if (unicodeChar != Character.MIN_VALUE)currentView.commitText(unicodeChar.toString())
            }
        }
    }

    fun updateTheme(theme: Theme) {
        val keyTextColor = ThemeManager.activeTheme.keyTextColor
        setBackgroundColor(theme.barColor)
        mEtPhrases.background = GradientDrawable().apply {
            setColor(ThemeManager.activeTheme.keyBackgroundColor)
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ThemeManager.prefs.keyRadius.getValue().toFloat()
        }
        mEtPhrases.setTextColor(keyTextColor)
        mEtPhrases.setHintTextColor(keyTextColor)
        mEtPhrasesTips.setTextColor(keyTextColor)
        mEtPhrasesQuickCode.setTextColor(keyTextColor)
    }
}
