package com.example.bigkey

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** 線で描いたアイコンのボタン。画像ファイルを使わず、図形で描く。 */
class IconButton(context: Context, private val icon: Icon, description: String) : View(context) {
    enum class Icon { LEFT, RIGHT, CLIP, EMOJI, MIC, KEY }

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
            Icon.LEFT -> drawChevron(canvas, cx, cy, s, -1)
            Icon.RIGHT -> drawChevron(canvas, cx, cy, s, 1)
            Icon.CLIP -> drawClip(canvas, cx, cy, s)
            Icon.EMOJI -> drawEmoji(canvas, cx, cy, s)
            Icon.MIC -> drawMic(canvas, cx, cy, s)
            Icon.KEY -> drawKey(canvas, cx, cy, s)
        }
    }

    /** 押し続けると繰り返し実行する動作（カーソル移動用）。設定すると、押した瞬間に1回実行する。 */
    var repeatAction: (() -> Unit)? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val repeater = object : Runnable {
        override fun run() {
            repeatAction?.invoke()
            handler.postDelayed(this, 60)
        }
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        val action = repeatAction ?: return super.onTouchEvent(e)
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                isPressed = true
                action()
                handler.postDelayed(repeater, 400)
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                handler.removeCallbacks(repeater)
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(repeater)
        super.onDetachedFromWindow()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        alpha = if (pressed) 0.6f else 1f
    }

    private fun drawChevron(c: Canvas, x: Float, y: Float, s: Float, dir: Int) {
        path.reset()
        path.moveTo(x - dir * s * 0.45f, y - s * 1.0f)
        path.lineTo(x + dir * s * 0.55f, y)
        path.lineTo(x - dir * s * 0.45f, y + s * 1.0f)
        c.drawPath(path, line)
    }

    private fun drawClip(c: Canvas, x: Float, y: Float, s: Float) {
        // クリップボード: 板と、上のクリップ、中の行
        rect.set(x - s * 0.95f, y - s * 1.05f, x + s * 0.95f, y + s * 1.2f)
        c.drawRoundRect(rect, s * 0.3f, s * 0.3f, line)
        rect.set(x - s * 0.45f, y - s * 1.4f, x + s * 0.45f, y - s * 0.7f)
        c.drawRoundRect(rect, s * 0.2f, s * 0.2f, line)
        c.drawLine(x - s * 0.5f, y - s * 0.05f, x + s * 0.5f, y - s * 0.05f, line)
        c.drawLine(x - s * 0.5f, y + s * 0.55f, x + s * 0.2f, y + s * 0.55f, line)
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
