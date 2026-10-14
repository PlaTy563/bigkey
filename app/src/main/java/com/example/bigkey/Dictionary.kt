package com.example.bigkey

import android.content.Context

/** 変換の窓口。辞書(assets/dict.bin, conn.bin)は別スレッドで読み込む。読み込み前はひらがな・カタカナだけを返す。 */
class Dictionary(context: Context) {
    class Cand(
        val text: String,
        val segReading: String?,
        val segSurface: String?,
        val predictReading: String? = null,
    ) {
        /** 非表示や学習で使う (読み, 表記) の組。 */
        fun keyReading(whole: String): String = predictReading ?: segReading ?: whole
        fun keySurface(): String = segSurface ?: text
    }

    @Volatile
    private var converter: Converter? = null
    private val learn = LearnStore.instance(context)
    private val hidden = HiddenStore.instance(context)

    private val bias = Converter.Bias { reading, surface -> learn.count(reading, surface) }

    init {
        val app = context.applicationContext
        Thread {
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

    /**
     * 候補の並び: 最有力の変換結果 → 予測変換 → 単語を替えた変換 → ひらがな → カタカナ。
     * 利用者が非表示にした候補は除く。
     */
    fun candidates(reading: String, limit: Int = 8, predict: Boolean = true): List<Cand> {
        if (reading.isEmpty()) return emptyList()
        val conv = converter
        val converted = ArrayList<Cand>()
        val seen = HashSet<String>()
        if (conv != null) {
            try {
                for (c in conv.convert(reading, limit, bias)) {
                    if (!seen.add(c.text)) continue
                    val seg = if (c.segSurface != null && c.segStart >= 0) reading.substring(c.segStart, c.segEnd) else null
                    converted.add(Cand(c.text, seg, c.segSurface))
                }
            } catch (_: Throwable) {
            }
        }
        val out = ArrayList<Cand>()
        converted.firstOrNull()?.let { out.add(it) }
        if (predict && conv != null && reading.length <= 6) {
            try {
                for (c in conv.predict(reading, 5, bias)) {
                    if (seen.add(c.text)) out.add(Cand(c.text, null, null, c.predictReading))
                }
            } catch (_: Throwable) {
            }
        }
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
            conv.liveText(reading, bias)
        } catch (_: Throwable) {
            reading
        }
    }

    /** 候補を今後出さないようにする。 */
    fun hide(reading: String, cand: Cand) {
        hidden.hide(cand.keyReading(reading), cand.keySurface())
    }

    /** 選んだ候補を覚える。差し替えた単語があればその単位で、予測変換なら読み全体で、全体がカタカナ変換なら全体で覚える。 */
    fun learn(reading: String, cand: Cand) {
        if (cand.predictReading != null) {
            learn.record(cand.predictReading, cand.text)
        } else if (cand.segReading != null && cand.segSurface != null) {
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
