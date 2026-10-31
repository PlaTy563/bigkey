package com.example.bigkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordBoundaryTest {

    @Test
    fun wordAtCursorKanji() {
        // 「漢字を変換する」: 漢字(0-2) を(2-3) 変換(3-5) する(5-7)
        val text = "漢字を変換する"
        assertEquals(0 to 2, WordBoundary.wordRangeAt(text, 1)) // 漢字の途中
        assertEquals(0 to 2, WordBoundary.wordRangeAt(text, 2)) // 直後は左の単語
        assertEquals(3 to 5, WordBoundary.wordRangeAt(text, 4)) // 変換の途中
        assertEquals(3 to 5, WordBoundary.wordRangeAt(text, 5)) // 直後は左（変換）
    }

    @Test
    fun wordAtCursorAsciiAndSpace() {
        val text = "hello world"
        assertEquals(0 to 5, WordBoundary.wordRangeAt(text, 0))
        assertEquals(0 to 5, WordBoundary.wordRangeAt(text, 5)) // 語末
        assertEquals(6 to 11, WordBoundary.wordRangeAt(text, 6)) // 空白の直後は右の単語
        assertEquals(6 to 11, WordBoundary.wordRangeAt(text, 8))
        assertEquals(6 to 11, WordBoundary.wordRangeAt(text, 11))
    }

    @Test
    fun wordAtCursorEmpty() {
        assertNull(WordBoundary.wordRangeAt("", 0))
        assertNull(WordBoundary.wordRangeAt("   ", 1))
    }

    @Test
    fun nextWordEndSteps() {
        val text = "漢字を変換する"
        assertEquals(2, WordBoundary.nextWordEnd(text, 0))
        assertEquals(3, WordBoundary.nextWordEnd(text, 2))
        assertEquals(5, WordBoundary.nextWordEnd(text, 3))
        assertEquals(7, WordBoundary.nextWordEnd(text, 5))
        assertEquals(7, WordBoundary.nextWordEnd(text, 7))
        val en = "foo bar baz"
        assertEquals(3, WordBoundary.nextWordEnd(en, 0))
        assertEquals(7, WordBoundary.nextWordEnd(en, 3)) // 空白を飛ばして bar の末尾
        assertEquals(11, WordBoundary.nextWordEnd(en, 7))
    }

    @Test
    fun prevWordStartSteps() {
        val text = "漢字を変換する"
        assertEquals(5, WordBoundary.prevWordStart(text, 7))
        assertEquals(3, WordBoundary.prevWordStart(text, 5))
        assertEquals(2, WordBoundary.prevWordStart(text, 3))
        assertEquals(0, WordBoundary.prevWordStart(text, 2))
        assertEquals(0, WordBoundary.prevWordStart(text, 0))
        val en = "foo bar baz"
        assertEquals(8, WordBoundary.prevWordStart(en, 11))
        assertEquals(4, WordBoundary.prevWordStart(en, 8)) // 空白を飛ばして bar の先頭
        assertEquals(0, WordBoundary.prevWordStart(en, 4))
    }
}
