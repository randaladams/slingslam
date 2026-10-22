package com.randaladams.slingslam

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pure game state and rules. No Android classes here so it can be tested on its own.
 *
 * World coordinates:
 *   x = left/right across the lane (-LANE_HALF .. LANE_HALF)
 *   y = height above the ground
 *   z = distance down-range from the player (0 = you, Z_FAR = where hazards appear)
 */
object K {
    const val LANE_HALF = 12f
    const val Z_FAR = 100f
    const val Z_REACH = 1.5f          // a hazard at or below this distance has reached you
    const val G = 60f                 // gravity
    const val LAUNCH_DEG_MIN = 12f    // weak shots fly nearly flat and fast
    const val LAUNCH_DEG_MAX = 52f    // full-power shots are high lobs
    const val LAUNCH_Y = 1.5f
    const val BOULDER_R = 1.4f
    const val MIN_RANGE = 4f
    const val MAX_RANGE = 104f
    const val MAX_AIM_DEG = 25f       // how far left/right you can aim
    const val SHIELD_MAX = 100f
    const val SHIELD_REGEN = 0.8f     // per second
    const val SHIELD_PER_KILL = 6f
    const val MAX_SLING = 5
    const val HAZARDS_PER_LEVEL = 6
}

enum class Kind(
    val good: Boolean,
    val radius: Float,
    val height: Float,
    val damage: Float,
    val points: Int,
    val hp: Int,
) {
    BARREL(false, 2.2f, 4.4f, 20f, 100, 1),
    ROCK(false, 3.0f, 5.0f, 30f, 200, 2),
    PLANT(false, 2.6f, 5.5f, 15f, 150, 1),
    ENERGY(true, 2.0f, 4.0f, 0f, 50, 1),
    UPGRADE(true, 2.0f, 4.0f, 0f, 100, 1),
    GOLD(true, 2.0f, 4.0f, 0f, 500, 1),
}

class Hazard(val kind: Kind, x: Float, var z: Float, var speed: Float, val shape: FloatArray) {
    var x = x
    val baseX = x
    var y = 0f
    var hp = kind.hp
    var age = 0f
    var flash = 0f
    var spin = 0f
}

class Boulder(
    var x: Float, var y: Float, var z: Float,
    var vx: Float, var vy: Float, var vz: Float,
) {
    val trail = ArrayList<FloatArray>()   // x, y, z samples for the side-view arc
    var landed = false
    var hit = false                       // it struck something
    var gone = false                      // shattered on a direct hit
    var rest = 0f
    var spin = 0f
    var landX = 0f
    var landZ = 0f
}

class Particle(
    var x: Float, var y: Float, var z: Float,
    var vx: Float, var vy: Float, var vz: Float,
    var life: Float, val maxLife: Float, val color: Int, val size: Float,
    val type: Int = DEBRIS,
) {
    companion object {
        const val DEBRIS = 0
        const val SMOKE = 1
        const val FIRE = 2
    }
}

class Popup(val text: String, val x: Float, val z: Float, val color: Int, val big: Boolean = false) {
    var t = 0f
}

/** An impact ring left on the ground where a boulder landed. */
class Mark(val x: Float, val z: Float, val hit: Boolean) {
    var t = 0f
}

enum class Sfx { THROW, LAND, HIT, BREAK, HURT, PICKUP, UPGRADE, GAMEOVER }

interface WorldListener {
    fun onSound(s: Sfx)
    fun onVibrate(ms: Long, amp: Int)
}

class World(private val listener: WorldListener, seed: Long = System.nanoTime()) {
    private val rnd = Random(seed)

    var score = 0
    var shield = K.SHIELD_MAX
    var level = 1
    var resolved = 0
    var slingLevel = 1
    var streak = 0
    var bestStreak = 0
    var thrown = 0
    var hits = 0
    var smashed = 0
    var time = 0f

    var hazard: Hazard? = null
    var spawnTimer = 1.6f
    val boulders = ArrayList<Boulder>()
    val particles = ArrayList<Particle>()
    val popups = ArrayList<Popup>()
    val marks = ArrayList<Mark>()
    var lastShot: Boulder? = null

    var loaded = true
    var reloadT = 1f      // 0..1 progress of the next boulder rolling off the pile

    var shake = 0f
    var hurtFlash = 0f
    var gameOver = false
    var banner: String? = null
    var bannerT = 0f

    val reloadDuration get() = 0.95f - 0.11f * (slingLevel - 1)
    val splash get() = 0.5f + 0.3f * (slingLevel - 1)     // extra hit radius when a boulder lands
    val hazardSpeed get() = min(5.5f * (1f + 0.10f * (level - 1)), 30f)
    val spawnDelay get() = max(0.4f, 1.8f - 0.1f * (level - 1))
    val multiplier get() = 1 + streak / 5

    /** Where a throw of this power would land (ignoring launch height). */
    fun rangeFor(power: Float) = K.MIN_RANGE + power.coerceIn(0f, 1f) * (K.MAX_RANGE - K.MIN_RANGE)

    /** Elevation in radians: low power throws flat and fast, high power lobs. */
    fun launchAngle(power: Float): Float {
        val p = power.coerceIn(0f, 1f)
        val deg = K.LAUNCH_DEG_MIN + (K.LAUNCH_DEG_MAX - K.LAUNCH_DEG_MIN) * Math.pow(p.toDouble(), 0.7).toFloat()
        return (deg * PI / 180.0).toFloat()
    }

    /** Launch speed and flight time for a throw of this power. */
    fun launchSpeed(power: Float): Float {
        val th = launchAngle(power)
        return sqrt(rangeFor(power) * K.G / sin(2f * th))
    }

    fun flightTime(power: Float) = 2f * launchSpeed(power) * sin(launchAngle(power)) / K.G

    fun throwBoulder(power: Float, aimRad: Float): Boolean {
        if (!loaded || gameOver) return false
        val th = launchAngle(power)
        val v = launchSpeed(power)
        val h = v * cos(th)
        val b = Boulder(0f, K.LAUNCH_Y, 0f, h * sin(aimRad), v * sin(th), h * cos(aimRad))
        b.trail.add(floatArrayOf(b.x, b.y, b.z))
        boulders.add(b)
        lastShot = b
        loaded = false
        reloadT = 0f
        thrown++
        listener.onSound(Sfx.THROW)
        listener.onVibrate(18, 90)
        return true
    }

    fun update(dt: Float) {
        // Small fixed steps so fast boulders can't skip through a hazard.
        var left = dt
        while (left > 0f) {
            val step = min(left, 1f / 120f)
            step(step)
            left -= step
        }
    }

    private fun step(dt: Float) {
        time += dt
        shake = max(0f, shake - dt * 2.5f)
        hurtFlash = max(0f, hurtFlash - dt * 2f)
        if (bannerT > 0f) bannerT -= dt
        updateEffects(dt)
        if (gameOver) return

        shield = min(K.SHIELD_MAX, shield + K.SHIELD_REGEN * dt)

        if (!loaded) {
            reloadT += dt / reloadDuration
            if (reloadT >= 1f) {
                reloadT = 1f
                loaded = true
            }
        }

        val h = hazard
        if (h == null) {
            spawnTimer -= dt
            if (spawnTimer <= 0f) spawn()
        } else {
            moveHazard(h, dt)
        }
        updateBoulders(dt)
    }

    private fun spawn() {
        val kind: Kind
        if (rnd.nextFloat() < 0.15f) {
            val g = rnd.nextFloat()
            kind = when {
                g < 0.4f && shield < 90f -> Kind.ENERGY
                g < 0.7f && slingLevel < K.MAX_SLING -> Kind.UPGRADE
                else -> Kind.GOLD
            }
        } else {
            val wBarrel = 1f
            val wRock = if (level >= 2) 0.6f else 0f
            val wPlant = if (level >= 3) 0.6f else 0f
            val r = rnd.nextFloat() * (wBarrel + wRock + wPlant)
            kind = when {
                r < wBarrel -> Kind.BARREL
                r < wBarrel + wRock -> Kind.ROCK
                else -> Kind.PLANT
            }
        }
        val x = (rnd.nextFloat() * 2f - 1f) * (K.LANE_HALF - 3f)
        val kindSpeed = when (kind) {
            Kind.ROCK -> 0.82f
            Kind.PLANT -> 0.9f
            Kind.BARREL -> 1f
            else -> 1.05f
        }
        val speed = hazardSpeed * kindSpeed * (0.92f + rnd.nextFloat() * 0.16f)
        val shape = FloatArray(9) { 0.78f + rnd.nextFloat() * 0.3f }
        hazard = Hazard(kind, x, K.Z_FAR + 2f, speed, shape)
    }

    private fun moveHazard(h: Hazard, dt: Float) {
        h.age += dt
        h.flash = max(0f, h.flash - dt * 4f)
        h.z -= h.speed * dt
        when (h.kind) {
            Kind.BARREL -> h.spin += h.speed * dt / h.kind.radius
            Kind.ROCK -> {
                h.spin += dt * h.speed * 0.15f
                h.y = abs(sin(h.age * 5f)) * 0.5f
            }
            Kind.PLANT -> h.x = (h.baseX + sin(h.age * 1.5f) * 3f)
                .coerceIn(-K.LANE_HALF + 2f, K.LANE_HALF - 2f)
            else -> {
                h.y = 1.2f + sin(h.age * 3f) * 0.6f
                h.spin += dt * 3f
            }
        }
        if (h.z <= K.Z_REACH) reached(h)
    }

    private fun reached(h: Hazard) {
        hazard = null
        resolveOne()
        if (h.kind.good) {
            popups.add(Popup("missed", h.x, 3f, 0xFFB0B0B0.toInt()))
            return
        }
        shield -= h.kind.damage
        streak = 0
        shake = 1f
        hurtFlash = 1f
        listener.onSound(Sfx.HURT)
        listener.onVibrate(140, 255)
        burst(h.x, 1f, 2f, 0xFF64B5F6.toInt(), 22, 18f)
        smoke(h.x, 1f, 1.5f, 6, 0xFF6F6A64.toInt(), 2f)
        popups.add(Popup("-${h.kind.damage.toInt()} SHIELD", h.x, 3f, 0xFFFF5252.toInt(), big = true))
        if (shield <= 0f) {
            shield = 0f
            gameOver = true
            listener.onSound(Sfx.GAMEOVER)
            listener.onVibrate(400, 255)
        }
    }

    private fun resolveOne() {
        resolved++
        val newLevel = 1 + resolved / K.HAZARDS_PER_LEVEL
        if (newLevel > level) {
            level = newLevel
            showBanner("LEVEL $level")
        }
        spawnTimer = spawnDelay
    }

    private fun showBanner(s: String) {
        banner = s
        bannerT = 1.6f
    }

    private fun updateBoulders(dt: Float) {
        val it = boulders.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (b.gone) { it.remove(); continue }
            if (b.landed) {
                b.rest += dt
                // a little roll after landing
                b.x += b.vx * dt
                b.z += b.vz * dt
                b.vx *= max(0f, 1f - 4f * dt)
                b.vz *= max(0f, 1f - 4f * dt)
                b.spin += hypot(b.vx, b.vz) * dt * 0.5f
                if (b.rest > 1.4f) it.remove()
                continue
            }
            b.vy -= K.G * dt
            b.x += b.vx * dt
            b.y += b.vy * dt
            b.z += b.vz * dt
            b.spin += dt * 6f
            b.trail.add(floatArrayOf(b.x, b.y, b.z))

            val h = hazard
            // Direct hit, rising or falling (flat throws can hit on the way out).
            if (h != null) {
                val d = hypot(b.x - h.x, b.z - h.z)
                if (d < h.kind.radius + K.BOULDER_R && b.y < h.y + h.kind.height + K.BOULDER_R * 0.5f) {
                    b.landX = b.x; b.landZ = b.z
                    b.hit = true
                    b.gone = true
                    marks.add(Mark(b.x, b.z, true))
                    burst(b.x, b.y, b.z, 0xFF77706A.toInt(), 10, 14f)
                    strike(h)
                    continue
                }
            }

            if (b.y <= 0f) {
                b.y = 0f
                b.landed = true
                b.landX = b.x; b.landZ = b.z
                b.vx *= 0.25f; b.vz *= 0.25f
                var struck = false
                if (h != null) {
                    val d = hypot(b.x - h.x, b.z - h.z)
                    if (d < h.kind.radius + K.BOULDER_R * 0.7f + splash) {
                        struck = true
                        b.hit = true
                        strike(h)
                    }
                }
                marks.add(Mark(b.x, b.z, struck))
                if (!struck) {
                    burst(b.x, 0.3f, b.z, 0xFF5C4A3A.toInt(), 12, 10f)
                    smoke(b.x, 0.5f, b.z, 4, 0xFF8D7B68.toInt(), 1.4f)
                    listener.onSound(Sfx.LAND)
                    listener.onVibrate(12, 60)
                }
            }
            if (b.z > K.Z_FAR + 30f || abs(b.x) > K.LANE_HALF * 4f) it.remove()
        }
    }

    private fun strike(h: Hazard) {
        hits++
        h.hp--
        h.flash = 1f
        if (h.hp > 0) {
            // Cracked rock: it slows down.
            h.speed *= 0.7f
            shake = max(shake, 0.3f)
            listener.onSound(Sfx.HIT)
            listener.onVibrate(30, 160)
            burst(h.x, h.kind.height * 0.5f, h.z, 0xFF7B6F63.toInt(), 14, 14f)
            popups.add(Popup("CRACK!", h.x, h.z, 0xFFFFE082.toInt()))
            return
        }

        hazard = null
        resolveOne()

        if (!h.kind.good) {
            smashed++
            streak++
            bestStreak = max(bestStreak, streak)
            val pts = h.kind.points * multiplier
            score += pts
            shield = min(K.SHIELD_MAX, shield + K.SHIELD_PER_KILL)
            shake = max(shake, 0.45f)
            listener.onSound(Sfx.BREAK)
            listener.onVibrate(45, 200)
            val color = when (h.kind) {
                Kind.BARREL -> 0xFF4E5340.toInt()
                Kind.ROCK -> 0xFF6E655B.toInt()
                else -> 0xFF4A3B26.toInt()
            }
            burst(h.x, h.kind.height * 0.5f, h.z, color, 26, 22f)
            if (h.kind == Kind.BARREL) fire(h.x, 2f, h.z, 14)
            smoke(h.x, 1.5f, h.z, 9, if (h.kind == Kind.BARREL) 0xFF3A3632.toInt() else 0xFF8D7B68.toInt(), 2.2f)
            popups.add(Popup("+$pts", h.x, h.z, 0xFFFFFFFF.toInt(), big = true))
            if (streak % 5 == 0) showBanner("STREAK! x$multiplier POINTS")
            return
        }

        when (h.kind) {
            Kind.ENERGY -> {
                shield = min(K.SHIELD_MAX, shield + 30f)
                score += h.kind.points
                popups.add(Popup("+30 SHIELD", h.x, h.z, 0xFF4DD0E1.toInt(), big = true))
                burst(h.x, 2f, h.z, 0xFF4DD0E1.toInt(), 30, 18f)
                listener.onSound(Sfx.PICKUP)
            }
            Kind.UPGRADE -> {
                slingLevel = min(K.MAX_SLING, slingLevel + 1)
                score += h.kind.points
                showBanner("SLING UPGRADED! LV $slingLevel")
                popups.add(Popup("FASTER RELOAD • BIGGER IMPACT", h.x, h.z, 0xFFFFEB3B.toInt()))
                burst(h.x, 2f, h.z, 0xFFFFEB3B.toInt(), 30, 18f)
                listener.onSound(Sfx.UPGRADE)
            }
            else -> {
                val pts = h.kind.points * multiplier
                score += pts
                popups.add(Popup("+$pts GOLD", h.x, h.z, 0xFFFFD54F.toInt(), big = true))
                burst(h.x, 2f, h.z, 0xFFFFD54F.toInt(), 30, 18f)
                listener.onSound(Sfx.PICKUP)
            }
        }
        listener.onVibrate(35, 150)
    }

    private fun burst(x: Float, y: Float, z: Float, color: Int, count: Int, speed: Float) {
        for (i in 0 until count) {
            if (particles.size > 600) return
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val s = speed * (0.3f + rnd.nextFloat() * 0.7f)
            val life = 0.5f + rnd.nextFloat() * 0.6f
            particles.add(
                Particle(
                    x, y, z,
                    cos(a) * s, speed * (0.4f + rnd.nextFloat() * 0.8f), sin(a) * s,
                    life, life, color, 0.2f + rnd.nextFloat() * 0.4f,
                )
            )
        }
    }

    /** Dust or smoke that drifts up and spreads out. */
    private fun smoke(x: Float, y: Float, z: Float, count: Int, color: Int, size: Float) {
        for (i in 0 until count) {
            if (particles.size > 600) return
            val life = 1.2f + rnd.nextFloat() * 1.2f
            particles.add(
                Particle(
                    x + (rnd.nextFloat() - 0.5f) * 2f, y, z + (rnd.nextFloat() - 0.5f) * 2f,
                    (rnd.nextFloat() - 0.5f) * 3f, 2f + rnd.nextFloat() * 3f, (rnd.nextFloat() - 0.5f) * 3f,
                    life, life, color, size * (0.7f + rnd.nextFloat() * 0.6f), Particle.SMOKE,
                )
            )
        }
    }

    /** A short bright flash of flame. */
    private fun fire(x: Float, y: Float, z: Float, count: Int) {
        for (i in 0 until count) {
            if (particles.size > 600) return
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val s = 4f + rnd.nextFloat() * 8f
            val life = 0.25f + rnd.nextFloat() * 0.3f
            particles.add(
                Particle(
                    x, y, z, cos(a) * s, 3f + rnd.nextFloat() * 6f, sin(a) * s,
                    life, life, 0xFFFFA726.toInt(), 0.8f + rnd.nextFloat() * 0.8f, Particle.FIRE,
                )
            )
        }
    }

    private fun updateEffects(dt: Float) {
        val pi = particles.iterator()
        while (pi.hasNext()) {
            val p = pi.next()
            if (p.type == Particle.DEBRIS) p.vy -= K.G * 0.6f * dt else {
                p.vx *= max(0f, 1f - 1.5f * dt)
                p.vz *= max(0f, 1f - 1.5f * dt)
                if (p.type == Particle.SMOKE) p.vy *= max(0f, 1f - 0.6f * dt) else p.vy *= max(0f, 1f - 3f * dt)
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.z += p.vz * dt
            if (p.y < 0f) {
                p.y = 0f
                p.vy *= -0.3f
                p.vx *= 0.6f
                p.vz *= 0.6f
            }
            p.life -= dt
            if (p.life <= 0f) pi.remove()
        }
        val ui = popups.iterator()
        while (ui.hasNext()) {
            val p = ui.next()
            p.t += dt
            if (p.t > 1.3f) ui.remove()
        }
        val mi = marks.iterator()
        while (mi.hasNext()) {
            val m = mi.next()
            m.t += dt
            if (m.t > 2.5f) mi.remove()
        }
    }
}
