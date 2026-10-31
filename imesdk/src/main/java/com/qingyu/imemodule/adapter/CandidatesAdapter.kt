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

    override fun getItemCount(): Int {
        return entries.size
    }

    inner class SymbolHolder(view: View) : RecyclerView.ViewHolder(view) {
        var textView: EmojiTextView = view.findViewById(R.id.gv_candidates_item)

        init {
            textView.setTextColor(textColor)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, instance.candidateTextSize)
            // 搜狗式固定单元格：每个候选只占一格，单行；长短词都靠自动缩放，
            // 极长的条目省略，而不是靠跨列改变整行节奏。
            textView.maxLines = 1
            textView.ellipsize = android.text.TextUtils.TruncateAt.END
            val maxSp = instance.candidateTextSize.toInt().coerceAtLeast(10)
            val minSp = minOf(10, maxSp)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(textView, minSp, maxSp, 1, TypedValue.COMPLEX_UNIT_SP)
        }
    }
}
