package com.qingyu.imemodule.prefs

/**
 * 触感强度的挡位定义。存储值就是挡位序号（0..4），与设置页「触感强度」滑杆的
 * 五段一一对应，键盘内面板与设置页因此共用同一套语义。
 *
 * 历史版本键盘内反馈面板误把 0..100 的滑杆进度直接写进这个键（见 0.1.0-dev 的
 * KeyboardFeedbackMenu），超过 4 的旧值在 [InputFeedbacks] 里本来也只会走「最强」
 * 分支（2/3 之外全是 0.8 基准）。[normalize] 因此把越界值统一收敛到「较强」，
 * 保留用户当时的实际手感，同时让挡位重新可枚举。
 */
object VibrationGear {
    const val SYSTEM = 0
    const val OFF = 1
    const val WEAK = 2
    const val MEDIUM = 3
    const val STRONG = 4

    const val COUNT = 5

    fun normalize(stored: Int): Int = when {
        stored <= SYSTEM -> SYSTEM
        stored <= STRONG -> stored
        else -> STRONG
    }
}
