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
        val r = hz.radius * u
        when (hz.kind) {
            Kind.BARREL -> roller(c, hz, cx, bottom - r, r)
            Kind.ROCK -> rock(c, hz, cx, bottom - r * 0.9f, r)
            Kind.BOSS -> {
                golem(c, hz, cx, bottom, r)
                if (hz.flash > 0f) {
                    fill(Color.argb((hz.flash * 140).toInt(), 255, 240, 220))
                    c.drawCircle(cx, bottom - r * 2f, r * 1.6f, paint)
                }
                return
            }
            Kind.PLANT -> scree(c, hz, cx, bottom, r)
            Kind.ENERGY -> shieldCell(c, hz, cx, bottom, r, clock)
            Kind.UPGRADE -> ammoCrate(c, cx, bottom, r)
            Kind.GOLD -> goldBars(c, hz, cx, bottom, r)
        }
        if (hz.flash > 0f) {
            fill(Color.argb((hz.flash * 160).toInt(), 255, 240, 220))
            c.drawCircle(cx, bottom - r, r * 1.15f, paint)
        }
    }

    /** A round granite boulder rolling straight at you: speckles and cracks turn with it. */
    private fun roller(c: Canvas, hz: Hazard, cx: Float, cy: Float, r: Float) {
        path.reset()
        val n = 14
        for (k in 0 until n) {
            val a = k * tau / n
            val rr = r * (0.94f + 0.06f * hz.shape[k % 9])
            val x = cx + cos(a) * rr
            val y = cy + sin(a) * rr
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        paint.color = Color.BLACK
        paint.shader = RadialGradient(
            cx - r * 0.35f, cy - r * 0.4f, r * 1.5f,
            intArrayOf(0xFF9A9286.toInt(), 0xFF5F584F.toInt(), 0xFF231F1B.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawPath(path, paint)
        paint.shader = null
        // granite speckle, rotating as it rolls
        for (k in 0 until 9) {
            val a = hz.spin + k * 0.7f
            val d = r * (0.2f + 0.6f * ((k * 37) % 10) / 10f)
            fill(if (k % 3 == 0) 0x55D8D0C4 else 0x55201C18)
            c.drawCircle(cx + cos(a) * d, cy + sin(a) * d * 0.95f, r * 0.05f, paint)
        }
        stroke.color = 0xAA1B1814.toInt()
        stroke.strokeWidth = max(1f, r * 0.05f)
        val ca = hz.spin
        c.drawLine(cx + cos(ca) * r * 0.1f, cy + sin(ca) * r * 0.1f, cx + cos(ca + 0.3f) * r * 0.8f, cy + sin(ca + 0.3f) * r * 0.8f, stroke)
        stroke.color = 0xFF1A1714.toInt()
        stroke.strokeWidth = max(1f, r * 0.05f)
        c.drawPath(path, stroke)
    }

    /** A loose cluster of stones tumbling together, weaving side to side. */
    private fun scree(c: Canvas, hz: Hazard, cx: Float, bottom: Float, r: Float) {
        val seed = (hz.baseX * 97f).toInt()
        val tumble = sin(hz.age * 4f) * 0.25f
        fill(Color.argb(70, 0, 0, 0))
        rect.set(cx - r * 1.2f, bottom - r * 0.15f, cx + r * 1.2f, bottom + r * 0.15f)
        c.drawOval(rect, paint)
        slab(c, cx - r * 0.55f, bottom - r * 0.42f, r * 0.5f, r * 0.42f, seed, tumble, 0.9f)
        slab(c, cx + r * 0.55f, bottom - r * 0.38f, r * 0.46f, r * 0.38f, seed + 1, -tumble, 0.85f)
        slab(c, cx + r * 0.05f, bottom - r * 0.95f, r * 0.55f, r * 0.48f, seed + 2, tumble * 1.5f, 1f)
        if (abs(sin(hz.age * 4f)) > 0.97f) {
            fill(0x338D7B68)
            c.drawCircle(cx, bottom - r * 0.1f, r * 0.6f, paint)
        }
    }

    // ------------------------------------------------------------ boss golem

    /** Deterministic 0..1 noise so each slab keeps its shape from frame to frame. */
    private fun hashF(seed: Int, k: Int): Float {
        val v = sin(seed * 12.9898f + k * 78.233f) * 43758.547f
        return v - kotlin.math.floor(v)
    }

    /**
     * One jagged stone slab: an irregular polygon lit from the upper left, with a dark
     * gap outline (so stacked slabs read as separate stones), cracks and lichen.
     */
    private fun slab(c: Canvas, cx: Float, cy: Float, rw: Float, rh: Float, seed: Int, rot: Float, tint: Float = 1f) {
        val n = 8
        path.reset()
        for (k in 0 until n) {
            val a = rot + k * tau / n + (hashF(seed, k + 20) - 0.5f) * 0.5f
            val m = 0.72f + 0.28f * hashF(seed, k)
            val x = cx + cos(a) * rw * m
            val y = cy + sin(a) * rh * m
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        // dark gap around the stone
        stroke.shader = null
        stroke.color = 0xFF16130F.toInt()
        stroke.strokeWidth = max(1.5f, (rw + rh) * 0.09f)
        c.drawPath(path, stroke)
        // body
        val rr = max(rw, rh)
        paint.color = Color.BLACK
        paint.shader = RadialGradient(
            cx - rw * 0.35f, cy - rh * 0.45f, rr * 1.5f,
            intArrayOf(Col.scale(0xFF8E877B.toInt(), tint), Col.scale(0xFF5C564D.toInt(), tint), Col.scale(0xFF2A2621.toInt(), tint)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawPath(path, paint)
        paint.shader = null
        // flat facet catching the light
        fill(0x1FFFFFFF)
        path.reset()
        path.moveTo(cx - rw * 0.55f, cy - rh * 0.2f)
        path.lineTo(cx - rw * 0.1f, cy - rh * 0.62f)
        path.lineTo(cx + rw * 0.25f, cy - rh * 0.35f)
        path.lineTo(cx - rw * 0.15f, cy + rh * 0.05f)
        path.close()
        c.drawPath(path, paint)
        // cracks
        stroke.color = 0xAA1B1814.toInt()
        stroke.strokeWidth = max(1f, rr * 0.04f)
        val ca = hashF(seed, 40) * tau
        c.drawLine(cx + cos(ca) * rw * 0.15f, cy + sin(ca) * rh * 0.15f, cx + cos(ca + 0.4f) * rw * 0.7f, cy + sin(ca + 0.4f) * rh * 0.7f, stroke)
        c.drawLine(cx + cos(ca + 0.4f) * rw * 0.45f, cy + sin(ca + 0.4f) * rh * 0.45f, cx + cos(ca + 1.2f) * rw * 0.62f, cy + sin(ca + 1.2f) * rh * 0.62f, stroke)
        // lichen and grit
        for (k in 0 until 3) {
            fill(if (k == 0) 0x40707A4A else 0x30000000)
            c.drawCircle(cx + (hashF(seed, 50 + k) - 0.5f) * rw, cy + (hashF(seed, 60 + k) - 0.3f) * rh * 0.8f, rr * (0.06f + 0.05f * hashF(seed, 70 + k)), paint)
        }
    }

    /**
     * The boss: a hulking, hunched giant built from stacked stone slabs.
     * Short thick legs, a massive chest, heavy shoulders, a small head sunk low between them,
     * and long arms ending in huge stone fists. It lumbers forward, and raises its arms while
     * it pulls stray stones in.
     */
    private fun golem(c: Canvas, hz: Hazard, cx: Float, bottom: Float, r: Float) {
        val walk = if (hz.summoning) 0f else sin(hz.age * 2.4f)
        val bob = if (hz.summoning) 0f else abs(walk) * r * 0.05f
        val base = bottom - bob

        // shadow
        fill(Color.argb(90, 0, 0, 0))
        rect.set(cx - r * 1.7f, bottom - r * 0.18f, cx + r * 1.7f, bottom + r * 0.18f)
        c.drawOval(rect, paint)

        // legs: two slabs each, stepping in turn
        for (side in intArrayOf(-1, 1)) {
            val lift = max(0f, walk * side) * r * 0.22f
            val lx = cx + side * r * 0.5f
            slab(c, lx, base - r * 1.05f - lift * 0.5f, r * 0.42f, r * 0.42f, 11 + side, 0.3f, 0.92f)
            slab(c, lx + side * r * 0.05f, base - r * 0.36f - lift, r * 0.48f, r * 0.36f, 13 + side, 0.1f, 0.85f)
        }

        // arms behind the body swing opposite the legs; raised when summoning
        val shY = base - r * 2.55f
        for (side in intArrayOf(-1, 1)) {
            val sx = cx + side * r * 1.1f
            val ex: Float
            val ey: Float
            val fx: Float
            val fy: Float
            if (hz.summoning || (hz.throwing > 0f && side == 1)) {
                val wob = sin(hz.age * 7f + side) * r * 0.06f
                ex = sx + side * r * 0.45f; ey = shY - r * 0.7f + wob
                fx = sx + side * r * 0.55f; fy = shY - r * 1.45f - wob
            } else {
                val sw = -walk * side * 0.35f
                ex = sx + side * r * 0.3f + sin(sw) * r * 0.25f; ey = shY + r * 0.85f
                fx = sx + side * r * 0.32f + sin(sw) * r * 0.55f; fy = shY + r * 1.75f
            }
            slab(c, (sx + ex) / 2f, (shY + ey) / 2f, r * 0.36f, r * 0.46f, 21 + side, 0.6f, 0.9f)
            slab(c, (ex + fx) / 2f, (ey + fy) / 2f, r * 0.33f, r * 0.42f, 23 + side, 1.2f, 0.88f)
            slab(c, fx, fy + (if (hz.summoning) 0f else r * 0.15f), r * 0.5f, r * 0.46f, 25 + side, 2.1f, 0.95f)
        }

        // hips and a massive, slightly hunched chest
        slab(c, cx, base - r * 1.5f, r * 0.85f, r * 0.42f, 31, 0.2f, 0.9f)
        slab(c, cx - r * 0.05f, base - r * 2.15f, r * 1.05f, r * 0.62f, 33, 0.5f, 1f)
        slab(c, cx + r * 0.25f, base - r * 1.95f, r * 0.5f, r * 0.38f, 34, 1.4f, 0.95f)

        // heavy shoulders, higher than the head
        for (side in intArrayOf(-1, 1)) {
            slab(c, cx + side * r * 0.95f, shY - r * 0.05f, r * 0.58f, r * 0.48f, 41 + side, 0.9f, 1.05f)
        }

        // small head sunk low between the shoulders, with deep-set embers for eyes
        val hy = shY - r * 0.12f
        slab(c, cx, hy, r * 0.36f, r * 0.33f, 51, 0.4f, 0.98f)
        fill(0xCC0E0C0A.toInt())
        rect.set(cx - r * 0.25f, hy - r * 0.08f, cx + r * 0.25f, hy + r * 0.04f)
        c.drawRect(rect, paint)
        val ember = if (hz.summoning) 1f else 0.55f + 0.25f * sin(hz.age * 3f)
        fill(Color.argb((70 * ember).toInt(), 255, 110, 30))
        c.drawCircle(cx - r * 0.12f, hy - r * 0.02f, r * 0.08f, paint)
        c.drawCircle(cx + r * 0.12f, hy - r * 0.02f, r * 0.08f, paint)
        fill(Color.argb((230 * ember).toInt(), 255, 150, 50))
        c.drawCircle(cx - r * 0.12f, hy - r * 0.02f, r * 0.03f, paint)
        c.drawCircle(cx + r * 0.12f, hy - r * 0.02f, r * 0.03f, paint)

        // dust shaken loose as it walks
        if (!hz.summoning && abs(walk) > 0.95f) {
            fill(0x338D7B68)
            c.drawCircle(cx + (if (walk > 0) -1 else 1) * r * 0.55f, bottom - r * 0.1f, r * 0.35f, paint)
        }
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
        if (hz.hp < hz.maxHp) {
            stroke.color = 0xFF14110E.toInt()
            stroke.strokeWidth = max(1f, r * 0.07f)
            c.drawLine(cx - r * 0.55f, cy - r * 0.45f, cx - r * 0.05f, cy + r * 0.05f, stroke)
            c.drawLine(cx - r * 0.05f, cy + r * 0.05f, cx + r * 0.15f, cy + r * 0.65f, stroke)
            c.drawLine(cx - r * 0.05f, cy + r * 0.05f, cx + r * 0.6f, cy - r * 0.25f, stroke)
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
