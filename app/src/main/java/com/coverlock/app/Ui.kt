package com.coverlock.app

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface

/** Colors, fonts and shapes shared by every screen. Look: klein blue, halftone, chrome sparkles, mono labels. */
object Ui {
    val BLUE = Color.rgb(21, 32, 238)
    val DEEP = Color.rgb(6, 10, 120)
    val INK = Color.rgb(9, 9, 20)
    val PAPER = Color.rgb(236, 236, 242)
    val ICE = Color.rgb(205, 213, 255)

    val HEAVY: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    val LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val MONO: Typeface = Typeface.create("monospace", Typeface.BOLD)

    fun white(alpha: Int): Int = Color.argb(alpha, 255, 255, 255)
    fun black(alpha: Int): Int = Color.argb(alpha, 0, 0, 0)

    /** Concave four-point star: the "chrome sparkle". */
    fun sparkle(cx: Float, cy: Float, r: Float): Path {
        val p = Path()
        p.moveTo(cx, cy - r)
        p.cubicTo(cx + r * 0.08f, cy - r * 0.30f, cx + r * 0.30f, cy - r * 0.08f, cx + r, cy)
        p.cubicTo(cx + r * 0.30f, cy + r * 0.08f, cx + r * 0.08f, cy + r * 0.30f, cx, cy + r)
        p.cubicTo(cx - r * 0.08f, cy + r * 0.30f, cx - r * 0.30f, cy + r * 0.08f, cx - r, cy)
        p.cubicTo(cx - r * 0.30f, cy - r * 0.08f, cx - r * 0.08f, cy - r * 0.30f, cx, cy - r)
        p.close()
        return p
    }

    /** Silvery white-to-ice gradient for sparkles. */
    fun chrome(cx: Float, cy: Float, r: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            cx - r, cy - r, cx + r, cy + r,
            intArrayOf(Color.WHITE, ICE, Color.rgb(140, 152, 255), Color.WHITE),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
    }
}
