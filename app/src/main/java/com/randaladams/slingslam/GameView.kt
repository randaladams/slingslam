package com.randaladams.slingslam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws the game and handles touch.
 *
 * Screen layout, top to bottom:
 *   HUD            score, level, shield bar, pause / sound buttons
 *   Side view      side-on strip: you on the left, the hazard coming from the right, the arc of each shot
 *   Lane view      looking down the battlefield at the incoming hazard, sling and boulder pile in front
 *   Ad strip       kept clear for a banner ad
 */
@SuppressLint("ViewConstructor")
class GameView(context: Context, private val onExit: () -> Unit = {}) : View(context), WorldListener {

    private enum class Mode { TITLE, PLAYING, PAUSED, OVER, SCORES }

    private var mode = Mode.TITLE
    private var world = World(this)
    private val prefs = context.getSharedPreferences("slingslam", Context.MODE_PRIVATE)
    private var best = prefs.getInt("best", 0)
    private var newBest = false
    private var shownScore = 0           // the scoreboard counts up as point popups arrive
    private var lastNs = 0L
    private var overT = 0f
    private var clock = 0f
    private val sfx = SoundFx(context).also { it.enabled = prefs.getBoolean("sound", true) }
    private val art = Sprites()

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    // ---- layout (pixels) ----
    private var w = 1f
    private var h = 1f
    private var topInset = 0f
    private var hudTop = 0f
    private var hudH = 0f
    private var sideTop = 0f
    private var sideBottom = 0f
    private var sideGround = 0f
    private var sidePadL = 0f
    private var sidePadR = 0f
    private var sideScaleY = 1f
    private var horizonY = 0f
    private var groundY = 0f
    private var adTop = 0f
    private var unitPx = 1f
    private var slingX = 0f
    private var slingY = 0f
    private var slingR = 1f
    private var maxPull = 1f
    private var deadPull = 0f
    private var rockPx = 1f
    private var pileX = 0f
    private var pileY = 0f
    private val pauseBtn = RectF()
    private val soundBtn = RectF()
    private val levelDown = RectF()      // TESTING: remove before release
    private val levelUp = RectF()        // TESTING: remove before release
    private val btnA = RectF()           // menu buttons (resume / play again)
    private val btnB = RectF()           // menu buttons (end game / menu / exit)
    private val btnC = RectF()           // menu buttons (scores)
    private val skipBtn = RectF()
    private var splitCx = 0f
    private var splitCy = 0f
    private var splitR = 1f
    private var scoresFrom = Mode.TITLE

    // ---- tutorial ----
    private var tutStep = -1
    private var tutBase = 0
    private var tutSpawned = false
    private var tutWait = 0f

    // ---- pre-painted scenery ----
    private var laneBmp: Bitmap? = null
    private val laneDst = RectF()

    // ---- aiming: power comes from how far you drag from where you first touched ----
    private var aiming = false
    private var pointerId = -1
    private var anchorX = 0f
    private var anchorY = 0f
    private var aimDx = 0f
    private var aimDy = 0f

    // ---- drawing tools ----
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val rect = RectF()
    private var dash: DashPathEffect? = null

    private val heightK = 0.35f      // lane view squashes heights so tall lobs stay on screen
    private val persp = 65f          // perspective depth constant

    // muted military palette
    private val khaki = 0xFFE6DCC2.toInt()
    private val amber = 0xFFF2B451.toInt()
    private val hudBg = 0xFF141618.toInt()

    // ===================================================================== lifecycle

    fun onHostPause() {
        if (mode == Mode.PLAYING) mode = Mode.PAUSED
        aiming = false
        lastNs = 0L
    }

    fun onHostResume() {
        lastNs = 0L
        postInvalidateOnAnimation()
    }

    fun release() = sfx.release()

    /** Returns true if the back press was used (to pause). */
    fun handleBack(): Boolean {
        if (mode == Mode.SCORES) {
            mode = scoresFrom
            return true
        }
        if (mode == Mode.PLAYING) {
            mode = Mode.PAUSED
            aiming = false
            return true
        }
        return false
    }

    private fun startGame() {
        tutStep = -1
        world = World(this)
        mode = Mode.PLAYING
        newBest = false
        shownScore = 0
        aiming = false
        lastNs = 0L
    }

    private fun endGame() {
        if (world.tutorial) {
            tutStep = -1
            mode = Mode.TITLE
            return
        }
        mode = Mode.OVER
        overT = 0f
        aiming = false
        shownScore = world.score
        recordScore(world.score, world.level)
        if (world.score > best) {
            best = world.score
            newBest = true
            prefs.edit().putInt("best", best).apply()
        }
    }

    override fun onSound(s: Sfx) = sfx.play(s)

    override fun onVibrate(ms: Long, amp: Int) {
        val v = vibrator ?: return
        try {
            val a = if (v.hasAmplitudeControl()) amp.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
            v.vibrate(VibrationEffect.createOneShot(ms, a))
        } catch (_: Exception) {
        }
    }

    // ===================================================================== layout

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val cut = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            insets.displayCutout?.safeInsetTop ?: 0
        } else 0
        if (cut.toFloat() != topInset) {
            topInset = cut.toFloat()
            if (width > 0) layoutFor(width, height)
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        super.onSizeChanged(nw, nh, ow, oh)
        layoutFor(nw, nh)
    }

    private fun layoutFor(pw: Int, ph: Int) {
        w = pw.toFloat()
        h = ph.toFloat()
        hudTop = topInset
        hudH = h * 0.072f
        sideTop = hudTop + hudH
        sideBottom = sideTop + h * 0.207f
        sideGround = sideBottom - (sideBottom - sideTop) * 0.16f
        sidePadL = w * 0.09f
        sidePadR = w * 0.04f
        sideScaleY = (sideGround - sideTop - h * 0.02f) / 34f

        adTop = h * (1f - AD_FRACTION)
        horizonY = sideBottom + h * 0.045f

        slingR = min(w * 0.15f, h * 0.07f)
        slingX = w / 2f
        slingY = h * 0.775f
        groundY = slingY - slingR * 1.3f
        maxPull = min(slingR * 2.1f, h - slingY - h * 0.01f)
        deadPull = slingR * 0.1f
        rockPx = slingR * 0.42f
        splitR = slingR * 0.5f
        splitCx = slingX - slingR * 2.4f
        splitCy = slingY + slingR * 0.05f
        pileX = w * 0.86f
        pileY = slingY + slingR * 0.8f
        unitPx = (w * 0.47f) / K.LANE_HALF

        val b = hudH * 0.62f
        pauseBtn.set(w - b - w * 0.03f, hudTop + hudH * 0.06f, w - w * 0.03f, hudTop + hudH * 0.06f + b)
        soundBtn.set(pauseBtn.left - b - w * 0.025f, pauseBtn.top, pauseBtn.left - w * 0.025f, pauseBtn.bottom)
        val lb = hudH * 0.42f
        val lcy = hudTop + hudH * 0.32f
        levelDown.set(w * 0.5f - w * 0.17f - lb, lcy - lb / 2f, w * 0.5f - w * 0.17f, lcy + lb / 2f)
        levelUp.set(w * 0.5f + w * 0.17f, lcy - lb / 2f, w * 0.5f + w * 0.17f + lb, lcy + lb / 2f)

        val d = w * 0.012f
        dash = DashPathEffect(floatArrayOf(d * 1.6f, d), 0f)

        // Paint the scenery once, at half resolution, and scale it up with filtering.
        // The battlefield is painted all the way up under the (see-through) side view.
        val bd = Backdrop(w, h, sideTop, adTop, horizonY, groundY, unitPx, persp)
        val (lanePx, ls) = bd.paintLane(2)
        laneBmp = Bitmap.createBitmap(lanePx, ls[0], ls[1], Bitmap.Config.ARGB_8888)
        laneDst.set(0f, sideTop, w, sideTop + ls[1] * 2f)
    }

    // ---- lane-view projection ----
    private fun sAt(z: Float) = persp / (persp + max(z, -persp * 0.6f))
    private val sFar get() = persp / (persp + K.Z_FAR)
    private fun ly(z: Float, y: Float = 0f): Float {
        val s = sAt(z)
        val t = (s - sFar) / (1f - sFar)
        return horizonY + (groundY - horizonY) * t - y * unitPx * s * heightK
    }
    private fun lx(x: Float, z: Float) = w / 2f + x * unitPx * sAt(z)

    // ---- side-view projection ----
    private fun sx(z: Float) = sidePadL + z / K.Z_FAR * (w - sidePadL - sidePadR)
    private fun sy(y: Float) = sideGround - y * sideScaleY

    // ===================================================================== loop

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else min((now - lastNs) / 1e9f, 0.05f)
        lastNs = now
        clock += dt

        when (mode) {
            Mode.PLAYING -> {
                world.update(dt)
                if (tutStep >= 0) tutorialTick(dt)
                if (world.gameOver) endGame()
            }
            Mode.OVER -> {
                world.update(dt)   // lets the last debris settle
                overT += dt
            }
            else -> {}
        }

        render(c)
        postInvalidateOnAnimation()
    }

    // ===================================================================== input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                if (inflate(soundBtn).contains(x, y)) {
                    sfx.enabled = !sfx.enabled
                    prefs.edit().putBoolean("sound", sfx.enabled).apply()
                    return true
                }
                when (mode) {
                    Mode.TITLE -> if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                        layoutMenu(Mode.TITLE)
                        when {
                            btnB.contains(x, y) -> onExit()
                            btnA.contains(x, y) -> startTutorial()
                            btnC.contains(x, y) -> { scoresFrom = Mode.TITLE; mode = Mode.SCORES }
                            !prefs.getBoolean("tutorialDone", false) -> startTutorial()
                            else -> startGame()
                        }
                    }
                    Mode.OVER -> if (overT > 0.9f && e.actionMasked == MotionEvent.ACTION_DOWN) {
                        layoutMenu(Mode.OVER)
                        if (btnA.contains(x, y)) startGame()
                        else if (btnC.contains(x, y)) { scoresFrom = Mode.OVER; mode = Mode.SCORES }
                        else if (btnB.contains(x, y)) mode = Mode.TITLE
                    }
                    Mode.SCORES -> if (e.actionMasked == MotionEvent.ACTION_DOWN) mode = scoresFrom
                    Mode.PAUSED -> if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                        layoutMenu(Mode.PAUSED)
                        if (btnA.contains(x, y)) {
                            mode = Mode.PLAYING
                            lastNs = 0L
                        } else if (btnB.contains(x, y)) {
                            world.gameOver = true
                            endGame()
                        }
                    }
                    Mode.PLAYING -> {
                        if (tutStep >= 0 && skipBtn.contains(x, y)) {
                            finishTutorial()
                        } else if (tutStep >= 0 && tutIsTapStep() && !inflate(pauseBtn).contains(x, y)) {
                            if (e.actionMasked == MotionEvent.ACTION_DOWN) advanceTutorial()
                        } else if (tutStep < 0 && inflate(levelDown).contains(x, y) && y < sideTop) {
                            world.debugSetLevel(world.level - 1)
                        } else if (tutStep < 0 && inflate(levelUp).contains(x, y) && y < sideTop) {
                            world.debugSetLevel(world.level + 1)
                        } else if (inflate(pauseBtn).contains(x, y)) {
                            mode = Mode.PAUSED
                            aiming = false
                        } else if (hypot(x - splitCx, y - splitCy) < splitR * 1.35f) {
                            if (world.toggleSplit()) {
                                sfx.play(Sfx.PICKUP)
                            } else {
                                world.popups.add(Popup("no split shots — earn one every 5 levels", 0f, 6f, khaki))
                            }
                        } else if (!aiming && hypot(x - slingX, y - slingY) < slingR * 1.8f) {
                            aiming = true
                            pointerId = e.getPointerId(i)
                            anchorX = x
                            anchorY = y
                            aimDx = 0f
                            aimDy = 0f
                        }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if (aiming) {
                val i = e.findPointerIndex(pointerId)
                if (i >= 0) setAim(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (aiming && e.getPointerId(e.actionIndex) == pointerId) {
                    val i = e.actionIndex
                    setAim(e.getX(i), e.getY(i))
                    releaseShot()
                }
            }
            MotionEvent.ACTION_CANCEL -> aiming = false
        }
        return true
    }

    private fun inflate(r: RectF): RectF {
        val p = r.width() * 0.35f
        rect.set(r.left - p, r.top - p, r.right + p, r.bottom + p)
        return rect
    }

    private fun setAim(x: Float, y: Float) {
        var dx = x - anchorX
        var dy = y - anchorY
        val len = hypot(dx, dy)
        if (len > maxPull) {
            dx *= maxPull / len
            dy *= maxPull / len
        }
        aimDx = dx
        aimDy = dy
    }

    /** 0..1 from how far you've pulled back (down). A small dead zone ignores finger jitter. */
    private fun power(): Float {
        if (aimDy <= 0f) return 0f
        return ((hypot(aimDx, aimDy) - deadPull) / (maxPull - deadPull)).coerceIn(0f, 1f)
    }

    /** Pulling down-left aims right, like a real sling. Halved so small finger moves give fine control. */
    private fun aimAngle(): Float {
        val maxA = (K.MAX_AIM_DEG * PI / 180.0).toFloat()
        return (atan2(-aimDx, max(aimDy, 1f)) * 0.5f).coerceIn(-maxA, maxA)
    }

    private fun releaseShot() {
        aiming = false
        // Drag back to where you started (or push forward) to cancel.
        if (aimDy <= 0f || hypot(aimDx, aimDy) < deadPull) return
        if (!world.throwBoulder(power(), aimAngle())) {
            world.popups.add(Popup("reloading…", 0f, 6f, khaki))
        }
    }

    // ===================================================================== render

    private fun render(c: Canvas) {
        c.drawColor(hudBg)
        c.save()
        val sh = world.shake
        if (sh > 0f) c.translate(sin(clock * 90f) * sh * w * 0.012f, cos(clock * 77f) * sh * w * 0.01f)

        c.save()
        c.clipRect(0f, sideTop, w, adTop)
        drawLane(c)
        drawSling(c)
        drawPile(c)
        drawSplitButton(c)
        drawLaneHud(c)
        c.restore()

        drawSideView(c)
        c.restore()

        drawAdStrip(c)
        drawHud(c)
        drawFlyingPoints(c)

        if (world.hurtFlash > 0f) {
            paint.shader = null
            paint.color = Color.argb((world.hurtFlash * 100).toInt(), 200, 30, 20)
            c.drawRect(0f, 0f, w, h, paint)
        }

        when (mode) {
            Mode.TITLE -> drawTitle(c)
            Mode.SCORES -> drawScores(c)
            Mode.PLAYING -> if (tutStep >= 0) drawTutorial(c)
            Mode.PAUSED -> drawPaused(c)
            Mode.OVER -> drawGameOver(c)
            else -> {}
        }
    }

    private fun drawAdStrip(c: Canvas) {
        paint.shader = null
        paint.color = 0xFF0C0D0E.toInt()
        c.drawRect(0f, adTop, w, h, paint)
    }

    // ---------------------------------------------------------------- lane view

    private fun drawLane(c: Canvas) {
        val bmp = laneBmp
        if (bmp != null) c.drawBitmap(bmp, null, laneDst, bmpPaint)

        drawPlumes(c)

        val L = K.LANE_HALF

        // wire fence along each side of the lane, with range markers
        text.textAlign = Paint.Align.CENTER
        for (zi in 10 downTo 0) {
            val z = zi * 10f
            val s = sAt(z)
            for (side in intArrayOf(-1, 1)) {
                val x = side * (L + 0.8f)
                val sxp = lx(x, z)
                val gy = ly(z)
                val ph = unitPx * s * 2.6f
                art.stake(c, sxp, gy, ph, max(2f, unitPx * s * 0.3f))
                if (zi < 10) {
                    val z2 = z + 10f
                    val s2 = sAt(z2)
                    val ph2 = unitPx * s2 * 2.6f
                    art.wire(c, sxp, gy - ph * 0.8f, lx(x, z2), ly(z2) - ph2 * 0.8f, unitPx * s * 0.4f, unitPx * s * 0.07f)
                    art.wire(c, sxp, gy - ph * 0.4f, lx(x, z2), ly(z2) - ph2 * 0.4f, unitPx * s * 0.3f, unitPx * s * 0.07f)
                }
                if (side == 1 && zi % 2 == 0 && zi > 0) {
                    text.textSize = max(w * 0.022f, w * 0.045f * s)
                    text.color = Color.argb(200, 230, 220, 194)
                    outlinedText(c, "${zi * 10}", sxp + text.textSize * 1.1f, gy - ph * 0.3f)
                }
            }
        }

        // impact marks
        for (m in world.marks) {
            val a = (1f - m.t / 2.5f).coerceIn(0f, 1f)
            val s = sAt(m.z)
            val rr = (K.BOULDER_R + world.splash) * unitPx * s
            rect.set(lx(m.x, m.z) - rr, ly(m.z) - rr * 0.32f, lx(m.x, m.z) + rr, ly(m.z) + rr * 0.32f)
            paint.shader = null
            paint.color = Color.argb((a * 120).toInt(), 20, 14, 8)
            c.drawOval(rect, paint)
            if (m.hit) {
                stroke.pathEffect = null
                stroke.color = Color.argb((a * 200).toInt(), 255, 190, 90)
                stroke.strokeWidth = max(2f, unitPx * s * 0.25f)
                c.drawOval(rect, stroke)
            }
        }

        // shadows
        paint.shader = null
        paint.color = Color.argb(90, 0, 0, 0)
        for (hz in world.hazards) {
            val s = sAt(hz.z)
            val r = hz.radius * unitPx * s
            rect.set(lx(hz.x, hz.z) - r * 1.2f, ly(hz.z) - r * 0.28f, lx(hz.x, hz.z) + r * 1.2f, ly(hz.z) + r * 0.28f)
            c.drawOval(rect, paint)
        }
        for (b in world.boulders) {
            val s = sAt(b.z)
            val r = b.r * unitPx * s * (1f / (1f + b.y * 0.03f))
            rect.set(lx(b.x, b.z) - r, ly(b.z) - r * 0.32f, lx(b.x, b.z) + r, ly(b.z) + r * 0.32f)
            c.drawOval(rect, paint)
        }

        // objects, far to near
        val items = ArrayList<Any>(world.boulders.size + world.particles.size + world.hazards.size)
        items.addAll(world.hazards)
        items.addAll(world.boulders)
        items.addAll(world.particles)
        items.sortByDescending {
            when (it) {
                is Hazard -> it.z
                is Boulder -> it.z
                is Particle -> it.z
                else -> 0f
            }
        }
        for (it in items) {
            when (it) {
                is Hazard -> {
                    val s = sAt(it.z)
                    art.hazard(c, it, lx(it.x, it.z), ly(it.z, it.y), unitPx * s, false, clock)
                    if (it.kind == Kind.BOSS) drawBossHealth(c, it, lx(it.x, it.z), ly(it.z, it.y) - it.radius * 3.75f * unitPx * s - unitPx * s, unitPx * s)
                }
                is Boulder -> {
                    val s = sAt(it.z)
                    val r = it.r * unitPx * s
                    if (it.split) {
                        stroke.pathEffect = null
                        stroke.color = Color.argb((160 + 90 * sin(clock * 12f)).toInt(), 242, 180, 81)
                        stroke.strokeWidth = max(2f, r * 0.25f)
                        c.drawCircle(lx(it.x, it.z), ly(it.z, it.y) - r, r * 1.35f, stroke)
                    }
                    val a = if (it.landed) ((it.groundLife - it.rest) / 0.5f).coerceIn(0f, 1f) else 1f
                    art.boulder(c, lx(it.x, it.z), ly(it.z, it.y) - r, r, it.spin, a)
                }
                is Particle -> drawParticle(c, it, lx(it.x, it.z), ly(it.z, it.y), unitPx * sAt(it.z))
            }
        }

        // your sandbag wall, with the shield shimmering over it
        drawSandbags(c)

        // floating score text
        for (p in world.popups) {
            if (p.value > 0) continue   // point popups are drawn on top, flying to the scoreboard
            val a = (1f - p.t / 1.3f).coerceIn(0f, 1f)
            val s = sAt(p.z)
            text.textSize = (if (p.big) w * 0.06f else w * 0.04f) * (0.6f + 0.4f * s)
            text.color = p.color
            text.alpha = (a * 255).toInt()
            val px = lx(p.x, p.z).coerceIn(w * 0.2f, w * 0.8f)
            val py = ly(p.z, 8f) - p.t * h * 0.05f
            outlinedText(c, p.text, px, py)
            text.alpha = 255
        }

        // banner
        val bn = world.banner
        if (bn != null && world.bannerT > 0f) {
            val a = min(1f, world.bannerT / 0.4f)
            text.textSize = w * 0.07f
            val tw = text.measureText(bn)
            if (tw > w * 0.92f) text.textSize *= w * 0.92f / tw
            text.color = amber
            text.alpha = (a * 255).toInt()
            outlinedText(c, bn, w / 2f, horizonY + (groundY - horizonY) * 0.3f)
            text.alpha = 255
        }
    }

    /** Distant columns of smoke rising from burning ruins. */
    private fun drawPlumes(c: Canvas) {
        paint.shader = null
        val bases = floatArrayOf(0.14f, 0.63f, 0.9f)
        for ((k, bx) in bases.withIndex()) {
            val x0 = w * bx
            for (i in 0 until 9) {
                val t = ((clock * 0.06f + i / 9f + k * 0.37f) % 1f)
                val rise = t * (horizonY - sideBottom) * 1.1f
                val x = x0 + t * w * 0.06f + sin(t * 6f + k) * w * 0.01f
                val y = horizonY - h * 0.01f - rise
                val r = w * (0.012f + t * 0.05f) * (1f - k * 0.2f)
                val a = (90 * (1f - t) * (if (t < 0.1f) t / 0.1f else 1f)).toInt()
                paint.color = Color.argb(a, 52, 50, 48)
                c.drawCircle(x, y, r, paint)
            }
            // glow at the base
            paint.color = Color.argb((50 + 25 * sin(clock * 3f + k)).toInt(), 230, 120, 50)
            c.drawCircle(x0, horizonY - h * 0.004f, w * 0.015f, paint)
        }
    }

    private fun drawParticle(c: Canvas, p: Particle, x: Float, y: Float, u: Float) {
        val life = (p.life / p.maxLife).coerceIn(0f, 1f)
        paint.shader = null
        when (p.type) {
            Particle.SMOKE -> {
                val grow = 1f + 2.2f * (1f - life)
                paint.color = p.color
                paint.alpha = (140 * life).toInt()
                c.drawCircle(x, y, max(2f, p.size * u * grow), paint)
            }
            Particle.FIRE -> {
                paint.color = Color.argb((220 * life).toInt(), 255, (140 + 100 * life).toInt(), 40)
                c.drawCircle(x, y, max(2f, p.size * u * (0.6f + life)), paint)
                paint.color = Color.argb((200 * life).toInt(), 255, 240, 180)
                c.drawCircle(x, y, max(1f, p.size * u * 0.4f * life), paint)
            }
            else -> {
                paint.color = p.color
                paint.alpha = (255 * min(1f, life * 2f)).toInt()
                c.drawCircle(x, y, max(1.5f, p.size * u), paint)
            }
        }
        paint.alpha = 255
    }

    /** Segmented health bar floating over the boss. */
    private fun drawBossHealth(c: Canvas, hz: Hazard, cx: Float, cy: Float, u: Float) {
        val n = hz.maxHp
        val segW = max(4f, u * 0.9f)
        val gap = segW * 0.25f
        val total = n * segW + (n - 1) * gap
        var x = cx - total / 2f
        val hh = max(3f, u * 0.35f)
        paint.shader = null
        for (i in 0 until n) {
            paint.color = if (i < hz.hp) 0xFFD2452F.toInt() else 0x66000000
            c.drawRect(x, cy - hh, x + segW, cy, paint)
            x += segW + gap
        }
    }

    /**
     * Point popups hover over what you smashed, then shoot up to the score and fade.
     * Each one adds to the scoreboard (with a tick) when it gets there.
     */
    private fun drawFlyingPoints(c: Canvas) {
        val targetX = w * 0.04f + w * 0.05f
        val targetY = hudTop + hudH * 0.32f
        text.textAlign = Paint.Align.CENTER
        for (p in world.popups) {
            if (p.value <= 0) continue
            val s = sAt(p.z)
            val startX = lx(p.x, p.z).coerceIn(w * 0.15f, w * 0.85f)
            val startY = ly(p.z, 8f)
            val x: Float
            val y: Float
            val a: Float
            val size: Float
            val base = w * 0.06f * (0.6f + 0.4f * s)
            if (p.t < Popup.HOVER) {
                val k = p.t / Popup.HOVER
                x = startX
                y = startY - k * h * 0.025f
                a = 1f
                size = base * (0.8f + 0.2f * min(1f, k * 3f))
            } else {
                val k = ((p.t - Popup.HOVER) / (Popup.FLY_END - Popup.HOVER)).coerceIn(0f, 1f)
                val e = k * k
                val fromY = startY - h * 0.025f
                x = startX + (targetX - startX) * e
                y = fromY + (targetY - fromY) * e
                a = 1f - 0.75f * k
                size = base * (1f - 0.55f * e)
            }
            if (!p.arrived && p.t >= Popup.FLY_END) {
                p.arrived = true
                shownScore = min(world.score, shownScore + p.value)
                sfx.play(Sfx.SCORE)
            }
            if (p.arrived) continue
            text.textSize = size
            text.color = p.color
            text.alpha = (a * 255).toInt()
            outlinedText(c, p.text, x, y)
            text.alpha = 255
        }
        // never let the board lag behind once popups are gone
        if (world.popups.none { it.value > 0 && !it.arrived } && shownScore != world.score && mode != Mode.PLAYING) {
            shownScore = world.score
        }
    }

    private fun drawSandbags(c: Canvas) {
        val L = K.LANE_HALF + 2f
        val s = sAt(-1f)
        val bagW = unitPx * s * 1.25f
        val bagH = unitPx * s * 0.6f
        val y0 = ly(-1f)
        val x0 = lx(-L, -1f)
        val x1 = lx(L, -1f)
        var row = 0
        while (row < 3) {
            val yy = y0 - row * bagH * 1.45f
            var x = x0 + (if (row % 2 == 1) bagW else 0f)
            var k = 0
            while (x < x1) {
                art.sandbag(c, x, yy, bagW, bagH, 0.85f + 0.15f * ((k * 7 + row * 3) % 5) / 4f)
                x += bagW * 2f
                k++
            }
            row++
        }
        // shield shimmer
        val sf = world.shield / K.SHIELD_MAX
        val pulse = 0.7f + 0.3f * sin(clock * 3f)
        val top = y0 - bagH * 1.45f * 3f - unitPx * s * 0.6f
        paint.color = Color.BLACK // opaque so the shader is not faded
        paint.shader = LinearGradient(
            0f, top, 0f, y0,
            Color.argb(0, 120, 190, 255), Color.argb((70 * sf * pulse).toInt() + 10, 120, 190, 255),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(x0, top, x1, y0, paint)
        paint.shader = null
        stroke.pathEffect = null
        stroke.color = Color.argb((60 + 120 * sf * pulse).toInt(), (120 + 135 * (1 - sf)).toInt(), (190 * sf + 60).toInt(), 255)
        stroke.strokeWidth = w * 0.004f
        c.drawLine(x0, top, x1, top, stroke)
    }

    private fun outlinedText(c: Canvas, s: String, x: Float, y: Float) {
        val col = text.color
        val alpha = text.alpha
        text.style = Paint.Style.STROKE
        text.strokeWidth = text.textSize * 0.14f
        text.color = Color.BLACK
        text.alpha = (alpha * 0.7f).toInt()
        c.drawText(s, x, y, text)
        text.style = Paint.Style.FILL
        text.color = col
        text.alpha = alpha
        c.drawText(s, x, y, text)
    }

    // ---------------------------------------------------------------- sling + pile

    private fun drawSling(c: Canvas) {
        val postL = slingX - slingR * 1.3f
        val postR = slingX + slingR * 1.3f
        val postTop = slingY - slingR * 1.0f
        val postW = slingR * 0.26f

        // earth mound the sling stands on
        paint.shader = null
        paint.color = Color.argb(110, 20, 14, 8)
        rect.set(slingX - slingR * 1.9f, slingY + slingR * 0.55f, slingX + slingR * 1.9f, slingY + slingR * 1.05f)
        c.drawOval(rect, paint)

        art.post(c, postL, postTop, slingY + slingR * 0.8f, postW)
        art.post(c, postR, postTop, slingY + slingR * 0.8f, postW)

        // the circle you put your finger in
        val ready = world.loaded && mode == Mode.PLAYING
        paint.color = Color.argb(if (aiming) 30 else 55, 0, 0, 0)
        c.drawCircle(slingX, slingY, slingR, paint)
        stroke.pathEffect = null
        stroke.color = if (ready) Color.argb((140 + 60 * sin(clock * 4f)).toInt(), 230, 220, 194) else Color.argb(70, 230, 220, 194)
        stroke.strokeWidth = slingR * 0.05f
        c.drawCircle(slingX, slingY, slingR, stroke)

        val p = if (aiming) power() else 0f
        if (aiming && p > 0f) {
            // aim direction on the ground (direction only — the side view tells you distance)
            val a = aimAngle()
            stroke.pathEffect = dash
            stroke.color = Color.argb(140, 230, 220, 194)
            stroke.strokeWidth = w * 0.006f
            path.reset()
            path.moveTo(lx(0f, 0f), ly(0f))
            for (k in 1..8) {
                val d = k * 2.5f
                path.lineTo(lx(sin(a) * d, cos(a) * d), ly(cos(a) * d))
            }
            c.drawPath(path, stroke)
            stroke.pathEffect = null

            // power ring
            val col = when {
                p < 0.5f -> Color.rgb((120 + 270 * p).toInt().coerceIn(0, 255), 200, 90)
                else -> Color.rgb(240, (200 * (1f - (p - 0.5f) * 2)).toInt().coerceIn(0, 255), 60)
            }
            stroke.color = col
            stroke.strokeWidth = slingR * 0.13f
            rect.set(slingX - slingR * 1.18f, slingY - slingR * 1.18f, slingX + slingR * 1.18f, slingY + slingR * 1.18f)
            c.drawArc(rect, -90f, 360f * p, false, stroke)
            text.textSize = slingR * 0.3f
            text.color = khaki
            text.textAlign = Paint.Align.CENTER
            outlinedText(c, "${(p * 100).roundToInt()}%", slingX, slingY - slingR * 1.36f)
        }

        val bx = if (aiming) slingX + aimDx else slingX
        val by = if (aiming) slingY + aimDy else slingY
        stroke.color = 0xFF3A2C1E.toInt()
        stroke.strokeWidth = slingR * 0.08f
        if (world.loaded) {
            c.drawLine(postL, postTop + postW * 0.5f, bx - rockPx * 0.8f, by, stroke)
            c.drawLine(postR, postTop + postW * 0.5f, bx + rockPx * 0.8f, by, stroke)
            // leather pouch
            paint.color = 0xFF3B2A1C.toInt()
            rect.set(bx - rockPx * 1.05f, by - rockPx * 0.2f, bx + rockPx * 1.05f, by + rockPx * 0.75f)
            c.drawOval(rect, paint)
            art.boulder(c, bx, by - rockPx * 0.1f, rockPx, 0.3f, 1f)
            if (world.splitArmed) {
                stroke.pathEffect = null
                stroke.color = Color.argb((170 + 85 * sin(clock * 10f)).toInt(), 242, 180, 81)
                stroke.strokeWidth = rockPx * 0.18f
                c.drawCircle(bx, by - rockPx * 0.1f, rockPx * 1.35f, stroke)
            }
        } else {
            c.drawLine(postL, postTop + postW * 0.5f, slingX, slingY + rockPx * 0.3f, stroke)
            c.drawLine(postR, postTop + postW * 0.5f, slingX, slingY + rockPx * 0.3f, stroke)
            paint.color = 0xFF3B2A1C.toInt()
            rect.set(slingX - rockPx * 0.8f, slingY, slingX + rockPx * 0.8f, slingY + rockPx * 0.6f)
            c.drawOval(rect, paint)
        }
    }

    private fun drawPile(c: Canvas) {
        val r = rockPx * 0.95f
        val base = pileY - r
        paint.shader = null
        paint.color = Color.argb(100, 0, 0, 0)
        rect.set(pileX - r * 3.2f, pileY - r * 0.35f, pileX + r * 3.2f, pileY + r * 0.35f)
        c.drawOval(rect, paint)
        for (i in 0..2) art.boulder(c, pileX + (i - 1) * r * 1.9f, base, r, i * 1.3f, 1f)
        for (i in 0..1) art.boulder(c, pileX + (i - 0.5f) * r * 1.9f, base - r * 1.65f, r, i * 2.1f + 0.5f, 1f)
        val topX = pileX
        val topY = base - r * 3.3f
        if (world.loaded) {
            art.boulder(c, topX, topY, r, 0.7f, 1f)
        } else {
            val t = world.reloadT
            val e = t * t * (3f - 2f * t)
            val x = topX + (slingX - topX) * e
            val y = topY + (slingY - topY) * e - sin(t * PI.toFloat()) * r * 1.2f
            art.boulder(c, x, y, r + (rockPx - r) * e, -e * 9f, 1f)
            if (t > 0.6f) art.boulder(c, topX, topY - (1f - t) * r * 2f, r, 2f, (t - 0.6f) / 0.4f)
        }
    }

    private fun drawLaneHud(c: Canvas) {
        val x0 = w * 0.04f
        val y0 = slingY + slingR * 0.95f
        text.textAlign = Paint.Align.LEFT
        text.textSize = w * 0.032f
        text.color = khaki
        outlinedText(c, "SLING", x0, y0)
        val pip = w * 0.016f
        for (i in 0 until K.MAX_SLING) {
            paint.shader = null
            paint.color = if (i < world.slingLevel) amber else 0x55E6DCC2
            c.drawCircle(x0 + pip + i * pip * 2.6f, y0 + pip * 2.2f, pip, paint)
        }
        if (world.streak > 0) {
            text.textSize = w * 0.034f
            text.color = amber
            outlinedText(c, "STREAK ${world.streak}", x0, y0 + pip * 6f)
            if (world.multiplier > 1) outlinedText(c, "x${world.multiplier} POINTS", x0, y0 + pip * 8.6f)
        }
        text.textAlign = Paint.Align.CENTER
    }

    // ---------------------------------------------------------------- side view

    private fun drawSideView(c: Canvas) {
        c.save()
        c.clipRect(0f, sideTop, w, sideBottom)
        // see-through: a light tint over the battlefield, a little darker behind the ground line and labels
        paint.shader = LinearGradient(0f, sideTop, 0f, sideGround, Color.argb(70, 10, 12, 16), Color.argb(25, 10, 12, 16), Shader.TileMode.CLAMP)
        c.drawRect(0f, sideTop, w, sideGround, paint)
        paint.shader = null
        paint.color = Color.argb(110, 20, 16, 12)
        c.drawRect(0f, sideGround, w, sideBottom, paint)
        paint.color = Color.argb(150, 94, 139, 50)
        c.drawRect(0f, sideGround - h * 0.0015f, w, sideGround + h * 0.0015f, paint)

        // range ticks
        text.textAlign = Paint.Align.CENTER
        text.textSize = w * 0.026f
        stroke.pathEffect = null
        for (zi in 0..10) {
            val x = sx(zi * 10f)
            stroke.color = Color.argb(if (zi % 2 == 0) 150 else 70, 230, 220, 194)
            stroke.strokeWidth = 2f
            c.drawLine(x, sideGround, x, sideGround + h * (if (zi % 2 == 0) 0.012f else 0.007f), stroke)
            if (zi % 2 == 0 && zi > 0) {
                text.color = Color.argb(190, 230, 220, 194)
                c.drawText("${zi * 10}m", x, sideBottom - h * 0.008f, text)
            }
        }

        // you: sling frame behind sandbags, with the shield in front
        val px = sx(0f)
        art.post(c, px - w * 0.035f, sideGround - sideScaleY * 4f, sideGround, w * 0.014f)
        val bagW = w * 0.018f
        val bagH = sideScaleY * 0.45f
        for (k in 0..2) art.sandbag(c, px - w * 0.008f, sideGround - bagH - k * bagH * 1.8f, bagW, bagH, 0.9f)
        val sf = world.shield / K.SHIELD_MAX
        stroke.color = Color.argb((80 + 130 * sf).toInt(), (120 + 135 * (1 - sf)).toInt(), (190 * sf + 60).toInt(), 255)
        stroke.strokeWidth = w * 0.006f
        c.drawLine(px + w * 0.014f, sideGround, px + w * 0.014f, sideGround - sideScaleY * 6.5f, stroke)

        // hazard (drawn a bit larger than true scale so it reads at this size)
        for (hz in world.hazards) {
            art.hazard(c, hz, sx(hz.z), sy(hz.y), sideScaleY * (if (hz.kind == Kind.BOSS) 0.75f else 1.5f), true, clock)
        }

        // the last shot's arc and where it came down
        val last = world.lastShot
        if (last != null) drawShotArc(c, last, true)
        for (b in world.boulders) if (b !== last && !b.landed) drawShotArc(c, b, false)

        for (b in world.boulders) {
            if (b.landed && b.rest > b.groundLife - 0.4f) continue
            val r = b.r * sideScaleY * 1.3f
            art.boulder(c, sx(b.z), sy(b.y) - r, r, b.spin, 1f)
        }

        for (p in world.particles) {
            val a = (p.life / p.maxLife).coerceIn(0f, 1f)
            paint.shader = null
            paint.color = if (p.type == Particle.FIRE) 0xFFFFA726.toInt() else p.color
            paint.alpha = (a * (if (p.type == Particle.SMOKE) 110 else 220)).toInt()
            val grow = if (p.type == Particle.SMOKE) 1f + 2f * (1f - a) else 1f
            c.drawCircle(sx(p.z), sy(p.y), max(1.5f, p.size * sideScaleY * 0.6f * grow), paint)
        }
        paint.alpha = 255

        text.textAlign = Paint.Align.LEFT
        text.textSize = w * 0.024f
        text.color = Color.argb(150, 230, 220, 194)
        c.drawText("SIDE VIEW", w * 0.02f, sideTop + text.textSize * 1.3f, text)
        text.textAlign = Paint.Align.CENTER
        c.restore()

        paint.shader = null
        paint.color = Color.argb(60, 230, 220, 194)
        c.drawRect(0f, sideBottom - h * 0.001f, w, sideBottom + h * 0.001f, paint)
    }

    private fun drawShotArc(c: Canvas, b: Boulder, isLast: Boolean) {
        val tr = b.trail
        if (tr.size < 2) return
        path.reset()
        path.moveTo(sx(tr[0][2]), sy(tr[0][1]))
        var i = 1
        while (i < tr.size) {
            path.lineTo(sx(tr[i][2]), sy(tr[i][1]))
            i += 2
        }
        val p = tr[tr.size - 1]
        path.lineTo(sx(p[2]), sy(p[1]))
        val done = b.landed || b.gone
        stroke.pathEffect = if (done) dash else null
        stroke.color = when {
            !done -> Color.argb(230, 240, 232, 210)
            b.hit -> Color.argb(210, 242, 180, 81)
            else -> Color.argb(170, 240, 232, 210)
        }
        stroke.strokeWidth = w * 0.005f
        c.drawPath(path, stroke)
        stroke.pathEffect = null

        if (done && isLast) {
            val x = sx(b.landZ)
            val y = sideGround
            val m = w * 0.018f
            stroke.color = if (b.hit) amber else 0xFFE5603A.toInt()
            stroke.strokeWidth = w * 0.007f
            c.drawLine(x - m, y - m, x + m, y + m, stroke)
            c.drawLine(x - m, y + m, x + m, y - m, stroke)
            text.textSize = w * 0.03f
            text.color = stroke.color
            text.textAlign = Paint.Align.CENTER
            outlinedText(c, "${b.landZ.roundToInt()}m", x, y - m * 1.6f)
        }
    }

    // ---------------------------------------------------------------- HUD

    private fun drawHud(c: Canvas) {
        paint.shader = null
        paint.color = hudBg
        c.drawRect(0f, 0f, w, hudTop + hudH, paint)

        val rowY = hudTop + hudH * 0.44f
        text.textSize = hudH * 0.34f
        text.color = khaki
        text.textAlign = Paint.Align.LEFT
        val scoreStr = "$shownScore"
        c.drawText(scoreStr, w * 0.04f, rowY, text)
        val scoreW = text.measureText(scoreStr)
        text.textSize = hudH * 0.19f
        text.color = Color.argb(140, 230, 220, 194)
        c.drawText("BEST $best", w * 0.04f + scoreW + w * 0.02f, rowY, text)
        text.textAlign = Paint.Align.CENTER
        text.textSize = hudH * 0.3f
        text.color = amber
        c.drawText("LEVEL ${world.level}", w * 0.5f, rowY, text)
        // TESTING: level -/+ buttons (remove before release)
        if (mode == Mode.PLAYING && tutStep < 0) {
            for ((r, label) in listOf(levelDown to "−", levelUp to "+")) {
                paint.shader = null
                paint.color = 0x44E6DCC2
                c.drawRoundRect(r, r.height() * 0.2f, r.height() * 0.2f, paint)
                text.textSize = r.height() * 0.85f
                text.color = khaki
                c.drawText(label, r.centerX(), r.centerY() + r.height() * 0.3f, text)
            }
        }

        // pause
        paint.color = 0x33E6DCC2
        c.drawCircle(pauseBtn.centerX(), pauseBtn.centerY(), pauseBtn.width() / 2f, paint)
        paint.color = khaki
        val bw = pauseBtn.width() * 0.12f
        val bh = pauseBtn.height() * 0.22f
        c.drawRect(pauseBtn.centerX() - bw * 2f, pauseBtn.centerY() - bh, pauseBtn.centerX() - bw * 0.7f, pauseBtn.centerY() + bh, paint)
        c.drawRect(pauseBtn.centerX() + bw * 0.7f, pauseBtn.centerY() - bh, pauseBtn.centerX() + bw * 2f, pauseBtn.centerY() + bh, paint)

        // sound
        paint.color = 0x33E6DCC2
        c.drawCircle(soundBtn.centerX(), soundBtn.centerY(), soundBtn.width() / 2f, paint)
        val cx = soundBtn.centerX()
        val cy = soundBtn.centerY()
        val u = soundBtn.width() * 0.11f
        paint.color = khaki
        path.reset()
        path.moveTo(cx - u * 2.6f, cy - u)
        path.lineTo(cx - u * 1.2f, cy - u)
        path.lineTo(cx + u * 0.4f, cy - u * 2.4f)
        path.lineTo(cx + u * 0.4f, cy + u * 2.4f)
        path.lineTo(cx - u * 1.2f, cy + u)
        path.lineTo(cx - u * 2.6f, cy + u)
        path.close()
        c.drawPath(path, paint)
        stroke.pathEffect = null
        stroke.color = khaki
        stroke.strokeWidth = u * 0.5f
        if (sfx.enabled) {
            rect.set(cx - u * 1.2f, cy - u * 2.2f, cx + u * 2.6f, cy + u * 2.2f)
            c.drawArc(rect, -45f, 90f, false, stroke)
        } else {
            c.drawLine(cx + u * 1.2f, cy - u, cx + u * 2.8f, cy + u, stroke)
            c.drawLine(cx + u * 1.2f, cy + u, cx + u * 2.8f, cy - u, stroke)
        }

        // shield bar
        val sf = world.shield / K.SHIELD_MAX
        val bx0 = w * 0.04f
        val bx1 = w * 0.96f
        val by0 = hudTop + hudH * 0.62f
        val by1 = hudTop + hudH * 0.9f
        rect.set(bx0, by0, bx1, by1)
        paint.color = 0xFF262A2D.toInt()
        c.drawRoundRect(rect, hudH * 0.06f, hudH * 0.06f, paint)
        rect.set(bx0, by0, bx0 + (bx1 - bx0) * sf, by1)
        paint.color = when {
            sf > 0.6f -> 0xFF5B9BC4.toInt()
            sf > 0.3f -> 0xFFD39A3E.toInt()
            else -> if (sin(clock * 12f) > 0) 0xFFD2452F.toInt() else 0xFF8A2416.toInt()
        }
        c.drawRoundRect(rect, hudH * 0.06f, hudH * 0.06f, paint)
        text.textSize = (by1 - by0) * 0.72f
        text.color = 0xFFF4EEDF.toInt()
        c.drawText("SHIELD ${world.shield.roundToInt()}", w / 2f, by1 - (by1 - by0) * 0.2f, text)
    }

    // ---------------------------------------------------------------- overlays

    private fun dim(c: Canvas, a: Int) {
        paint.shader = null
        paint.color = Color.argb(a, 10, 9, 8)
        c.drawRect(0f, 0f, w, h, paint)
    }

    private fun drawTitle(c: Canvas) {
        dim(c, 185)
        text.textAlign = Paint.Align.CENTER
        text.color = 0xFFD9C08A.toInt()
        text.textSize = w * 0.16f
        outlinedText(c, "SLING", w / 2f, h * 0.24f)
        text.color = khaki
        outlinedText(c, "SLAM", w / 2f, h * 0.24f + w * 0.16f)
        art.boulder(c, w * 0.82f, h * 0.17f + sin(clock * 2f) * h * 0.006f, w * 0.055f, clock * 0.3f, 1f)

        val lines = listOf(
            "Pull back on the sling to lob boulders.",
            "Smash what rolls in before it reaches",
            "your shield. Watch the SIDE VIEW up top",
            "to see where each shot lands.",
        )
        text.textSize = w * 0.04f
        text.color = Color.argb(230, 230, 220, 194)
        var y = h * 0.46f
        for (l in lines) {
            c.drawText(l, w / 2f, y, text)
            y += text.textSize * 1.35f
        }
        // bonus legend
        val ly0 = y + h * 0.02f
        val u = w * 0.05f
        val items = listOf(Kind.ENERGY to "SHIELD", Kind.UPGRADE to "SLING UP", Kind.GOLD to "POINTS")
        text.textSize = w * 0.03f
        c.drawText("Hit these for bonuses:", w / 2f, ly0, text)
        for ((i, it) in items.withIndex()) {
            val x = w * (0.22f + 0.28f * i)
            val fake = Hazard(it.first, 0f, 0f, 0f, FloatArray(9) { 0.95f })
            fake.age = clock
            art.hazard(c, fake, x, ly0 + u * 2.2f, u * 0.55f, false, clock)
            text.textSize = w * 0.03f
            text.color = khaki
            c.drawText(it.second, x, ly0 + u * 2.9f, text)
        }

        text.textSize = w * 0.065f
        text.color = Color.argb((170 + 85 * sin(clock * 4f)).toInt(), 242, 180, 81)
        outlinedText(c, "TAP TO START", w / 2f, h * 0.775f)
        if (best > 0) {
            text.textSize = w * 0.04f
            text.color = khaki
            c.drawText("HIGH SCORE  $best", w / 2f, h * 0.81f, text)
        }
        layoutMenu(Mode.TITLE)
        drawButton(c, btnA, "HOW TO PLAY", false)
        drawButton(c, btnC, "SCORES", false)
        drawButton(c, btnB, "EXIT", false)
    }

    // ---------------------------------------------------------------- scoreboard

    private fun loadScores(): List<Pair<Int, Int>> {
        val raw = prefs.getString("scores", "") ?: ""
        return raw.split(',').mapNotNull {
            val p = it.split(':')
            if (p.size == 2) {
                val sc = p[0].toIntOrNull()
                val lv = p[1].toIntOrNull()
                if (sc != null && lv != null) sc to lv else null
            } else null
        }
    }

    /** Keeps the last 20 runs, newest first. */
    private fun recordScore(score: Int, level: Int) {
        val list = (listOf(score to level) + loadScores()).take(20)
        val ed = prefs.edit().putString("scores", list.joinToString(",") { "${it.first}:${it.second}" })
        if (score >= best) ed.putInt("bestLevel", level)
        ed.apply()
    }

    private fun drawScores(c: Canvas) {
        dim(c, 235)
        text.textAlign = Paint.Align.CENTER
        text.textSize = w * 0.09f
        text.color = khaki
        outlinedText(c, "SCOREBOARD", w / 2f, h * 0.12f)

        // featured high score
        rect.set(w * 0.1f, h * 0.15f, w * 0.9f, h * 0.27f)
        paint.shader = null
        paint.color = 0xFF2A2418.toInt()
        c.drawRoundRect(rect, h * 0.015f, h * 0.015f, paint)
        stroke.pathEffect = null
        stroke.color = amber
        stroke.strokeWidth = w * 0.006f
        c.drawRoundRect(rect, h * 0.015f, h * 0.015f, stroke)
        text.textSize = w * 0.04f
        text.color = amber
        c.drawText("HIGH SCORE", w / 2f, h * 0.18f, text)
        text.textSize = w * 0.1f
        text.color = 0xFFF4EEDF.toInt()
        c.drawText("$best", w / 2f, h * 0.232f, text)
        val bl = prefs.getInt("bestLevel", 0)
        if (bl > 0) {
            text.textSize = w * 0.035f
            text.color = khaki
            c.drawText("reached level $bl", w / 2f, h * 0.258f, text)
        }

        val list = loadScores()
        text.textSize = w * 0.035f
        text.color = Color.argb(170, 230, 220, 194)
        c.drawText("LAST ${list.size} RUNS (NEWEST FIRST)", w / 2f, h * 0.305f, text)
        val rowH = h * 0.026f
        var y = h * 0.34f
        text.textSize = w * 0.04f
        if (list.isEmpty()) {
            text.color = khaki
            c.drawText("No runs yet", w / 2f, y + rowH, text)
        }
        for ((i, e) in list.withIndex()) {
            val isBest = e.first == best && best > 0
            if (i % 2 == 0) {
                paint.color = 0x14FFFFFF
                c.drawRect(w * 0.1f, y - rowH * 0.75f, w * 0.9f, y + rowH * 0.3f, paint)
            }
            text.color = if (isBest) amber else khaki
            text.textAlign = Paint.Align.LEFT
            c.drawText("${i + 1}.", w * 0.13f, y, text)
            c.drawText("Lv ${e.second}", w * 0.28f, y, text)
            text.textAlign = Paint.Align.RIGHT
            c.drawText("${e.first}" + if (isBest) "  ★" else "", w * 0.87f, y, text)
            y += rowH
        }
        text.textAlign = Paint.Align.CENTER
        text.textSize = w * 0.045f
        text.color = Color.argb((170 + 85 * sin(clock * 4f)).toInt(), 242, 180, 81)
        c.drawText("TAP TO GO BACK", w / 2f, h * 0.9f, text)
    }

    // ---------------------------------------------------------------- split button

    private fun drawSplitButton(c: Canvas) {
        val n = world.splitCharges
        val armed = world.splitArmed
        paint.shader = null
        paint.color = when {
            armed -> Color.argb((200 + 55 * sin(clock * 8f)).toInt(), 184, 134, 46)
            n > 0 -> 0xCC2A2D30.toInt()
            else -> 0x662A2D30
        }
        c.drawCircle(splitCx, splitCy, splitR, paint)
        stroke.pathEffect = null
        stroke.color = if (n > 0) amber else 0x55E6DCC2
        stroke.strokeWidth = splitR * 0.08f
        c.drawCircle(splitCx, splitCy, splitR, stroke)
        // three rocks fanning out
        val a = if (n > 0) 1f else 0.4f
        val rr = splitR * 0.2f
        art.boulder(c, splitCx, splitCy + splitR * 0.3f, rr * 1.2f, 0.3f, a)
        for (k in intArrayOf(-1, 0, 1)) {
            val ang = -PI.toFloat() / 2f + k * 0.7f
            art.boulder(c, splitCx + cos(ang) * splitR * 0.5f, splitCy + splitR * 0.05f + sin(ang) * splitR * 0.5f, rr * 0.8f, k.toFloat(), a)
        }
        // charges badge
        val bx = splitCx + splitR * 0.72f
        val by = splitCy - splitR * 0.72f
        paint.color = if (n > 0) amber else 0xFF555555.toInt()
        c.drawCircle(bx, by, splitR * 0.3f, paint)
        text.textAlign = Paint.Align.CENTER
        text.textSize = splitR * 0.4f
        text.color = 0xFF1A1408.toInt()
        c.drawText("$n", bx, by + text.textSize * 0.36f, text)
        text.textSize = splitR * 0.32f
        text.color = if (n > 0) khaki else 0x88E6DCC2.toInt()
        outlinedText(c, if (armed) "ARMED" else "SPLIT", splitCx, splitCy + splitR + text.textSize * 1.1f)
    }

    // ---------------------------------------------------------------- tutorial

    private fun startTutorial() {
        world = World(this).also { it.tutorial = true }
        mode = Mode.PLAYING
        shownScore = 0
        aiming = false
        lastNs = 0L
        tutStep = 0
        tutBase = 0
        tutSpawned = false
        tutWait = 0f
    }

    private fun finishTutorial() {
        prefs.edit().putBoolean("tutorialDone", true).apply()
        startGame()
    }

    private fun tutIsTapStep() = tutStep == 1 || tutStep == 6 || tutStep == 7

    private fun advanceTutorial() {
        tutStep++
        tutSpawned = false
        tutWait = 0.6f
        tutBase = if (tutStep == 0) world.thrown else world.smashed
        if (tutStep > 7) finishTutorial()
    }

    private fun tutText(): String = when (tutStep) {
        0 -> "Touch inside the circle at the bottom, pull back, and let go to throw a boulder."
        1 -> "The SIDE VIEW up top shows the arc of every shot. The ✕ marks where it landed. Tap to continue."
        2 -> "An oil drum is rolling in. The farther you pull back, the farther you throw. Smash it!"
        3 -> "Close ones need a short, gentle pull. Short throws fly low and fast."
        4 -> "To aim RIGHT, pull down and to the LEFT. To aim left, pull to the right."
        5 -> "Tap the SPLIT button, then hit a drum. The boulder bursts into 3 rocks that spread the damage."
        6 -> "You earn a split shot every 5 levels (hold up to 3). Hit power cells for shield, ammo crates to upgrade your sling, and gold for points. Tap to continue."
        else -> "Every 10th level a BOSS golem appears. Any stones left on the field get sucked into it and make it tougher, so keep your misses down! Tap to start."
    }

    private fun tutorialTick(dt: Float) {
        val w0 = world
        if (tutWait > 0f) {
            tutWait -= dt
            return
        }
        when (tutStep) {
            0 -> if (w0.thrown > tutBase) advanceTutorial()
            2, 3, 4, 5 -> {
                if (!tutSpawned) {
                    tutSpawned = true
                    tutBase = w0.smashed
                    when (tutStep) {
                        2 -> w0.spawnTutorial(Kind.BARREL, 0f, 60f, 0.5f)
                        3 -> w0.spawnTutorial(Kind.BARREL, -2f, 20f, 0.2f)
                        4 -> w0.spawnTutorial(Kind.BARREL, 7f, 45f, 0.35f)
                        5 -> {
                            w0.splitCharges = max(w0.splitCharges, 1)
                            w0.spawnTutorial(Kind.BARREL, -3f, 50f, 0.3f)
                            w0.spawnTutorial(Kind.BARREL, 3f, 53f, 0.3f)
                        }
                    }
                } else if (w0.hazards.isEmpty()) {
                    if (w0.smashed > tutBase) {
                        advanceTutorial()
                    } else {
                        // missed: try again
                        tutSpawned = false
                        tutWait = 1f
                    }
                }
            }
        }
    }

    private fun wrap(s: String, maxW: Float): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (word in s.split(' ')) {
            val t = if (line.isEmpty()) word else "$line $word"
            if (text.measureText(t) > maxW && line.isNotEmpty()) {
                out.add(line)
                line = word
            } else line = t
        }
        if (line.isNotEmpty()) out.add(line)
        return out
    }

    private fun drawTutorial(c: Canvas) {
        // highlight what the step is about
        val pulse = 0.5f + 0.5f * sin(clock * 5f)
        stroke.pathEffect = null
        stroke.color = Color.argb((120 + 120 * pulse).toInt(), 242, 180, 81)
        stroke.strokeWidth = w * 0.01f
        when (tutStep) {
            0, 2, 3, 4 -> c.drawCircle(slingX, slingY, slingR * (1.25f + 0.1f * pulse), stroke)
            1 -> {
                rect.set(w * 0.01f, sideTop + h * 0.004f, w * 0.99f, sideBottom - h * 0.004f)
                c.drawRoundRect(rect, w * 0.02f, w * 0.02f, stroke)
            }
            5 -> c.drawCircle(splitCx, splitCy, splitR * (1.3f + 0.15f * pulse), stroke)
        }

        // instruction panel just below the side view
        text.textAlign = Paint.Align.CENTER
        text.textSize = w * 0.042f
        val lines = wrap(tutText(), w * 0.82f)
        val lineH = text.textSize * 1.3f
        // Sit just below the side view, unless a hazard would be hidden there; then sit just above the sandbags.
        val panelH = lines.size * lineH + h * 0.055f
        val upperTop = sideBottom + h * 0.012f
        val lowerTop = groundY - h * 0.075f - panelH
        fun covered(t: Float): Float {
            var sum = 0f
            for (hz in world.hazards) {
                val sc = sAt(hz.z)
                val hb = ly(hz.z, hz.y)
                val ht = hb - hz.height * unitPx * sc * 1.2f
                sum += max(0f, min(t + panelH, hb) - max(t, ht))
            }
            return sum
        }
        val top = if (tutStep == 1 || covered(upperTop) <= covered(lowerTop)) upperTop else lowerTop
        val bottom = top + panelH
        rect.set(w * 0.04f, top, w * 0.96f, bottom)
        paint.shader = null
        paint.color = 0xA6141618.toInt()
        c.drawRoundRect(rect, w * 0.03f, w * 0.03f, paint)
        stroke.color = amber
        stroke.strokeWidth = w * 0.004f
        c.drawRoundRect(rect, w * 0.03f, w * 0.03f, stroke)
        text.color = 0xFFF4EEDF.toInt()
        var y = top + h * 0.012f + text.textSize
        for (l in lines) {
            c.drawText(l, w / 2f, y, text)
            y += lineH
        }
        // footer: step count and skip
        text.textSize = w * 0.032f
        text.textAlign = Paint.Align.LEFT
        text.color = Color.argb(170, 230, 220, 194)
        c.drawText("TUTORIAL  ${tutStep + 1} / 8", w * 0.08f, bottom - h * 0.014f, text)
        text.textAlign = Paint.Align.RIGHT
        text.color = amber
        c.drawText("SKIP ›", w * 0.92f, bottom - h * 0.014f, text)
        skipBtn.set(w * 0.68f, bottom - h * 0.045f, w * 0.96f, bottom + h * 0.01f)
        text.textAlign = Paint.Align.CENTER
    }

    private fun drawPaused(c: Canvas) {
        dim(c, 160)
        text.textAlign = Paint.Align.CENTER
        text.textSize = w * 0.12f
        text.color = khaki
        outlinedText(c, "PAUSED", w / 2f, h * 0.4f)
        layoutMenu(Mode.PAUSED)
        drawButton(c, btnA, "RESUME", true)
        drawButton(c, btnB, "END GAME", false)
    }

    /** Places the two menu buttons for the given screen. */
    private fun layoutMenu(m: Mode) {
        val bw = w * 0.56f
        val bh = h * 0.062f
        when (m) {
            Mode.TITLE -> {
                val ew = w * 0.29f
                val gap = w * 0.03f
                val y0 = h * 0.835f
                val y1 = y0 + bh * 0.8f
                btnA.set(w / 2f - ew * 1.5f - gap, y0, w / 2f - ew * 0.5f - gap, y1)
                btnC.set(w / 2f - ew * 0.5f, y0, w / 2f + ew * 0.5f, y1)
                btnB.set(w / 2f + ew * 0.5f + gap, y0, w / 2f + ew * 1.5f + gap, y1)
            }
            Mode.PAUSED -> {
                btnA.set(w / 2f - bw / 2f, h * 0.46f, w / 2f + bw / 2f, h * 0.46f + bh)
                btnB.set(w / 2f - bw / 2f, h * 0.55f, w / 2f + bw / 2f, h * 0.55f + bh)
            }
            else -> {
                btnA.set(w / 2f - bw / 2f, h * 0.67f, w / 2f + bw / 2f, h * 0.67f + bh)
                btnC.set(w / 2f - bw / 2f, h * 0.75f, w / 2f + bw / 2f, h * 0.75f + bh)
                btnB.set(w / 2f - bw / 2f, h * 0.83f, w / 2f + bw / 2f, h * 0.83f + bh)
            }
        }
    }

    private fun drawButton(c: Canvas, r: RectF, label: String, primary: Boolean) {
        paint.shader = null
        paint.color = if (primary) 0xFFB8862E.toInt() else 0xFF3A3D40.toInt()
        c.drawRoundRect(r, r.height() * 0.18f, r.height() * 0.18f, paint)
        stroke.pathEffect = null
        stroke.color = if (primary) 0xFFF2B451.toInt() else 0xFF8A8D90.toInt()
        stroke.strokeWidth = r.height() * 0.05f
        c.drawRoundRect(r, r.height() * 0.18f, r.height() * 0.18f, stroke)
        text.textAlign = Paint.Align.CENTER
        text.textSize = r.height() * 0.42f
        val tw = text.measureText(label)
        if (tw > r.width() * 0.88f) text.textSize *= r.width() * 0.88f / tw
        text.color = if (primary) 0xFF1A1408.toInt() else khaki
        c.drawText(label, r.centerX(), r.centerY() + text.textSize * 0.36f, text)
    }

    private fun drawGameOver(c: Canvas) {
        dim(c, (min(1f, overT / 0.6f) * 185).toInt())
        text.textAlign = Paint.Align.CENTER
        text.textSize = w * 0.11f
        text.color = 0xFFD2452F.toInt()
        outlinedText(c, "SHIELD DOWN!", w / 2f, h * 0.33f)
        text.textSize = w * 0.16f
        text.color = khaki
        outlinedText(c, "${world.score}", w / 2f, h * 0.45f)
        text.textSize = w * 0.055f
        if (newBest) {
            text.color = amber
            outlinedText(c, "NEW BEST!", w / 2f, h * 0.51f)
        } else {
            text.color = Color.argb(200, 230, 220, 194)
            c.drawText("Best  $best", w / 2f, h * 0.51f, text)
        }
        val acc = if (world.thrown > 0) min(100, 100 * world.hits / world.thrown) else 0
        text.textSize = w * 0.045f
        text.color = Color.argb(220, 230, 220, 194)
        c.drawText("Level ${world.level}   •   Smashed ${world.smashed}", w / 2f, h * 0.58f, text)
        c.drawText("Accuracy $acc%   •   Best streak ${world.bestStreak}", w / 2f, h * 0.62f, text)
        if (overT > 0.9f) {
            layoutMenu(Mode.OVER)
            drawButton(c, btnA, "PLAY AGAIN", true)
            drawButton(c, btnC, "SCOREBOARD", false)
            drawButton(c, btnB, "MENU", false)
        }
    }

    companion object {
        /** Bottom share of the screen kept free for a banner ad (a 50dp banner fits on typical phones). */
        const val AD_FRACTION = 0.075f
    }
}
