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

/**
 * প্রথম সংস্করণের অবতার: ক্যানভাসে আঁকা স্টাইলাইজড (২D) চরিত্র।
 * চোখের পলক, মাথা নাড়া, শ্বাসের দুলুনি এবং কথা বলার সময় মুখ নড়ে।
 * কথা বললে লাল আলো, চুপ থাকলে নীল আলো।
 * (বাস্তবসম্মত 3D মডেল ও সঠিক lip-sync পরের ধাপে।)
 */
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

        // আলোর আভা
        val pulse = if (animationsOn) 0.75f + 0.25f * sin(t * (if (speaking) 6f else 2f)) else 0.85f
        p.reset()
        p.isAntiAlias = true
        p.shader = RadialGradient(
            150f, 270f, 240f,
            intArrayOf(withAlpha(glow, (150 * pulse).toInt()), withAlpha(glow, 0)),
            null, Shader.TileMode.CLAMP
        )
        c.drawCircle(150f, 270f, 240f, p)

        // নিয়ন রিং
        rect.set(18f, 14f, 282f, 506f)
        stroke(withAlpha(glow, 200), 7f)
        p.maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(rect, 130f, 130f, p)
        stroke(glow, 2.5f)
        c.drawRoundRect(rect, 130f, 130f, p)

        // শরীরের দুলুনি
        val sway = if (animationsOn) sin(t * 1.1f) * 3f else 0f
        val breath = if (animationsOn) sin(t * 1.8f) * 1.5f else 0f
        c.save()
        c.translate(sway, breath)

        // পেছনের চুল
        fill(hair)
        rect.set(100f, 40f, 200f, 190f)
        c.drawOval(rect, p)
        rect.set(96f, 100f, 204f, 275f)
        c.drawRoundRect(rect, 42f, 42f, p)

        // পা ও জুতো
        fill(skin)
        rect.set(120f, 370f, 144f, 490f)
        c.drawRoundRect(rect, 10f, 10f, p)
        rect.set(156f, 370f, 180f, 490f)
        c.drawRoundRect(rect, 10f, 10f, p)
        fill(0xFF1A1030.toInt())
        rect.set(110f, 482f, 148f, 502f)
        c.drawOval(rect, p)
        rect.set(152f, 482f, 190f, 502f)
        c.drawOval(rect, p)

        // পোশাক
        path.reset()
        path.moveTo(112f, 200f)
        path.lineTo(188f, 200f)
        path.quadTo(205f, 290f, 228f, 392f)
        path.quadTo(150f, 414f, 72f, 392f)
        path.quadTo(95f, 290f, 112f, 200f)
        path.close()
        p.reset()
        p.isAntiAlias = true
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            0f, 200f, 0f, 400f,
            0xFF7B3FF2.toInt(), 0xFF2B2FA8.toInt(), Shader.TileMode.CLAMP
        )
        c.drawPath(path, p)

        // হাত
        val lift = if (speaking && animationsOn) 22f + 10f * sin(t * 5f) else 0f
        stroke(skin, 15f)
        c.drawLine(112f, 210f, 92f, 305f, p)
        c.drawLine(188f, 210f, 210f, 305f - lift, p)
        fill(skin)
        c.drawCircle(92f, 310f, 8f, p)
        c.drawCircle(210f, 310f - lift, 8f, p)

        // গলা
        fill(skin)
        rect.set(138f, 165f, 162f, 212f)
        c.drawRoundRect(rect, 8f, 8f, p)

        // মাথা (সামান্য নড়ে)
        c.save()
        if (animationsOn) c.rotate(sin(t * 0.9f) * 2.5f, 150f, 178f)

        fill(skin)
        rect.set(108f, 55f, 192f, 178f)
        c.drawOval(rect, p)

        fill(hair)
        rect.set(103f, 44f, 197f, 142f)
        c.drawArc(rect, 180f, 180f, true, p)

        // গালে লালচে আভা
        fill(0x33FF6A7A)
        c.drawCircle(122f, 140f, 9f, p)
        c.drawCircle(178f, 140f, 9f, p)

        // চোখের পলক
        val phase = if (animationsOn) t % 4.2f else 0f
        val eyeOpen = if (phase > 4.0f) max(0.1f, abs(phase - 4.1f) / 0.1f) else 1f
        for (cx in floatArrayOf(132f, 168f)) {
            fill(0xFFFFFFFF.toInt())
            rect.set(cx - 9f, 115f - 6f * eyeOpen, cx + 9f, 115f + 6f * eyeOpen)
            c.drawOval(rect, p)
            fill(0xFF3A2418.toInt())
            rect.set(cx - 4.5f, 115f - 4.5f * eyeOpen, cx + 4.5f, 115f + 4.5f * eyeOpen)
            c.drawOval(rect, p)
            stroke(hair, 2.5f)
            c.drawLine(cx - 10f, 115f - 6f * eyeOpen, cx + 10f, 115f - 6f * eyeOpen, p)
        }

        // ভুরু ও নাক
        stroke(hair, 3f)
        c.drawLine(121f, 101f, 142f, 97f, p)
        c.drawLine(158f, 97f, 179f, 101f, p)
        stroke(0xFFD9A07F.toInt(), 2f)
        c.drawLine(150f, 122f, 147f, 136f, p)

        // মুখ
        val open = if (speaking && animationsOn) {
            abs(sin(t * 13f)) * 0.6f + abs(sin(t * 7.3f)) * 0.4f
        } else 0f
        if (open < 0.08f) {
            stroke(0xFFB02A44.toInt(), 3f)
            rect.set(138f, 140f, 162f, 156f)
            c.drawArc(rect, 20f, 140f, false, p)
        } else {
            fill(0xFFB02A44.toInt())
            rect.set(139f, 148f, 161f, 151f + open * 14f)
            c.drawOval(rect, p)
        }
        c.restore() // মাথা

        c.restore() // দুলুনি
        c.restore() // স্কেল

        if (animationsOn) postInvalidateOnAnimation()
    }
}
