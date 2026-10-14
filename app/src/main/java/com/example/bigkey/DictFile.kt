package com.example.bigkey

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 外部の単語リストの読み込み・書き出し。
 * 形式は、1行に「読み」「表記」（その後ろに品詞などがあっても無視）を、タブかカンマで区切ったもの。
 * Google 日本語入力・Mozc・Microsoft IME の書き出しファイルがそのまま読める。
 * 文字コードは UTF-8 / UTF-16（BOM付き）/ Shift_JIS を自動で判定する。
 */
object DictFile {
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        return strict(bytes, start, Charsets.UTF_8) ?: strict(bytes, 0, Charset.forName("Shift_JIS")) ?: String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    private fun strict(bytes: ByteArray, start: Int, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, start, bytes.size - start))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** 読み込める行だけを (読み, 表記) にして返す。 */
    fun parse(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (raw in text.lineSequence()) {
            val line = raw.trim('﻿', '\r', '\n')
            if (line.isBlank() || line.startsWith("!") || line.startsWith("#")) continue
            val cols = if (line.contains('\t')) line.split('\t') else line.split(',')
            if (cols.size < 2) continue
            val reading = UserDict.normalizeReading(cols[0])
            val surface = cols[1].trim().trim('"')
            if (reading.isNotEmpty() && surface.isNotEmpty()) out.add(reading to surface)
        }
        return out
    }

    /** 書き出し用。Google 日本語入力などで読み込める「読み タブ 表記 タブ 名詞」の形。 */
    fun format(pairs: List<Pair<String, String>>): String =
        pairs.joinToString("\n") { (r, s) -> "$r\t$s\t名詞" } + "\n"
}
