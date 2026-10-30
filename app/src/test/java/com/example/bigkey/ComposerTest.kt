package com.example.bigkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerTest {

    private fun typed(input: String): Composer {
        val c = Composer()
        for (ch in input) c.inputLetter(ch)
        return c
    }

    @Test
    fun basicConversion() {
        assertEquals("こんにちは", typed("konnichiha").kanaText())
        assertEquals("かんじ", typed("kanji").kanaText())
        assertEquals("ありがとう", typed("arigatou").kanaText())
    }

    @Test
    fun sokuon() {
        assertEquals("きっぷ", typed("kippu").kanaText())
        assertEquals("がっこう", typed("gakkou").kanaText())
    }

    @Test
    fun pendingIsShownAsRomaji() {
        val c = typed("k")
        assertEquals("", c.kanaText())
        assertEquals("k", c.displayText())
        assertEquals("k", c.trailingRomaji())
    }

    @Test
    fun trailingNPreviewsAsN() {
        val c = typed("kan")
        // 画面にはローマ字のまま、変換用の読みでは「ん」として扱う
        assertEquals("かn", c.displayText())
        assertEquals("かん", c.previewReading())
        c.flushPending()
        assertEquals("かん", c.kanaText())
        assertEquals("かん", c.displayText())
    }

    @Test
    fun nnThenVowelStartsNextSyllable() {
        // konnichiha の「ん」の後の n は、次の音（に）の頭になる
        val c = typed("konn")
        assertEquals("こん", c.kanaText())
        assertEquals("こん", c.displayText()) // 保留中の n は画面に出ない
    }

    @Test
    fun backspaceRemovesPendingFirst() {
        val c = typed("kak")
        assertEquals("かk", c.displayText())
        c.backspace()
        assertEquals("か", c.displayText())
        c.backspace()
        assertTrue(c.isEmpty())
    }

    @Test
    fun backspaceAfterNnRemovesN() {
        val c = typed("konn")
        c.backspace()
        assertEquals("こ", c.kanaText())
    }

    @Test
    fun appendLiteralFlushesPending() {
        val c = typed("kan")
        c.appendLiteral("1")
        assertEquals("かん1", c.kanaText())
    }

    @Test
    fun clearResets() {
        val c = typed("kanji")
        c.clear()
        assertTrue(c.isEmpty())
        assertEquals("", c.displayText())
    }

    @Test
    fun canContinueDelegatesToRomaji() {
        val c = typed("k")
        assertTrue(c.canContinue('a'))
        assertFalse(c.canContinue('z'))
    }
}
