package com.example.bigkey

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 利用者が登録した単語（読み → 表記）。端末内のファイルにだけ保存されるため、バックアップの対象にする。 */
class UserDict private constructor(private val file: File) {
    private val map = LinkedHashMap<String, MutableList<String>>()

    /** 内容が変わるたびに増える。変換側が、辞書を読み直す必要があるかの判定に使う。 */
    @Volatile
    var version = 0
        private set

    companion object {
        @Volatile
        private var inst: UserDict? = null

        fun instance(ctx: Context): UserDict =
            inst ?: synchronized(this) {
                inst ?: UserDict(File(ctx.applicationContext.filesDir, "userdict.json")).also { inst = it }
            }

        /** 読みに使える文字（ひらがな・長音）だけにそろえる。カタカナはひらがなに直す。 */
        fun normalizeReading(s: String): String {
            val sb = StringBuilder()
            for (ch in s.trim()) {
                when (ch) {
                    in 'ァ'..'ヶ' -> sb.append((ch.code - 0x60).toChar())
                    in 'ぁ'..'ゖ', 'ー' -> sb.append(ch)
                    else -> return ""
                }
            }
            return sb.toString()
        }
    }

    init {
        try {
            if (file.exists()) loadJson(JSONObject(file.readText(Charsets.UTF_8)))
        } catch (_: Exception) {
        }
    }

    private fun loadJson(o: JSONObject) {
        map.clear()
        val it = o.keys()
        while (it.hasNext()) {
            val r = it.next()
            val a = o.optJSONArray(r) ?: continue
            val list = ArrayList<String>()
            for (i in 0 until a.length()) a.optString(i, "").takeIf { s -> s.isNotEmpty() }?.let { s -> list.add(s) }
            if (list.isNotEmpty()) map[r] = list
        }
    }

    /** 追加する。読みか表記が不正なら false。 */
    @Synchronized
    fun add(reading: String, surface: String): Boolean {
        val r = normalizeReading(reading)
        val s = surface.trim()
        if (r.isEmpty() || s.isEmpty() || r.length > 30 || s.length > 60) return false
        val list = map.getOrPut(r) { ArrayList() }
        if (s !in list) list.add(0, s)
        bump()
        return true
    }

    /** まとめて追加する（読み込み用）。追加できた件数を返す。 */
    @Synchronized
    fun addAll(pairs: List<Pair<String, String>>): Int {
        var n = 0
        for ((r0, s0) in pairs) {
            val r = normalizeReading(r0)
            val s = s0.trim()
            if (r.isEmpty() || s.isEmpty() || r.length > 30 || s.length > 60) continue
            val list = map.getOrPut(r) { ArrayList() }
            if (s !in list) {
                list.add(s)
                n++
            }
        }
        bump()
        return n
    }

    @Synchronized
    fun remove(reading: String, surface: String) {
        val list = map[reading] ?: return
        list.remove(surface)
        if (list.isEmpty()) map.remove(reading)
        bump()
    }

    @Synchronized
    fun all(): List<Pair<String, String>> = map.flatMap { (r, l) -> l.map { r to it } }

    @Synchronized
    fun snapshot(): Map<String, List<String>> = map.mapValues { ArrayList(it.value) }

    @Synchronized
    fun toJson(): JSONObject {
        val o = JSONObject()
        for ((r, l) in map) o.put(r, JSONArray().also { a -> l.forEach { a.put(it) } })
        return o
    }

    @Synchronized
    fun replaceFrom(o: JSONObject) {
        loadJson(o)
        bump()
    }

    private fun bump() {
        version++
        try {
            file.writeText(toJson().toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }
}
