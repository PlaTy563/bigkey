package com.example.bigkey

import android.content.Context

/** 変換の窓口。辞書(assets/dict.bin, conn.bin)は別スレッドで読み込む。読み込み前はひらがな・カタカナだけを返す。 */
class Dictionary(context: Context) {
    class Cand(val text: String, val segReading: String?, val segSurface: String?)

    @Volatile
    private var converter: Converter? = null
    private val learn = LearnStore.instance(context)

    private val bias = Converter.Bias { reading, surface -> learn.count(reading, surface) }

    init {
        val app = context.applicationContext
        Thread {
            try {
                val d = app.assets.open("dict.bin").use { it.readBytes() }
                val c = app.assets.open("conn.bin").use { it.readBytes() }
                converter = Converter.load(d, c)
            } catch (_: Throwable) {
                // 辞書がなくても、ひらがな・カタカナ入力は使える
            }
        }.start()
    }

    /** 候補の並び: 変換結果（先頭が最有力）→ ひらがな → カタカナ */
    fun candidates(reading: String, limit: Int = 8): List<Cand> {
        if (reading.isEmpty()) return emptyList()
        val out = ArrayList<Cand>()
        val seen = HashSet<String>()
        converter?.let { conv ->
            try {
                for (c in conv.convert(reading, limit, bias)) {
                    if (!seen.add(c.text)) continue
                    val seg = if (c.segSurface != null && c.segStart >= 0) reading.substring(c.segStart, c.segEnd) else null
                    out.add(Cand(c.text, seg, c.segSurface))
                }
            } catch (_: Throwable) {
            }
        }
        if (seen.add(reading)) out.add(Cand(reading, null, null))
        val kata = toKatakana(reading)
        if (seen.add(kata)) out.add(Cand(kata, null, null))
        return out
    }

    /** 選んだ候補を覚える。差し替えた単語があればその単位で、全体がひらがな・カタカナ変換なら全体で覚える。 */
    fun learn(reading: String, cand: Cand) {
        if (cand.segReading != null && cand.segSurface != null) {
            learn.record(cand.segReading, cand.segSurface)
        } else if (cand.text != reading && cand.text == toKatakana(reading)) {
            learn.record(reading, cand.text)
        }
    }

    private fun toKatakana(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(if (ch in 'ぁ'..'ゖ') (ch.code + 0x60).toChar() else ch)
        }
        return sb.toString()
    }
}
