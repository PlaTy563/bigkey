package com.example.bigkey

/**
 * 入力中の文字列を管理する。確定済みのかな(kana)と、まだかなにならないローマ字(pending)を分けて持つ。
 *
 * 「nn」で「ん」になった直後の n は hiddenN として画面に出さず保留する。
 * 次が母音・y なら次の音の頭（konnichiha → こんにちは）、それ以外なら「nn」の2文字目として捨てる（kannji → かんじ）。
 */
class Composer {
    private val kana = StringBuilder()
    private var pending = ""
    private var hiddenN = false

    fun isEmpty(): Boolean = kana.isEmpty() && pending.isEmpty()

    /** 画面に表示する文字列（かな＋未確定ローマ字）。 */
    fun displayText(): String = kana.toString() + (if (hiddenN) "" else pending)

    fun kanaText(): String = kana.toString()

    /** かなになっていない末尾のローマ字（画面に出ている分）。 */
    fun trailingRomaji(): String = if (hiddenN) "" else pending

    /** 変換候補の検索に使う読み。末尾の n は「ん」として扱う。 */
    fun previewReading(): String =
        kana.toString() + (if (!hiddenN && pending == "n") "ん" else "")

    fun canContinue(c: Char): Boolean = Romaji.canContinue(pending, c.lowercaseChar())

    fun inputLetter(ch: Char) {
        val c = ch.lowercaseChar()
        if (hiddenN) {
            hiddenN = false
            if (c == 'n') return // 保留していた n が、そのまま通常の未確定 n になる
            if (c !in "aiueoy") pending = "" // 保留 n は「nn」の2文字目だったので捨てる
        } else if (pending == "n" && c == 'n') {
            kana.append('ん')
            pending = "n"
            hiddenN = true
            return
        }

        pending += c
        while (pending.isNotEmpty()) {
            val hit = Romaji.table[pending]
            if (hit != null) {
                kana.append(hit)
                pending = ""
                return
            }
            if (pending.length >= 2 && pending[0] == pending[1] && Romaji.isDoublable(pending[0])) {
                kana.append('っ')
                pending = pending.substring(1)
                continue
            }
            if (Romaji.isPrefix(pending)) return
            if (pending[0] == 'n') {
                kana.append('ん')
                pending = pending.substring(1)
                continue
            }
            // どの規則にも合わない先頭1文字はそのまま出す
            kana.append(pending[0])
            pending = pending.substring(1)
        }
    }

    /** かな以外の文字（数字・記号・大文字など）をそのまま追加する。 */
    fun appendLiteral(s: String) {
        flushPending()
        kana.append(s)
    }

    /** 未確定のローマ字を確定させる（末尾の n は「ん」、他は文字のまま）。 */
    fun flushPending() {
        if (hiddenN) {
            hiddenN = false
            pending = ""
            return
        }
        if (pending == "n") kana.append('ん') else kana.append(pending)
        pending = ""
    }

    fun backspace() {
        if (hiddenN) {
            hiddenN = false
            pending = ""
            if (kana.isNotEmpty()) kana.setLength(kana.length - 1)
            return
        }
        if (pending.isNotEmpty()) {
            pending = pending.dropLast(1)
            return
        }
        if (kana.isNotEmpty()) kana.setLength(kana.length - 1)
    }

    fun clear() {
        kana.setLength(0)
        pending = ""
        hiddenN = false
    }
}
