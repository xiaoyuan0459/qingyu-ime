package com.qingyu.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.clipboard.ChineseLexicon
import com.qingyu.imemodule.data.clipboard.ClipboardTextSplitter
import com.qingyu.imemodule.data.theme.ThemeManager.activeTheme
import com.qingyu.imemodule.utils.keyboardItemBackground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.dimensions.dp

/**
 * 剪切板「拆词」面板：把整条文本一次性炸成词，每个词可点选，再对上屏/复制。
 *
 * 交互参考锤子 Big Bang：**点词是选中，不是立刻上屏**。选中多段后按文本顺序拼接，
 * 这样既能只取一个词，也能挑几个词拼成一句话，还能整段取回。
 *
 * 片段只存在于本视图生命周期内，不进持久化，也不进 saved instance state。
 */
@SuppressLint("ViewConstructor")
internal class ClipboardSplitPanel(
    context: Context,
    text: String,
    private val insert: (String) -> Unit,
) : LinearLayout(context) {
    private var source = text
    private val scope = MainScope()
    private var splitJob: Job? = null
    private var characters = false
    private var failed = false
    private val selected = sortedSetOf<Int>()
    private val parts = PartsAdapter()

    private val status = TextView(context).apply {
        textSize = 14f
        setTextColor(activeTheme.keyTextColor)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(2), dp(12), dp(2))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setOnClickListener { if (failed) split() }
        isClickable = false
    }
    private val words = control(R.string.clipboard_split_words) { characters = false; split() }
    private val letters = control(R.string.clipboard_split_characters) { characters = true; split() }
    private val selectAll = control(R.string.clipboard_split_select_all) { toggleSelectAll() }
    private val list = RecyclerView(context).apply {
        layoutManager = FlexboxLayoutManager(context)
        adapter = parts
        itemAnimator = null
        setPadding(dp(8), dp(2), dp(8), dp(2))
    }
    private val insertAction = action(R.string.clipboard_split_insert, primary = true) { commit(insert = true) }
    private val copyAction = action(R.string.clipboard_split_copy, primary = false) { commit(insert = false) }
    private val actions = LinearLayout(context).apply {
        orientation = HORIZONTAL
        addView(insertAction, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(copyAction, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        visibility = View.GONE
    }

    init {
        orientation = VERTICAL
        addView(LinearLayout(context).apply {
            listOf(words, letters, selectAll).forEach {
                addView(it, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        })
        addView(status, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(list, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(actions, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun control(title: Int, action: () -> Unit) = TextView(context).apply {
        setText(title)
        textSize = 16f
        setTextColor(activeTheme.keyTextColor)
        gravity = Gravity.CENTER
        minHeight = dp(44)
        maxLines = 2
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = keyboardItemBackground(context)
        setOnClickListener { action() }
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
                if (title != R.string.clipboard_split_select_all) {
                    info.isCheckable = true
                    info.isChecked = host.isSelected
                }
            }
        })
    }

    private fun action(title: Int, primary: Boolean, run: () -> Unit) = TextView(context).apply {
        setText(title)
        textSize = 16f
        gravity = Gravity.CENTER
        minHeight = dp(46)
        maxLines = 1
        setTextColor(if (primary) activeTheme.accentKeyTextColor else activeTheme.keyTextColor)
        background = if (primary) {
            GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(activeTheme.accentKeyBackgroundColor)
            }
        } else keyboardItemBackground(context)
        val margin = dp(4)
        layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(margin, margin, margin, margin)
        }
        setOnClickListener { run() }
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        split()
    }

    private fun split() {
        splitJob?.cancel()
        failed = false
        status.isClickable = false
        status.minHeight = 0
        status.background = null
        words.isSelected = !characters
        letters.isSelected = characters
        selected.clear()
        updateActions()
        val content = source
        val characterMode = characters
        parts.submit(emptyList())
        status.setText(R.string.clipboard_split_loading)
        splitJob = scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    ClipboardTextSplitter.split(content, characterMode, ChineseLexicon.get(context))
                }
                parts.submit(result.parts)
                status.setText(when {
                    result.parts.isEmpty() -> R.string.clipboard_split_empty
                    result.limited -> R.string.clipboard_split_limited
                    else -> R.string.clipboard_split_hint
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 异常信息与拆分内容都属敏感数据，不写日志
                failed = true
                status.isClickable = true
                status.minHeight = dp(44)
                status.background = keyboardItemBackground(context)
                status.setText(R.string.clipboard_split_failed)
            }
        }
    }

    private fun toggle(index: Int) {
        if (!selected.add(index)) selected.remove(index)
        parts.notifyItemChanged(index)
        updateActions()
    }

    private fun toggleSelectAll() {
        val total = parts.itemCount
        if (total == 0) return
        if (selected.size == total) {
            selected.clear()
        } else {
            for (i in 0 until total) selected.add(i)
        }
        parts.notifyDataSetChanged()
        updateActions()
    }

    /** 选中的片段按**文本顺序**拼接，而不是点选顺序——拼出来的话才是原文顺序。 */
    private fun selectedText(): String = selected.joinToString("") { parts.valueOf(it) }

    private fun commit(insert: Boolean) {
        if (selected.isEmpty()) return
        val text = selectedText()
        if (text.isEmpty()) return
        if (insert) {
            if (isShown) this.insert(text)
            return
        }
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText("text", text))
        status.setText(context.getString(R.string.clipboard_split_copied, selected.size))
    }

    private fun updateActions() {
        val count = selected.size
        actions.visibility = if (count == 0) View.GONE else View.VISIBLE
        insertAction.text = if (count > 0) context.getString(R.string.clipboard_split_insert) + "($count)"
        else context.getString(R.string.clipboard_split_insert)
        copyAction.text = if (count > 0) context.getString(R.string.clipboard_split_copy) + "($count)"
        else context.getString(R.string.clipboard_split_copy)
        if (count > 0 && !failed) status.setText(context.getString(R.string.clipboard_split_selected, count))
    }

    fun release() {
        source = ""
        splitJob?.cancel()
        scope.cancel()
        selected.clear()
        parts.submit(emptyList())
        list.adapter = null
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private inner class PartsAdapter : RecyclerView.Adapter<PartsAdapter.Holder>() {
        private var values: List<String> = emptyList()
        inner class Holder(val label: TextView) : RecyclerView.ViewHolder(label)

        fun valueOf(index: Int): String = values.getOrElse(index) { "" }

        fun submit(value: List<String>) {
            values = value
            selected.clear()
            notifyDataSetChanged()
            updateActions()
        }

        override fun getItemCount() = values.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(TextView(context).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            minWidth = dp(44)
            minHeight = dp(44)
            maxWidth = (parent.width - dp(24)).coerceAtLeast(dp(44))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = FlexboxLayoutManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
            ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isCheckable = true
                    info.isChecked = host.isSelected
                    info.className = android.widget.ToggleButton::class.java.name
                }
            })
        })

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val value = values[position]
            val label = holder.label
            val isSelected = position in selected
            label.isSelected = isSelected
            label.text = if (value.all(Char::isWhitespace)) {
                value.replace("\r\n", "↵").map {
                    when (it) {
                        '\n', '\r' -> '↵'
                        '\t' -> '⇥'
                        else -> if (it.isWhitespace()) '·' else it
                    }
                }.joinToString("")
            } else value
            // 选中态用强调色反白，未选中用键盘键面底色，深浅主题都成立
            if (isSelected) {
                label.setTextColor(activeTheme.accentKeyTextColor)
                label.background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(activeTheme.accentKeyBackgroundColor)
                }
            } else {
                label.setTextColor(activeTheme.keyTextColor)
                label.background = keyboardItemBackground(context)
            }
            val whitespaceLabel = when {
                value.all { it == ' ' } -> R.string.clipboard_split_spaces
                value.all { it == '\r' || it == '\n' } -> R.string.clipboard_split_linebreak
                value.all { it == '\t' } -> R.string.clipboard_split_tabs
                else -> R.string.clipboard_split_whitespace
            }
            label.contentDescription = if (value.all(Char::isWhitespace)) {
                context.getString(whitespaceLabel, value.length)
            } else value
            label.setOnClickListener { toggle(position) }
        }
    }
}
