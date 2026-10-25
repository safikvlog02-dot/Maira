package com.maira.assistant

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** বড় মাইক্রোফোন বোতাম */
class OrbView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var listening: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val t = (System.nanoTime() / 1_000_000_000.0).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) / 2f
        val pulse = 1f + 0.04f * sin(t * (if (listening) 7f else 2f))
        val col = if (listening) 0xFFFF2A7B.toInt() else 0xFF8A4DFF.toInt()

        p.reset()
        p.isAntiAlias = true
        p.shader = RadialGradient(
            cx, cy, base,
            intArrayOf((col and 0x00FFFFFF) or (140 shl 24), (col and 0x00FFFFFF)),
            null, Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, base * pulse, p)

        p.reset()
        p.isAntiAlias = true
        p.style = Paint.Style.STROKE
        p.strokeWidth = 5f
        p.color = 0xFFB9A4FF.toInt()
        c.drawCircle(cx, cy, base * 0.72f * pulse, p)

        p.reset()
        p.isAntiAlias = true
        p.style = Paint.Style.FILL
        p.color = 0xFF140A3A.toInt()
        c.drawCircle(cx, cy, base * 0.64f, p)

        // মাইক্রোফোন আইকন
        p.color = 0xFFFFFFFF.toInt()
        val mw = base * 0.14f
        val rr = RectF(cx - mw, cy - base * 0.3f, cx + mw, cy + base * 0.08f)
        c.drawRoundRect(rr, mw, mw, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.strokeCap = Paint.Cap.ROUND
        val arc = RectF(cx - base * 0.24f, cy - base * 0.2f, cx + base * 0.24f, cy + base * 0.2f)
        c.drawArc(arc, 20f, 140f, false, p)
        c.drawLine(cx, cy + base * 0.2f, cx, cy + base * 0.3f, p)
        c.drawLine(cx - base * 0.1f, cy + base * 0.3f, cx + base * 0.1f, cy + base * 0.3f, p)

        postInvalidateOnAnimation()
    }
}

/** শব্দতরঙ্গ */
class WaveView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var level: Float = 0f
    var active: Boolean = false

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val t = (System.nanoTime() / 1_000_000_000.0).toFloat()
        val n = 48
        val gap = width / n.toFloat()
        p.reset()
        p.isAntiAlias = true
        p.strokeWidth = gap * 0.45f
        p.strokeCap = Paint.Cap.ROUND
        p.color = if (active) 0xFFFF5A7A.toInt() else 0xFF6A8CFF.toInt()
        val amp = if (active) 0.25f + level * 0.75f else 0.12f
        for (i in 0 until n) {
            val h = (abs(sin(t * 3f + i * 0.5f)) * 0.7f + 0.3f) * amp * height
            val x = gap * i + gap / 2f
            c.drawLine(x, height / 2f - h / 2f, x, height / 2f + h / 2f, p)
        }
        postInvalidateOnAnimation()
    }
}

/** ছোট রেখাচিত্র */
class SparkView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var values: List<Int> = emptyList()
        set(v) {
            field = v
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (values.size < 2) return
        val mx = (values.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        val step = width / (values.size - 1).toFloat()
        path.reset()
        for (i in values.indices) {
            val x = step * i
            val y = height - (values[i] / mx) * (height * 0.85f) - height * 0.05f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        p.reset()
        p.isAntiAlias = true
        p.style = Paint.Style.STROKE
        p.strokeWidth = 5f
        p.color = 0xFF8A6CFF.toInt()
        c.drawPath(path, p)
    }
}
