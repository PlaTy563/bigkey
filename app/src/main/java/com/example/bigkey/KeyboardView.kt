package com.example.bigkey

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * 大きなキーのQWERTYキーボード。
 *
 * 誤打を減らすための工夫:
 *  1. キーの高さを設定で変えられる（横幅は画面幅で決まるため、縦に大きくする）
 *  2. キー同士の隙間も判定領域に含め、「どのキーにも当たらない」状態をなくす
 *  3. 指を離した位置のキーで確定する（押したまま滑らせて直せる）
 *  4. 境界付近では、ローマ字として次に来そうなキーを優先する（誤タップ補正）
 *  5. マルチタッチ対応（前のキーを離す前に次のキーを押しても取りこぼさない）
 */
class KeyboardView(context: Context) : View(context) {

    enum class Kind { LETTER, DIGIT, SYMBOL, SPACE, BACKSPACE, ENTER, MODE, SHIFT, LAYER, SPACER }

    class Key(
        val kind: Kind,
        val label: String,
        val text: String = label,
        val weight: Float = 1f,
        val hint: String? = null,
    ) {
        val rect = RectF()
    }

    interface Listener {
        fun onText(key: Key, shifted: Boolean)
        fun onSpace()
        fun onBackspace()
        fun onEnter()
        fun onToggleMode()
    }

    var listener: Listener? = null

    /** 文字キー c が「次に打つ文字として自然か」を返す。null なら補正しない。 */
    var likelyPredicate: ((Char) -> Boolean)? = null

    private var japanese = true
    private var symbolLayer = false
    private var shift = false
    private var composing = false
    private var showNumberRow = false
    private var hitCorrection = true
    private var haptic = true
    private var keyHeightDp = Prefs.DEFAULT_HEIGHT

    private var rows: List<List<Key>> = emptyList()
    private val pressed = HashMap<Int, Key>()
    private val longPress = HashMap<Int, Runnable>()
    private val longFired = HashSet<Int>()

    private val handler = Handler(Looper.getMainLooper())
    private var repeatPointer = -1
    private val repeatRunnable = object : Runnable {
        override fun run() {
            listener?.onBackspace()
            handler.postDelayed(this, 55)
        }
    }

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = COLOR_TEXT
    }
    private val tmp = RectF()

    init {
        reloadSettings()
    }

    // ---- 外部から呼ぶ設定 ----

    fun reloadSettings() {
        keyHeightDp = Prefs.keyHeightDp(context)
        showNumberRow = Prefs.showNumberRow(context)
        hitCorrection = Prefs.hitCorrection(context)
        haptic = Prefs.haptic(context)
        rebuild()
    }

    fun setJapanese(value: Boolean) {
        japanese = value
        rebuild()
    }

    fun setSymbolLayer(value: Boolean) {
        symbolLayer = value
        shift = false
        rebuild()
    }

    fun setComposing(value: Boolean) {
        if (composing != value) {
            composing = value
            invalidate()
        }
    }

    // ---- レイアウト ----

    private fun rebuild() {
        rows = buildRows()
        layoutKeys()
        requestLayout()
        invalidate()
    }

    private fun buildRows(): List<List<Key>> {
        val res = ArrayList<List<Key>>()
        fun letters(s: String, hints: String = "") = s.mapIndexed { i, c ->
            Key(Kind.LETTER, c.toString(), hint = hints.getOrNull(i)?.toString())
        }
        fun digits(s: String) = s.map { Key(Kind.DIGIT, it.toString()) }
        fun symbols(s: String) = s.map { Key(Kind.SYMBOL, it.toString()) }

        if (symbolLayer) {
            res += digits("1234567890")
            res += symbols("@#$%&*_+()")
            res += listOf(Key(Kind.SYMBOL, "=", weight = 1.5f)) +
                symbols("!\"':;/?") +
                listOf(Key(Kind.BACKSPACE, "⌫", weight = 1.5f))
        } else {
            if (showNumberRow) res += digits("1234567890")
            res += letters("qwertyuiop", if (showNumberRow) "" else "1234567890")
            res += listOf(Key(Kind.SPACER, "", weight = 0.5f)) +
                letters("asdfghjkl", "@#$%&-+()") +
                listOf(Key(Kind.SPACER, "", weight = 0.5f))
            res += listOf(Key(Kind.SHIFT, "⇧", weight = 1.5f)) +
                letters("zxcvbnm", "*\"':;!?") +
                listOf(Key(Kind.BACKSPACE, "⌫", weight = 1.5f))
        }

        res += listOf(
            Key(Kind.LAYER, if (symbolLayer) "文字" else "記号", weight = 1.2f),
            Key(Kind.MODE, if (japanese) "日本語" else "English", weight = 1.7f),
            Key(Kind.SYMBOL, if (japanese) "、" else ",", weight = 1f),
            Key(Kind.SPACE, "", weight = 3f),
            Key(Kind.SYMBOL, if (japanese) "。" else ".", weight = 1f),
            Key(Kind.SYMBOL, if (japanese) "ー" else "-", weight = 1f),
            Key(Kind.ENTER, "↵", weight = 1.6f),
        )
        return res
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** 1行の高さ。横向きで画面を占領しすぎないよう、全体が画面高の半分以内に収まるよう上限をかける。 */
    private fun rowHeight(): Float {
        val dm = resources.displayMetrics
        val base = keyHeightDp * dm.density
        val cap = dm.heightPixels * 0.5f / maxOf(rows.size, 1)
        return minOf(base, cap)
    }

    private fun layoutKeys() {
        if (width == 0) return
        val rowH = rowHeight()
        var top = 0f
        for (row in rows) {
            var sum = 0f
            for (k in row) sum += k.weight
            var x = 0f
            for (k in row) {
                val w = width * k.weight / sum
                k.rect.set(x, top, x + w, top + rowH)
                x += w
            }
            top += rowH
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (rowHeight() * rows.size).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys()
    }

    // ---- 描画 ----

    private fun isSpecial(k: Key): Boolean = when (k.kind) {
        Kind.SHIFT, Kind.BACKSPACE, Kind.ENTER, Kind.MODE, Kind.LAYER -> true
        else -> false
    }

    private fun labelOf(k: Key): String = when (k.kind) {
        Kind.LETTER -> if (shift) k.label.uppercase() else k.label
        Kind.SPACE -> if (composing) "変換" else if (japanese) "空白（日本語）" else "Space（English）"
        Kind.ENTER -> if (composing) "確定" else "↵"
        else -> k.label
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(COLOR_BG)
        val gap = dp(1.5f)
        val radius = dp(7f)
        val rowH = rowHeight()
        val pressedKeys = pressed.values.toSet()

        for (row in rows) {
            for (k in row) {
                if (k.kind == Kind.SPACER) continue
                tmp.set(k.rect.left + gap, k.rect.top + gap, k.rect.right - gap, k.rect.bottom - gap)
                keyPaint.color = when {
                    k in pressedKeys -> COLOR_PRESSED
                    k.kind == Kind.SHIFT && shift -> COLOR_ACTIVE
                    k.kind == Kind.MODE -> if (japanese) COLOR_JA else COLOR_EN
                    isSpecial(k) -> COLOR_SPECIAL
                    else -> COLOR_KEY
                }
                canvas.drawRoundRect(tmp, radius, radius, keyPaint)

                val hint = k.hint
                if (hint != null) {
                    textPaint.color = COLOR_HINT
                    textPaint.textSize = rowH * 0.24f
                    canvas.drawText(hint, tmp.right - rowH * 0.17f, tmp.top + rowH * 0.28f, textPaint)
                    textPaint.color = COLOR_TEXT
                }
                val label = labelOf(k)
                if (label.isEmpty()) continue
                textPaint.textSize = if (label.length >= 2) rowH * 0.27f else rowH * 0.40f
                val y = tmp.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(label, tmp.centerX(), y, textPaint)
            }
        }
    }

    // ---- タッチ ----

    /**
     * 座標に最も近いキーを返す。判定はキー中心からの距離（キーの幅・高さで正規化）で行うため、
     * キー同士の隙間や画面端でも必ずどれかのキーに割り当てられる。
     * 補正が有効で、ローマ字として続かない文字キーは距離を大きく見積もり、境界付近で選ばれにくくする。
     */
    private fun hit(x: Float, y: Float): Key? {
        var best: Key? = null
        var bestScore = Float.MAX_VALUE
        val predicate = if (hitCorrection) likelyPredicate else null
        for (row in rows) {
            for (k in row) {
                if (k.kind == Kind.SPACER) continue
                val dx = (x - k.rect.centerX()) / k.rect.width()
                val dy = (y - k.rect.centerY()) / k.rect.height()
                var score = dx * dx + dy * dy
                if (predicate != null && k.kind == Kind.LETTER && !predicate(k.text[0])) {
                    score *= UNLIKELY_PENALTY
                }
                if (score < bestScore) {
                    bestScore = score
                    best = k
                }
            }
        }
        return best
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                onDown(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) onMove(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                onUp(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    private fun onDown(id: Int, x: Float, y: Float) {
        val k = hit(x, y) ?: return
        pressed[id] = k
        if (haptic) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (k.kind == Kind.BACKSPACE) {
            // 削除は押した瞬間に実行し、押し続けると連続削除する
            listener?.onBackspace()
            repeatPointer = id
            handler.postDelayed(repeatRunnable, 400)
        }
        if (k.hint != null) {
            val r = Runnable {
                longPress.remove(id)
                if (pressed[id] === k) {
                    longFired.add(id)
                    if (haptic) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val h = k.hint ?: return@Runnable
                    listener?.onText(Key(if (h[0].isDigit()) Kind.DIGIT else Kind.SYMBOL, h), false)
                    invalidate()
                }
            }
            longPress[id] = r
            handler.postDelayed(r, LONG_PRESS_MS)
        }
        invalidate()
    }

    private fun cancelLongPress(id: Int) {
        longPress.remove(id)?.let { handler.removeCallbacks(it) }
    }

    private fun onMove(id: Int, x: Float, y: Float) {
        val cur = pressed[id] ?: return
        if (id == repeatPointer) return
        val next = hit(x, y) ?: return
        if (next !== cur && id !in longFired) {
            cancelLongPress(id)
            pressed[id] = next
            invalidate()
        }
    }

    private fun onUp(id: Int, x: Float, y: Float) {
        val cur = pressed.remove(id)
        cancelLongPress(id)
        if (longFired.remove(id)) {
            invalidate()
            return
        }
        if (id == repeatPointer) {
            stopRepeat()
            invalidate()
            return
        }
        if (cur != null) {
            fire(hit(x, y) ?: cur)
        }
        invalidate()
    }

    private fun cancelAll() {
        for (id in longPress.keys.toList()) cancelLongPress(id)
        longFired.clear()
        pressed.clear()
        stopRepeat()
        invalidate()
    }

    private fun stopRepeat() {
        handler.removeCallbacks(repeatRunnable)
        repeatPointer = -1
    }

    private fun fire(k: Key) {
        when (k.kind) {
            Kind.LETTER -> {
                val shifted = shift
                shift = false
                listener?.onText(k, shifted)
            }
            Kind.DIGIT, Kind.SYMBOL -> listener?.onText(k, false)
            Kind.SPACE -> listener?.onSpace()
            Kind.BACKSPACE -> listener?.onBackspace()
            Kind.ENTER -> listener?.onEnter()
            Kind.MODE -> listener?.onToggleMode()
            Kind.SHIFT -> shift = !shift
            Kind.LAYER -> setSymbolLayer(!symbolLayer)
            Kind.SPACER -> Unit
        }
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val UNLIKELY_PENALTY = 2.4f
        private const val LONG_PRESS_MS = 350L
        private const val COLOR_JA = 0xFF2F5FB8.toInt()
        private const val COLOR_EN = 0xFFB86A1E.toInt()
        private const val COLOR_HINT = 0xFF9AA0AA.toInt()
        private const val COLOR_BG = 0xFF161616.toInt()
        private const val COLOR_KEY = 0xFF3B3B3B.toInt()
        private const val COLOR_SPECIAL = 0xFF2A2A2A.toInt()
        private const val COLOR_PRESSED = 0xFF5B7FD6.toInt()
        private const val COLOR_ACTIVE = 0xFF4A6FC4.toInt()
        private const val COLOR_TEXT = 0xFFF2F2F2.toInt()
    }
}
