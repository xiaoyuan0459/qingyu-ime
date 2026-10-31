package com.qingyu.imemodule.adapter

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.emoji2.widget.EmojiTextView
import androidx.recyclerview.widget.RecyclerView
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.theme.ThemeManager.activeTheme
import com.qingyu.imemodule.database.entry.Clipboard
import com.qingyu.imemodule.utils.keyboardItemBackground
import splitties.dimensions.dp

/**
 * 剪切板界面适配器。
 *
 * 对齐搜狗：固定行宽的单列扁平列表，左对齐、无大圆角卡片，只在条目底部画一条细分割线。
 */
class ClipBoardAdapter(
    context: Context,
    datas: MutableList<Clipboard>,
    private val split: ((String) -> Unit)? = null,
) : RecyclerView.Adapter<ClipBoardAdapter.SymbolHolder>() {
    private var mDatas: MutableList<Clipboard> = datas
    private val mContext: Context
    private var textColor: Int

    init {
        textColor = activeTheme.keyTextColor
        mContext = context
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolHolder {
        val container = RelativeLayout(mContext)
        container.minimumHeight = mContext.dp(52)
        container.gravity = Gravity.CENTER_VERTICAL
        container.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        container.background = keyboardItemBackground(mContext)

        val content = EmojiTextView(mContext).apply {
            id = R.id.clipboard_adapter_content
            // 搜狗长文本最多两行，超出省略，行高不会被一条长文本撑得过厚。
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(mContext.dp(16), mContext.dp(12), mContext.dp(16), mContext.dp(12))
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT,
            ).apply { addRule(RelativeLayout.CENTER_VERTICAL, RelativeLayout.TRUE) }
        }
        val topTips = ImageView(mContext).apply {
            id = R.id.clipboard_adapter_top_tips
            setImageResource(R.drawable.ic_baseline_top_tips_32)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.TRUE)
                addRule(RelativeLayout.ALIGN_PARENT_END, RelativeLayout.TRUE)
                setMargins(0, mContext.dp(6), mContext.dp(12), 0)
            }
        }
        container.addView(content)
        container.addView(topTips)
        return SymbolHolder(container)
    }

    override fun onBindViewHolder(holder: SymbolHolder, position: Int) {
        val data = mDatas[position]
        holder.textView.text = data.content.replace("\n", "\\n")
        holder.ivTopTips.visibility = if (data.isKeep == 1) View.VISIBLE else View.GONE
        if (split != null) {
            holder.itemView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            ViewCompat.setAccessibilityDelegate(holder.itemView, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.text = data.content
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            R.id.accessibility_clipboard_split,
                            host.context.getString(R.string.clipboard_split_action),
                        )
                    )
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    if (action == R.id.accessibility_clipboard_split) {
                        split.invoke(data.content)
                        return true
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            })
        }
    }

    override fun getItemCount(): Int = mDatas.size

    inner class SymbolHolder(view: RelativeLayout) : RecyclerView.ViewHolder(view) {
        var textView: TextView = view.findViewById(R.id.clipboard_adapter_content)
        var ivTopTips: ImageView = view.findViewById(R.id.clipboard_adapter_top_tips)

        init {
            textView.setTextColor(textColor)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
    }
}
