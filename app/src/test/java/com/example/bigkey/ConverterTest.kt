package com.example.bigkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 実際の辞書（assets の dict.bin / conn.bin / pos.bin）を使った変換エンジンのテスト。
 * 期待値は tools/ConverterCli.java でJVM上に出力を確認したもの。
 */
class ConverterTest {

    companion object {
        private lateinit var converter: Converter

        private fun asset(name: String): ByteArray {
            val candidates = listOf(
                File("src/main/assets/$name"),
                File("app/src/main/assets/$name"),
                File("../app/src/main/assets/$name")
            )
            val f = candidates.firstOrNull { it.isFile }
                ?: error("assets/$name が見つかりません (作業ディレクトリ: ${File(".").absolutePath})")
            return f.readBytes()
        }

        @JvmStatic
        @BeforeClass
        fun setUp() {
            converter = Converter.load(asset("dict.bin"), asset("conn.bin"))
            converter.setPredictable(asset("pos.bin"))
        }
    }

    private fun texts(reading: String, limit: Int): List<String> =
        converter.convert(reading, limit).map { it.text }

    @Test
    fun topCandidates() {
        assertEquals("今日はいい天気ですね", texts("きょうはいいてんきですね", 6).first())
        assertEquals("私は学生です", texts("わたしはがくせいです", 3).first())
        assertEquals("ありがとうございます", texts("ありがとうございます", 4).first())
    }

    @Test
    fun alternativeCandidates() {
        val list = texts("かんじをへんかんする", 6)
        assertEquals("感じを変換する", list.first())
        assertTrue("漢字を変換する が候補にあること", list.contains("漢字を変換する"))
        assertTrue(texts("ありがとうございます", 4).contains("有難うございます"))
    }

    @Test
    fun unknownTextPassesThrough() {
        assertEquals(listOf("123"), texts("123", 3))
        assertEquals(listOf("abc"), texts("abc", 3))
        assertEquals(emptyList<String>(), texts("", 3))
    }

    @Test
    fun userWordWins() {
        val c = Converter.load(asset("dict.bin"), asset("conn.bin"))
        c.setUserWords(mapOf("てすと" to listOf("テスト単語")))
        assertEquals("テスト単語", c.convert("てすと", 5).first().text)
    }

    @Test
    fun biasChangesTop() {
        val reading = "きのうはあめがふっていました"
        assertTrue(texts(reading, 3).first().startsWith("機能は"))
        val bias = Converter.Bias { rd, surface -> if (rd == "きのう" && surface == "昨日") 1 else 0 }
        val withBias = converter.convert(reading, 3, bias).map { it.text }
        assertTrue(withBias.first().startsWith("昨日は"))
    }

    @Test
    fun altCandidateCarriesSegmentInfo() {
        val list = converter.convert("きのうはあめがふっていました", 8)
        assertEquals(-1, list.first().segStart)
        val alt = list.firstOrNull { it.segSurface == "昨日" }
        assertTrue("「昨日」への差し替え候補があること", alt != null)
        assertEquals(0, alt!!.segStart)
        assertEquals(3, alt.segEnd)
    }

    @Test
    fun okuriganaAltCandidatesAreShown() {
        // 送り仮名が一致する差し替えは、短いかなの語でも候補に出る（以前は出なかった）
        assertTrue(texts("あれたしょうがっこうだ", 6).contains("荒れた小学校だ"))
        assertTrue(texts("たべすぎておなかがいたい", 6).contains("食べ過ぎてお腹が痛い"))
    }

    @Test
    fun liveTextConvertsConfidentPart() {
        assertEquals("今日はいい天気ですね", converter.liveText("きょうはいいてんきですね", null))
    }

    @Test
    fun predictReturnsReadingAndText() {
        val list = converter.predict("ありが", 8, null)
        assertTrue(list.isNotEmpty())
        assertEquals("ありがとう", list.first().text)
        assertEquals("ありがとう", list.first().predictReading)
        assertTrue(list.all { it.predictReading?.startsWith("ありが") == true })
    }
}
