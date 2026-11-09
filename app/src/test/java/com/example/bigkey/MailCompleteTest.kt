package com.example.bigkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MailCompleteTest {

    @Test
    fun noAtSignNoCompletion() {
        assertTrue(MailComplete.complete("yuto").isEmpty())
        assertTrue(MailComplete.complete("").isEmpty())
    }

    @Test
    fun justAtShowsPopularDomains() {
        val sug = MailComplete.complete("yuto@")
        assertEquals("gmail.com", sug.first())
        assertTrue(sug.size <= 6)
    }

    @Test
    fun prefixFiltersDomains() {
        assertEquals(listOf("gmail.com"), MailComplete.complete("yuto@gm"))
        assertEquals(listOf("yahoo.co.jp", "yahoo.com"), MailComplete.complete("yuto@yah"))
        assertEquals(listOf("gmail.com"), MailComplete.complete("yuto@GMAIL"))
    }

    @Test
    fun completedDomainNotSuggested() {
        assertTrue(MailComplete.complete("yuto@gmail.com").isEmpty())
    }

    @Test
    fun usesLastAtSign() {
        assertEquals(listOf("gmail.com"), MailComplete.complete("a@b@gm"))
    }
}
