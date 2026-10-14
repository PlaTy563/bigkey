package com.example.bigkey

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * クリップボードの履歴。
 * - 履歴: 端末のメモリ上だけに持つ（キーボードが終了すると消える）。パスワードなどが残り続けないようにするため。
 * - 固定: 利用者が固定した文だけをファイルに保存する。バックアップの対象。
 */
class ClipStore private constructor(private val file: File) {
    private val history = ArrayList<String>()
    private val pinned = ArrayList<String>()

    companion object {
        private const val MAX_HISTORY = 30
        private const val MAX_LENGTH = 2000

        @Volatile
        private var inst: ClipStore? = null

        fun instance(ctx: Context): ClipStore =
            inst ?: synchronized(this) {
                inst ?: ClipStore(File(ctx.applicationContext.filesDir, "clip_pinned.json")).also { inst = it }
            }
    }

    init {
        load()
    }

    @Synchronized
    fun add(text: String) {
        if (text.isBlank() || text.length > MAX_LENGTH) return
        history.remove(text)
        history.add(0, text)
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
    }

    @Synchronized
    fun pinnedList(): List<String> = ArrayList(pinned)

    /** 固定していない履歴だけ（新しい順）。 */
    @Synchronized
    fun historyList(): List<String> = history.filter { it !in pinned }

    @Synchronized
    fun isPinned(text: String): Boolean = text in pinned

    @Synchronized
    fun togglePin(text: String) {
        if (!pinned.remove(text)) pinned.add(0, text)
        save()
    }

    @Synchronized
    fun addPinned(text: String) {
        if (text.isBlank() || text.length > MAX_LENGTH) return
        pinned.remove(text)
        pinned.add(0, text)
        save()
    }

    @Synchronized
    fun removePinned(text: String) {
        if (pinned.remove(text)) save()
    }

    @Synchronized
    fun clearHistory() {
        history.clear()
    }

    @Synchronized
    fun toJson(): JSONArray = JSONArray().also { a -> pinned.forEach { a.put(it) } }

    @Synchronized
    fun replacePinned(a: JSONArray) {
        pinned.clear()
        for (i in 0 until a.length()) {
            val s = a.optString(i, "")
            if (s.isNotBlank() && s.length <= MAX_LENGTH) pinned.add(s)
        }
        save()
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val a = JSONArray(file.readText(Charsets.UTF_8))
            for (i in 0 until a.length()) {
                val s = a.optString(i, "")
                if (s.isNotBlank()) pinned.add(s)
            }
        } catch (_: Exception) {
        }
    }

    private fun save() {
        try {
            file.writeText(toJson().toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }
}
