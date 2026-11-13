package com.coverlock.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Full-screen klein-blue background with halftone clouds and a few chrome sparkles. Drawn once, then cached. */
class BackdropView(context: Context) : View(context) {
    private var cache: Bitmap? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cache = if (w > 0 && h > 0) build(w, h) else null
    }

    override fun onDraw(canvas: Canvas) {
        cache?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    private fun build(w: Int, h: Int): Bitmap {
        val d = resources.displayMetrics.density
        val wf = w.toFloat()
        val hf = h.toFloat()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, hf,
                intArrayOf(Ui.BLUE, Ui.BLUE, Ui.DEEP),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, wf, hf, bg)

        // soft light from the top right
        val glow = Paint().apply {
            shader = RadialGradient(
                wf * 0.9f, hf * 0.04f, wf * 0.9f,
                intArrayOf(Ui.white(70), Ui.white(0)),
                null,
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, wf, hf, glow)

        // halftone clouds: dot size follows a soft field that fades toward the bottom
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.white(95) }
        val step = 10f * d
        var row = 0
        var y = step / 2f
        while (y < hf) {
            var x = if (row % 2 == 0) step / 2f else step
            while (x < wf) {
                val r = step * 0.52f * cloud(x / wf, y / hf)
                if (r > 0.7f) c.drawCircle(x, y, r, dot)
                x += step
            }
            y += step * 0.87f
            row++
        }

        // chrome sparkles near the edges
        val spots = listOf(
            floatArrayOf(0.95f, 0.30f, 13f),
            floatArrayOf(0.05f, 0.56f, 9f),
            floatArrayOf(0.93f, 0.84f, 16f)
        )
        for (s in spots) {
            val cx = wf * s[0]
            val cy = hf * s[1]
            val r = s[2] * d
            c.drawPath(Ui.sparkle(cx, cy, r), Ui.chrome(cx, cy, r))
        }
        return bmp
    }

    private fun cloud(u: Float, v: Float): Float {
        val fall = (1f - v * 1.5f).coerceIn(0f, 1f)
        if (fall <= 0f) return 0f
        val n = 0.5f + 0.28f * sin(u * 9f + v * 5f) + 0.22f * sin(u * 19f - v * 11f + 1.7f)
        return (fall * n * 1.1f).coerceIn(0f, 0.9f)   // capped so neighbouring dots never merge
    }
}

/** A small chrome sparkle, used in headers. */
class SparkleView(context: Context) : View(context) {
    override fun onDraw(canvas: Canvas) {
        val r = min(width, height) / 2f
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawPath(Ui.sparkle(cx, cy, r), Ui.chrome(cx, cy, r))
    }
}

/**
 * Big illustration at the top of each setup page: an outlined step number next to a glass tile
 * with a simple glyph, an orbit ring and a little sparkle flying around it.
 * kind: 1 = listen, 2 = battery / power, 3 = wallpaper, 4 = done.
 */
class HeroView(context: Context, private val kind: Int) : View(context) {
    private val d = resources.displayMetrics.density
    private var phase = 0f
    private var animator: ValueAnimator? = null

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.4f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val numLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.HEAVY
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 1.6f * d
    }
    private val numFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Ui.HEAVY
        color = Ui.white(28)
    }
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.white(34) }
    private val glassEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Ui.white(140)
        strokeWidth = 1.5f * d
    }
    private val orbitLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Ui.white(150)
        strokeWidth = 1.3f * d
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 7000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // outlined step number
        val label = when (kind) {
            1 -> "01"
            2 -> "02"
            3 -> "03"
            else -> "GO"
        }
        numLine.textSize = h * 0.56f
        numFill.textSize = h * 0.56f
        val base = h * 0.80f
        c.drawText(label, 0f, base, numFill)
        c.drawText(label, 0f, base, numLine)

        // glass tile
        val tile = RectF(w * 0.54f, h * 0.08f, w, h * 0.92f)
        val corner = 28f * d
        c.drawRoundRect(tile, corner, corner, glass)
        c.drawRoundRect(tile, corner, corner, glassEdge)

        val cx = tile.centerX()
        val cy = tile.centerY()
        val g = min(tile.height() * 0.24f, tile.width() * 0.2f)

        // orbit ring
        val orbit = RectF(cx - g * 1.9f, cy - g * 0.75f, cx + g * 1.9f, cy + g * 0.75f)
        c.save()
        c.rotate(-20f, cx, cy)
        c.drawOval(orbit, orbitLine)
        c.restore()

        // glyph
        when (kind) {
            1 -> equalizer(c, cx, cy, g)
            2 -> power(c, cx, cy, g)
            3 -> picture(c, cx, cy, g)
            else -> c.drawPath(Ui.sparkle(cx, cy, g * 1.15f), Ui.chrome(cx, cy, g * 1.15f))
        }

        // little sparkle flying along the orbit
        val th = phase * 2f * PI.toFloat()
        val ox = (orbit.width() / 2f) * cos(th)
        val oy = (orbit.height() / 2f) * sin(th)
        val phi = (-20.0 * PI / 180.0).toFloat()
        val sx = cx + ox * cos(phi) - oy * sin(phi)
        val sy = cy + ox * sin(phi) + oy * cos(phi)
        val sr = g * 0.3f
        c.drawPath(Ui.sparkle(sx, sy, sr), Ui.chrome(sx, sy, sr))
    }

    private fun equalizer(c: Canvas, cx: Float, cy: Float, g: Float) {
        val bw = g * 0.26f
        val gap = g * 0.2f
        val total = 5 * bw + 4 * gap
        var x = cx - total / 2f
        val hs = floatArrayOf(0.7f, 1.2f, 0.9f, 1.5f, 0.6f)
        for (i in 0 until 5) {
            val k = 0.65f + 0.35f * sin(phase * 2f * PI.toFloat() * 2f + i * 1.3f)
            val bh = g * hs[i] * k
            c.drawRoundRect(RectF(x, cy - bh, x + bw, cy + bh), bw / 2f, bw / 2f, fill)
            x += bw + gap
        }
    }

    private fun power(c: Canvas, cx: Float, cy: Float, g: Float) {
        c.drawArc(RectF(cx - g, cy - g, cx + g, cy + g), -60f, 300f, false, stroke)
        c.drawLine(cx, cy - g * 1.15f, cx, cy - g * 0.1f, stroke)
    }

    private fun picture(c: Canvas, cx: Float, cy: Float, g: Float) {
        c.drawRoundRect(RectF(cx - g * 1.2f, cy - g * 0.9f, cx + g * 1.2f, cy + g * 0.9f), g * 0.25f, g * 0.25f, stroke)
        c.drawCircle(cx + g * 0.5f, cy - g * 0.3f, g * 0.2f, fill)
        val p = android.graphics.Path()
        p.moveTo(cx - g * 1.1f, cy + g * 0.7f)
        p.lineTo(cx - g * 0.3f, cy - g * 0.1f)
        p.lineTo(cx + g * 0.2f, cy + g * 0.4f)
        p.lineTo(cx + g * 0.6f, cy + g * 0.1f)
        p.lineTo(cx + g * 1.1f, cy + g * 0.7f)
        c.drawPath(p, stroke)
    }
}
