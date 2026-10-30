package com.example.bigkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomajiTest {

    @Test
    fun basicRows() {
        assertEquals("あ", Romaji.table["a"])
        assertEquals("か", Romaji.table["ka"])
        assertEquals("し", Romaji.table["shi"])
        assertEquals("ち", Romaji.table["chi"])
        assertEquals("つ", Romaji.table["tsu"])
        assertEquals("ふ", Romaji.table["fu"])
        assertEquals("を", Romaji.table["wo"])
        assertEquals("じ", Romaji.table["ji"])
    }

    @Test
    fun youonAndSmallKana() {
        assertEquals("きゃ", Romaji.table["kya"])
        assertEquals("しゃ", Romaji.table["sha"])
        assertEquals("ちょ", Romaji.table["cho"])
        assertEquals("じゃ", Romaji.table["ja"])
        assertEquals("ぁ", Romaji.table["xa"])
        assertEquals("っ", Romaji.table["xtu"])
        assertEquals("ゃ", Romaji.table["lya"])
    }

    @Test
    fun prefixes() {
        assertTrue(Romaji.isPrefix("k"))
        assertTrue(Romaji.isPrefix("sh"))
        assertTrue(Romaji.isPrefix("ky"))
        // 確定する読みは「途中」ではない
        assertFalse(Romaji.isPrefix("ka"))
        assertFalse(Romaji.isPrefix("q"))
    }

    @Test
    fun doublable() {
        assertTrue(Romaji.isDoublable('k'))
        assertTrue(Romaji.isDoublable('s'))
        assertTrue(Romaji.isDoublable('p'))
        // n と母音は「っ」にならない
        assertFalse(Romaji.isDoublable('n'))
        assertFalse(Romaji.isDoublable('a'))
    }

    @Test
    fun canContinueCases() {
        assertTrue(Romaji.canContinue("k", 'a')) // ka になる
        assertTrue(Romaji.canContinue("k", 'k')) // 「っ」になる
        assertTrue(Romaji.canContinue("n", 'a')) // na になる
        assertTrue(Romaji.canContinue("n", 'k')) // 「ん」＋次の音になる
        assertFalse(Romaji.canContinue("k", 'z'))
    }
}
