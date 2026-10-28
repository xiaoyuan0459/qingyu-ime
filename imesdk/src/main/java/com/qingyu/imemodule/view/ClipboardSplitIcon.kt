package com.qingyu.imemodule.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.qingyu.imemodule.R
import splitties.dimensions.dp

/** 剪贴板建议条上的“拆分”图标：前后两页、前页遮住后页，字落在前页中心。 */
@SuppressLint("ViewConstructor")
internal class ClipboardSplitIcon(context: Context) : View(context) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f).toFloat()
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 13f * resources.displayMetrics.scaledDensity
    }
    private val label = context.getString(R.string.clipboard_split_short)
    private val backRect = RectF()
    private val frontRect = RectF()

    fun setColors(strokeColor: Int, fillColor: Int) {
        strokePaint.color = strokeColor
        textPaint.color = strokeColor
        fillPaint.color = fillColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val inset = dp(1f).toFloat() + strokePaint.strokeWidth / 2f
        val pageOffset = dp(3f).toFloat()
        val pageSize = size - inset * 2 - pageOffset
        val left = inset
        val top = inset + pageOffset
        frontRect.set(left, top, left + pageSize, top + pageSize)
        backRect.set(left + pageOffset, inset, left + pageOffset + pageSize, inset + pageSize)

        val radius = dp(4f).toFloat()
        canvas.drawRoundRect(backRect, radius, radius, strokePaint)
        canvas.drawRoundRect(frontRect, radius, radius, fillPaint)
        canvas.drawRoundRect(frontRect, radius, radius, strokePaint)
        val baseline = frontRect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, frontRect.centerX(), baseline, textPaint)
    }
}
