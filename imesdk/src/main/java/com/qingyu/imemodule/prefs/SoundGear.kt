package com.qingyu.imemodule.prefs

/**
 * 按键音量的挡位定义。存储值沿用历史音量刻度（0/10/20/30/40），
 * 设置页与键盘内反馈面板都只暴露这五挡，滑条按挡位吸附而不是连续取值。
 */
object SoundGear {
    const val SYSTEM = 0
    const val OFF = 10
    const val SMALL = 20
    const val MEDIUM = 30
    const val LARGE = 40

    val VALUES = intArrayOf(SYSTEM, OFF, SMALL, MEDIUM, LARGE)
    val COUNT = VALUES.size

    fun normalize(stored: Int): Int = when {
        stored < 5 -> SYSTEM
        stored < 15 -> OFF
        stored < 25 -> SMALL
        stored < 35 -> MEDIUM
        else -> LARGE
    }

    fun indexOf(stored: Int): Int = VALUES.indexOf(normalize(stored)).coerceAtLeast(0)
}
