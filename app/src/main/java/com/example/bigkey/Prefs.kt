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
}
