package com.maira.assistant

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/** মাঝখানের অবতার: ছবির চারপাশে আলো। কথা বললে লাল, চুপ থাকলে নীল। */
class AvatarView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    var speaking: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** কণ্ঠের জোর (০ থেকে ১) */
    var level: Float = 0f

    var animationsOn: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private val bmp = BitmapFactory.decodeResource(
        resources, R.drawable.avatar,
        BitmapFactory.Options().apply { inScaled = false }
    )
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    private fun withAlpha(color: Int, a: Int): Int =
        (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (width == 0 || height == 0) return
        val t = if (animationsOn) (System.nanoTime() / 1_000_000_000.0).toFloat() else 0f
        val glow = if (speaking) 0xFFFF2A3D.toInt() else 0xFF2A7BFF.toInt()
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) / 2f

        // আলোর আভা (কথার জোরে বাড়ে-কমে)
        val pulse = if (animationsOn) 0.7f + 0.3f * sin(t * (if (speaking) 6f else 1.6f)) else 0.8f
        val boost = if (speaking) level * 0.5f else 0f
        val r = base * (1.15f + boost)
        p.reset()
        p.isAntiAlias = true
        p.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(withAlpha(glow, (170 * (pulse + boost)).toInt()), withAlpha(glow, 0)),
            null, Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, p)

        // ঘোরানো আলোর রিং
        p.reset()
        p.isAntiAlias = true
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = withAlpha(glow, 200)
        rect.set(cx - base * 0.95f, cy - base * 0.95f, cx + base * 0.95f, cy + base * 0.95f)
        c.drawArc(rect, (t * 40f) % 360f, 90f, false, p)
        c.drawArc(rect, (t * 40f + 180f) % 360f, 90f, false, p)

        // অবতারের ছবি, সামান্য ভেসে থাকে
        val bob = if (animationsOn) sin(t * 1.4f) * 6f else 0f
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val scale = min(width / bw, height / bh) * 0.96f * (1f + boost * 0.03f)
        val dw = bw * scale
        val dh = bh * scale
        p.reset()
        p.isAntiAlias = true
        p.isFilterBitmap = true
        rect.set(cx - dw / 2f, cy - dh / 2f + bob, cx + dw / 2f, cy + dh / 2f + bob)
        c.drawBitmap(bmp, null, rect, p)

        if (animationsOn) postInvalidateOnAnimation()
    }
}

