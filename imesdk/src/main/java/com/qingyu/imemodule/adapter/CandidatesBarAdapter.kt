package com.qingyu.imemodule.adapter

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.emoji2.widget.EmojiTextView
import androidx.recyclerview.widget.RecyclerView
import com.qingyu.imemodule.R
import com.qingyu.imemodule.callback.OnRecyclerItemClickListener
import com.qingyu.imemodule.data.theme.ThemeManager.activeTheme
import com.qingyu.imemodule.service.DecodingInfo
import com.qingyu.imemodule.singleton.EnvironmentSingleton.Companion.instance

/**
 * 候选词界面适配器
 */
class CandidatesBarAdapter(context: Context?) :
    RecyclerView.Adapter<CandidatesBarAdapter.SymbolHolder>() {
    private val inflater: LayoutInflater
    private var mOnItemClickListener: OnRecyclerItemClickListener? = null
    private var mActiveCandNo:Int = 0
    fun setOnItemClickLitener(mOnItemClickLitener: OnRecyclerItemClickListener?) {
        mOnItemClickListener = mOnItemClickLitener
    }

    init {
        mActiveCandNo = 0
        inflater = LayoutInflater.from(context)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolHolder {
        val view = inflater.inflate(R.layout.sdk_item_recyclerview_candidates_bar, parent, false)
        return SymbolHolder(view)
    }

    override fun onBindViewHolder(holder: SymbolHolder, position: Int) {
        if(DecodingInfo.isCandidatesEmpty) return
        val text = DecodingInfo.candidates[position].text
        holder.textView.text = text
        // TalkBack 逐条读候选时会连序号一起念，否则一排候选听不出「念到第几个、还有几个」。
        // 组装这个串要查资源并格式化，文本与序号都没变时不重复做。
        if (holder.boundText != text || holder.boundPosition != position) {
            holder.boundText = text
            holder.boundPosition = position
            holder.itemView.contentDescription = holder.itemView.context.getString(
                R.string.accessibility_candidate, position + 1, text
            )
        }
        holder.textView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // 对齐搜狗：手写注音不放候选字上方，首选拼音由组合串行（composing）显示，
        // 与拼音打字时 ni'hao 的位置一致（见 CandidatesBar.showCandidates）
        holder.pinyinView.visibility = View.GONE
        holder.pinyinView.setTextColor(activeTheme.keyTextColor)
        // setTextSize 每次都会 requestLayout，而字号只在设置里改；按 holder 记录避免每键重设
        val textSize = instance.candidateTextSize
        if (holder.appliedTextSize != textSize) {
            holder.appliedTextSize = textSize
            holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
        }
        val primary = if (mActiveCandNo > 0) mActiveCandNo - 1 == position else !DecodingInfo.isAssociate && position == 0
        holder.textView.setTextColor(if (primary) activeTheme.accentKeyBackgroundColor else activeTheme.keyTextColor)
        if (mOnItemClickListener != null) {
            holder.itemView.setOnClickListener { view: View? ->
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) mOnItemClickListener?.onItemClick(this@CandidatesBarAdapter, view, index)
            }
        }
    }

    override fun getItemCount(): Int {
        return DecodingInfo.candidateSize
    }

    fun activeCandidates(activeCandNo:Int) {
        mActiveCandNo = activeCandNo
    }

    private var lastItemCount = -1

    /**
     * 候选内容整体替换。
     *
     * 条数不变时只重绑可见项，不触发 RecyclerView 的完整重新布局；条数变化才走
     * 全量通知（此时结构确实变了）。候选栏每敲一个键都会刷新，这条路径在热路径上。
     */
    fun notifyChanged() {
        val size = DecodingInfo.candidateSize
        if (size == lastItemCount) {
            if (size > 0) notifyItemRangeChanged(0, size)
            return
        }
        lastItemCount = size
        notifyDataSetChanged()
    }

    inner class SymbolHolder(view: View) : RecyclerView.ViewHolder(view) {
        var textView: EmojiTextView = view.findViewById(R.id.gv_candidates_bar_item)
        val pinyinView: TextView = view.findViewById(R.id.gv_candidates_bar_pinyin)
        // 已应用的候选字号与无障碍文案，避免重复触发布局
        var appliedTextSize: Float = -1f
        var boundText: String? = null
        var boundPosition: Int = RecyclerView.NO_POSITION
        init {
            textView.setTextColor(activeTheme.keyTextColor)
        }
    }
}
