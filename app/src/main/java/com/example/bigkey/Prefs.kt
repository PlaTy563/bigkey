package com.example.bigkey

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val KEY_HEIGHT_DP = "key_height_dp"
    const val SHOW_NUMBER_ROW = "show_number_row"
    const val HIT_CORRECTION = "hit_correction"
    const val HAPTIC = "haptic"
    const val AUTO_CONVERT = "auto_convert"
    const val SHOW_BITWARDEN = "show_bitwarden"
    const val CLIP_RECORD = "clip_record"
    const val TAP_LEARN = "tap_learn"
    const val TAP_DX = "tap_dx"
    const val TAP_DY = "tap_dy"
    const val TOOLBAR_ORDER = "toolbar_order"
    const val TOOLBAR_HIDDEN = "toolbar_hidden"

    val TOOLBAR_ALL = listOf("bw", "clip", "emoji", "settings", "mic", "spacer", "select", "left", "right")

    const val DEFAULT_HEIGHT = 64
    const val MIN_HEIGHT = 44
    const val MAX_HEIGHT = 96

    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("bigkey_prefs", Context.MODE_PRIVATE)

    fun keyHeightDp(ctx: Context): Int =
        get(ctx).getInt(KEY_HEIGHT_DP, DEFAULT_HEIGHT).coerceIn(MIN_HEIGHT, MAX_HEIGHT)

    fun showNumberRow(ctx: Context): Boolean = get(ctx).getBoolean(SHOW_NUMBER_ROW, false)

    fun hitCorrection(ctx: Context): Boolean = get(ctx).getBoolean(HIT_CORRECTION, true)

    fun haptic(ctx: Context): Boolean = get(ctx).getBoolean(HAPTIC, true)

    fun autoConvert(ctx: Context): Boolean = get(ctx).getBoolean(AUTO_CONVERT, true)

    fun showBitwarden(ctx: Context): Boolean = get(ctx).getBoolean(SHOW_BITWARDEN, true)

    fun clipRecord(ctx: Context): Boolean = get(ctx).getBoolean(CLIP_RECORD, true)

    /** ツールバーのボタンの並び。保存されていない・足りないものは標準の位置に補う。 */
    fun toolbarOrder(ctx: Context): List<String> {
        val saved = (get(ctx).getString(TOOLBAR_ORDER, "") ?: "").split(",").filter { it in TOOLBAR_ALL }.distinct()
        return saved + TOOLBAR_ALL.filter { it !in saved }
    }

    fun toolbarHidden(ctx: Context): Set<String> {
        val sp = get(ctx)
        // 未設定のときは音声ボタンを隠す（設定ボタンに置き換えたため。設定から戻せる）
        if (!sp.contains(TOOLBAR_HIDDEN)) return setOf("mic")
        return (sp.getString(TOOLBAR_HIDDEN, "") ?: "").split(",").filter { it in TOOLBAR_ALL && it != "spacer" }.toSet()
    }

    fun tapLearn(ctx: Context): Boolean = get(ctx).getBoolean(TAP_LEARN, true)

    fun tapDx(ctx: Context): Float = get(ctx).getFloat(TAP_DX, 0f)

    fun tapDy(ctx: Context): Float = get(ctx).getFloat(TAP_DY, 0f)
}
