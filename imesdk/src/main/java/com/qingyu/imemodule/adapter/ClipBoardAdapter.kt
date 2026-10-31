package com.qingyu.imemodule.adapter

import android.content.Context
import android.os.Bundle
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.emoji2.widget.EmojiTextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.theme.ThemeManager
import com.qingyu.imemodule.data.theme.ThemeManager.activeTheme
import com.qingyu.imemodule.database.entry.Clipboard
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.behavior.ClipboardLayoutMode
import com.qingyu.imemodule.singleton.EnvironmentSingleton.Companion.instance
import com.qingyu.imemodule.utils.DevicesUtils.dip2px
import com.qingyu.imemodule.utils.keyboardItemBackground
import splitties.views.dsl.core.margin
import splitties.dimensions.dp

/**
 * 剪切板界面适配器
 */
class ClipBoardAdapter(context: Context, datas: MutableList<Clipboard>, private val split: ((String) -> Unit)? = null) :
    RecyclerView.Adapter<ClipBoardAdapter.SymbolHolder>() {
    private var mDatas : MutableList<Clipboard> = datas
    private val mContext: Context
    private var textColor: Int
    private var clipboardLayoutCompact: ClipboardLayoutMode

    init {
        val theme = activeTheme
        textColor = theme.keyTextColor
        mContext = context
        clipboardLayoutCompact = AppPrefs.getInstance().clipboard.clipboardLayoutCompact.getValue()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolHolder {
        val mContainer = RelativeLayout(mContext)
        mContainer.minimumHeight = mContext.dp(48)
        mContainer.gravity = Gravity.CENTER_VERTICAL
        val marginValue = dip2px(3)
        val listMode = clipboardLayoutCompact == ClipboardLayoutMode.ListView
        if (listMode) {
            // 对齐搜狗剪贴板：单列扁平行，左对齐、无大圆角卡片，只在底部画一条细分割线。
            mContainer.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            mContainer.background = keyboardItemBackground(mContext)
        } else {
            mContainer.layoutParams = GridLayoutManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(marginValue, marginValue, marginValue, marginValue)
            }
            mContainer.background = GradientDrawable().apply {
                setColor(activeTheme.keyBackgroundColor)
                setShape(GradientDrawable.RECTANGLE)
                setCornerRadius(ThemeManager.prefs.keyRadius.getValue().toFloat())
            }
        }
        val viewContext = EmojiTextView(mContext).apply {
            id = R.id.clipboard_adapter_content
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            if (listMode) {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(mContext.dp(16), mContext.dp(10), mContext.dp(16), mContext.dp(10))
                layoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                ).apply { addRule(RelativeLayout.CENTER_VERTICAL, RelativeLayout.TRUE) }
            } else {
                gravity = Gravity.CENTER
                layoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    margin = marginValue
                    addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
                }
            }
        }
        val viewIvYopTips = ImageView(mContext).apply {
            id = R.id.clipboard_adapter_top_tips
            setImageResource(R.drawable.ic_baseline_top_tips_32)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.TRUE)
                addRule(RelativeLayout.ALIGN_PARENT_END, RelativeLayout.TRUE)
                if (listMode) setMargins(0, mContext.dp(6), mContext.dp(12), 0)
            }
        }
        mContainer.addView(viewContext)
        mContainer.addView(viewIvYopTips)
        if (listMode) {
            val dividerColor = androidx.core.graphics.ColorUtils.setAlphaComponent(
                textColor, (android.graphics.Color.alpha(textColor) * 0.12f).toInt(),
            )
            mContainer.addView(View(mContext).apply {
                setBackgroundColor(dividerColor)
                layoutParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT, 1,
                ).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE)
                    setMargins(mContext.dp(16), 0, mContext.dp(16), 0)
                }
            })
        }
        return SymbolHolder(mContainer)
    }

    override fun onBindViewHolder(holder: SymbolHolder, position: Int) {
        val data = mDatas[position]
        holder.textView.text = data.content.replace("\n", "\\n")
        holder.ivTopTips.visibility = if(data.isKeep == 1)View.VISIBLE else View.GONE
        if (split != null) {
            holder.itemView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            ViewCompat.setAccessibilityDelegate(holder.itemView, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.text = data.content
                    info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        R.id.accessibility_clipboard_split, host.context.getString(R.string.clipboard_split_action)))
                }
                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    if (action == R.id.accessibility_clipboard_split) { split.invoke(data.content); return true }
                    return super.performAccessibilityAction(host, action, args)
                }
            })
        }
    }

    override fun getItemCount(): Int {
        return mDatas.size
    }

    inner class SymbolHolder(view: RelativeLayout) : RecyclerView.ViewHolder(view) {
        var textView: TextView = view.findViewById(R.id.clipboard_adapter_content)
        var ivTopTips: ImageView
        init {
            textView.setTextColor(textColor)
            if (clipboardLayoutCompact == ClipboardLayoutMode.ListView) {
                textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            } else {
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, instance.candidateTextSize)
            }
            ivTopTips = view.findViewById(R.id.clipboard_adapter_top_tips)
        }
    }
}
