package com.qingyu.imemodule.adapter

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.recyclerview.widget.RecyclerView
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.theme.ThemeManager.activeTheme
import com.qingyu.imemodule.keyboard.CandidatePanelState
import com.qingyu.imemodule.utils.keyboardItemBackground
import com.qingyu.imemodule.view.popup.AutoScaleTextView

/**
 * 展开候选面板左列的读音列表。
 *
 * 与 [PrefixAdapter] 分开：读音需要「首音节 + 余下音节」两行，纠错读数（xiao / hong）
 * 才能完整显示；PrefixAdapter 仍负责笔画、九键前缀等单行标签，避免两套语义挤在一个
 * 适配器里。选中态、点击回调与侧栏底色沿用展开面板的既有规则。
 */
class ReadingAdapter(
    context: Context,
    private val readings: List<CandidatePanelState.Reading>,
    private val itemHeight: Int,
    private val onSelect: (Int) -> Unit,
) : RecyclerView.Adapter<ReadingAdapter.Holder>() {
    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private val textColor: Int = activeTheme.keyTextColor
    private val secondaryColor: Int = ColorUtils.setAlphaComponent(
        textColor,
        (Color.alpha(textColor) * SECONDARY_ALPHA).toInt(),
    )
    private var selectedIndex = -1

    fun setSelectedIndex(index: Int) {
        if (index == selectedIndex) return
        val previous = selectedIndex
        selectedIndex = index
        if (previous in readings.indices) notifyItemChanged(previous)
        if (index in readings.indices) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = inflater.inflate(R.layout.sdk_item_list_candidate_reading, parent, false)
        view.layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            itemHeight,
        )
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val reading = readings[position]
        holder.primary.text = reading.primary
        holder.primary.setTextColor(textColor)
        holder.secondary.text = reading.secondary
        holder.secondary.setTextColor(secondaryColor)
        holder.secondary.visibility = if (reading.secondary.isEmpty()) View.GONE else View.VISIBLE
        holder.itemView.contentDescription = reading.full
        holder.itemView.isSelected = position == selectedIndex
        holder.itemView.background = if (position == selectedIndex) {
            ColorUtils.blendARGB(activeTheme.keyBackgroundColor, textColor, .14f).toDrawable()
        } else {
            keyboardItemBackground(holder.itemView.context)
        }
        holder.itemView.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) onSelect(index)
        }
    }

    override fun getItemCount(): Int = readings.size

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val primary: AutoScaleTextView = view.findViewById(R.id.tv_reading_primary)
        val secondary: TextView = view.findViewById(R.id.tv_reading_secondary)

        init {
            primary.scaleMode = AutoScaleTextView.Mode.Proportional
            primary.centerByInk = true
            primary.setTextSize(TypedValue.COMPLEX_UNIT_SP, PRIMARY_TEXT_SP)
            primary.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            secondary.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private companion object {
        const val PRIMARY_TEXT_SP = 20f
        const val SECONDARY_ALPHA = 0.62f
    }
}
