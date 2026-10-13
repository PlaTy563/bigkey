package com.example.bigkey

import android.content.Context
import org.json.JSONObject
import java.io.File

/** 変換候補の選択回数（学習履歴）。端末内のファイルにだけ保存されるため、バックアップ機能の対象にする。 */
class LearnStore private constructor(private val file: File) {
    private val counts = HashMap<String, Int>()

    companion object {
        @Volatile
        private var inst: LearnStore? = null

        fun instance(ctx: Context): LearnStore =
            inst ?: synchronized(this) {
                inst ?: LearnStore(File(ctx.applicationContext.filesDir, "learn.json")).also { inst = it }
            }
    }

    init {
        load()
    }

    @Synchronized
    fun count(reading: String, cand: String): Int = counts["$reading|$cand"] ?: 0

    @Synchronized
    fun record(reading: String, cand: String) {
        val k = "$reading|$cand"
        counts[k] = (counts[k] ?: 0) + 1
        save()
    }

    @Synchronized
    fun toJson(): JSONObject {
        val o = JSONObject()
        for ((k, v) in counts) o.put(k, v)
        return o
    }

    @Synchronized
    fun replaceFrom(o: JSONObject) {
        counts.clear()
        val it = o.keys()
        while (it.hasNext()) {
            val k = it.next()
            val n = o.optInt(k, 0)
            if (n > 0) counts[k] = n
        }
        save()
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val o = JSONObject(file.readText(Charsets.UTF_8))
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                val n = o.optInt(k, 0)
                if (n > 0) counts[k] = n
            }
        } catch (_: Exception) {
            // 壊れていたら空から始める
        }
    }

    private fun save() {
        try {
            file.writeText(toJson().toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }
}
