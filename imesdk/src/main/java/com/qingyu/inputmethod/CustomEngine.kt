package com.qingyu.inputmethod

import com.qingyu.imemodule.database.ImeDataRepository
import com.qingyu.imemodule.utils.StringUtils
import com.qingyu.imemodule.utils.TimeUtils
import com.qingyu.imemodule.libs.expression.ExpressionBuilder
import com.qingyu.imemodule.prefs.AppPrefs.Companion.getInstance
import kotlin.math.roundToInt

object CustomEngine {
    fun parseExpressionAtEnd(input: String): String? {
        val expressionEndPattern = "((φ|π|pi|sin|cos|tan|cot|asin|acos|atan|sinh|cosh|tanh|abs|log|log1p|ceil|floor|sqrt|cbrt|pow|exp|expm|signum|csc|sec|csch|sech|coth|toradian|todegree|\\(|\\)|^|%|\\+|-|\\*|/|\\.|e|E|\\d)+)$".toRegex()
        return expressionEndPattern.find(input.removeSuffix("="))?.value
    }

    fun expressionCalculator(input: String, expression: String?):Array<String>{
        val results = mutableListOf<String>()
        if(!expression.isNullOrBlank() && expression.length < 500 && !StringUtils.isNumber(expression) && !StringUtils.isLetter(expression)){
            try {
                val evaluate = ExpressionBuilder(expression).build().evaluate()
                val  resultFloat = evaluate.toFloat()
                val  resultInt = evaluate.roundToInt()
                if(evaluate.compareTo(resultInt) == 0){
                    if(!input.endsWith("=")) results.add("=${resultInt}")
                    else results.add(resultInt.toString())
                } else {
                    val resultPercentage = "${(evaluate * 100).roundToInt()}%"
                    if(!input.endsWith("=")) {
                        results.add("=".plus(resultFloat))
                        results.add("≈".plus(resultInt))
                        if(evaluate < 10 && evaluate > 0.005) results.add("=${resultPercentage}")
                    } else {
                        results.add(resultFloat.toString())
                        if(evaluate < 10 && evaluate > 0.005) results.add(resultPercentage)
                    }
                }
            } catch (_:Exception){ }
        }
        results.addAll(arrayOf("=", "+", "-", "*", "/", "%", ".", ",", "'", "(", ")"))
        return results.toTypedArray()
    }

    /**
     * 标点联动候选。
     *
     * 产品决定（2026-09-19）：**不做日期联想** —— 原先「今天/昨天/明天…」结尾时注入日期的分支已移除。
     * 是否启用本函数由设置项 `chinese_association_punctuation_enable`（默认关闭）控制。
     */
    fun predictAssociationWordsChinese(text: String):MutableList<String> {
        val associations = mutableListOf("，", "。")
        val suffixesExclamation = setOf("啊", "呀", "呐", "啦", "噢", "哇", "吧", "呗", "了")
        val suffixesQuestion = setOf("吗", "啊", "呢", "吧", "谁", "何", "什么", "哪", "几", "多少", "怎", "难道", "岂", "不")
        if(suffixesExclamation.any{ text.endsWith(it) }){
            associations.add(0, "！")
        } else if(suffixesQuestion.any{ text.contains(it) }){
            associations.add(0, "？")
        }
        return associations
    }

    /**
     * 日期快捷输入的触发码：26 键的缩写 + 九键同码（九键键码取每键首字母大写）。
     *
     * 只认缩写。`riqi` / `shijian` 这种**完整拼音**不触发：它们是常用词，把时间戳插在
     * rime 候选之前会把「时间/日期」挤成次选（真机测试 D-02）。九键的 `PGPG` / `PGGJGAM`
     * 就是完整拼音的键码串，同理不触发——九键用户敲 `PP` / `PJ` 一样拿得到。
     */
    private val datetimeShortcutDate = setOf("rq", "PP")

    /** 时间快捷输入的触发码，规则同 [datetimeShortcutDate]。 */
    private val datetimeShortcutTime = setOf("sj", "PJ")

    /**
     * 组装「插在 rime 候选之前」的自建候选。
     *
     * 两类来源由两个独立设置控制（2026-09-19 拆分）：
     * - [com.qingyu.imemodule.prefs.AppPrefs.Input.phraseCandidates]：用户自建常用语
     * - [com.qingyu.imemodule.prefs.AppPrefs.Input.datetimeShortcut]：日期 / 时间快捷输入
     */
    fun processPhrase(text: String): MutableList<String> {
        val phrases = mutableListOf<String>()
        if (getInstance().input.phraseCandidates.getValue()) {
            phrases.addAll(ImeDataRepository.phrasesForCode(text).map { it.content })
        }
        if (getInstance().input.datetimeShortcut.getValue()) {
            if (datetimeShortcutDate.any { it == text }) {
                phrases.addAll(TimeUtils.getData())
            }
            if (datetimeShortcutTime.any { it == text }) {
                phrases.addAll(TimeUtils.getTime())
            }
        }
        return phrases
    }
}
