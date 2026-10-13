package com.example.bigkey

import android.content.Context
import org.json.JSONObject

/** 設定と学習履歴を1つのJSONに書き出す／復元する。 */
object Backup {
    private const val APP_ID = "bigkey"
    private const val VERSION = 1

    fun export(ctx: Context): String {
        val settings = JSONObject()
            .put(Prefs.KEY_HEIGHT_DP, Prefs.keyHeightDp(ctx))
            .put(Prefs.SHOW_NUMBER_ROW, Prefs.showNumberRow(ctx))
            .put(Prefs.HIT_CORRECTION, Prefs.hitCorrection(ctx))
            .put(Prefs.HAPTIC, Prefs.haptic(ctx))
        return JSONObject()
            .put("app", APP_ID)
            .put("version", VERSION)
            .put("settings", settings)
            .put("learn", LearnStore.instance(ctx).toJson())
            .toString(2)
    }

    fun restore(ctx: Context, text: String): Result<Unit> = runCatching {
        val root = JSONObject(text)
        require(root.optString("app") == APP_ID) { "このアプリのバックアップではありません" }

        val s = root.optJSONObject("settings")
        if (s != null) {
            val e = Prefs.get(ctx).edit()
            if (s.has(Prefs.KEY_HEIGHT_DP)) {
                e.putInt(
                    Prefs.KEY_HEIGHT_DP,
                    s.optInt(Prefs.KEY_HEIGHT_DP, Prefs.DEFAULT_HEIGHT)
                        .coerceIn(Prefs.MIN_HEIGHT, Prefs.MAX_HEIGHT)
                )
            }
            if (s.has(Prefs.SHOW_NUMBER_ROW)) e.putBoolean(Prefs.SHOW_NUMBER_ROW, s.optBoolean(Prefs.SHOW_NUMBER_ROW))
            if (s.has(Prefs.HIT_CORRECTION)) e.putBoolean(Prefs.HIT_CORRECTION, s.optBoolean(Prefs.HIT_CORRECTION, true))
            if (s.has(Prefs.HAPTIC)) e.putBoolean(Prefs.HAPTIC, s.optBoolean(Prefs.HAPTIC, true))
            e.apply()
        }

        val l = root.optJSONObject("learn")
        if (l != null) LearnStore.instance(ctx).replaceFrom(l)
    }
}
