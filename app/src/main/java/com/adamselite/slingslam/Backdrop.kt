package com.adamselite.slingslam

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/** Small value-noise toolkit for painting terrain and sky. */
object Noise {
    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x7fffffff) / 2147483647f
    }

    fun value(x: Float, y: Float, seed: Int): Float {
        val xf = floor(x)
        val yf = floor(y)
        val xi = xf.toInt()
        val yi = yf.toInt()
        val tx = x - xf
        val ty = y - yf
        val sx = tx * tx * (3f - 2f * tx)
        val sy = ty * ty * (3f - 2f * ty)
        val a = hash(xi, yi, seed)
        val b = hash(xi + 1, yi, seed)
        val c = hash(xi, yi + 1, seed)
        val d = hash(xi + 1, yi + 1, seed)
        val top = a + (b - a) * sx
        val bot = c + (d - c) * sx
        return top + (bot - top) * sy
    }

    fun fbm(x: Float, y: Float, octaves: Int, seed: Int): Float {
        var sum = 0f
        var amp = 0.5f
        var f = 1f
        var norm = 0f
        for (o in 0 until octaves) {
            sum += value(x * f, y * f, seed + o * 31) * amp
            norm += amp
            amp *= 0.5f
            f *= 2.03f
        }
        return sum / norm
    }

    fun grain(x: Int, y: Int, seed: Int) = hash(x, y, seed)
}

/** Colour helpers working on packed ARGB ints. */
object Col {
    fun r(c: Int) = (c shr 16) and 255
    fun g(c: Int) = (c shr 8) and 255
    fun b(c: Int) = c and 255

    fun rgb(r: Float, g: Float, b: Float): Int {
        val ri = r.toInt().coerceIn(0, 255)
        val gi = g.toInt().coerceIn(0, 255)
        val bi = b.toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return rgb(
            r(a) + (r(b) - r(a)) * u,
            g(a) + (g(b) - g(a)) * u,
            b(a) + (b(b) - b(a)) * u,
        )
    }

    fun scale(c: Int, k: Float) = rgb(r(c) * k, g(c) * k, b(c) * k)
}

/**
 * Pre-painted scenery. Painted once per screen size into pixel arrays,
 * then drawn as bitmaps each frame.
 */
class Backdrop(
    private val w: Float,
    private val h: Float,
    private val top: Float,       // first screen row of the lane view (sky starts here)
    private val bottom: Float,    // last screen row of the lane view
    private val horizonY: Float,
    private val groundY: Float,
    private val unitPx: Float,
    private val persp: Float,
) {
    private val sFar = persp / (persp + K.Z_FAR)

    private class Crater(val x: Float, val z: Float, val r: Float)

    private val craters: List<Crater> = run {
        val rnd = Random(11)
        List(34) {
            Crater(
                (rnd.nextFloat() * 2f - 1f) * 45f,
                -4f + rnd.nextFloat() * 170f,
                1.5f + rnd.nextFloat() * rnd.nextFloat() * 5f,
            )
        }
    }

    // palette
    private val skyTop = 0xFF4D545C.toInt()
    private val skyMid = 0xFF7D7A74.toInt()
    private val skyLow = 0xFFC09A72.toInt()       // smoky, fire-lit horizon
    private val ruin = 0xFF2F3236.toInt()
    private val haze = 0xFF9A8B78.toInt()
    private val mudDark = 0xFF362C22.toInt()
    private val mudLight = 0xFF6E5B45.toInt()
    private val grass = 0xFF5E5C38.toInt()
    private val water = 0xFF2A2825.toInt()

    /** Height of the distant skyline (ruined buildings and broken trees) above the horizon at screen x. */
    private fun skyline(x: Float): Float {
        val u = x / w
        var hgt = h * 0.012f * (0.6f + Noise.fbm(u * 6f, 0f, 3, 5))
        // blocky ruins in a few clusters
        val block = floor(u * 38f)
        val cluster = Noise.value(u * 4f, 3f, 9)
        if (cluster > 0.55f) {
            val bh = Noise.grain(block.toInt(), 1, 77)
            if (bh > 0.25f) {
                var bhPx = h * 0.03f * bh * (cluster - 0.45f) * 2.2f
                // broken tops
                bhPx *= 0.75f + 0.25f * Noise.value(x * 0.25f, 1f, 13)
                hgt = max(hgt, bhPx)
            }
        }
        // a few dead trees / poles
        val pole = Noise.grain((u * 120f).toInt(), 2, 41)
        if (pole > 0.965f && abs((u * 120f) % 1f - 0.5f) < 0.12f) hgt = max(hgt, h * 0.03f * (pole - 0.9f) * 12f)
        return hgt
    }

    private fun sky(x: Float, y: Float, y0: Float, y1: Float): Int {
        val t = ((y - y0) / (y1 - y0)).coerceIn(0f, 1f)
        var c = if (t < 0.55f) Col.mix(skyTop, skyMid, t / 0.55f) else Col.mix(skyMid, skyLow, (t - 0.55f) / 0.45f)
        val cl = Noise.fbm(x * 0.006f, y * 0.018f, 4, 3)
        c = Col.scale(c, 0.82f + cl * 0.36f)
        // smoke smudges
        val sm = Noise.fbm(x * 0.003f + 20f, y * 0.01f, 3, 8)
        if (sm > 0.55f) c = Col.mix(c, 0xFF3C3A38.toInt(), (sm - 0.55f) * 1.6f)
        return c
    }

    /** Lane view scenery: sky, skyline and the muddy field in perspective. Half resolution when [step] = 2. */
    fun paintLane(step: Int): Pair<IntArray, IntArray> {
        val bw = (w / step).toInt().coerceAtLeast(1)
        val bh = ((bottom - top) / step).toInt().coerceAtLeast(1)
        val px = IntArray(bw * bh)
        val lane = K.LANE_HALF
        for (j in 0 until bh) {
            val sy = top + j * step
            for (i in 0 until bw) {
                val sx = i * step.toFloat()
                var c: Int
                if (sy < horizonY) {
                    c = sky(sx, sy, top, horizonY)
                    val sk = skyline(sx)
                    if (sy > horizonY - sk) {
                        val k = (horizonY - sy) / max(1f, sk)
                        c = Col.mix(ruin, Col.mix(ruin, skyLow, 0.25f), 1f - k)
                        if (Noise.grain(i, j, 3) > 0.995f && k > 0.4f) c = 0xFFE08A3C.toInt() // a lit window / fire
                    }
                } else {
                    val t = (sy - horizonY) / (groundY - horizonY)
                    val s = sFar + t * (1f - sFar)
                    val z = persp / s - persp
                    val wx = (sx - w / 2f) / (unitPx * s)
                    c = ground(wx, z, i, j)
                    // the lane you defend is a little more churned up
                    if (abs(wx) < lane) c = Col.scale(c, 0.95f)
                    // fog with distance
                    val fog = 1f - exp(-max(z, 0f) / 85f)
                    c = Col.mix(c, haze, fog * 0.75f)
                }
                // vignette
                val dx = (sx / w - 0.5f) * 2f
                val dy = ((sy - top) / (bottom - top) - 0.45f) * 2f
                val v = 1f - 0.28f * (dx * dx * 0.8f + max(0f, dy) * max(0f, dy) * 0.6f)
                px[j * bw + i] = Col.scale(c, v)
            }
        }
        return px to intArrayOf(bw, bh)
    }

    private fun ground(wx: Float, z: Float, i: Int, j: Int): Int {
        val n = Noise.fbm(wx * 0.22f, z * 0.22f, 4, 1)
        var c = Col.mix(mudDark, mudLight, n * 1.25f - 0.12f)
        // dead grass patches
        val g = Noise.fbm(wx * 0.07f + 100f, z * 0.07f, 3, 2)
        if (g > 0.56f) c = Col.mix(c, grass, (g - 0.56f) * 3f)
        // ruts from old vehicle tracks
        for (rx in floatArrayOf(-6.5f, -4.2f, 3.8f, 6.1f)) {
            val wob = (Noise.value(z * 0.08f, rx, 4) - 0.5f) * 1.6f
            val d = abs(wx - rx - wob)
            if (d < 0.55f) c = Col.scale(c, 0.78f + d * 0.3f)
        }
        // puddles reflect the sky
        val p = Noise.fbm(wx * 0.05f + 50f, z * 0.05f, 3, 6)
        if (p > 0.66f) {
            val k = ((p - 0.66f) * 12f).coerceIn(0f, 1f)
            c = Col.mix(c, Col.mix(water, skyMid, 0.35f + 0.2f * Noise.value(wx * 0.6f, z * 0.6f, 7)), k)
        }
        // craters: dark bowl lit from the upper left, raised lighter rim
        for (cr in craters) {
            val dx = wx - cr.x
            val dz = z - cr.z
            if (abs(dx) > cr.r * 1.4f || abs(dz) > cr.r * 1.4f) continue
            val d = sqrt(dx * dx + dz * dz) / cr.r
            if (d < 1f) {
                val shade = 0.55f + 0.35f * d + 0.25f * (dx / cr.r)
                c = Col.scale(c, shade)
                if (d < 0.35f && Noise.value(wx * 2f, z * 2f, 12) > 0.6f) c = Col.mix(c, water, 0.6f)
            } else if (d < 1.35f) {
                c = Col.scale(c, 1.05f + 0.15f * (1f - (d - 1f) / 0.35f) - 0.08f * (dx / cr.r))
            }
        }
        // grit
        val gr = Noise.grain(i, j, 21)
        c = Col.scale(c, 0.9f + gr * 0.2f)
        if (gr > 0.985f) c = Col.mix(c, 0xFFA59C8E.toInt(), 0.6f)   // pebbles
        return c
    }

    /** Side-view strip: sky, skyline and a cut-away of the earth. */
    fun paintSide(sideTop: Float, sideBottom: Float, sideGround: Float, step: Int): Pair<IntArray, IntArray> {
        val bw = (w / step).toInt().coerceAtLeast(1)
        val bh = ((sideBottom - sideTop) / step).toInt().coerceAtLeast(1)
        val px = IntArray(bw * bh)
        for (j in 0 until bh) {
            val sy = sideTop + j * step
            for (i in 0 until bw) {
                val sx = i * step.toFloat()
                var c: Int
                if (sy < sideGround) {
                    c = sky(sx, sy * 1.6f, sideTop * 1.6f, sideGround * 1.6f)
                    val sk = skyline(sx) * 0.7f
                    if (sy > sideGround - sk) c = Col.mix(ruin, haze, 0.25f)
                    c = Col.scale(c, 0.8f)
                } else {
                    val d = (sy - sideGround) / max(1f, sideBottom - sideGround)
                    val n = Noise.fbm(sx * 0.03f, sy * 0.08f, 3, 14)
                    c = Col.mix(0xFF4A3A2A.toInt(), 0xFF2A2018.toInt(), d * 0.8f + n * 0.3f)
                    if (d < 0.12f) c = Col.mix(c, grass, 0.5f)
                    if (Noise.grain(i, j, 5) > 0.97f) c = Col.mix(c, 0xFF8A8074.toInt(), 0.5f)
                }
                px[j * bw + i] = c
            }
        }
        return px to intArrayOf(bw, bh)
    }
}
