package com.randaladams.slingslam

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Shaded drawings of everything that moves: boulders, hazards, bonuses. */
class Sprites {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()
    private val tau = (2 * PI).toFloat()

    private fun fill(color: Int) {
        paint.shader = null
        paint.color = color
    }

    // ------------------------------------------------------------ boulder

    fun boulder(c: Canvas, cx: Float, cy: Float, r: Float, spin: Float, alpha: Float) {
        if (alpha <= 0f || r < 0.5f) return
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        paint.color = Color.BLACK // opaque so the shader is not faded
        paint.shader = RadialGradient(
            cx - r * 0.35f, cy - r * 0.4f, r * 1.45f,
            intArrayOf(0xFFB2ACA3.toInt(), 0xFF77716A.toInt(), 0xFF3A3631.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        paint.alpha = a
        // slightly lumpy outline
        path.reset()
        for (k in 0 until 10) {
            val ang = spin + k * tau / 10f
            val rr = r * (0.93f + 0.07f * sin(k * 2.7f + 1f))
            val x = cx + cos(ang) * rr
            val y = cy + sin(ang) * rr
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, paint)
        paint.shader = null
        // a few irregular pits and a hairline crack that turn with it
        paint.color = 0xFF4A4540.toInt()
        val pits = floatArrayOf(0.52f, 0.31f, 0.66f, 0.18f)
        for (k in pits.indices) {
            val ang = spin + k * 1.7f + k * k * 0.4f
            paint.alpha = (a * (0.35f + 0.1f * k)).toInt()
            c.drawCircle(cx + cos(ang) * r * pits[k], cy + sin(ang) * r * pits[k] * 0.9f, r * (0.05f + 0.025f * k), paint)
        }
        stroke.shader = null
        stroke.color = 0xFF2E2A26.toInt()
        stroke.alpha = (a * 0.5f).toInt()
        stroke.strokeWidth = max(1f, r * 0.04f)
        val ca = spin + 0.6f
        path.reset()
        path.moveTo(cx + cos(ca) * r * 0.15f, cy + sin(ca) * r * 0.15f)
        path.lineTo(cx + cos(ca + 0.35f) * r * 0.5f, cy + sin(ca + 0.35f) * r * 0.5f)
        path.lineTo(cx + cos(ca + 0.15f) * r * 0.85f, cy + sin(ca + 0.15f) * r * 0.85f)
        c.drawPath(path, stroke)
        paint.alpha = 255
        stroke.alpha = 255
    }

    // ------------------------------------------------------------ hazards

    /** Draws a hazard standing on (cx, bottom). [u] is pixels per world unit there. */
    fun hazard(c: Canvas, hz: Hazard, cx: Float, bottom: Float, u: Float, side: Boolean, clock: Float) {
        val r = hz.kind.radius * u
        when (hz.kind) {
            Kind.BARREL -> drum(c, hz, cx, bottom, r, side)
            Kind.ROCK -> rock(c, hz, cx, bottom - r * 0.9f, r)
            Kind.PLANT -> bramble(c, hz, cx, bottom, u)
            Kind.ENERGY -> shieldCell(c, hz, cx, bottom, r, clock)
            Kind.UPGRADE -> ammoCrate(c, cx, bottom, r)
            Kind.GOLD -> goldBars(c, hz, cx, bottom, r)
        }
        if (hz.flash > 0f) {
            fill(Color.argb((hz.flash * 160).toInt(), 255, 240, 220))
            c.drawCircle(cx, bottom - r, r * 1.15f, paint)
        }
    }

    /** Olive oil drum. Head-on in the lane (rolling at you), end-on in the side view. */
    private fun drum(c: Canvas, hz: Hazard, cx: Float, bottom: Float, r: Float, side: Boolean) {
        val cy = bottom - r
        if (side) {
            paint.color = Color.BLACK // opaque so the shader is not faded
            paint.shader = RadialGradient(
                cx - r * 0.3f, cy - r * 0.3f, r * 1.3f,
                intArrayOf(0xFF7D8559.toInt(), 0xFF4B5233.toInt(), 0xFF22261A.toInt()),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawCircle(cx, cy, r, paint)
            paint.shader = null
            stroke.color = 0xFF2B2F20.toInt()
            stroke.strokeWidth = r * 0.12f
            c.drawCircle(cx, cy, r * 0.88f, stroke)
            // bung hole turning as it rolls
            val a = -hz.spin
            fill(0xFF1E2116.toInt())
            c.drawCircle(cx + cos(a) * r * 0.55f, cy + sin(a) * r * 0.55f, r * 0.12f, paint)
            fill(0x557A4A2A)
            c.drawCircle(cx + cos(a + 2f) * r * 0.4f, cy + sin(a + 2f) * r * 0.4f, r * 0.25f, paint)
            return
        }
        val hw = r * 1.1f
        rect.set(cx - hw, cy - r, cx + hw, cy + r)
        paint.color = Color.BLACK // opaque so the shader is not faded
        paint.shader = LinearGradient(
            cx - hw, 0f, cx + hw, 0f,
            intArrayOf(0xFF1F2316.toInt(), 0xFF4B5233.toInt(), 0xFF8A9264.toInt(), 0xFF4B5233.toInt(), 0xFF1F2316.toInt()),
            floatArrayOf(0f, 0.25f, 0.42f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRoundRect(rect, r * 0.18f, r * 0.18f, paint)
        paint.shader = null
        // rust blotches
        for (k in 0 until 4) {
            fill(Color.argb(110, 122, 74, 42))
            val bx = cx + (hz.shape[k] - 0.93f) * hw * 5f
            val by = cy + (hz.shape[k + 4] - 0.93f) * r * 5f
            c.drawCircle(bx.coerceIn(cx - hw * 0.8f, cx + hw * 0.8f), by.coerceIn(cy - r * 0.8f, cy + r * 0.8f), r * 0.16f * hz.shape[k + 1], paint)
        }
        // rolling ribs and a faded yellow band
        val phase = ((hz.spin / tau) % 1f + 1f) % 1f
        for (k in 0..2) {
            val t = (k / 3f + phase) % 1f
            val vis = sin(t * PI.toFloat())
            val yy = cy - r * cos(t * PI.toFloat())
            val thick = r * 0.1f * vis
            fill(if (k == 1) Color.argb((200 * vis).toInt(), 184, 150, 46) else Color.argb((220 * vis).toInt(), 25, 28, 18))
            c.drawRect(cx - hw, yy - thick, cx + hw, yy + thick, paint)
        }
        stroke.color = 0xFF15170F.toInt()
        stroke.strokeWidth = max(1f, r * 0.06f)
        rect.set(cx - hw, cy - r, cx + hw, cy + r)
        c.drawRoundRect(rect, r * 0.18f, r * 0.18f, stroke)
    }

    private fun rock(c: Canvas, hz: Hazard, cx: Float, cy: Float, r: Float) {
        path.reset()
        val n = hz.shape.size
        for (i in 0 until n) {
            val a = hz.spin + i * tau / n
            val rr = r * hz.shape[i]
            val x = cx + cos(a) * rr
            val y = cy + sin(a) * rr * 0.85f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        paint.color = Color.BLACK // opaque so the shader is not faded
        paint.shader = RadialGradient(
            cx - r * 0.4f, cy - r * 0.45f, r * 1.6f,
            intArrayOf(0xFFA39A8E.toInt(), 0xFF6A6157.toInt(), 0xFF2E2924.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawPath(path, paint)
        paint.shader = null
        // lichen and speckles
        for (k in 0 until 5) {
            val a = hz.spin + k * 1.3f
            fill(if (k % 2 == 0) Color.argb(90, 40, 36, 30) else Color.argb(70, 120, 125, 90))
            c.drawCircle(cx + cos(a) * r * 0.5f * hz.shape[k], cy + sin(a) * r * 0.4f, r * 0.09f * (1 + k % 3), paint)
        }
        stroke.color = 0xFF221E1A.toInt()
        stroke.strokeWidth = max(1f, r * 0.05f)
        c.drawPath(path, stroke)
        if (hz.hp < hz.kind.hp) {
            stroke.color = 0xFF14110E.toInt()
            stroke.strokeWidth = max(1f, r * 0.07f)
            c.drawLine(cx - r * 0.55f, cy - r * 0.45f, cx - r * 0.05f, cy + r * 0.05f, stroke)
            c.drawLine(cx - r * 0.05f, cy + r * 0.05f, cx + r * 0.15f, cy + r * 0.65f, stroke)
            c.drawLine(cx - r * 0.05f, cy + r * 0.05f, cx + r * 0.6f, cy - r * 0.25f, stroke)
        }
    }

    /** A tangle of thorn bramble that creeps and sways toward you. */
    private fun bramble(c: Canvas, hz: Hazard, cx: Float, bottom: Float, u: Float) {
        val sway = sin(hz.age * 2.2f) * 0.25f
        fill(Color.argb(70, 0, 0, 0))
        rect.set(cx - u * 2.6f, bottom - u * 0.5f, cx + u * 2.6f, bottom + u * 0.3f)
        c.drawOval(rect, paint)
        for (k in 0 until 14) {
            val base = -PI.toFloat() / 2f + (k - 6.5f) * 0.19f + sway * (0.5f + k % 3 * 0.3f)
            val len = u * (2.6f + 2.2f * hz.shape[k % 9]) * (if (k % 4 == 0) 1.15f else 1f)
            val bx = cx + (k - 6.5f) * u * 0.18f
            val ex = bx + cos(base) * len
            val ey = bottom + sin(base) * len
            val mx = bx + cos(base - 0.5f) * len * 0.55f
            val my = bottom + sin(base - 0.5f) * len * 0.55f
            path.reset()
            path.moveTo(bx, bottom)
            path.quadTo(mx, my, ex, ey)
            stroke.color = if (k % 3 == 0) 0xFF4A3420.toInt() else 0xFF3A2A1C.toInt()
            stroke.strokeWidth = max(1f, u * (0.16f + 0.05f * (k % 2)))
            c.drawPath(path, stroke)
            // thorns
            stroke.strokeWidth = max(1f, u * 0.07f)
            for (t in 1..3) {
                val f = t / 4f
                val tx = bx + (mx - bx) * f * 2f * (1 - f) + (ex - bx) * f * f
                val ty = bottom + (my - bottom) * f * 2f * (1 - f) + (ey - bottom) * f * f
                val ta = base + (if (t % 2 == 0) 1.2f else -1.2f)
                c.drawLine(tx, ty, tx + cos(ta) * u * 0.35f, ty + sin(ta) * u * 0.35f, stroke)
            }
            // a few dull leaves
            if (k % 3 == 1) {
                fill(0xFF55603A.toInt())
                rect.set(ex - u * 0.35f, ey - u * 0.18f, ex + u * 0.35f, ey + u * 0.18f)
                c.drawOval(rect, paint)
            }
        }
    }

    /** Rugged case with a glowing power cell window: restores shield. */
    private fun shieldCell(c: Canvas, hz: Hazard, cx: Float, bottom: Float, r: Float, clock: Float) {
        val top = bottom - r * 1.6f
        val hw = r * 1.15f
        val glow = 0.6f + 0.4f * sin(clock * 5f + hz.age)
        fill(Color.argb((60 * glow).toInt(), 90, 200, 255))
        c.drawCircle(cx, top + r * 0.8f, r * 1.6f, paint)
        rect.set(cx - hw, top, cx + hw, bottom)
        paint.color = Color.BLACK // opaque so the shader is not faded
        paint.shader = LinearGradient(0f, top, 0f, bottom, 0xFF5A5F63.toInt(), 0xFF2A2D30.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r * 0.15f, r * 0.15f, paint)
        paint.shader = null
        rect.set(cx - hw * 0.6f, top + r * 0.35f, cx + hw * 0.6f, bottom - r * 0.35f)
        fill(Color.rgb((60 + 60 * glow).toInt(), (170 + 60 * glow).toInt(), 255))
        c.drawRoundRect(rect, r * 0.08f, r * 0.08f, paint)
        stroke.color = 0xFF16181A.toInt()
        stroke.strokeWidth = max(1f, r * 0.08f)
        rect.set(cx - hw, top, cx + hw, bottom)
        c.drawRoundRect(rect, r * 0.15f, r * 0.15f, stroke)
        fill(0xFF1C1E20.toInt())
        c.drawRect(cx - hw * 0.35f, top - r * 0.18f, cx + hw * 0.35f, top, paint)
    }

    /** Olive ammo crate with stenciled chevrons: sling upgrade. */
    private fun ammoCrate(c: Canvas, cx: Float, bottom: Float, r: Float) {
        val top = bottom - r * 1.5f
        val hw = r * 1.3f
        rect.set(cx - hw, top, cx + hw, bottom)
        paint.color = Color.BLACK // opaque so the shader is not faded
        paint.shader = LinearGradient(0f, top, 0f, bottom, 0xFF6B6E45.toInt(), 0xFF3C3F26.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(rect, paint)
        paint.shader = null
        stroke.color = 0xFF2A2C1A.toInt()
        stroke.strokeWidth = max(1f, r * 0.06f)
        for (k in 1..2) {
            val yy = top + (bottom - top) * k / 3f
            c.drawLine(cx - hw, yy, cx + hw, yy, stroke)
        }
        c.drawRect(rect, stroke)
        // chevrons
        stroke.color = 0xFFE2D59A.toInt()
        stroke.strokeWidth = max(1.5f, r * 0.14f)
        for (k in 0..1) {
            val yy = top + r * (0.55f + k * 0.45f)
            c.drawLine(cx - r * 0.5f, yy + r * 0.25f, cx, yy - r * 0.1f, stroke)
            c.drawLine(cx, yy - r * 0.1f, cx + r * 0.5f, yy + r * 0.25f, stroke)
        }
    }

    /** A small stack of gold bars: points. */
    private fun goldBars(c: Canvas, hz: Hazard, cx: Float, bottom: Float, r: Float) {
        val bw = r * 1.1f
        val bh = r * 0.55f
        val spots = arrayOf(floatArrayOf(-0.55f, 0f), floatArrayOf(0.55f, 0f), floatArrayOf(0f, -1f))
        for (s in spots) {
            val x = cx + s[0] * bw
            val yb = bottom + s[1] * bh
            path.reset()
            path.moveTo(x - bw * 0.5f, yb)
            path.lineTo(x + bw * 0.5f, yb)
            path.lineTo(x + bw * 0.36f, yb - bh)
            path.lineTo(x - bw * 0.36f, yb - bh)
            path.close()
            paint.color = Color.BLACK // opaque so the shader is not faded
            paint.shader = LinearGradient(
                x - bw * 0.5f, yb - bh, x + bw * 0.5f, yb,
                intArrayOf(0xFFFFE9A8.toInt(), 0xFFD9A93A.toInt(), 0xFF8A6418.toInt()),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawPath(path, paint)
            paint.shader = null
            stroke.color = 0xFF5E440F.toInt()
            stroke.strokeWidth = max(1f, r * 0.04f)
            c.drawPath(path, stroke)
        }
        // glint
        val g = abs(sin(hz.age * 2.5f))
        if (g > 0.85f) {
            stroke.color = Color.argb((255 * (g - 0.85f) / 0.15f).toInt(), 255, 255, 255)
            stroke.strokeWidth = max(1f, r * 0.06f)
            val gx = cx + r * 0.2f
            val gy = bottom - bh * 1.7f
            c.drawLine(gx - r * 0.3f, gy, gx + r * 0.3f, gy, stroke)
            c.drawLine(gx, gy - r * 0.3f, gx, gy + r * 0.3f, stroke)
        }
    }

    // ------------------------------------------------------------ scenery props

    /** One sandbag lying lengthwise, centred at (cx, cy). */
    fun sandbag(c: Canvas, cx: Float, cy: Float, hw: Float, hh: Float, tint: Float) {
        rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
        paint.color = Color.BLACK // opaque so the shader is not faded
        paint.shader = LinearGradient(
            0f, cy - hh, 0f, cy + hh,
            Col.scale(0xFFB09468.toInt(), tint), Col.scale(0xFF5A4A30.toInt(), tint), Shader.TileMode.CLAMP,
        )
        c.drawRoundRect(rect, hh * 0.9f, hh * 0.9f, paint)
        paint.shader = null
        stroke.color = Col.scale(0xFF3A3122.toInt(), tint)
        stroke.strokeWidth = max(1f, hh * 0.1f)
        c.drawRoundRect(rect, hh * 0.9f, hh * 0.9f, stroke)
        // tied ends
        c.drawLine(cx - hw * 0.72f, cy - hh * 0.8f, cx - hw * 0.72f, cy + hh * 0.8f, stroke)
        c.drawLine(cx + hw * 0.72f, cy - hh * 0.8f, cx + hw * 0.72f, cy + hh * 0.8f, stroke)
    }

    /** A timber stake with a little sag of barbed wire to the next one. */
    fun stake(c: Canvas, x: Float, ground: Float, hgt: Float, wd: Float) {
        rect.set(x - wd / 2f, ground - hgt, x + wd / 2f, ground)
        paint.color = Color.BLACK // opaque so the shader is not faded
        paint.shader = LinearGradient(x - wd / 2f, 0f, x + wd / 2f, 0f, 0xFF6B5136.toInt(), 0xFF2C2116.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(rect, paint)
        paint.shader = null
    }

    fun wire(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, sag: Float, width: Float) {
        path.reset()
        path.moveTo(x0, y0)
        path.quadTo((x0 + x1) / 2f, (y0 + y1) / 2f + sag, x1, y1)
        stroke.shader = null
        stroke.color = 0xCC2A2826.toInt()
        stroke.strokeWidth = max(1f, width)
        c.drawPath(path, stroke)
        // barbs
        stroke.strokeWidth = max(1f, width * 0.8f)
        for (k in 1..4) {
            val t = k / 5f
            val bx = x0 + (x1 - x0) * t
            val by = y0 + (y1 - y0) * t + sag * 4f * t * (1 - t) * 0.5f
            c.drawLine(bx - width * 2f, by - width * 2f, bx + width * 2f, by + width * 2f, stroke)
        }
    }

    /** A heavy timber upright of the sling frame. */
    fun post(c: Canvas, x: Float, top: Float, bottom: Float, wd: Float) {
        rect.set(x - wd / 2f, top, x + wd / 2f, bottom)
        paint.color = Color.BLACK // opaque so the shader is not faded
        paint.shader = LinearGradient(
            x - wd / 2f, 0f, x + wd / 2f, 0f,
            intArrayOf(0xFF2E2016.toInt(), 0xFF7A5A3C.toInt(), 0xFF3A2A1C.toInt()),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(rect, paint)
        paint.shader = null
        // rope lashing
        stroke.color = 0xFF8C7A55.toInt()
        stroke.strokeWidth = max(1f, wd * 0.12f)
        for (k in 0..2) {
            val yy = top + wd * (0.4f + k * 0.28f)
            c.drawLine(x - wd / 2f, yy, x + wd / 2f, yy + wd * 0.12f, stroke)
        }
    }
}
