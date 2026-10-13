package com.example.bigkey

import android.content.Context
import android.content.res.Configuration

/** キーボードの配色。端末の暗い表示／明るい表示に合わせる。 */
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
)

object Theme {
    private val DARK = Palette(
        bg = 0xFF17181C.toInt(), key = 0xFF383B42.toInt(), special = 0xFF26282D.toInt(),
        pressed = 0xFF565B66.toInt(), accent = 0xFFA8C7FA.toInt(), accentText = 0xFF062E6F.toInt(),
        text = 0xFFE3E5EA.toInt(), hint = 0xFF8A8F9B.toInt(),
        ja = 0xFF2B4170.toInt(), jaText = 0xFFD6E3FF.toInt(),
        en = 0xFF5A3A1E.toInt(), enText = 0xFFFFDCC2.toInt(),
        bar = 0xFF17181C.toInt(), divider = 0xFF33363D.toInt(),
    )
    private val LIGHT = Palette(
        bg = 0xFFEEF0F5.toInt(), key = 0xFFFFFFFF.toInt(), special = 0xFFD9DDE6.toInt(),
        pressed = 0xFFC3CAD8.toInt(), accent = 0xFFD3E3FD.toInt(), accentText = 0xFF041E49.toInt(),
        text = 0xFF1B1D22.toInt(), hint = 0xFF70757F.toInt(),
        ja = 0xFFD3E3FD.toInt(), jaText = 0xFF041E49.toInt(),
        en = 0xFFFFDCC2.toInt(), enText = 0xFF3A1D00.toInt(),
        bar = 0xFFEEF0F5.toInt(), divider = 0xFFD5D9E2.toInt(),
    )

    fun of(ctx: Context): Palette {
        val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
    }
}
