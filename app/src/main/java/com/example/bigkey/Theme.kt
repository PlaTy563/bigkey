package com.example.bigkey

import android.content.Context
import android.content.res.Configuration

/** キーボードの配色。端末の暗い表示／明るい表示に合わせる。 */
class Palette(
    val bg: Int,
    val key: Int,
    val keyEdge: Int,
    val special: Int,
    val pressed: Int,
    val accent: Int,
    val text: Int,
    val hint: Int,
    val ja: Int,
    val en: Int,
    val bar: Int,
    val chip: Int,
)

object Theme {
    private val DARK = Palette(
        bg = 0xFF121316.toInt(), key = 0xFF3A3D44.toInt(), keyEdge = 0xFF0A0B0D.toInt(),
        special = 0xFF272A30.toInt(), pressed = 0xFF5B7FD6.toInt(), accent = 0xFF4A7BE0.toInt(),
        text = 0xFFF2F3F5.toInt(), hint = 0xFF9097A5.toInt(), ja = 0xFF2F5FB8.toInt(),
        en = 0xFFC4701F.toInt(), bar = 0xFF1B1D21.toInt(), chip = 0xFF2C2F36.toInt(),
    )
    private val LIGHT = Palette(
        bg = 0xFFD9DCE2.toInt(), key = 0xFFFFFFFF.toInt(), keyEdge = 0xFFA3A9B4.toInt(),
        special = 0xFFBBC1CC.toInt(), pressed = 0xFFA9C0F0.toInt(), accent = 0xFF2F6BDB.toInt(),
        text = 0xFF1A1C20.toInt(), hint = 0xFF6B7280.toInt(), ja = 0xFF2F6BDB.toInt(),
        en = 0xFFC4701F.toInt(), bar = 0xFFECEEF2.toInt(), chip = 0xFFFFFFFF.toInt(),
    )

    fun of(ctx: Context): Palette {
        val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
    }
}
