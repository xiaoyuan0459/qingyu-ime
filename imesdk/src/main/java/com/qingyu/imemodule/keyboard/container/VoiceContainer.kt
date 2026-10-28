package com.qingyu.imemodule.keyboard.container

import android.annotation.SuppressLint

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.keyboard.InputView
import com.qingyu.imemodule.service.OnDeviceVoiceInput
import splitties.dimensions.dp

/** 容器依赖 InputView 的服务状态，只能由键盘创建，不支持 XML 与预览。 */
@SuppressLint("ViewConstructor")
class VoiceContainer(context: Context, inputView: InputView) : BaseContainer(context, inputView) {
    private val status = TextView(context).apply {
        textSize = 20f
        gravity = Gravity.CENTER
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setTextColor(ThemeManager.activeTheme.keyTextColor)
    }
    private val action = Button(context).apply { setOnClickListener { inputView.requestVoiceInput() } }
    override val chromeInBar: Boolean get() = true
    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(status, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(action, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(Button(context).apply {
                setText(R.string.cancel)
                setOnClickListener { inputView.cancelVoiceInput() }
            }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onChromeBack() {
        inputView.cancelVoiceInput()
    }
    fun bind(state: OnDeviceVoiceInput.State) {
        // 本地识别会带回逐字上屏的中间结果，优先显示它，用户能看见自己在说什么
        status.text = if (state.text.isNotEmpty()) state.text else context.getString(state.message)
        action.setText(if (state.phase == OnDeviceVoiceInput.Phase.Listening) R.string.voice_stop else R.string.voice_retry)
        action.visibility = if (state.phase == OnDeviceVoiceInput.Phase.Processing) GONE else VISIBLE
    }
}
