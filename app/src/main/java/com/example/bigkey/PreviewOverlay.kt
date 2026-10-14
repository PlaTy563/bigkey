package com.example.bigkey

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** 押しているキーの上に文字を大きく浮かべる。タッチは受け取らず、下のキーボードにそのまま渡す。 */
class PreviewOverlay(context: Context) : View(context) {
    private val pal = Theme.of(context)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.pressed }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = pal.text
    }
    private val rect = RectF()
    private var label: String? = null
    private var l = 0f
    private var t = 0f
    private var r = 0f
    private var b = 0f

    fun show(label: String?, l: Float, t: Float, r: Float, b: Float) {
        this.label = label
        this.l = l
        this.t = t
        this.r = r
        this.b = b
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onDraw(canvas: Canvas) {
        val s = label ?: return
        val d = resources.displayMetrics.density
        val keyW = r - l
        val keyH = b - t
        val w = keyW * 1.3f
        val h = keyH * 1.15f
        var left = (l + r) / 2f - w / 2f
        left = left.coerceIn(0f, maxOf(0f, width - w))
        var top = t - h - 4f * d
        if (top < 0f) top = 0f
        rect.set(left, top, left + w, top + h)
        val radius = 12f * d
        rect.offset(0f, 2f * d)
        canvas.drawRoundRect(rect, radius, radius, shadow)
        rect.offset(0f, -2f * d)
        canvas.drawRoundRect(rect, radius, radius, bg)
        text.textSize = h * 0.55f
        val y = rect.centerY() - (text.descent() + text.ascent()) / 2f
        canvas.drawText(s, rect.centerX(), y, text)
    }
}
