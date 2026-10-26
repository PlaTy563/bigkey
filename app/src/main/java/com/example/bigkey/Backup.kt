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
            .put(Prefs.AUTO_CONVERT, Prefs.autoConvert(ctx))
            .put(Prefs.SHOW_BITWARDEN, Prefs.showBitwarden(ctx))
            .put(Prefs.CLIP_RECORD, Prefs.clipRecord(ctx))
            .put(Prefs.TAP_LEARN, Prefs.tapLearn(ctx))
            .put(Prefs.TAP_DX, Prefs.tapDx(ctx).toDouble())
            .put(Prefs.TAP_DY, Prefs.tapDy(ctx).toDouble())
            .put(Prefs.TOOLBAR_ORDER, Prefs.toolbarOrder(ctx).joinToString(","))
            .put(Prefs.TOOLBAR_HIDDEN, Prefs.toolbarHidden(ctx).joinToString(","))
        return JSONObject()
            .put("app", APP_ID)
            .put("version", VERSION)
            .put("settings", settings)
            .put("learn", LearnStore.instance(ctx).toJson())
            .put("pinned", ClipStore.instance(ctx).toJson())
            .put("hidden", HiddenStore.instance(ctx).toJson())
            .put("userdict", UserDict.instance(ctx).toJson())
            .put("scenes", SceneStore.instance(ctx).toJson())
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
            if (s.has(Prefs.AUTO_CONVERT)) e.putBoolean(Prefs.AUTO_CONVERT, s.optBoolean(Prefs.AUTO_CONVERT, true))
            if (s.has(Prefs.SHOW_BITWARDEN)) e.putBoolean(Prefs.SHOW_BITWARDEN, s.optBoolean(Prefs.SHOW_BITWARDEN, true))
            if (s.has(Prefs.CLIP_RECORD)) e.putBoolean(Prefs.CLIP_RECORD, s.optBoolean(Prefs.CLIP_RECORD, true))
            if (s.has(Prefs.TAP_LEARN)) e.putBoolean(Prefs.TAP_LEARN, s.optBoolean(Prefs.TAP_LEARN, true))
            if (s.has(Prefs.TAP_DX)) e.putFloat(Prefs.TAP_DX, s.optDouble(Prefs.TAP_DX, 0.0).toFloat())
            if (s.has(Prefs.TAP_DY)) e.putFloat(Prefs.TAP_DY, s.optDouble(Prefs.TAP_DY, 0.0).toFloat())
            if (s.has(Prefs.TOOLBAR_ORDER)) e.putString(Prefs.TOOLBAR_ORDER, s.optString(Prefs.TOOLBAR_ORDER))
            if (s.has(Prefs.TOOLBAR_HIDDEN)) e.putString(Prefs.TOOLBAR_HIDDEN, s.optString(Prefs.TOOLBAR_HIDDEN))
            e.apply()
        }

        val l = root.optJSONObject("learn")
        if (l != null) LearnStore.instance(ctx).replaceFrom(l)
        root.optJSONObject("userdict")?.let { UserDict.instance(ctx).replaceFrom(it) }
        root.optJSONArray("hidden")?.let { HiddenStore.instance(ctx).replaceFrom(it) }
        root.optJSONArray("scenes")?.let { SceneStore.instance(ctx).replaceFrom(it) }
        root.optJSONArray("pinned")?.let { ClipStore.instance(ctx).replacePinned(it) }
    }
}
