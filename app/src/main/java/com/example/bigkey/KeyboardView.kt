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
        fun onCursor(delta: Int)
        fun onDeleteWord() {}
    }

    var listener: Listener? = null

    /** 押しているキーの拡大表示を出す／消す。(表示する文字, キーの左・上・右・下。消すときは null) */
    var previewCallback: ((String?, Float, Float, Float, Float) -> Unit)? = null

    // タップ位置のずれの学習（キーの大きさに対する割合）
    private var tapLearn = true
    private var tapDx = 0f
    private var tapDy = 0f
    private var refW = 1f
    private var refH = 1f
    private var pendDx = 0f
    private var pendDy = 0f
    private var pendTime = 0L
    private var hasPend = false
    private var acceptedTaps = 0

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
    private var pal = Theme.of(context)

    private var rows: List<List<Key>> = emptyList()
    private val pressed = HashMap<Int, Key>()
    private val longPress = HashMap<Int, Runnable>()
    private val longFired = HashSet<Int>()
    private val spaceLastX = HashMap<Int, Float>()
    private val spaceAcc = HashMap<Int, Float>()
    private val spaceSwiped = HashSet<Int>()

    private val handler = Handler(Looper.getMainLooper())
    private var repeatPointer = -1
    private var bsLastX = 0f
    private var bsAcc = 0f
    private val repeatRunnable = object : Runnable {
        override fun run() {
            listener?.onBackspace()
            handler.postDelayed(this, 55)
        }
    }

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    private val tmp = RectF()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()

    init {
        reloadSettings()
    }

    // ---- 外部から呼ぶ設定 ----

    fun reloadSettings() {
        pal = Theme.of(context)
        keyHeightDp = Prefs.keyHeightDp(context)
        showNumberRow = Prefs.showNumberRow(context)
        hitCorrection = Prefs.hitCorrection(context)
        haptic = Prefs.haptic(context)
        tapLearn = Prefs.tapLearn(context)
        tapDx = Prefs.tapDx(context)
        tapDy = Prefs.tapDy(context)
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
            val h2 = if (japanese) "@#$%&…〜「」" else "@#$%&-+()"
            val h3 = if (japanese) "・（）『』！？" else "*\"':;!?"
            res += letters("qwertyuiop", if (showNumberRow) "" else "1234567890")
            res += listOf(Key(Kind.SPACER, "", weight = 0.5f)) +
                letters("asdfghjkl", h2) +
                listOf(Key(Kind.SPACER, "", weight = 0.5f))
            res += listOf(Key(Kind.SHIFT, "⇧", weight = 1.5f)) +
                letters("zxcvbnm", h3) +
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
                if (k.kind == Kind.LETTER) {
                    refW = w
                    refH = rowH
                }
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
        Kind.SPACE -> if (composing) "変換" else ""
        Kind.ENTER -> ""
        Kind.BACKSPACE, Kind.SHIFT -> ""
        else -> k.label
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(pal.bg)
        val gapX = dp(3f)
        val gapY = dp(4.5f)
        val radius = dp(12f)
        val rowH = rowHeight()
        val pressedKeys = pressed.values.toSet()

        for (row in rows) {
            for (k in row) {
                if (k.kind == Kind.SPACER) continue
                tmp.set(k.rect.left + gapX, k.rect.top + gapY, k.rect.right - gapX, k.rect.bottom - gapY)
                val isPressed = k in pressedKeys
                var fill = pal.key
                var fg = pal.text
                when {
                    isPressed -> fill = pal.pressed
                    k.kind == Kind.MODE -> {
                        fill = if (japanese) pal.ja else pal.en
                        fg = if (japanese) pal.jaText else pal.enText
                    }
                    k.kind == Kind.SHIFT && shift -> { fill = pal.accent; fg = pal.accentText }
                    k.kind == Kind.ENTER -> { fill = pal.accent; fg = pal.accentText }
                    isSpecial(k) -> fill = pal.special
                }
                keyPaint.color = fill
                canvas.drawRoundRect(tmp, radius, radius, keyPaint)

                val cx = tmp.centerX()
                val cy = tmp.centerY()
                val s = rowH * 0.15f
                when (k.kind) {
                    Kind.BACKSPACE -> drawBackspace(canvas, cx, cy, s, fg)
                    Kind.SHIFT -> drawShift(canvas, cx, cy, s, fg, shift)
                    Kind.ENTER -> if (composing) drawLabel(canvas, "確定", cx, cy, rowH * 0.27f, fg) else drawEnter(canvas, cx, cy, s, fg)
                    else -> {
                        val hint = k.hint
                        if (hint != null) {
                            textPaint.color = pal.hint
                            textPaint.textSize = rowH * 0.22f
                            canvas.drawText(hint, tmp.right - rowH * 0.16f, tmp.top + rowH * 0.27f, textPaint)
                        }
                        val label = labelOf(k)
                        if (label.isNotEmpty()) {
                            val size = if (label.length >= 2) rowH * 0.26f else rowH * 0.40f
                            val color = if (k.kind == Kind.SPACE && !composing) pal.hint else fg
                            drawLabel(canvas, label, cx, cy, size, color)
                        }
                    }
                }
            }
        }
    }

    private fun drawLabel(canvas: Canvas, label: String, cx: Float, cy: Float, size: Float, color: Int) {
        textPaint.color = color
        textPaint.textSize = size
        val y = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, y, textPaint)
    }

    private fun strokePaint(color: Int): Paint {
        iconPaint.color = color
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = dp(2f)
        iconPaint.strokeCap = Paint.Cap.ROUND
        iconPaint.strokeJoin = Paint.Join.ROUND
        return iconPaint
    }

    private fun drawBackspace(c: Canvas, x: Float, y: Float, s: Float, color: Int) {
        val p = strokePaint(color)
        path.reset()
        path.moveTo(x - s * 1.4f, y)
        path.lineTo(x - s * 0.6f, y - s * 0.95f)
        path.lineTo(x + s * 1.4f, y - s * 0.95f)
        path.lineTo(x + s * 1.4f, y + s * 0.95f)
        path.lineTo(x - s * 0.6f, y + s * 0.95f)
        path.close()
        c.drawPath(path, p)
        c.drawLine(x - s * 0.1f, y - s * 0.4f, x + s * 0.8f, y + s * 0.4f, p)
        c.drawLine(x - s * 0.1f, y + s * 0.4f, x + s * 0.8f, y - s * 0.4f, p)
    }

    private fun drawShift(c: Canvas, x: Float, y: Float, s: Float, color: Int, filled: Boolean) {
        val p = strokePaint(color)
        if (filled) p.style = Paint.Style.FILL_AND_STROKE
        path.reset()
        path.moveTo(x, y - s * 1.2f)
        path.lineTo(x + s * 1.2f, y + s * 0.1f)
        path.lineTo(x + s * 0.55f, y + s * 0.1f)
        path.lineTo(x + s * 0.55f, y + s * 1.1f)
        path.lineTo(x - s * 0.55f, y + s * 1.1f)
        path.lineTo(x - s * 0.55f, y + s * 0.1f)
        path.lineTo(x - s * 1.2f, y + s * 0.1f)
        path.close()
        c.drawPath(path, p)
    }

    private fun drawEnter(c: Canvas, x: Float, y: Float, s: Float, color: Int) {
        val p = strokePaint(color)
        path.reset()
        path.moveTo(x + s * 1.2f, y - s * 0.9f)
        path.lineTo(x + s * 1.2f, y + s * 0.2f)
        path.lineTo(x - s * 1.2f, y + s * 0.2f)
        c.drawPath(path, p)
        path.reset()
        path.moveTo(x - s * 0.45f, y - s * 0.55f)
        path.lineTo(x - s * 1.2f, y + s * 0.2f)
        path.lineTo(x - s * 0.45f, y + s * 0.95f)
        c.drawPath(path, p)
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
        // 利用者がいつもずれて触れる分だけ、触れた位置を戻して判定する
        val ax = if (tapLearn) x - tapDx * refW else x
        val ay = if (tapLearn) y - tapDy * refH else y
        for (row in rows) {
            for (k in row) {
                if (k.kind == Kind.SPACER) continue
                val dx = (ax - k.rect.centerX()) / k.rect.width()
                val dy = (ay - k.rect.centerY()) / k.rect.height()
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
        showPreview(k)
        if (k.kind == Kind.BACKSPACE) {
            // 削除は押した瞬間に実行し、押し続けると連続削除する
            hasPend = false // 直前の入力は間違いだったので、位置の学習には使わない
            listener?.onBackspace()
            repeatPointer = id
            bsLastX = x
            bsAcc = 0f
            handler.postDelayed(repeatRunnable, 400)
        }
        if (k.kind == Kind.SPACE) {
            spaceLastX[id] = x
            spaceAcc[id] = 0f
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
        if (id == repeatPointer) {
            // 削除キーの上を左へなぞると、1語ずつ消す（連続削除は止める）
            bsAcc += x - bsLastX
            bsLastX = x
            val step = dp(44f)
            while (bsAcc <= -step) {
                bsAcc += step
                handler.removeCallbacks(repeatRunnable)
                listener?.onDeleteWord()
            }
            if (bsAcc > 0f) bsAcc = 0f
            return
        }
        if (cur.kind == Kind.SPACE && id in spaceLastX) {
            // スペースキーの上を左右になぞるとカーソルが動く
            val acc = (spaceAcc[id] ?: 0f) + (x - (spaceLastX[id] ?: x))
            spaceLastX[id] = x
            val step = dp(16f)
            var a = acc
            while (a >= step) { a -= step; spaceSwiped.add(id); listener?.onCursor(1) }
            while (a <= -step) { a += step; spaceSwiped.add(id); listener?.onCursor(-1) }
            spaceAcc[id] = a
            return
        }
        val next = hit(x, y) ?: return
        if (next !== cur && id !in longFired) {
            cancelLongPress(id)
            pressed[id] = next
            showPreview(next)
            invalidate()
        }
    }

    private fun onUp(id: Int, x: Float, y: Float) {
        val cur = pressed.remove(id)
        previewCallback?.invoke(null, 0f, 0f, 0f, 0f)
        cancelLongPress(id)
        spaceLastX.remove(id)
        spaceAcc.remove(id)
        if (spaceSwiped.remove(id)) {
            invalidate()
            return
        }
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
            val target = hit(x, y) ?: cur
            if (target.kind == Kind.LETTER) noteTap(target, x, y)
            fire(target)
        }
        invalidate()
    }

    private fun showPreview(k: Key) {
        val cb = previewCallback ?: return
        if (k.kind == Kind.LETTER || k.kind == Kind.DIGIT || k.kind == Kind.SYMBOL) {
            cb(labelOf(k), k.rect.left, k.rect.top, k.rect.right, k.rect.bottom)
        } else {
            cb(null, 0f, 0f, 0f, 0f)
        }
    }

    /** 文字キーを押した位置を覚える。直前の入力がすぐ消されていなければ、その位置を「いつもの触れ方」として学習する。 */
    private fun noteTap(k: Key, x: Float, y: Float) {
        if (!tapLearn) return
        val now = android.os.SystemClock.uptimeMillis()
        if (hasPend && now - pendTime < 1500L) {
            tapDx = (tapDx + (pendDx - tapDx) * 0.03f).coerceIn(-0.2f, 0.2f)
            tapDy = (tapDy + (pendDy - tapDy) * 0.03f).coerceIn(-0.2f, 0.2f)
            acceptedTaps++
            if (acceptedTaps % 40 == 0) {
                Prefs.get(context).edit().putFloat(Prefs.TAP_DX, tapDx).putFloat(Prefs.TAP_DY, tapDy).apply()
            }
        }
        pendDx = (x - k.rect.centerX()) / k.rect.width()
        pendDy = (y - k.rect.centerY()) / k.rect.height()
        pendTime = now
        hasPend = true
    }

    private fun cancelAll() {
        previewCallback?.invoke(null, 0f, 0f, 0f, 0f)
        for (id in longPress.keys.toList()) cancelLongPress(id)
        longFired.clear()
        spaceLastX.clear()
        spaceAcc.clear()
        spaceSwiped.clear()
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
    }
}
