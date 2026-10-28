package com.qingyu.imemodule.view.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * 「手写设置」页顶部的笔迹预览：用与 HandwritingKeyboard 相同的「沿路径逐点描边」方式
 * 绘制示例笔画，改颜色/粗细/笔锋即时可见。
 */
class HandwritingPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var strokeColor = Color.BLACK
        set(value) {
            field = value
            invalidate()
        }

    var strokeWidthPx = 0f
        set(value) {
            field = value
            invalidate()
        }

    var penTipEnabled = true
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pathMeasure = PathMeasure()
    private val samplePaths = mutableListOf<Path>()
    private val position = FloatArray(2)

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildSamplePaths(w, h)
    }

    /** 归一化坐标下的四笔示例：横、竖、撇、捺 */
    private fun buildSamplePaths(w: Int, h: Int) {
        samplePaths.clear()
        if (w == 0 || h == 0) return
        val strokes = listOf(
            listOf(0.22f to 0.30f, 0.50f to 0.24f, 0.78f to 0.30f),
            listOf(0.50f to 0.24f, 0.52f to 0.56f, 0.50f to 0.80f),
            listOf(0.50f to 0.50f, 0.36f to 0.66f, 0.20f to 0.80f),
            listOf(0.54f to 0.52f, 0.68f to 0.66f, 0.82f to 0.80f),
        )
        strokes.forEach { stroke ->
            val path = Path()
            stroke.forEachIndexed { index, (nx, ny) ->
                val x = nx * w
                val y = ny * h
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            samplePaths.add(path)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (strokeWidthPx <= 0f) return
        paint.color = strokeColor
        // Sample densely enough for the thinnest tapered tip, then join samples.
        val advance = max(0.5f, strokeWidthPx * 0.15f)
        samplePaths.forEach { path ->
            pathMeasure.setPath(path, false)
            val length = pathMeasure.length
            var distance = 0f
            var previousX = 0f
            var previousY = 0f
            while (distance <= length) {
                val t = if (length == 0f) 0f else distance / length
                // 起收笔细、中段粗，模拟运笔提按
                val taper = if (penTipEnabled) 0.4f + 0.6f * sin(PI * t).toFloat() else 1f
                paint.strokeWidth = strokeWidthPx * taper
                pathMeasure.getPosTan(distance, position, null)
                if (distance == 0f) canvas.drawPoint(position[0], position[1], paint)
                else canvas.drawLine(previousX, previousY, position[0], position[1], paint)
                previousX = position[0]
                previousY = position[1]
                distance += advance
            }
        }
    }
}
