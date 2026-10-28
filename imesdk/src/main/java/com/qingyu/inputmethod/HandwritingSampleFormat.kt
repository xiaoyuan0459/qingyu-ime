package com.qingyu.inputmethod

import kotlin.math.roundToLong

/**
 * 手写轨迹样本 v1 编码。字段与约束见 qingyu-handwriting-dataset 仓库的 sample-format.md。
 * 只做编码和边界校验，不读写文件，也不判断当前是否允许采集。
 */
object HandwritingSampleFormat {

    const val MAX_STROKES = 64
    const val MAX_POINTS_PER_STROKE = 2048
    const val MAX_TOTAL_POINTS = 8192
    const val LINE_PREFIX = "{\"format\":\"qingyu-hw-trajectory\",\"version\":1,"

    fun encode(
        label: String,
        draft: HandwritingSampleDraft,
        id: String,
        createdAt: String,
    ): String? {
        if (!isSingleHan(label)) return null
        if (draft.canvasWidth <= 0 || draft.canvasHeight <= 0) return null
        if (draft.strokes.size !in 1..MAX_STROKES) return null
        var totalPoints = 0
        for (stroke in draft.strokes) {
            if (stroke.size !in 2..MAX_POINTS_PER_STROKE) return null
            totalPoints += stroke.size
        }
        if (totalPoints > MAX_TOTAL_POINTS) return null

        val firstTime = draft.strokes.first().first().time
        val aspect = draft.canvasWidth.toDouble() / draft.canvasHeight
        val json = StringBuilder(192 + totalPoints * 20)
        json.append(LINE_PREFIX)
            .append("\"id\":\"")
            .append(id)
            .append("\",\"label\":\"")
            .append(label)
            .append("\",\"created_at\":\"")
            .append(createdAt)
            .append("\",\"canvas\":{\"aspect\":")
            .append(decimal(aspect))
            .append(",\"unit\":\"normalized\"},\"strokes\":[")

        var lastTime = 0L
        draft.strokes.forEachIndexed { strokeIndex, stroke ->
            if (strokeIndex > 0) json.append(',')
            json.append('[')
            stroke.forEachIndexed { pointIndex, point ->
                if (pointIndex > 0) json.append(',')
                val x = (point.x.toDouble() / draft.canvasWidth).coerceIn(0.0, 1.0)
                val y = (point.y.toDouble() / draft.canvasHeight).coerceIn(0.0, 1.0)
                val time = (point.time - firstTime).coerceAtLeast(lastTime)
                lastTime = time
                json.append('[')
                    .append(decimal(x))
                    .append(',')
                    .append(decimal(y))
                    .append(',')
                    .append(time)
                    .append(']')
            }
            json.append(']')
        }
        json.append("]}")
        return json.toString()
    }

    /** 固定四位小数，避免每个点都走一次 String.format。 */
    private fun decimal(value: Double): String {
        val scaled = (value * 10_000).roundToLong()
        val whole = scaled / 10_000
        val fraction = (scaled % 10_000).toString().padStart(4, '0')
        return "$whole.$fraction"
    }

    private fun isSingleHan(text: String): Boolean {
        if (text.isEmpty()) return false
        val codePoint = text.codePointAt(0)
        if (Character.charCount(codePoint) != text.length) return false
        return codePoint in 0x3400..0x4DBF ||
            codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0x20000..0x2FA1F
    }
}

data class HandwritingPoint(
    val x: Int,
    val y: Int,
    val time: Long,
)

data class HandwritingSampleDraft(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val strokes: List<List<HandwritingPoint>>,
)
