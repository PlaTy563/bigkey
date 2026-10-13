package com.example.bigkey

import android.content.Context

/**
 * 変換辞書。assets/dict.txt を読み込む。
 * 形式: 「よみ 候補1,候補2,…」（1行1語、先頭が # の行はコメント）
 */
class Dictionary(context: Context) {
    private val map = HashMap<String, MutableList<String>>()
    private var maxReadingLen = 1
    private val learn = LearnStore.instance(context)

    init {
        try {
            context.assets.open("dict.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank() || line.startsWith("#")) continue
                    val idx = line.indexOf(' ')
                    if (idx <= 0) continue
                    val reading = line.substring(0, idx)
                    val words = line.substring(idx + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (words.isEmpty()) continue
                    map.getOrPut(reading) { ArrayList() }.addAll(words)
                    if (reading.length > maxReadingLen) maxReadingLen = reading.length
                }
            }
        } catch (_: Exception) {
            // 辞書がなくても、ひらがな・カタカナ入力は使える
        }
    }

    /** 候補の並び: 辞書の候補（学習順）→ 文節ごとの最長一致変換 → ひらがな → カタカナ */
    fun candidates(reading: String): List<String> {
        if (reading.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        map[reading]?.sortedByDescending { learn.count(reading, it) }?.let { out.addAll(it) }
        segment(reading)?.let { out.add(it) }
        out.add(reading)
        out.add(toKatakana(reading))
        return out.toList()
    }

    fun learn(reading: String, cand: String) {
        learn.record(reading, cand)
    }

    private fun best(reading: String, words: List<String>): String =
        words.maxByOrNull { learn.count(reading, it) } ?: words.first()

    /** 先頭から最長一致で区切って変換する。辞書にない部分はそのまま残す。 */
    private fun segment(reading: String): String? {
        val sb = StringBuilder()
        var i = 0
        var converted = false
        while (i < reading.length) {
            var matched = false
            val maxLen = minOf(maxReadingLen, reading.length - i)
            for (len in maxLen downTo 1) {
                val key = reading.substring(i, i + len)
                val words = map[key]
                if (words != null) {
                    sb.append(best(key, words))
                    i += len
                    matched = true
                    converted = true
                    break
                }
            }
            if (!matched) {
                sb.append(reading[i])
                i++
            }
        }
        return if (converted) sb.toString() else null
    }

    private fun toKatakana(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(if (ch in 'ぁ'..'ゖ') (ch.code + 0x60).toChar() else ch)
        }
        return sb.toString()
    }
}
