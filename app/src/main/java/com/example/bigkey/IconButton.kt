package com.example.bigkey

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** 線で描いたアイコンのボタン。画像ファイルを使わず、図形で描く。 */
class IconButton(context: Context, private val icon: Icon, description: String) : View(context) {
    enum class Icon { EDIT, EMOJI, MIC, KEY }

    private val pal = Theme.of(context)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.special }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = pal.text
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.text }
    private val path = Path()
    private val rect = RectF()

    init {
        contentDescription = description
        isClickable = true
        if (icon == Icon.KEY) bgPaint.color = pal.accent.also { line.color = pal.accentText; fill.color = pal.accentText }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, bgPaint)
        val cx = w / 2f
        val cy = h / 2f
        val s = minOf(w, h) * 0.26f // アイコンの基準の長さ
        line.strokeWidth = minOf(w, h) * 0.075f
        when (icon) {
            Icon.EDIT -> drawEdit(canvas, cx, cy, s)
            Icon.EMOJI -> drawEmoji(canvas, cx, cy, s)
            Icon.MIC -> drawMic(canvas, cx, cy, s)
            Icon.KEY -> drawKey(canvas, cx, cy, s)
        }
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        alpha = if (pressed) 0.6f else 1f
    }

    private fun drawEdit(c: Canvas, x: Float, y: Float, s: Float) {
        // えんぴつ
        path.reset()
        path.moveTo(x - s * 1.0f, y + s * 1.0f)
        path.lineTo(x - s * 0.9f, y + s * 0.35f)
        path.lineTo(x + s * 0.55f, y - s * 1.1f)
        path.lineTo(x + s * 1.1f, y - s * 0.55f)
        path.lineTo(x - s * 0.35f, y + s * 0.9f)
        path.close()
        c.drawPath(path, line)
        c.drawLine(x + s * 0.2f, y - s * 0.75f, x + s * 0.8f, y - s * 0.15f, line)
    }

    private fun drawEmoji(c: Canvas, x: Float, y: Float, s: Float) {
        c.drawCircle(x, y, s * 1.2f, line)
        c.drawCircle(x - s * 0.42f, y - s * 0.3f, s * 0.12f, fill)
        c.drawCircle(x + s * 0.42f, y - s * 0.3f, s * 0.12f, fill)
        rect.set(x - s * 0.7f, y - s * 0.5f, x + s * 0.7f, y + s * 0.75f)
        c.drawArc(rect, 25f, 130f, false, line)
    }

    private fun drawMic(c: Canvas, x: Float, y: Float, s: Float) {
        rect.set(x - s * 0.45f, y - s * 1.2f, x + s * 0.45f, y + s * 0.25f)
        c.drawRoundRect(rect, s * 0.45f, s * 0.45f, line)
        rect.set(x - s * 0.9f, y - s * 0.55f, x + s * 0.9f, y + s * 0.65f)
        c.drawArc(rect, 20f, 140f, false, line)
        c.drawLine(x, y + s * 0.65f, x, y + s * 1.1f, line)
        c.drawLine(x - s * 0.5f, y + s * 1.1f, x + s * 0.5f, y + s * 1.1f, line)
    }

    private fun drawKey(c: Canvas, x: Float, y: Float, s: Float) {
        c.drawCircle(x - s * 0.55f, y, s * 0.5f, line)
        c.drawLine(x - s * 0.05f, y, x + s * 1.1f, y, line)
        c.drawLine(x + s * 0.7f, y, x + s * 0.7f, y + s * 0.5f, line)
        c.drawLine(x + s * 1.1f, y, x + s * 1.1f, y + s * 0.4f, line)
    }
}
