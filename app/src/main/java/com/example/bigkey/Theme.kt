package com.example.bigkey

import android.content.Context
import android.content.res.Configuration

/** キーボードの配色。テーマ設定（自動／ダーク／ライト／ブループリント）に従う。 */
class Palette(
    val bg: Int,
    val key: Int,
    val special: Int,
    val pressed: Int,
    val accent: Int,
    val accentText: Int,
    val text: Int,
    val hint: Int,
    val ja: Int,
    val jaText: Int,
    val en: Int,
    val enText: Int,
    val bar: Int,
    val divider: Int,
    /** キーの輪郭線の色。0 なら輪郭線を描かない。 */
    val stroke: Int = 0,
    /** 輪郭線の太さ（dp）。 */
    val strokeWidthDp: Float = 0f,
)

object Theme {
    // キーと背景のコントラストを抑え、境界が目立たないフラットな配色にしている
    private val DARK = Palette(
        bg = 0xFF17181C.toInt(), key = 0xFF2B2E35.toInt(), special = 0xFF22242A.toInt(),
        pressed = 0xFF484E5A.toInt(), accent = 0xFFA8C7FA.toInt(), accentText = 0xFF062E6F.toInt(),
        text = 0xFFE3E5EA.toInt(), hint = 0xFF8A8F9B.toInt(),
        ja = 0xFF2B4170.toInt(), jaText = 0xFFD6E3FF.toInt(),
        en = 0xFF5A3A1E.toInt(), enText = 0xFFFFDCC2.toInt(),
        bar = 0xFF17181C.toInt(), divider = 0xFF2E3138.toInt(),
    )
    private val LIGHT = Palette(
        bg = 0xFFEEF0F5.toInt(), key = 0xFFF8F9FB.toInt(), special = 0xFFE3E6ED.toInt(),
        pressed = 0xFFC3CAD8.toInt(), accent = 0xFFD3E3FD.toInt(), accentText = 0xFF041E49.toInt(),
        text = 0xFF1B1D22.toInt(), hint = 0xFF70757F.toInt(),
        ja = 0xFFD3E3FD.toInt(), jaText = 0xFF041E49.toInt(),
        en = 0xFFFFDCC2.toInt(), enText = 0xFF3A1D00.toInt(),
        bar = 0xFFEEF0F5.toInt(), divider = 0xFFDDE0E8.toInt(),
    )

    /** ブループリント（青図）風: 濃紺の地に、薄い水色の細い輪郭線でキーを描く。 */
    private val BLUEPRINT = Palette(
        bg = 0xFF0A2A52.toInt(), key = 0xFF10355F.toInt(), special = 0xFF0C2C50.toInt(),
        pressed = 0xFF1D4E80.toInt(), accent = 0xFF8FD0FF.toInt(), accentText = 0xFF06263F.toInt(),
        text = 0xFFEAF4FF.toInt(), hint = 0xFF8FB4DC.toInt(),
        ja = 0xFF1B5E8F.toInt(), jaText = 0xFFEAF4FF.toInt(),
        en = 0xFF146B6B.toInt(), enText = 0xFFD9FFFB.toInt(),
        bar = 0xFF0A2A52.toInt(), divider = 0xFF2A5A8F.toInt(),
        stroke = 0xFF7FB2E8.toInt(), strokeWidthDp = 1f,
    )

    fun of(ctx: Context): Palette {
        return when (Prefs.themeMode(ctx)) {
            Prefs.THEME_DARK -> DARK
            Prefs.THEME_LIGHT -> LIGHT
            Prefs.THEME_BLUEPRINT -> BLUEPRINT
            else -> {
                val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (night == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
            }
        }
    }
}
