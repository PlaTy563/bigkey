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
        // 学習の効きは1件500（最大3件で-1500）。昨日と機能の費用差はその範囲なので、3件で逆転する
        val bias = Converter.Bias { rd, surface -> if (rd == "きのう" && surface == "昨日") 3 else 0 }
        val withBias = converter.convert(reading, 3, bias).map { it.text }
        assertTrue(withBias.first().startsWith("昨日は"))
    }

    @Test
    fun domainWordsConvert() {
        val c = Converter.load(asset("dict.bin"), asset("conn.bin"))
        c.setDomainWords(mapOf("がいえい" to listOf("外穎"), "かいか" to listOf("開花"), "やく" to listOf("葯")))
        assertEquals("外穎", c.convert("がいえい", 16).first().text)
        assertEquals("稲の外穎", c.convert("いねのがいえい", 16).first().text)
        assertEquals("開花", c.convert("かいか", 16).first().text)
        // 辞書に無い専門用語が候補の上位に出る
        val yaku = c.convert("やく", 16).map { it.text }
        assertTrue("葯 should be a top candidate: $yaku", yaku.indexOf("葯") in 0..2)
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
    fun liveTextConvertsHomophoneVerbStem() {
        // 言っ（コスト0）と行っ（コスト8）のように語幹のコスト差が小さい同音語でも、
        // 打ち終わった文節は最良候補に変換される（以前は文全体がかなのままだった）
        assertEquals("言ってほしかった", converter.liveText("いってほしかった", null))
        assertEquals("感じを変換する", converter.liveText("かんじをへんかんする", null))
    }

    @Test
    fun liveTextHoldsLastSegment() {
        // 打ちかけの最後の文節は変換しない
        assertEquals("しらべる", converter.liveText("しらべる", null))
        assertEquals("かんじ", converter.liveText("かんじ", null))
    }

    @Test
    fun personNamesAreNotAltCandidates() {
        // 人名は差し替え候補に出さない（最良経路には残る）
        val list = texts("しょう", 20)
        for (name in listOf("翔", "晶", "聖", "彰", "昭", "松", "唱")) {
            assertTrue("$name が候補に出ないこと", !list.contains(name))
        }
        // 人名でない候補は残る
        assertTrue(list.contains("章"))
        assertTrue(list.contains("焼"))
    }

    @Test
    fun katakanaRenderingsAreNotAltCandidates() {
        // 読みをカタカナで書いただけの差し替えは出さない
        assertTrue(!texts("かんじ", 10).contains("カンジ"))
        assertTrue(!texts("きょう", 20).contains("キョウ"))
        assertTrue(texts("かんじ", 10).contains("漢字"))
    }

    @Test
    fun learningBiasKeepsAlternatives() {
        // 感じを過去に選んだ学習があっても、同じ読みの漢字・幹事が候補から消えないこと
        val bias = object : Converter.Bias {
            override fun count(reading: String, surface: String): Int =
                if (reading == "かんじ" && surface == "感じ") 3 else 0
        }
        val biased = converter.convert("かんじ", 16, bias).map { it.text }
        assertEquals("感じ", biased.first())
        assertTrue("漢字 should survive learning bias: $biased", "漢字" in biased)
        assertTrue("幹事 should survive learning bias: $biased", "幹事" in biased)
    }

    @Test
    fun singleCharBiasDoesNotBreakSentence() {
        // 別の単語の一部として選ばれた1文字の漢字（都・化）への学習が強くても、
        // 助詞の位置にある と・か を押しのけて文の変換を壊さないこと
        val bias = object : Converter.Bias {
            override fun count(reading: String, surface: String): Int = when {
                reading == "と" && surface == "都" -> 3
                reading == "か" && surface == "化" -> 3
                else -> 0
            }
        }
        val list = converter.convert("しらかわごうとか", 16, bias)
        assertEquals("白川郷とか", list.first().text)
    }

    @Test
    fun segmentBiasDoesNotStackToBreakWords() {
        // 複数の区間への学習が重なっても、辞書本来の語（自分・基本）が負けないこと
        val bias = object : Converter.Bias {
            override fun count(reading: String, surface: String): Int = when {
                reading == "じ" && surface == "時" -> 3
                reading == "ぶん" && surface == "分" -> 3
                reading == "き" && surface == "気" -> 3
                reading == "ほん" && surface == "本" -> 3
                reading == "で" && surface == "出" -> 3
                reading == "ない" && surface == "内" -> 3
                else -> 0
            }
        }
        assertEquals("自分", converter.convert("じぶん", 16, bias).first().text)
        assertEquals("基本でない", converter.convert("きほんでない", 16, bias).first().text)
    }

    @Test
    fun findReadingReversesSurface() {
        assertEquals("きのう", converter.findReading("昨日"))
        assertEquals("きのう", converter.findReading("機能"))
        assertEquals("きょう", converter.findReading("今日"))
        // 人名の表記も逆引きできる（確定済みの単語を変換し直すために使う）
        assertEquals("しょう", converter.findReading("翔"))
        assertEquals(null, converter.findReading("デビアン"))
        assertEquals(null, converter.findReading(""))
    }

    @Test
    fun segmentReadingSplitsSelection() {
        val c = Converter.load(asset("dict.bin"), asset("conn.bin"))
        c.buildReverseIndex()
        // 索引を使っても単体の逆引きは同じ結果
        assertEquals("きのう", c.findReading("昨日"))
        assertEquals(null, c.findReading("デビアン"))
        // 途中まで選んだ範囲（かん＋時）や、複合語を単語の並びに分けて読みを推定する
        assertEquals("かんじ", c.segmentReading("かん時"))
        assertEquals("かんじへんかん", c.segmentReading("漢字変換"))
        assertEquals("たべる", c.segmentReading("食べる"))
        // 辞書に無い文字だけの範囲は推定できない
        assertEquals(null, c.segmentReading("龘"))
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
