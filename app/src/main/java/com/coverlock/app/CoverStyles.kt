package com.coverlock.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Draws the lock screen wallpaper: the album cover fills the whole screen,
 * with soft dark fades at the top and bottom so the clock, notifications and player stay readable.
 */
object CoverStyles {

    /**
     * @param focusPct which part of the cover stays in view when it has to be cropped
     *                 to the screen's shape (0 = left edge, 50 = middle, 100 = right edge)
     */
    fun render(art: Bitmap, w: Int, h: Int, focusPct: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)

        // Huge HD covers are shrunk in half-steps first, so drawing them smaller stays crisp, not shimmery.
        val src = shrink(art, max(w, h))
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val aw = src.width * scale
        val ah = src.height * scale
        val dx = (w - aw) * (focusPct / 100f)
        val dy = (h - ah) / 2f
        c.drawBitmap(
            src, null, RectF(dx, dy, dx + aw, dy + ah),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )

        legibility(c, w.toFloat(), h.toFloat())
        grain(c, 40)
        return out
    }

    /** Dark fade at the top and bottom. */
    private fun legibility(c: Canvas, w: Float, h: Float) {
        val v = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(Color.argb(130, 0, 0, 0), 0x00000000, 0x00000000, Color.argb(200, 0, 0, 0)),
                floatArrayOf(0f, 0.28f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w, h, v)
    }

    /** Halves the bitmap repeatedly while it is still at least twice [target] (clean downscaling). */
    private fun shrink(bm: Bitmap, target: Int): Bitmap {
        var cur = bm
        while (min(cur.width, cur.height) / 2 >= target) {
            cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        }
        return cur
    }

    // very light film grain, hides banding in dark gradients
    private val noiseBitmap: Bitmap by lazy {
        val n = 128
        val px = IntArray(n * n)
        val rnd = Random(11)
        for (i in px.indices) {
            val v = rnd.nextInt(256)
            px[i] = Color.argb(rnd.nextInt(256), v, v, v)
        }
        val bm = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        bm.setPixels(px, 0, n, 0, 0, n, n)
        bm
    }

    private fun grain(c: Canvas, amount: Int) {
        val p = Paint().apply {
            shader = BitmapShader(noiseBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            alpha = amount
        }
        c.drawRect(0f, 0f, c.width.toFloat(), c.height.toFloat(), p)
    }
}
