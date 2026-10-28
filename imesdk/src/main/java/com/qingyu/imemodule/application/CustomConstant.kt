package com.qingyu.imemodule.application

import com.qingyu.imemodule.data.flower.FlowerTypefaceMode

/**
 * 用户自定义常量类
 */
object CustomConstant {
    const val SCHEMA_ZH_T9 = "t9_pinyin" // 拼音九键
    const val SCHEMA_ZH_QWERTY = "pinyin" // 拼音全键
    const val SCHEMA_EN = "english"         // 英语方案
    const val SCHEMA_ZH_HANDWRITING = "handwriting" // 手写输入
    const val SCHEMA_ZH_DOUBLE_FLYPY = "double_pinyin_" // 小鹤双拼
    const val SCHEMA_ZH_DOUBLE_LX17 = "double_pinyin_ls17" // 乱序17双拼
    const val SCHEMA_ZH_STROKE = "stroke" // 五笔画
    const val PROJECT_REPO = "https://github.com/xiaoyuan0459/qingyu-ime"
    const val LICENSE_URL = "$PROJECT_REPO/blob/master/LICENSE"
    const val FEEDBACK_TXC_REPO = "https://github.com/xiaoyuan0459/qingyu-ime/issues"

    // 花漾字状态
    var flowerTypeface = FlowerTypefaceMode.Disabled
}
