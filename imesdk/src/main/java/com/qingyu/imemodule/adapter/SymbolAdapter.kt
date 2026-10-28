package com.qingyu.imemodule.adapter

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import splitties.dimensions.dp
import android.widget.TextView
import androidx.emoji2.widget.EmojiTextView
import androidx.recyclerview.widget.RecyclerView
import com.qingyu.imemodule.R
import com.qingyu.imemodule.data.theme.ThemeManager.activeTheme
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.prefs.behavior.HalfWidthSymbolsMode
import com.qingyu.imemodule.prefs.behavior.SymbolMode
import com.qingyu.imemodule.singleton.EnvironmentSingleton.Companion.instance
import com.qingyu.imemodule.utils.StringUtils

/**
 * 表情或符号界面适配器
 */
class SymbolAdapter(context: Context?, val viewType: SymbolMode, private val pagerIndex: Int, private val cellHeight: Int = 0, private val onClickSymbol: (String, Int) -> Unit) :
    RecyclerView.Adapter<SymbolAdapter.SymbolHolder>() {
    private val inflater: LayoutInflater
    var mDatas: List<String>? = null
    private val halfWidthSymbolsMode = AppPrefs.getInstance().keyboardSetting.halfWidthSymbolsMode.getValue()

    init {
        inflater = LayoutInflater.from(context)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolHolder {
        return SymbolHolder(inflater.inflate(R.layout.sdk_item_recyclerview_symbols_emoji, parent, false))
    }

    override fun onBindViewHolder(holder: SymbolHolder, position: Int) {
        val data = mDatas!![position]
        holder.textView.text = data
        holder.tVSdb.visibility = if(viewType != SymbolMode.Symbol) View.GONE else {
            when (halfWidthSymbolsMode) {
                HalfWidthSymbolsMode.All -> if (StringUtils.isDBCSymbol(data)) View.VISIBLE else View.GONE
                HalfWidthSymbolsMode.OnlyUsed -> if (pagerIndex == 0 && StringUtils.isDBCSymbol(data)) View.VISIBLE else View.GONE
                HalfWidthSymbolsMode.None -> View.GONE
            }
        }
        holder.textView.setOnClickListener { _: View? ->
            onClickSymbol(mDatas!![position], position)
        }
    }

    override fun getItemCount(): Int {
        return mDatas?.size?:0
    }

    inner class SymbolHolder(view: View) : RecyclerView.ViewHolder(view) {
        var textView: EmojiTextView = view.findViewById(R.id.gv_symbols_item)
        var tVSdb: TextView
        init {
            // 表格式白底单元格；发丝分隔线由 SymbolPagerAdapter 的 ItemDecoration 绘制
            view.setBackgroundColor(activeTheme.keyBackgroundColor)
            // 行高由面板可视区高度均分而来（SymbolContainer 计算）：取整后最后一行正好落在
            // 分类带上沿，不会被切掉半行；cellHeight 为 0 时（未量到高度）退回最小行高
            view.minimumHeight = if (cellHeight > 0) cellHeight else view.dp(48)
            if (cellHeight > 0) view.layoutParams?.height = cellHeight
            textView.background = null
            textView.gravity = android.view.Gravity.CENTER
            textView.setPadding(view.dp(6), view.dp(10), view.dp(6), view.dp(10))
            textView.setTextColor(activeTheme.keyTextColor)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, instance.candidateTextSize * if(viewType != SymbolMode.Emojicon)1f else 0.9f)
            tVSdb = view.findViewById(R.id.tv_sdb_symbols_item)
            tVSdb.setTextColor(activeTheme.keyTextColor)
        }
    }
}
