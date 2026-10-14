package com.example.bigkey

import android.content.Context

/** 変換の窓口。辞書(assets/dict.bin, conn.bin)は別スレッドで読み込む。読み込み前はひらがな・カタカナだけを返す。 */
class Dictionary(context: Context) {
    class Cand(
        val text: String,
        val segReading: String?,
        val segSurface: String?,
        val predictReading: String? = null,
        val surfaces: Array<String>? = null,
        val readings: Array<String>? = null,
        val isEmoji: Boolean = false,
    ) {
        /** 非表示や学習で使う (読み, 表記) の組。 */
        fun keyReading(whole: String): String = predictReading ?: segReading ?: whole
        fun keySurface(): String = segSurface ?: text
    }

    @Volatile
    private var converter: Converter? = null
    private val learn = LearnStore.instance(context)
    private val hidden = HiddenStore.instance(context)
    private val user = UserDict.instance(context)
    private var syncedUserVersion = -1

    @Volatile
    private var emojiByReading: Map<String, List<String>> = emptyMap()

    @Volatile
    private var phrases: List<Pair<String, String>> = emptyList()

    private val bias = object : Converter.Bias {
        override fun count(reading: String, surface: String): Int = learn.count(reading, surface)
        override fun pair(prevSurface: String, surface: String): Int = learn.count("pair:$prevSurface", surface)
    }

    init {
        val app = context.applicationContext
        Thread {
            try {
                emojiByReading = loadEmoji(app)
                phrases = loadPhrases(app)
            } catch (_: Throwable) {
            }
            try {
                val d = app.assets.open("dict.bin").use { it.readBytes() }
                val c = app.assets.open("conn.bin").use { it.readBytes() }
                val conv = Converter.load(d, c)
                try {
                    conv.setPredictable(app.assets.open("pos.bin").use { it.readBytes() })
                } catch (_: Exception) {
                }
                converter = conv
            } catch (_: Throwable) {
                // 辞書がなくても、ひらがな・カタカナ入力は使える
            }
        }.start()
    }

    private fun loadEmoji(app: Context): Map<String, List<String>> {
        val m = HashMap<String, MutableList<String>>()
        app.assets.open("emoji_reading.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val cols = line.split('\t')
                if (cols.size < 2) continue
                val list = m.getOrPut(cols[0]) { ArrayList() }
                for (e in cols[1].split(' ')) if (e.isNotEmpty() && e !in list) list.add(e)
            }
        }
        return m
    }

    private fun loadPhrases(app: Context): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        app.assets.open("next_phrases.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val cols = line.split('\t')
                if (cols.size >= 2 && cols[0].isNotEmpty() && cols[1].isNotEmpty()) out.add(cols[0] to cols[1])
            }
        }
        return out
    }

    /** 利用者の登録単語が変わっていたら、変換エンジンに渡し直す。 */
    private fun syncUser(conv: Converter) {
        val v = user.version
        if (v != syncedUserVersion) {
            conv.setUserWords(user.snapshot())
            syncedUserVersion = v
        }
    }

    /**
     * 候補の並び: 最有力の変換結果 → 登録単語 → 予測変換 → 絵文字 → 単語を替えた変換 → ひらがな → カタカナ。
     * 利用者が非表示にした候補は除く。
     */
    fun candidates(reading: String, limit: Int = 8, predict: Boolean = true): List<Cand> {
        if (reading.isEmpty()) return emptyList()
        val conv = converter
        val converted = ArrayList<Cand>()
        val seen = HashSet<String>()
        if (conv != null) {
            try {
                syncUser(conv)
                for (c in conv.convert(reading, limit, bias)) {
                    if (!seen.add(c.text)) continue
                    val seg = if (c.segSurface != null && c.segStart >= 0) reading.substring(c.segStart, c.segEnd) else null
                    converted.add(Cand(c.text, seg, c.segSurface, null, c.surfaces, c.readings))
                }
            } catch (_: Throwable) {
            }
        }
        val out = ArrayList<Cand>()
        converted.firstOrNull()?.let { out.add(it) }

        // 登録単語（読みが一致するもの）
        user.snapshot()[reading]?.forEach { if (seen.add(it)) out.add(Cand(it, null, null, reading)) }

        if (predict && reading.length <= 6) {
            // 登録単語の予測（読みの先頭が一致）
            var userPredicted = 0
            for ((r, list) in user.snapshot()) {
                if (userPredicted >= 3) break
                if (r.length > reading.length && r.startsWith(reading)) {
                    for (s in list) if (seen.add(s)) { out.add(Cand(s, null, null, r)); userPredicted++ }
                }
            }
            if (conv != null) {
                try {
                    for (c in conv.predict(reading, 5, bias)) {
                        if (seen.add(c.text)) out.add(Cand(c.text, null, null, c.predictReading))
                    }
                } catch (_: Throwable) {
                }
            }
        }
        // 絵文字（読みが完全に一致したとき）
        emojiByReading[reading]?.forEach { if (seen.add(it)) out.add(Cand(it, null, null, null, null, null, true)) }

        out.addAll(converted.drop(1))
        if (seen.add(reading)) out.add(Cand(reading, null, null))
        val kata = toKatakana(reading)
        if (seen.add(kata)) out.add(Cand(kata, null, null))
        // 非表示の候補を除く（ひらがなそのものは必ず残す）
        val filtered = out.filter {
            it.text == reading || !hidden.isHidden(it.keyReading(reading), it.keySurface())
        }
        return if (filtered.isEmpty()) listOf(Cand(reading, null, null)) else filtered
    }

    /** 自動変換で入力中に表示する文字列。確信のある部分だけ変換し、打ちかけの末尾はひらがなのまま残す。 */
    fun liveText(reading: String): String {
        if (reading.isEmpty()) return reading
        val conv = converter ?: return reading
        return try {
            syncUser(conv)
            conv.liveText(reading, bias)
        } catch (_: Throwable) {
            reading
        }
    }

    /** 候補を今後出さないようにする。 */
    fun hide(reading: String, cand: Cand) {
        hidden.hide(cand.keyReading(reading), cand.keySurface())
    }

    /**
     * 選んだ候補を覚える。
     * 変換結果なら、単語ごとの選択と、隣り合う単語の並びを覚える。予測変換なら読み全体、カタカナ変換なら全体で覚える。
     */
    fun learn(reading: String, cand: Cand) {
        if (cand.isEmoji) return
        val s = cand.surfaces
        val r = cand.readings
        if (cand.predictReading != null) {
            learn.record(cand.predictReading, cand.text)
        } else if (s != null && r != null && s.size == r.size) {
            for (i in s.indices) {
                if (s[i] != r[i]) learn.record(r[i], s[i])
                if (i > 0) learn.record("pair:${s[i - 1]}", s[i])
            }
        } else if (cand.text != reading && cand.text == toKatakana(reading)) {
            learn.record(reading, cand.text)
        }
    }

    /** 確定した文字列の並びを覚える（次の語の予測に使う）。 */
    fun recordNext(prev: String, cur: String) {
        if (prev.isEmpty() || cur.isEmpty() || prev.length > 30 || cur.length > 30) return
        learn.record("next:$prev", cur)
    }

    /** 直前に確定した文字列のあとに続きそうな言葉。よく使うもの → 定型の表現の順。 */
    fun nextSuggestions(last: String, limit: Int = 6): List<String> {
        if (last.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for ((s, _) in learn.top("next:$last|", limit)) out.add(s)
        for ((ctx, sug) in phrases) {
            if (last.endsWith(ctx)) out.add(sug)
            if (out.size >= limit) break
        }
        return out.filter { !hidden.isHidden("next:$last", it) }.take(limit)
    }

    private fun toKatakana(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(if (ch in 'ぁ'..'ゖ') (ch.code + 0x60).toChar() else ch)
        }
        return sb.toString()
    }
}
