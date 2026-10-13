package com.example.bigkey

/** ローマ字→かなの変換表と、「次にこの文字を打てるか」の判定。 */
object Romaji {
    val table: Map<String, String>
    private val prefixes: Set<String>

    init {
        val m = HashMap<String, String>()
        val v = "aiueo"

        fun row(prefix: String, kana: String) {
            for (i in 0 until 5) m[prefix + v[i]] = kana[i].toString()
        }

        fun youon(prefix: String, base: Char) {
            m[prefix + "ya"] = "${base}ゃ"
            m[prefix + "yi"] = "${base}ぃ"
            m[prefix + "yu"] = "${base}ゅ"
            m[prefix + "ye"] = "${base}ぇ"
            m[prefix + "yo"] = "${base}ょ"
        }

        row("", "あいうえお")
        row("k", "かきくけこ")
        row("s", "さしすせそ")
        row("t", "たちつてと")
        row("n", "なにぬねの")
        row("h", "はひふへほ")
        row("m", "まみむめも")
        row("r", "らりるれろ")
        row("g", "がぎぐげご")
        row("z", "ざじずぜぞ")
        row("d", "だぢづでど")
        row("b", "ばびぶべぼ")
        row("p", "ぱぴぷぺぽ")

        m["ya"] = "や"; m["yi"] = "い"; m["yu"] = "ゆ"; m["ye"] = "いぇ"; m["yo"] = "よ"
        m["wa"] = "わ"; m["wi"] = "うぃ"; m["wu"] = "う"; m["we"] = "うぇ"; m["wo"] = "を"

        youon("k", 'き'); youon("g", 'ぎ'); youon("s", 'し'); youon("z", 'じ')
        youon("t", 'ち'); youon("d", 'ぢ'); youon("n", 'に'); youon("h", 'ひ')
        youon("m", 'み'); youon("r", 'り'); youon("b", 'び'); youon("p", 'ぴ')
        youon("j", 'じ')

        m["shi"] = "し"; m["sha"] = "しゃ"; m["shu"] = "しゅ"; m["she"] = "しぇ"; m["sho"] = "しょ"
        m["chi"] = "ち"; m["cha"] = "ちゃ"; m["chu"] = "ちゅ"; m["che"] = "ちぇ"; m["cho"] = "ちょ"
        m["tsu"] = "つ"
        m["ji"] = "じ"; m["ja"] = "じゃ"; m["ju"] = "じゅ"; m["je"] = "じぇ"; m["jo"] = "じょ"
        m["tha"] = "てゃ"; m["thi"] = "てぃ"; m["thu"] = "てゅ"; m["the"] = "てぇ"; m["tho"] = "てょ"
        m["dha"] = "でゃ"; m["dhi"] = "でぃ"; m["dhu"] = "でゅ"; m["dhe"] = "でぇ"; m["dho"] = "でょ"
        m["twu"] = "とぅ"; m["dwu"] = "どぅ"
        m["fa"] = "ふぁ"; m["fi"] = "ふぃ"; m["fu"] = "ふ"; m["fe"] = "ふぇ"; m["fo"] = "ふぉ"
        m["va"] = "ゔぁ"; m["vi"] = "ゔぃ"; m["vu"] = "ゔ"; m["ve"] = "ゔぇ"; m["vo"] = "ゔぉ"
        m["ca"] = "か"; m["ci"] = "し"; m["cu"] = "く"; m["ce"] = "せ"; m["co"] = "こ"

        for (p in listOf("x", "l")) {
            row(p, "ぁぃぅぇぉ")
            m[p + "ya"] = "ゃ"; m[p + "yu"] = "ゅ"; m[p + "yo"] = "ょ"; m[p + "wa"] = "ゎ"
            m[p + "tu"] = "っ"; m[p + "tsu"] = "っ"
        }

        table = m

        val pre = HashSet<String>()
        for (k in m.keys) {
            for (len in 1 until k.length) pre.add(k.substring(0, len))
        }
        prefixes = pre
    }

    /** 変換表のキーの途中まで（まだ確定しない状態）か。 */
    fun isPrefix(s: String): Boolean = prefixes.contains(s)

    /** 同じ子音を2回打つと「っ」になる文字か（n・母音は除く）。 */
    fun isDoublable(c: Char): Boolean = c in "bcdfghjklmpqrstvwxyz"

    /**
     * 未確定のローマ字 pending の後に c を打つのが、ローマ字として自然かを返す。
     * 誤タップ補正で「次に来そうなキー」を判断するために使う。
     */
    fun canContinue(pending: String, c: Char): Boolean {
        val s = pending + c
        if (table.containsKey(s) || prefixes.contains(s)) return true
        if (pending.length == 1 && pending[0] == c && isDoublable(c)) return true
        if (pending == "n" && c == 'n') return true
        // n の後に子音が来ると「ん」＋次の音になる
        if (pending == "n" && (isPrefix(c.toString()) || table.containsKey(c.toString()))) return true
        return false
    }
}
