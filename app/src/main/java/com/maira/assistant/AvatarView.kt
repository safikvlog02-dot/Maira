package com.maira.assistant

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class AvatarView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var speaking: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var animationsOn: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun fill(color: Int) {
        p.reset()
        p.isAntiAlias = true
        p.style = Paint.Style.FILL
        p.color = color
    }

    private fun stroke(color: Int, w: Float) {
        p.reset()
        p.isAntiAlias = true
        p.style = Paint.Style.STROKE
        p.color = color
        p.strokeWidth = w
        p.strokeCap = Paint.Cap.ROUND
    }

    private fun withAlpha(color: Int, a: Int): Int =
        (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val s = min(width / 300f, height / 520f)
        if (s <= 0f) return

        val t = if (animationsOn) (System.nanoTime() / 1_000_000_000.0).toFloat() else 0f
        val glow = if (speaking) 0xFFFF2A3D.toInt() else 0xFF2A7BFF.toInt()
        val skin = 0xFFF1C7A5.toInt()
        val hair = 0xFF1B1020.toInt()

        c.save()
        c.translate((width - 300f * s) / 2f, (height - 520f * s) / 2f)
        c.scale(s, s)

        val pulse = if (animationsOn) 0.75f + 0.25f * sin(t * (if (speaking) 6f else 2f)) else 0.85f
        p.reset()
        p.isAntiAlias = true
        p.shader = RadialGradient(
            150f, 270f, 240f,
            intArrayOf(withAlpha(glow, (150 * pulse).toInt()), withAlpha(glow, 0)),
            null, Shader.TileMode.CLAMP
        )
        c.drawCircle(150f, 270f, 240f, p)

        rect.set(18f, 14f, 282f, 506f)
        stroke(withAlpha(glow, 200), 7f)
        p.maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(rect, 130f, 130f, p)
        stroke(glow, 2.5f)
        c.drawRoundRect(rect, 130f, 130f, p)

        val sway = if (animationsOn) sin(t * 1.1f) * 3f else 0f
        val breath = if (animationsOn) sin(t * 1.8f) * 1.5f else 0f
        c.save()
        c.translate(sway, breath)

        fill(hair)
        rect.s
