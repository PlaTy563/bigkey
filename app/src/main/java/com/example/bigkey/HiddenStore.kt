package com.example.bigkey

import android.content.Context
import org.json.JSONArray
import java.io.File

/** 利用者が「候補に出さない」と決めた (読み, 表記)。端末内のファイルにだけ保存されるため、バックアップの対象にする。 */
class HiddenStore private constructor(private val file: File) {
    private val keys = LinkedHashSet<String>()

    companion object {
        @Volatile
        private var inst: HiddenStore? = null

        fun instance(ctx: Context): HiddenStore =
            inst ?: synchronized(this) {
                inst ?: HiddenStore(File(ctx.applicationContext.filesDir, "hidden.json")).also { inst = it }
            }

        fun key(reading: String, surface: String) = "$reading|$surface"
    }

    init {
        try {
            if (file.exists()) {
                val a = JSONArray(file.readText(Charsets.UTF_8))
                for (i in 0 until a.length()) keys.add(a.getString(i))
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun isHidden(reading: String, surface: String): Boolean = key(reading, surface) in keys

    @Synchronized
    fun hide(reading: String, surface: String) {
        keys.add(key(reading, surface))
        save()
    }

    @Synchronized
    fun clear() {
        keys.clear()
        save()
    }

    @Synchronized
    fun count(): Int = keys.size

    @Synchronized
    fun toJson(): JSONArray = JSONArray().also { a -> keys.forEach { a.put(it) } }

    @Synchronized
    fun replaceFrom(a: JSONArray) {
        keys.clear()
        for (i in 0 until a.length()) a.optString(i, "").takeIf { it.isNotEmpty() }?.let { keys.add(it) }
        save()
    }

    private fun save() {
        try {
            file.writeText(toJson().toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }
}
