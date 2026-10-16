package com.example.bigkey

import android.content.Context

/** 英単語の補完。よく使われる順に並んだ語の一覧から、打ちかけの語で始まるものを返す。 */
class EnglishDict(private val ctx: Context) {
    @Volatile
    private var words: List<String> = emptyList()

    init {
        Thread {
            try {
                val list = ctx.assets.open("en_words.txt").bufferedReader().useLines { it.filter { w -> w.isNotEmpty() }.toList() }
                words = list
            } catch (_: Exception) {
            }
        }.start()
    }

    /** prefix で始まる語を、よく使われる順に最大 limit 個。prefix と同じ語自体は含めない。 */
    fun complete(prefix: String, limit: Int): List<String> {
        if (prefix.length < 2) return emptyList()
        val lower = prefix.lowercase()
        val allUpper = prefix.length >= 2 && prefix.all { it.isUpperCase() }
        val capital = prefix[0].isUpperCase()
        val out = ArrayList<String>(limit)
        for (w in words) {
            if (w.length > lower.length && w.startsWith(lower)) {
                out.add(
                    when {
                        allUpper -> w.uppercase()
                        capital -> w.replaceFirstChar { it.uppercase() }
                        else -> w
                    }
                )
                if (out.size >= limit) break
            }
        }
        return out
    }
}
