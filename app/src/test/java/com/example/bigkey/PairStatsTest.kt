package com.example.bigkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 単語の並びの統計（pairs.bin）を使った文脈判断のテスト。
 * 統計なしでは外していた同音語の文が、統計ありで正解になることを確認する。
 */
class PairStatsTest {

    companion object {
        private lateinit var converter: Converter
        private lateinit var stats: PairStats

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
            converter.setPairStats(asset("pairs.bin"))
            stats = PairStats.load(asset("pairs.bin"))
        }
    }

    private fun top(reading: String): String = converter.convert(reading, 1)[0].text

    @Test
    fun pairStatsPrefersFrequentContinuation() {
        // 「昨日は」は「機能は」よりはるかに多い並び
        assertTrue(stats.bonus("昨日", "は") > stats.bonus("機能", "は"))
        // 目的語の後では「言っ」が「行っ」より多い
        assertTrue(stats.bonus("を", "言っ") > stats.bonus("を", "行っ"))
        // 文頭では「昨日」が「機能」よりはるかに多い
        assertTrue(stats.bonus("\u0000", "昨日") > stats.bonus("\u0000", "機能"))
        // 統計にない並びは0
        assertEquals(0, stats.bonus("存在しない単語", "は"))
    }

    @Test
    fun contextFixesKinou() {
        assertEquals("昨日は雨が降っていました", top("きのうはあめがふっていました"))
    }

    @Test
    fun contextFixesItte() {
        assertEquals("本当のことを言ってほしかった", top("ほんとうのことをいってほしかった"))
    }

    @Test
    fun contextFixesFuru() {
        assertEquals("雨が降るかもしれない", top("あめがふるかもしれない"))
    }
}
