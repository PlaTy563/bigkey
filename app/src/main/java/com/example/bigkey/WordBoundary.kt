package com.example.bigkey

/**
 * 文字の種類（漢字・ひらがな・カタカナ・英数字・空白・記号）の連なりを「単語」とみなして、
 * カーソル位置の単語の範囲や、単語単位の移動先を求める純粋関数群。
 * 削除の単語単位処理（BigKeyService.wordLengthBefore）と同じ分類を使う。
 */
object WordBoundary {

    private fun charClass(cp: Int): Int = when {
        Character.isWhitespace(cp) -> 0
        cp < 128 && (Character.isLetterOrDigit(cp) || cp == '_'.code) -> 1
        cp in 0x3040..0x309F -> 2 // ひらがな
        cp in 0x30A0..0x30FF -> 3 // カタカナ（ー を含む）
        Character.isLetterOrDigit(cp) -> 4 // 漢字など
        else -> 5 // 記号
    }

    /** [start, end) の範囲で、同じ種類の文字が続く区間を返す。i は区間内の位置（UTF-16単位）。 */
    private fun runRange(text: String, i: Int): Pair<Int, Int> {
        val cls = charClass(Character.codePointAt(text, i))
        var s = i
        while (s > 0) {
            val cp = Character.codePointBefore(text, s)
            if (charClass(cp) != cls) break
            s -= Character.charCount(cp)
        }
        var e = i
        while (e < text.length) {
            val cp = Character.codePointAt(text, e)
            if (charClass(cp) != cls) break
            e += Character.charCount(cp)
        }
        return s to e
    }

    /**
     * カーソル位置 pos（0..text.length）にある単語の範囲 [start, end) を返す。
     * 左の文字が単語ならその区間、空白の上なら右側の単語、それも無ければ左の単語。
     * テキストが空なら null。
     */
    fun wordRangeAt(text: String, pos: Int): Pair<Int, Int>? {
        if (text.isEmpty()) return null
        val p = pos.coerceIn(0, text.length)
        if (p > 0) {
            val cp = Character.codePointBefore(text, p)
            if (charClass(cp) != 0) return runRange(text, p - Character.charCount(cp))
        }
        if (p < text.length) {
            val cp = Character.codePointAt(text, p)
            if (charClass(cp) != 0) return runRange(text, p)
        }
        // 空白の上: 右にある単語 → 無ければ左の単語
        var i = p
        while (i < text.length && charClass(Character.codePointAt(text, i)) == 0) {
            i += Character.charCount(Character.codePointAt(text, i))
        }
        if (i < text.length) return runRange(text, i)
        var j = p
        while (j > 0 && charClass(Character.codePointBefore(text, j)) == 0) {
            j -= Character.charCount(Character.codePointBefore(text, j))
        }
        if (j > 0) {
            val cp = Character.codePointBefore(text, j)
            return runRange(text, j - Character.charCount(cp))
        }
        return null
    }

    /** pos から見て次の単語の末尾（単語ジャンプの右方向の着地点）。 */
    fun nextWordEnd(text: String, pos: Int): Int {
        var i = pos.coerceIn(0, text.length)
        // 今いる単語の途中・末尾なら、その単語の末尾までが最初の着地点
        if (i < text.length && i > 0) {
            val at = Character.codePointAt(text, i)
            val before = Character.codePointBefore(text, i)
            if (charClass(at) != 0 && charClass(before) == charClass(at)) {
                return runRange(text, i).second
            }
        }
        // 空白を飛ばして次の単語に入り、その末尾まで
        while (i < text.length && charClass(Character.codePointAt(text, i)) == 0) {
            i += Character.charCount(Character.codePointAt(text, i))
        }
        if (i >= text.length) return text.length
        return runRange(text, i).second
    }

    /** pos から見て前の単語の先頭（単語ジャンプの左方向の着地点）。 */
    fun prevWordStart(text: String, pos: Int): Int {
        var i = pos.coerceIn(0, text.length)
        if (i > 0 && i < text.length) {
            val before = Character.codePointBefore(text, i)
            val at = Character.codePointAt(text, i)
            if (charClass(before) != 0 && charClass(at) == charClass(before)) {
                return runRange(text, i - Character.charCount(before)).first
            }
        }
        while (i > 0 && charClass(Character.codePointBefore(text, i)) == 0) {
            i -= Character.charCount(Character.codePointBefore(text, i))
        }
        if (i <= 0) return 0
        val cp = Character.codePointBefore(text, i)
        return runRange(text, i - Character.charCount(cp)).first
    }
}
