package com.qingyu.inputmethod.util

object QwertyPinYinUtils {
    /**
     * 组合串的展示形态：逐音节换成候选注释里的真实读音，音节之间用 `'` 断开
     * （`zong` 配上注释 `zhong` 显示成 `z'ong`）。
     *
     * `'` 只在两段内容之间加：串首没有可分隔的内容，凭空多一个 `'` 看起来就像输入法自己
     * 塞了个分词符（候选读音与输入对不上时必现，比如模糊音命中的首条候选）。
     */
    fun getQwertyComposition(composition: String, comment: String): String {
        if (comment.isEmpty()) return composition
        val compositionList = composition.filter { it.code <= 0xFF }.split("'".toRegex())
        val hint = StringBuilder()
        fun separator() {
            if (hint.isNotEmpty()) hint.append('\'')
        }
        hint.append(composition.filter { it.code > 0xFF })
        comment.split("'").zip(compositionList).forEach { (pinyin, compo) ->
            if (pinyin.startsWith(compo)) {
                // 读音就是敲进去的音（简拼则只显示敲到的那截）
                separator()
                hint.append(pinyin.substring(0, compo.length))
            } else {
                // 读音与输入在某个字母上分叉，把分叉点标出来：[匹配到的前缀]'[其余输入]
                val common = compo.zip(pinyin).takeWhile { (typed, sound) -> typed == sound }
                    .joinToString("") { (typed, _) -> typed.toString() }
                separator()
                hint.append(common)
                separator()
                hint.append(compo.removePrefix(common))
            }
        }
        if (composition.endsWith("'") && hint.isNotEmpty() && !hint.endsWith("'")) hint.append('\'')
        return hint.toString()
    }
}
