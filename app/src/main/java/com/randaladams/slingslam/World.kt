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
    const val MISS_LIFE = 7f          // seconds a missed boulder lies on the field
    const val MAX_ON_GROUND = 40
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
    BOSS(false, 4.0f, 12.8f, 40f, 2000, 3),
}

class Hazard(val kind: Kind, x: Float, var z: Float, var speed: Float, val shape: FloatArray) {
    var x = x
    val baseX = x
    var y = 0f
    var hp = kind.hp
    var maxHp = kind.hp
    var summoning = false         // boss: arms raised, pulling stones in
    var fed = 0                   // boss: stones absorbed
    var radius = kind.radius      // the boss grows, so size lives on the hazard
    var height = kind.height
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
    var rolls = false                     // sling level 5: drops and keeps rolling after a direct hit
    var rollKeep = 0.25f                  // share of speed kept on landing (grows with sling level)
    var friction = 4f                     // how fast it stops rolling (drops with sling level)
    var life = 1.4f                       // seconds on the ground before it fades
    var pulled = false                    // being sucked into the boss
    var pullT = 0f
    var pullDelay = 0f
    var pullDur = 1f
    var pullX0 = 0f
    var pullZ0 = 0f
    val missed get() = struck.isEmpty()
    /** Seconds it lies on the ground: misses linger, boulders that hit something fade once stopped. */
    val groundLife get() = if (missed) K.MISS_LIFE else life
    val struck = HashSet<Hazard>()        // hazards this boulder already hit (no double hits)
    var r = K.BOULDER_R                   // fragments are smaller
    var split = false                     // split shot: bursts into 3 rocks when it hits something
    var fragment = false
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

/**
 * Floating text. Point popups ([value] > 0) hover over the hazard, then fly up to the
 * scoreboard; the view banks the points and plays a tick when each one arrives.
 */
class Popup(
    val text: String, val x: Float, val z: Float, val color: Int,
    val big: Boolean = false, val value: Int = 0,
) {
    var t = 0f
    var arrived = false
    val life get() = if (value > 0) FLY_END + 0.05f else 1.3f

    companion object {
        const val HOVER = 0.45f       // seconds hovering above the hazard
        const val FLY_END = 1.05f     // reaches the scoreboard at this age
    }
}

/** An impact ring left on the ground where a boulder landed. */
class Mark(val x: Float, val z: Float, val hit: Boolean) {
    var t = 0f
}

enum class Sfx { THROW, LAND, HIT, BREAK, HURT, PICKUP, UPGRADE, GAMEOVER, SCORE }

interface WorldListener {
    fun onSound(s: Sfx)
    fun onVibrate(ms: Long, amp: Int)
}

class World(private val listener: WorldListener, seed: Long = System.nanoTime()) {
    companion object {
        /** Rolling resistance after a billiard knock: low, so a hard hit rolls a long way. */
        const val BILLIARD_FRICTION = 0.55f
    }

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

    val hazards = ArrayList<Hazard>()
    var spawnTimer = 1.6f
    private var bossLevel = 0         // level whose boss has already appeared
    val boulders = ArrayList<Boulder>()
    val particles = ArrayList<Particle>()
    val popups = ArrayList<Popup>()
    val marks = ArrayList<Mark>()
    var lastShot: Boulder? = null

    var loaded = true
    var reloadT = 1f      // 0..1 progress of the next boulder rolling off the pile

    var shake = 0f
    var hurtFlash = 0f
    var tutorial = false              // practice mode: no auto spawns, no damage, no levels
    var splitCharges = 0
    var splitArmed = false
    private val pending = ArrayList<Boulder>()
    var gameOver = false
    var banner: String? = null
    var bannerT = 0f

    val reloadDuration get() = 0.95f - 0.11f * (slingLevel - 1)
    val splash get() = 0.5f + 0.3f * (slingLevel - 1)     // extra hit radius when a boulder lands
    val hazardSpeed get() = min(5.5f * (1f + 0.10f * (level - 1)), 30f)
    val spawnDelay get() = max(0.4f, 1.8f - 0.1f * (level - 1))
    val multiplier get() = 1 + streak / 5
    val waveSize get() = when {
        level >= 12 -> 3
        level >= 7 -> 2
        else -> 1
    }
    val boss: Hazard? get() = hazards.firstOrNull { it.kind == Kind.BOSS }

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
        if (splitArmed && splitCharges > 0) {
            b.split = true
            splitCharges--
        }
        splitArmed = false
        // Boulders roll a little at first and farther as the sling is upgraded.
        val lv = (slingLevel - 1).toFloat() / (K.MAX_SLING - 1)
        b.rolls = slingLevel >= K.MAX_SLING
        b.rollKeep = 0.25f + 0.2f * lv
        b.friction = 4f - 2.4f * lv
        b.life = 1.4f + 1.2f * lv
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

        if (hazards.isEmpty() && !tutorial) {
            spawnTimer -= dt
            if (spawnTimer <= 0f) spawnWave()
        } else {
            for (i in hazards.indices.reversed()) {
                if (i < hazards.size) moveHazard(hazards[i], dt)
            }
        }
        updateBoulders(dt)
    }

    /** Every 10th level opens with a boss; otherwise 1, 2 or 3 hazards come together. */
    private fun spawnWave() {
        if (level % 10 == 0 && bossLevel != level) {
            bossLevel = level
            val shape = FloatArray(9) { 0.82f + rnd.nextFloat() * 0.22f }
            val x = (rnd.nextFloat() * 2f - 1f) * 3f
            val boss = Hazard(Kind.BOSS, x, K.Z_FAR + 6f, hazardSpeed * 0.45f, shape)
            // each boss is a little tougher than the last
            boss.hp = Kind.BOSS.hp + (level / 10 - 1)
            boss.maxHp = boss.hp
            hazards.add(boss)
            // every stone left on the field gets sucked into it, one after another
            var k = 0
            for (b in boulders) {
                if (b.landed && !b.gone && !b.pulled && !b.fragment) {
                    startPull(b, 0.8f + k * 0.18f)
                    k++
                }
            }
            showBanner(if (k > 0) "THE BOSS DRAWS IN $k STRAY STONE${if (k > 1) "S" else ""}!" else "BOSS BOULDER!")
            if (k > 0) shake = 0.6f
            return
        }
        val n = waveSize
        val band = (2f * (K.LANE_HALF - 2.5f)) / n
        for (i in 0 until n) {
            val x = -K.LANE_HALF + 2.5f + band * (i + 0.15f + rnd.nextFloat() * 0.7f)
            spawnOne(x, K.Z_FAR + 2f + if (i == 0) 0f else 3f + rnd.nextFloat() * 9f)
        }
    }

    private fun spawnOne(x: Float, z: Float) {
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
        val kindSpeed = when (kind) {
            Kind.ROCK -> 0.82f
            Kind.PLANT -> 0.9f
            Kind.BARREL -> 1f
            else -> 1.05f
        }
        val speed = hazardSpeed * kindSpeed * (0.92f + rnd.nextFloat() * 0.16f)
        val shape = FloatArray(9) { 0.78f + rnd.nextFloat() * 0.3f }
        hazards.add(Hazard(kind, x, z, speed, shape))
    }

    private fun moveHazard(h: Hazard, dt: Float) {
        h.age += dt
        h.flash = max(0f, h.flash - dt * 4f)
        h.z -= h.speed * dt
        when (h.kind) {
            Kind.BARREL -> h.spin += h.speed * dt / h.radius
            Kind.ROCK -> {
                h.spin += dt * h.speed * 0.15f
                h.y = abs(sin(h.age * 5f)) * 0.5f
            }
            Kind.PLANT -> h.x = (h.baseX + sin(h.age * 1.5f) * 3f)
                .coerceIn(-K.LANE_HALF + 2f, K.LANE_HALF - 2f)
            Kind.BOSS -> {
                h.y = abs(sin(h.age * 3f)) * 0.3f
                h.summoning = boulders.any { it.pulled }
                if (h.summoning) {
                    // summoning: holds still, the ground rumbles, dust swirls around it
                    h.z += h.speed * dt
                    shake = max(shake, 0.3f)
                    if (rnd.nextFloat() < dt * 25f) {
                        val a = rnd.nextFloat() * 2f * PI.toFloat()
                        smoke(h.x + cos(a) * h.radius * 1.3f, 0.5f, h.z + sin(a) * h.radius * 0.6f, 1, 0xFF8D7B68.toInt(), 2.5f)
                    }
                }
            }
            else -> {
                h.y = 1.2f + sin(h.age * 3f) * 0.6f
                h.spin += dt * 3f
            }
        }
        if (h.z <= K.Z_REACH) reached(h)
    }

    private fun startPull(b: Boulder, delay: Float) {
        b.pulled = true
        b.pullT = 0f
        b.pullDelay = delay
        b.pullX0 = b.x
        b.pullZ0 = b.z
        b.vx = 0f
        b.vz = 0f
        val bs = boss
        val d = if (bs != null) hypot(bs.x - b.x, bs.z - b.z) else 50f
        b.pullDur = 0.8f + d / 70f
    }

    /** A boulder sucked into the boss makes it bigger and tougher. */
    private fun growBoss(h: Hazard) {
        h.radius = min(9f, h.radius + 0.35f)
        h.height = h.radius * 3.2f
        h.fed++
        if (h.fed % 2 == 0) h.hp = min(15, h.hp + 1)
        h.maxHp = max(h.maxHp, h.hp)
        h.flash = 0.6f
        shake = max(shake, 0.25f)
        popups.add(Popup("BOSS GREW!", h.x, h.z, 0xFFFF8A65.toInt()))
        listener.onSound(Sfx.HIT)
        listener.onVibrate(40, 120)
    }

    /** Testing aid: jump straight to a level. */
    fun debugSetLevel(n: Int) {
        val old = level
        level = n.coerceIn(1, 99)
        for (lv in old + 1..level) if (lv % 5 == 0) grantSplit()
        resolved = (level - 1) * K.HAZARDS_PER_LEVEL
        bossLevel = 0
        hazards.clear()
        spawnTimer = 0.6f
        showBanner("LEVEL $level")
    }

    /** One split shot every 5 levels, holding at most 3. */
    private fun grantSplit() {
        if (splitCharges < 3) {
            splitCharges++
            popups.add(Popup("SPLIT SHOT EARNED!", 0f, 30f, 0xFFF2B451.toInt(), big = true))
            listener.onSound(Sfx.UPGRADE)
        }
    }

    /** Tap the split button: arms (or disarms) the boulder in the sling. */
    fun toggleSplit(): Boolean {
        if (splitCharges <= 0) return false
        splitArmed = !splitArmed
        return true
    }

    fun spawnTutorial(kind: Kind, x: Float, z: Float, speedMul: Float) {
        hazards.add(Hazard(kind, x, z, 5.5f * speedMul, FloatArray(9) { 0.82f + rnd.nextFloat() * 0.22f }))
    }

    private fun reached(h: Hazard) {
        hazards.remove(h)
        if (tutorial) {
            popups.add(Popup("missed — try again", h.x, 3f, 0xFFE6DCC2.toInt(), big = true))
            return
        }
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
        if (tutorial) return
        resolved++
        val newLevel = 1 + resolved / K.HAZARDS_PER_LEVEL
        if (newLevel > level) {
            level = newLevel
            showBanner(if (level % 10 == 9) "LEVEL $level — BOSS NEXT! KEEP THE FIELD CLEAR" else "LEVEL $level")
            if (level % 5 == 0) grantSplit()
        }
        spawnTimer = spawnDelay
        if (level % 10 == 0 && bossLevel != level) showBanner("LEVEL $level — BOSS INCOMING")
    }

    private fun showBanner(s: String) {
        banner = s
        bannerT = 1.6f
    }

    /** Boulders on the ground knock each other around like billiard balls. */
    private fun collideBoulders() {
        val n = boulders.size
        for (i in 0 until n) {
            val a = boulders[i]
            if (!a.landed || a.pulled || a.gone) continue
            for (j in i + 1 until n) {
                val b = boulders[j]
                if (!b.landed || b.pulled || b.gone) continue
                val minD = a.r + b.r
                val dx = b.x - a.x
                val dz = b.z - a.z
                if (abs(dx) > minD || abs(dz) > minD) continue
                val d = hypot(dx, dz)
                if (d >= minD || d < 1e-4f) continue
                val nx = dx / d
                val nz = dz / d
                // push apart
                val push = (minD - d) / 2f
                a.x -= nx * push; a.z -= nz * push
                b.x += nx * push; b.z += nz * push
                // equal masses: swap the velocity along the line between centres
                val u = (a.vx - b.vx) * nx + (a.vz - b.vz) * nz
                if (u > 0f) {
                    knock(a, b, nx, nz, u)
                    if (u > 3f) {
                        burst((a.x + b.x) / 2f, 0.8f, (a.z + b.z) / 2f, 0xFF8A8580.toInt(), 4, 6f)
                        listener.onSound(Sfx.LAND)
                        listener.onVibrate(10, 60)
                    }
                }
            }
        }
    }

    /**
     * Equal-mass elastic hit along the line between centres (like billiard balls).
     * Both roll on low friction afterwards, so a harder hit sends them farther.
     */
    private fun knock(a: Boulder, b: Boulder, nx: Float, nz: Float, u: Float) {
        val j = u * 0.92f
        a.vx -= j * nx; a.vz -= j * nz
        b.vx += j * nx; b.vz += j * nz
        for (q in arrayOf(a, b)) {
            q.friction = min(q.friction, BILLIARD_FRICTION)
            q.rest = min(q.rest, max(0f, q.groundLife - 3f))
        }
    }

    /** Hit areas a little larger than the drawings so edge hits count. */
    private fun hitR(h: Hazard) = h.radius * 1.2f
    private fun hitH(h: Hazard) = h.height * 1.5f

    /** A split shot bursts into three smaller rocks that fan out and can hit other hazards. */
    private fun onHit(b: Boulder, h: Hazard) {
        if (!b.split) return
        b.split = false
        var fx = b.vx
        var fz = b.vz
        val len = hypot(fx, fz)
        if (len < 0.1f) { fx = 0f; fz = 1f } else { fx /= len; fz /= len }
        for (k in -1..1) {
            val a = k * 0.75f
            val dx = fx * cos(a) - fz * sin(a)
            val dz = fx * sin(a) + fz * cos(a)
            val f = Boulder(b.x, max(b.y, 1f), b.z, dx * 15f, 13f, dz * 15f)
            f.r = K.BOULDER_R * 0.6f
            f.fragment = true
            f.life = 1.0f
            f.rollKeep = 0.4f
            f.friction = 3f
            f.struck.add(h)
            pending.add(f)
        }
        burst(b.x, max(b.y, 1f), b.z, 0xFFF2B451.toInt(), 14, 16f)
        listener.onSound(Sfx.BREAK)
    }

    private fun updateBoulders(dt: Float) {
        if (pending.isNotEmpty()) {
            boulders.addAll(pending)
            pending.clear()
        }
        collideBoulders()
        // keep the field from filling up: oldest misses go first
        var onGround = boulders.count { it.landed && !it.pulled }
        if (onGround > K.MAX_ON_GROUND) {
            for (b in boulders) {
                if (onGround <= K.MAX_ON_GROUND) break
                if (b.landed && !b.pulled && !b.gone) { b.gone = true; onGround-- }
            }
        }
        val bossNow = boss
        val it = boulders.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (b.gone) { it.remove(); continue }
            if (b.landed && b.pulled) {
                if (bossNow == null) {
                    b.pulled = false
                } else {
                    b.pullT += dt
                    b.rest = 0f
                    if (b.pullT < b.pullDelay) {
                        // trembling before it lifts off
                        b.y = abs(sin(b.pullT * 40f)) * 0.25f
                        b.pullX0 = b.x
                        b.pullZ0 = b.z
                        continue
                    }
                    val t = ((b.pullT - b.pullDelay) / b.pullDur).coerceIn(0f, 1f)
                    val e = t * t
                    val lift = 5f + hypot(bossNow.x - b.pullX0, bossNow.z - b.pullZ0) * 0.08f
                    b.x = b.pullX0 + (bossNow.x - b.pullX0) * e
                    b.z = b.pullZ0 + (bossNow.z - b.pullZ0) * e
                    b.y = sin(t * PI.toFloat()) * lift + t * bossNow.height * 0.45f
                    b.spin += dt * (6f + 20f * t)
                    if (rnd.nextFloat() < dt * 20f) smoke(b.x, b.y, b.z, 1, 0xFF6F665C.toInt(), 0.9f)
                    if (t >= 1f) {
                        b.gone = true
                        burst(b.x, b.y, b.z, 0xFF77706A.toInt(), 8, 10f)
                        growBoss(bossNow)
                    }
                    continue
                }
            }
            if (b.landed) {
                b.rest += dt
                b.x += b.vx * dt
                b.z += b.vz * dt
                b.vx *= max(0f, 1f - b.friction * dt)
                b.vz *= max(0f, 1f - b.friction * dt)
                val sp = hypot(b.vx, b.vz)
                b.spin += sp * dt * 0.5f
                // a rolling boulder can plough into hazards
                if (sp > 2f) {
                    for (i in hazards.indices.reversed()) {
                        if (i >= hazards.size) continue
                        val h = hazards[i]
                        if (b.struck.contains(h)) continue
                        if (hypot(b.x - h.x, b.z - h.z) < hitR(h) + b.r) {
                            b.struck.add(h)
                            b.hit = true
                            b.vx *= 0.6f; b.vz *= 0.6f
                            marks.add(Mark(b.x, b.z, true))
                            onHit(b, h)
                            strike(h)
                        }
                    }
                }
                // Only misses stay on the field; boulders that hit something fade once they stop.
                val life = b.groundLife
                if (b.missed) {
                    if (bossNow != null && b.z < bossNow.z) {
                        // fell short of the boss: it gets sucked in
                        startPull(b, 0.15f)
                    }
                }
                if (b.rest > life || b.z > K.Z_FAR + 30f || abs(b.x) > K.LANE_HALF * 4f) it.remove()
                continue
            }
            b.vy -= K.G * dt
            b.x += b.vx * dt
            b.y += b.vy * dt
            b.z += b.vz * dt
            b.spin += dt * 6f
            b.trail.add(floatArrayOf(b.x, b.y, b.z))

            // Direct hit, rising or falling (flat throws can hit on the way out).
            var direct: Hazard? = null
            for (h in hazards) {
                if (b.struck.contains(h)) continue
                val d = hypot(b.x - h.x, b.z - h.z)
                if (d < hitR(h) + b.r && b.y < h.y + hitH(h) + b.r * 0.5f) {
                    direct = h
                    break
                }
            }
            if (direct != null) {
                b.landX = b.x; b.landZ = b.z
                b.hit = true
                b.struck.add(direct)
                marks.add(Mark(b.x, b.z, true))
                burst(b.x, b.y, b.z, 0xFF77706A.toInt(), 10, 14f)
                if (b.rolls) {
                    // drops and keeps rolling
                    b.landed = true
                    b.y = 0f
                    b.vy = 0f
                    b.vx *= 0.55f; b.vz *= 0.55f
                } else {
                    b.gone = true
                }
                onHit(b, direct)
                strike(direct)
                continue
            }

            if (b.y <= 0f) {
                b.y = 0f
                b.landed = true
                b.landX = b.x; b.landZ = b.z
                // landing right on a stone hits it with the full force of the throw
                var target: Boulder? = null
                var best = K.BOULDER_R * 2.2f
                for (o in boulders) {
                    if (o === b || !o.landed || o.pulled || o.gone) continue
                    val d = hypot(o.x - b.x, o.z - b.z)
                    if (d < best) { best = d; target = o }
                }
                if (target != null) {
                    val d = max(best, 1e-3f)
                    val nx = (target.x - b.x) / d
                    val nz = (target.z - b.z) / d
                    val u = b.vx * nx + b.vz * nz
                    if (u > 0f) {
                        knock(b, target, nx, nz, u)
                        burst(target.x, 1f, target.z, 0xFF8A8580.toInt(), 8, 9f)
                        listener.onSound(Sfx.HIT)
                        listener.onVibrate(20, 120)
                    }
                }
                b.vx *= b.rollKeep; b.vz *= b.rollKeep
                var struckAny = false
                for (i in hazards.indices.reversed()) {
                    if (i >= hazards.size) continue
                    val h = hazards[i]
                    if (b.struck.contains(h)) continue
                    if (hypot(b.x - h.x, b.z - h.z) < hitR(h) + b.r * 0.7f + splash) {
                        struckAny = true
                        b.hit = true
                        b.struck.add(h)
                        onHit(b, h)
                        strike(h)
                    }
                }
                marks.add(Mark(b.x, b.z, struckAny))
                if (!struckAny) {
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
            if (h.kind == Kind.BOSS) {
                popups.add(Popup("${h.hp} HITS LEFT", h.x, h.z, 0xFFFFE082.toInt()))
            } else {
                // Cracked rock: it slows down.
                h.speed *= 0.7f
                popups.add(Popup("CRACK!", h.x, h.z, 0xFFFFE082.toInt()))
            }
            shake = max(shake, 0.3f)
            listener.onSound(Sfx.HIT)
            listener.onVibrate(30, 160)
            burst(h.x, h.height * 0.5f, h.z, 0xFF7B6F63.toInt(), 14, 14f)
            return
        }

        hazards.remove(h)
        resolveOne()

        if (!h.kind.good) {
            smashed++
            streak++
            bestStreak = max(bestStreak, streak)
            val pts = h.kind.points * multiplier
            score += pts
            shield = min(K.SHIELD_MAX, shield + if (h.kind == Kind.BOSS) 25f else K.SHIELD_PER_KILL)
            shake = max(shake, if (h.kind == Kind.BOSS) 1f else 0.45f)
            if (h.kind == Kind.BOSS) showBanner("BOSS DESTROYED!")
            listener.onSound(Sfx.BREAK)
            listener.onVibrate(45, 200)
            val color = when (h.kind) {
                Kind.BARREL -> 0xFF4E5340.toInt()
                Kind.ROCK, Kind.BOSS -> 0xFF6E655B.toInt()
                else -> 0xFF4A3B26.toInt()
            }
            val big = if (h.kind == Kind.BOSS) 3 else 1
            burst(h.x, h.height * 0.5f, h.z, color, 26 * big, 22f * (1f + (big - 1) * 0.3f))
            if (h.kind == Kind.BARREL) fire(h.x, 2f, h.z, 14)
            smoke(h.x, 1.5f, h.z, 9 * big, if (h.kind == Kind.BARREL) 0xFF3A3632.toInt() else 0xFF8D7B68.toInt(), 2.2f * big)
            popups.add(Popup("+$pts", h.x, h.z, 0xFFFFFFFF.toInt(), big = true, value = pts))
            if (streak % 5 == 0) showBanner("STREAK! x$multiplier POINTS")
            return
        }

        when (h.kind) {
            Kind.ENERGY -> {
                shield = min(K.SHIELD_MAX, shield + 30f)
                score += h.kind.points
                popups.add(Popup("+30 SHIELD", h.x, h.z, 0xFF4DD0E1.toInt(), big = true))
                popups.add(Popup("+${h.kind.points}", h.x, h.z, 0xFFFFFFFF.toInt(), value = h.kind.points))
                burst(h.x, 2f, h.z, 0xFF4DD0E1.toInt(), 30, 18f)
                listener.onSound(Sfx.PICKUP)
            }
            Kind.UPGRADE -> {
                slingLevel = min(K.MAX_SLING, slingLevel + 1)
                score += h.kind.points
                showBanner(if (slingLevel == K.MAX_SLING) "MAX SLING! BOULDERS ROLL" else "SLING UPGRADED! LV $slingLevel")
                popups.add(Popup("+${h.kind.points}", h.x, h.z, 0xFFFFFFFF.toInt(), value = h.kind.points))
                burst(h.x, 2f, h.z, 0xFFFFEB3B.toInt(), 30, 18f)
                listener.onSound(Sfx.UPGRADE)
            }
            else -> {
                val pts = h.kind.points * multiplier
                score += pts
                popups.add(Popup("+$pts", h.x, h.z, 0xFFFFD54F.toInt(), big = true, value = pts))
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
            if (p.t > p.life) ui.remove()
        }
        val mi = marks.iterator()
        while (mi.hasNext()) {
            val m = mi.next()
            m.t += dt
            if (m.t > 2.5f) mi.remove()
        }
    }
}
