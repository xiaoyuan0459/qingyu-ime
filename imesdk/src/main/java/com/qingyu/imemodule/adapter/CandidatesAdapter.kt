package com.qingyu.imemodule.adapter

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.emoji2.widget.EmojiTextView
import androidx.recyclerview.widget.RecyclerView
import com.qingyu.imemodule.R
import com.qingyu.imemodule.callback.OnRecyclerItemClickListener
import com.qingyu.imemodule.data.theme.ThemeManager.activeTheme
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import androidx.core.graphics.ColorUtils
import com.qingyu.imemodule.keyboard.CandidatePanelState
import androidx.core.widget.TextViewCompat
import com.qingyu.imemodule.singleton.EnvironmentSingleton.Companion.instance

/**
 * 候选词界面适配器
 */
class CandidatesAdapter(private val context: Context) :
    RecyclerView.Adapter<CandidatesAdapter.SymbolHolder>() {
    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private val textColor: Int = activeTheme.keyTextColor
    private var mOnItemClickListener: OnRecyclerItemClickListener? = null
    private var entries: List<CandidatePanelState.Entry> = emptyList()
    fun submitEntries(value: List<CandidatePanelState.Entry>) {
        entries = value
        notifyDataSetChanged()
    }
    fun setOnItemClickLitener(mOnItemClickLitener: OnRecyclerItemClickListener?) {
        mOnItemClickListener = mOnItemClickLitener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolHolder {
        val view = inflater.inflate(R.layout.sdk_item_recyclerview_candidates, parent, false)
        view.background = RippleDrawable(ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(activeTheme.accentKeyBackgroundColor, 48)), null, null)
        return SymbolHolder(view)
    }

    override fun onBindViewHolder(holder: SymbolHolder, position: Int) {
        holder.textView.text = entries[position].text
        holder.textView.setTextColor(textColor)
        holder.textView.setOnClickListener { view: View? ->
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) entries.getOrNull(index)?.let {
                mOnItemClickListener?.onItemClick(this@CandidatesAdapter, view, it.displayIndex)
            }
        }
    }

    /**
     * 展开面板的跨列数。
     *
     * 长候选固定塞进四列会被压成「我…」。按内容宽度让 5–6 个汉字跨两列、更长跨整行，
     * 配合不限行高的自动换行，候选能完整显示；短候选仍保持四列密度。
     */
    fun spanFor(position: Int): Int {
        val text = entries.getOrNull(position)?.text.orEmpty()
        val width = visualWidth(text)
        return when {
            width <= 6 -> 1
            width <= 12 -> 2
            else -> 4
        }.let { if (position < 2 && entries.size > 2) maxOf(it, 2) else it }
    }

    /** 汉字/全角字符按两格估算，ASCII 与 emoji 记一格。 */
    private fun visualWidth(text: String): Int {
        var width = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            width += if (codePoint >= 0x2E80) 2 else 1
            index += Character.charCount(codePoint)
        }
        return width
    }

    override fun getItemCount(): Int {
        return entries.size
    }

    inner class SymbolHolder(view: View) : RecyclerView.ViewHolder(view) {
        var textView: EmojiTextView = view.findViewById(R.id.gv_candidates_item)

        init {
            textView.setTextColor(textColor)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, instance.candidateTextSize)
            // 行高交给内容：长候选可以继续换行，由面板滚动承载，不能用固定高度截断。
            val maxSp = instance.candidateTextSize.toInt().coerceAtLeast(10)
            val minSp = minOf(10, maxSp)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(textView, minSp, maxSp, 1, TypedValue.COMPLEX_UNIT_SP)
        }
    }
}
