package com.example.bigkey

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 場面ごとの定型文。
 * 場面 = 名前 + 対象アプリ（任意）+ 画面上部の文字（任意）+ 定型文の一覧。
 * 例: 名前「田中さん」、アプリ LINE、画面上部の文字「田中」 → LINEで田中さんのトークを開いているときだけ使う。
 */
class Scene(
    val id: String,
    var name: String,
    var pkg: String,
    var keyword: String,
    val snippets: MutableList<String>
)

class SceneStore private constructor(private val file: File) {
    private val scenes = ArrayList<Scene>()

    companion object {
        private const val MAX_LENGTH = 2000

        @Volatile
        private var inst: SceneStore? = null

        fun instance(ctx: Context): SceneStore =
            inst ?: synchronized(this) {
                inst ?: SceneStore(File(ctx.applicationContext.filesDir, "scenes.json")).also { inst = it }
            }
    }

    init {
        load()
    }

    @Synchronized
    fun all(): List<Scene> = scenes.map { Scene(it.id, it.name, it.pkg, it.keyword, ArrayList(it.snippets)) }

    @Synchronized
    fun get(id: String): Scene? = scenes.firstOrNull { it.id == id }?.let {
        Scene(it.id, it.name, it.pkg, it.keyword, ArrayList(it.snippets))
    }

    @Synchronized
    fun add(name: String, pkg: String, keyword: String): String {
        val s = Scene(UUID.randomUUID().toString(), name.trim(), pkg.trim(), keyword.trim(), ArrayList())
        scenes.add(s)
        save()
        return s.id
    }

    @Synchronized
    fun update(id: String, name: String, pkg: String, keyword: String) {
        val s = scenes.firstOrNull { it.id == id } ?: return
        s.name = name.trim()
        s.pkg = pkg.trim()
        s.keyword = keyword.trim()
        save()
    }

    @Synchronized
    fun remove(id: String) {
        if (scenes.removeAll { it.id == id }) save()
    }

    @Synchronized
    fun addSnippet(id: String, text: String) {
        if (text.isBlank() || text.length > MAX_LENGTH) return
        val s = scenes.firstOrNull { it.id == id } ?: return
        s.snippets.remove(text)
        s.snippets.add(0, text)
        save()
    }

    @Synchronized
    fun removeSnippet(id: String, text: String) {
        val s = scenes.firstOrNull { it.id == id } ?: return
        if (s.snippets.remove(text)) save()
    }

    /** どの場面のキーワードを画面から探す必要があるか（対象アプリの一覧）。 */
    @Synchronized
    fun watchedPackages(): Set<String> =
        scenes.filter { it.pkg.isNotEmpty() && it.keyword.isNotEmpty() }.map { it.pkg }.toSet()

    /**
     * 今の入力先に合う場面を返す。
     * - 対象アプリが違う場面は除く（対象アプリが空なら、どのアプリでも対象）。
     * - キーワードがある場面は、画面上部の文字に含まれるときだけ合う。
     * - 合う場面が複数あれば、キーワードがある場面（絞り込みが強い方）を優先する。
     */
    @Synchronized
    fun match(pkg: String?, titles: List<String>): Scene? {
        var best: Scene? = null
        var bestScore = -1
        for (s in scenes) {
            if (s.pkg.isNotEmpty() && s.pkg != pkg) continue
            var score = if (s.pkg.isNotEmpty()) 1 else 0
            if (s.keyword.isNotEmpty()) {
                if (titles.none { it.contains(s.keyword, ignoreCase = true) }) continue
                score += 2
            } else if (s.pkg.isEmpty()) {
                continue // アプリもキーワードも空の場面は自動では選ばない（手動でだけ使う）
            }
            if (score > bestScore) {
                best = s
                bestScore = score
            }
        }
        return best?.let { Scene(it.id, it.name, it.pkg, it.keyword, ArrayList(it.snippets)) }
    }

    @Synchronized
    fun toJson(): JSONArray {
        val a = JSONArray()
        for (s in scenes) {
            a.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("pkg", s.pkg)
                    .put("keyword", s.keyword)
                    .put("snippets", JSONArray().also { j -> s.snippets.forEach { j.put(it) } })
            )
        }
        return a
    }

    @Synchronized
    fun replaceFrom(a: JSONArray) {
        scenes.clear()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim()
            if (name.isEmpty()) continue
            val sn = ArrayList<String>()
            o.optJSONArray("snippets")?.let { j ->
                for (k in 0 until j.length()) {
                    val t = j.optString(k, "")
                    if (t.isNotBlank() && t.length <= MAX_LENGTH) sn.add(t)
                }
            }
            scenes.add(
                Scene(
                    o.optString("id", "").ifEmpty { UUID.randomUUID().toString() },
                    name, o.optString("pkg", ""), o.optString("keyword", ""), sn
                )
            )
        }
        save()
    }

    private fun load() {
        try {
            if (!file.exists()) return
            replaceFromNoSave(JSONArray(file.readText(Charsets.UTF_8)))
        } catch (_: Exception) {
        }
    }

    private fun replaceFromNoSave(a: JSONArray) {
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim()
            if (name.isEmpty()) continue
            val sn = ArrayList<String>()
            o.optJSONArray("snippets")?.let { j ->
                for (k in 0 until j.length()) {
                    val t = j.optString(k, "")
                    if (t.isNotBlank()) sn.add(t)
                }
            }
            scenes.add(Scene(o.optString("id", UUID.randomUUID().toString()), name, o.optString("pkg", ""), o.optString("keyword", ""), sn))
        }
    }

    private fun save() {
        try {
            file.writeText(toJson().toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }
}
