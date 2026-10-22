package com.randaladams.slingslam

import android.annotation.SuppressLint
import android.content.Context
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
import kotlin.math.abs
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
 *   Side view      a side-on strip: you on the left, hazards coming from the right, the arc of each shot
 *   Lane view      looking down the lane at the incoming hazard, with the sling circle and the boulder pile
 */
@SuppressLint("ViewConstructor")
class GameView(context: Context) : View(context), WorldListener {

    private enum class Mode { TITLE, PLAYING, PAUSED, OVER }

    private var mode = Mode.TITLE
    private var world = World(this)
    private val prefs = context.getSharedPreferences("slingslam", Context.MODE_PRIVATE)
    private var best = prefs.getInt("best", 0)
    private var newBest = false
    private var lastNs = 0L
    private var overT = 0f
    private var clock = 0f
    private val sfx = SoundFx(context).also { it.enabled = prefs.getBoolean("sound", true) }

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
    private var unitPx = 1f
    private var slingX = 0f
    private var slingY = 0f
    private var slingR = 1f
    private var maxPull = 1f
    private var rockPx = 1f
    private var pileX = 0f
    private var pileY = 0f
    private val pauseBtn = RectF()
    private val soundBtn = RectF()

    // ---- aiming ----
    private var aiming = false
    private var pointerId = -1
    private var aimDx = 0f
    private var aimDy = 0f

    // ---- drawing tools ----
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
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
    private var skyShader: Shader? = null
    private var grassShader: Shader? = null
    private var roadShader: Shader? = null
    private var sideSkyShader: Shader? = null
    private var dash: DashPathEffect? = null

    private val heightK = 0.35f      // lane view squashes heights so tall lobs stay on screen
    private val persp = 65f          // perspective depth constant

    init {
        isFocusable = true
    }

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
        if (mode == Mode.PLAYING) {
            mode = Mode.PAUSED
            aiming = false
            return true
        }
        return false
    }

    private fun startGame() {
        world = World(this)
        mode = Mode.PLAYING
        newBest = false
        aiming = false
        lastNs = 0L
    }

    private fun endGame() {
        mode = Mode.OVER
        overT = 0f
        aiming = false
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
        hudH = h * 0.075f
        sideTop = hudTop + hudH
        sideBottom = sideTop + h * 0.25f
        sideGround = sideBottom - (sideBottom - sideTop) * 0.16f
        sidePadL = w * 0.09f
        sidePadR = w * 0.04f
        sideScaleY = (sideGround - sideTop - h * 0.02f) / 34f

        horizonY = sideBottom + h * 0.045f
        groundY = h * 0.76f
        unitPx = (w * 0.47f) / K.LANE_HALF

        slingR = min(w * 0.12f, h * 0.058f)
        slingX = w / 2f
        slingY = h * 0.84f
        maxPull = min(slingR * 2.2f, h - slingY - h * 0.015f)
        rockPx = slingR * 0.5f
        pileX = w * 0.85f
        pileY = slingY + slingR * 0.75f

        val b = hudH * 0.62f
        pauseBtn.set(w - b - w * 0.03f, hudTop + hudH * 0.06f, w - w * 0.03f, hudTop + hudH * 0.06f + b)
        soundBtn.set(pauseBtn.left - b - w * 0.025f, pauseBtn.top, pauseBtn.left - w * 0.025f, pauseBtn.bottom)

        skyShader = LinearGradient(0f, sideBottom, 0f, horizonY, 0xFF5DA9E9.toInt(), 0xFFFFD8A8.toInt(), Shader.TileMode.CLAMP)
        grassShader = LinearGradient(0f, horizonY, 0f, h, 0xFF6E9B3A.toInt(), 0xFF3F6B22.toInt(), Shader.TileMode.CLAMP)
        roadShader = LinearGradient(0f, horizonY, 0f, h, 0xFFB79A6E.toInt(), 0xFF8C6B45.toInt(), Shader.TileMode.CLAMP)
        sideSkyShader = LinearGradient(0f, sideTop, 0f, sideGround, 0xFF22324A.toInt(), 0xFF46668C.toInt(), Shader.TileMode.CLAMP)
        val d = w * 0.012f
        dash = DashPathEffect(floatArrayOf(d * 1.6f, d), 0f)
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
                if (soundBtn.contains(x, y) || inflate(soundBtn).contains(x, y)) {
                    sfx.enabled = !sfx.enabled
                    prefs.edit().putBoolean("sound", sfx.enabled).apply()
                    return true
                }
                when (mode) {
                    Mode.TITLE -> if (e.actionMasked == MotionEvent.ACTION_DOWN) startGame()
                    Mode.OVER -> if (overT > 0.9f && e.actionMasked == MotionEvent.ACTION_DOWN) startGame()
                    Mode.PAUSED -> if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                        mode = Mode.PLAYING
                        lastNs = 0L
                    }
                    Mode.PLAYING -> {
                        if (inflate(pauseBtn).contains(x, y)) {
                            mode = Mode.PAUSED
                            aiming = false
                        } else if (!aiming && hypot(x - slingX, y - slingY) < slingR * 1.7f) {
                            aiming = true
                            pointerId = e.getPointerId(i)
                            setAim(x, y)
                        }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if (aiming) {
                val i = e.findPointerIndex(pointerId)
                if (i >= 0) setAim(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (aiming && e.getPointerId(e.actionIndex) == pointerId) releaseShot()
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
        var dx = x - slingX
        var dy = y - slingY
        val len = hypot(dx, dy)
        if (len > maxPull) {
            dx *= maxPull / len
            dy *= maxPull / len
        }
        aimDx = dx
        aimDy = dy
    }

    /** 0..1. Only pulling back (down) gives power. */
    private fun power(): Float {
        if (aimDy <= 0f) return 0f
        return (hypot(aimDx, aimDy) / maxPull).coerceIn(0f, 1f)
    }

    /** Pulling down-left aims right, like a real sling. Halved so small finger moves give fine control. */
    private fun aimAngle(): Float {
        val maxA = (K.MAX_AIM_DEG * PI / 180.0).toFloat()
        return (atan2(-aimDx, max(aimDy, 1f)) * 0.5f).coerceIn(-maxA, maxA)
    }

    private fun releaseShot() {
        aiming = false
        val p = power()
        if (p < 0.06f) return
        if (!world.throwBoulder(p, aimAngle())) {
            world.popups.add(Popup("reloading…", 0f, 6f, 0xFFFFFFFF.toInt()))
        }
    }

    // ===================================================================== render

    private fun render(c: Canvas) {
        c.drawColor(0xFF101418.toInt())
        c.save()
        val sh = world.shake
        if (sh > 0f) c.translate(sin(clock * 90f) * sh * w * 0.012f, cos(clock * 77f) * sh * w * 0.01f)

        c.save()
        c.clipRect(0f, sideBottom, w, h)
        drawLane(c)
        drawSling(c)
        drawPile(c)
        drawLaneHud(c)
        c.restore()

        drawSideView(c)
        c.restore()

        drawHud(c)

        if (world.hurtFlash > 0f) {
            paint.shader = null
            paint.color = Color.argb((world.hurtFlash * 110).toInt(), 255, 30, 30)
            c.drawRect(0f, 0f, w, h, paint)
        }

        when (mode) {
            Mode.TITLE -> drawTitle(c)
            Mode.PAUSED -> drawPaused(c)
            Mode.OVER -> drawGameOver(c)
            else -> {}
        }
    }

    // ---------------------------------------------------------------- lane view

    private fun drawLane(c: Canvas) {
        // sky + distant hills
        paint.shader = skyShader
        c.drawRect(0f, sideBottom, w, horizonY + 2f, paint)
        paint.shader = null
        paint.color = 0xFF7A8F6A.toInt()
        path.reset()
        path.moveTo(0f, horizonY + 1f)
        var x = 0f
        while (x <= w + 1f) {
            val y = horizonY - (h * 0.018f) * (1.2f + sin(x / w * 7f) + 0.5f * sin(x / w * 17f + 1f))
            path.lineTo(x, y)
            x += w / 40f
        }
        path.lineTo(w, horizonY + 1f)
        path.close()
        c.drawPath(path, paint)

        // grass
        paint.shader = grassShader
        c.drawRect(0f, horizonY, w, h, paint)

        // the lane
        val L = K.LANE_HALF
        val zNear = -14f
        path.reset()
        path.moveTo(lx(-L, K.Z_FAR), ly(K.Z_FAR))
        path.lineTo(lx(L, K.Z_FAR), ly(K.Z_FAR))
        path.lineTo(lx(L, zNear), ly(zNear))
        path.lineTo(lx(-L, zNear), ly(zNear))
        path.close()
        paint.shader = roadShader
        c.drawPath(path, paint)
        paint.shader = null

        // lane edges
        stroke.pathEffect = null
        stroke.color = 0xFF5E4630.toInt()
        stroke.strokeWidth = w * 0.008f
        c.drawLine(lx(-L, K.Z_FAR), ly(K.Z_FAR), lx(-L, zNear), ly(zNear), stroke)
        c.drawLine(lx(L, K.Z_FAR), ly(K.Z_FAR), lx(L, zNear), ly(zNear), stroke)

        // distance lines every 10 m, labels every 20 m
        text.textAlign = Paint.Align.LEFT
        text.textSize = w * 0.028f
        for (zi in 1..9) {
            val z = zi * 10f
            val yy = ly(z)
            stroke.color = Color.argb(if (zi % 2 == 0) 70 else 35, 255, 255, 255)
            stroke.strokeWidth = max(1.5f, w * 0.004f * sAt(z) * 2f)
            c.drawLine(lx(-L, z), yy, lx(L, z), yy, stroke)
            if (zi % 2 == 0) {
                text.color = Color.argb(150, 255, 255, 255)
                c.drawText("${zi * 10}", lx(L, z) + w * 0.012f, yy + text.textSize * 0.35f, text)
            }
        }
        text.textAlign = Paint.Align.CENTER

        // impact rings
        for (m in world.marks) {
            val a = (1f - m.t / 2.5f).coerceIn(0f, 1f)
            val s = sAt(m.z)
            val rr = (K.BOULDER_R + world.splash) * unitPx * s
            rect.set(lx(m.x, m.z) - rr, ly(m.z) - rr * 0.35f, lx(m.x, m.z) + rr, ly(m.z) + rr * 0.35f)
            stroke.color = if (m.hit) Color.argb((a * 220).toInt(), 255, 213, 79) else Color.argb((a * 160).toInt(), 70, 45, 25)
            stroke.strokeWidth = max(2f, unitPx * s * 0.35f)
            c.drawOval(rect, stroke)
        }

        // shield wall at your end of the lane
        val sf = world.shield / K.SHIELD_MAX
        val sr = (255 * (1f - sf)).toInt().coerceIn(0, 255)
        val sg = (120 + 80 * sf).toInt().coerceIn(0, 255)
        val pulse = 0.75f + 0.25f * sin(clock * 4f)
        paint.color = Color.argb((40 + 70 * sf * pulse).toInt(), sr, sg, 255)
        c.drawRect(lx(-L, 0f), groundY - unitPx * 1.1f, lx(L, 0f), groundY + unitPx * 0.2f, paint)
        stroke.color = Color.argb((120 + 120 * sf).toInt(), sr, sg, 255)
        stroke.strokeWidth = w * 0.006f
        c.drawLine(lx(-L, 0f), groundY - unitPx * 1.1f, lx(L, 0f), groundY - unitPx * 1.1f, stroke)

        // shadows
        paint.color = Color.argb(70, 0, 0, 0)
        world.hazard?.let { hz ->
            val s = sAt(hz.z)
            val r = hz.kind.radius * unitPx * s
            rect.set(lx(hz.x, hz.z) - r * 1.1f, ly(hz.z) - r * 0.3f, lx(hz.x, hz.z) + r * 1.1f, ly(hz.z) + r * 0.3f)
            c.drawOval(rect, paint)
        }
        for (b in world.boulders) {
            val s = sAt(b.z)
            val r = K.BOULDER_R * unitPx * s * (1f / (1f + b.y * 0.03f))
            rect.set(lx(b.x, b.z) - r, ly(b.z) - r * 0.35f, lx(b.x, b.z) + r, ly(b.z) + r * 0.35f)
            c.drawOval(rect, paint)
        }

        // objects, far to near
        val hz = world.hazard
        val items = ArrayList<Any>(world.boulders.size + world.particles.size + 1)
        if (hz != null) items.add(hz)
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
                    drawHazard(c, it, lx(it.x, it.z), ly(it.z, it.y), unitPx * s, false)
                }
                is Boulder -> {
                    val s = sAt(it.z)
                    val r = K.BOULDER_R * unitPx * s
                    val a = if (it.landed) (1f - (it.rest - 0.9f) / 0.5f).coerceIn(0f, 1f) else 1f
                    drawBoulder(c, lx(it.x, it.z), ly(it.z, it.y) - r, r, it.spin, a)
                }
                is Particle -> {
                    val s = sAt(it.z)
                    val a = (it.life / it.maxLife).coerceIn(0f, 1f)
                    paint.color = it.color
                    paint.alpha = (a * 255).toInt()
                    c.drawCircle(lx(it.x, it.z), ly(it.z, it.y), max(2f, it.size * unitPx * s), paint)
                    paint.alpha = 255
                }
            }
        }

        // floating score text
        for (p in world.popups) {
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
            text.textSize = w * 0.075f
            text.color = 0xFFFFEB3B.toInt()
            text.alpha = (a * 255).toInt()
            outlinedText(c, bn, w / 2f, horizonY + (groundY - horizonY) * 0.32f)
            text.alpha = 255
        }
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
        // platform
        paint.shader = null
        paint.color = 0xFF5E4630.toInt()
        rect.set(slingX - slingR * 1.6f, slingY + slingR * 0.55f, slingX + slingR * 1.6f, slingY + slingR * 0.95f)
        c.drawRoundRect(rect, slingR * 0.2f, slingR * 0.2f, paint)

        // posts
        val postL = slingX - slingR * 1.25f
        val postR = slingX + slingR * 1.25f
        val postTop = slingY - slingR * 0.9f
        stroke.pathEffect = null
        stroke.color = 0xFF6D4C2F.toInt()
        stroke.strokeWidth = slingR * 0.22f
        c.drawLine(postL, slingY + slingR * 0.6f, postL, postTop, stroke)
        c.drawLine(postR, slingY + slingR * 0.6f, postR, postTop, stroke)

        // the circle you put your finger in
        val ready = world.loaded && mode == Mode.PLAYING
        paint.color = Color.argb(if (aiming) 40 else 70, 0, 0, 0)
        c.drawCircle(slingX, slingY, slingR, paint)
        stroke.color = if (ready) Color.argb((170 + 60 * sin(clock * 5f)).toInt(), 255, 255, 255) else Color.argb(90, 255, 255, 255)
        stroke.strokeWidth = slingR * 0.07f
        c.drawCircle(slingX, slingY, slingR, stroke)

        val p = if (aiming) power() else 0f
        if (aiming && p > 0f) {
            // aim direction drawn on the ground (direction only, no distance — use the side view for that)
            val a = aimAngle()
            stroke.pathEffect = dash
            stroke.color = Color.argb(150, 255, 255, 255)
            stroke.strokeWidth = w * 0.007f
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
                p < 0.5f -> Color.rgb((255 * p * 2).toInt(), 230, 80)
                else -> Color.rgb(255, (230 * (1f - (p - 0.5f) * 2)).toInt().coerceIn(0, 255), 60)
            }
            stroke.color = col
            stroke.strokeWidth = slingR * 0.16f
            rect.set(slingX - slingR * 1.25f, slingY - slingR * 1.25f, slingX + slingR * 1.25f, slingY + slingR * 1.25f)
            c.drawArc(rect, -90f, 360f * p, false, stroke)
            text.textSize = slingR * 0.32f
            text.color = Color.WHITE
            outlinedText(c, "${(p * 100).roundToInt()}%", slingX, slingY - slingR * 1.45f)
        }

        // boulder in the pouch
        val bx: Float
        val by: Float
        if (aiming) {
            bx = slingX + aimDx
            by = slingY + aimDy
        } else {
            bx = slingX
            by = slingY
        }
        // bands
        stroke.color = 0xFF3B2A1A.toInt()
        stroke.strokeWidth = slingR * 0.09f
        if (world.loaded) {
            c.drawLine(postL, postTop, bx - rockPx * 0.7f, by, stroke)
            c.drawLine(postR, postTop, bx + rockPx * 0.7f, by, stroke)
            drawBoulder(c, bx, by, rockPx, 0f, 1f)
        } else {
            c.drawLine(postL, postTop, slingX, slingY + rockPx * 0.3f, stroke)
            c.drawLine(postR, postTop, slingX, slingY + rockPx * 0.3f, stroke)
        }
        // post caps
        paint.color = 0xFF8D6E4A.toInt()
        c.drawCircle(postL, postTop, slingR * 0.15f, paint)
        c.drawCircle(postR, postTop, slingR * 0.15f, paint)
    }

    private fun drawPile(c: Canvas) {
        val r = rockPx * 0.78f
        // rows of 3 and 2, plus a top boulder waiting its turn
        val base = pileY - r
        for (i in 0..2) drawBoulder(c, pileX + (i - 1) * r * 1.9f, base, r, i * 1.3f, 1f)
        for (i in 0..1) drawBoulder(c, pileX + (i - 0.5f) * r * 1.9f, base - r * 1.65f, r, i * 2.1f + 0.5f, 1f)
        val topX = pileX
        val topY = base - r * 3.3f
        if (world.loaded) {
            drawBoulder(c, topX, topY, r, 0.7f, 1f)
        } else {
            // the next boulder rolling off the pile into the sling
            val t = world.reloadT
            val e = t * t * (3f - 2f * t)
            val x = topX + (slingX - topX) * e
            val y = topY + (slingY - topY) * e - sin(t * PI.toFloat()) * r * 1.2f
            drawBoulder(c, x, y, r, -e * 9f, 1f)
            // and the replacement settling on top
            if (t > 0.6f) drawBoulder(c, topX, topY - (1f - t) * r * 2f, r, 2f, (t - 0.6f) / 0.4f)
        }
    }

    private fun drawLaneHud(c: Canvas) {
        // sling level and streak, bottom-left
        val x0 = w * 0.05f
        val y0 = slingY - slingR * 0.2f
        text.textAlign = Paint.Align.LEFT
        text.textSize = w * 0.033f
        text.color = Color.WHITE
        outlinedText(c, "SLING", x0, y0)
        val pip = w * 0.018f
        for (i in 0 until K.MAX_SLING) {
            paint.color = if (i < world.slingLevel) 0xFFFFEB3B.toInt() else 0x55FFFFFF
            c.drawCircle(x0 + pip + i * pip * 2.6f, y0 + pip * 2.2f, pip, paint)
        }
        if (world.streak > 0) {
            text.textSize = w * 0.035f
            text.color = 0xFFFFD54F.toInt()
            outlinedText(c, "STREAK ${world.streak}  x${world.multiplier}", x0, y0 + pip * 6f)
        }
        text.textAlign = Paint.Align.CENTER
    }

    // ---------------------------------------------------------------- side view

    private fun drawSideView(c: Canvas) {
        c.save()
        c.clipRect(0f, sideTop, w, sideBottom)
        paint.shader = sideSkyShader
        c.drawRect(0f, sideTop, w, sideGround, paint)
        paint.shader = null
        paint.color = 0xFF5B4632.toInt()
        c.drawRect(0f, sideGround, w, sideBottom, paint)
        paint.color = 0xFF5E8B32.toInt()
        c.drawRect(0f, sideGround, w, sideGround + h * 0.006f, paint)

        // distance ticks
        text.textSize = w * 0.026f
        for (zi in 0..10) {
            val x = sx(zi * 10f)
            stroke.pathEffect = null
            stroke.color = Color.argb(if (zi % 2 == 0) 120 else 60, 255, 255, 255)
            stroke.strokeWidth = 2f
            c.drawLine(x, sideGround, x, sideGround + h * (if (zi % 2 == 0) 0.012f else 0.007f), stroke)
            if (zi % 2 == 0 && zi > 0) {
                text.color = Color.argb(170, 255, 255, 255)
                c.drawText("${zi * 10}m", x, sideBottom - h * 0.008f, text)
            }
        }

        // you: a little sling tower behind the shield
        val px = sx(0f)
        stroke.color = 0xFF8D6E4A.toInt()
        stroke.strokeWidth = w * 0.012f
        c.drawLine(px - w * 0.03f, sideGround, px - w * 0.03f, sideGround - sideScaleY * 3.2f, stroke)
        c.drawLine(px - w * 0.03f, sideGround - sideScaleY * 2.2f, px - w * 0.045f, sideGround - sideScaleY * 3.6f, stroke)
        c.drawLine(px - w * 0.03f, sideGround - sideScaleY * 2.2f, px - w * 0.015f, sideGround - sideScaleY * 3.6f, stroke)
        val sf = world.shield / K.SHIELD_MAX
        stroke.color = Color.argb((90 + 140 * sf).toInt(), (255 * (1 - sf)).toInt(), (120 + 80 * sf).toInt(), 255)
        stroke.strokeWidth = w * 0.008f
        c.drawLine(px + w * 0.005f, sideGround, px + w * 0.005f, sideGround - sideScaleY * 6f, stroke)

        // hazard
        world.hazard?.let { hz ->
            drawHazard(c, hz, sx(hz.z), sy(hz.y), sideScaleY * 1.5f, true)
        }

        // the last shot's arc and where it came down
        val last = world.lastShot
        if (last != null) drawArc(c, last, true)
        for (b in world.boulders) if (b !== last && !b.landed) drawArc(c, b, false)

        // boulders
        for (b in world.boulders) {
            if (b.landed && b.rest > 1.0f) continue
            drawBoulder(c, sx(b.z), sy(b.y) - K.BOULDER_R * sideScaleY * 1.3f, K.BOULDER_R * sideScaleY * 1.3f, b.spin, 1f)
        }

        // particles
        for (p in world.particles) {
            val a = (p.life / p.maxLife).coerceIn(0f, 1f)
            paint.color = p.color
            paint.alpha = (a * 220).toInt()
            c.drawCircle(sx(p.z), sy(p.y), max(1.5f, p.size * sideScaleY * 0.6f), paint)
        }
        paint.alpha = 255

        text.textAlign = Paint.Align.LEFT
        text.textSize = w * 0.025f
        text.color = Color.argb(140, 255, 255, 255)
        c.drawText("SIDE VIEW", w * 0.02f, sideTop + text.textSize * 1.3f, text)
        text.textAlign = Paint.Align.CENTER
        c.restore()

        paint.color = 0xFF101418.toInt()
        c.drawRect(0f, sideBottom - h * 0.004f, w, sideBottom + h * 0.002f, paint)
    }

    private fun drawArc(c: Canvas, b: Boulder, isLast: Boolean) {
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
            !done -> Color.argb(230, 255, 255, 255)
            b.hit -> Color.argb(200, 255, 213, 79)
            else -> Color.argb(170, 255, 255, 255)
        }
        stroke.strokeWidth = w * 0.006f
        c.drawPath(path, stroke)
        stroke.pathEffect = null

        if (done && isLast) {
            val x = sx(b.landZ)
            val y = sideGround
            val m = w * 0.018f
            stroke.color = if (b.hit) 0xFFFFD54F.toInt() else 0xFFFF7043.toInt()
            stroke.strokeWidth = w * 0.007f
            c.drawLine(x - m, y - m, x + m, y + m, stroke)
            c.drawLine(x - m, y + m, x + m, y - m, stroke)
            text.textSize = w * 0.03f
            text.color = stroke.color
            outlinedText(c, "${b.landZ.roundToInt()}m", x, y - m * 1.6f)
        }
    }

    // ---------------------------------------------------------------- HUD

    private fun drawHud(c: Canvas) {
        paint.shader = null
        paint.color = 0xFF101418.toInt()
        c.drawRect(0f, 0f, w, hudTop + hudH, paint)

        val rowY = hudTop + hudH * 0.44f
        text.textSize = hudH * 0.34f
        text.color = Color.WHITE
        text.textAlign = Paint.Align.LEFT
        val scoreStr = "${world.score}"
        c.drawText(scoreStr, w * 0.04f, rowY, text)
        val scoreW = text.measureText(scoreStr)
        text.textSize = hudH * 0.19f
        text.color = Color.argb(150, 255, 255, 255)
        c.drawText("BEST $best", w * 0.04f + scoreW + w * 0.02f, rowY, text)
        text.textAlign = Paint.Align.CENTER
        text.textSize = hudH * 0.3f
        text.color = 0xFFFFEB3B.toInt()
        c.drawText("LEVEL ${world.level}", w * 0.5f, rowY, text)

        // pause
        paint.color = 0x33FFFFFF
        c.drawCircle(pauseBtn.centerX(), pauseBtn.centerY(), pauseBtn.width() / 2f, paint)
        paint.color = Color.WHITE
        val bw = pauseBtn.width() * 0.12f
        val bh = pauseBtn.height() * 0.22f
        c.drawRect(pauseBtn.centerX() - bw * 2f, pauseBtn.centerY() - bh, pauseBtn.centerX() - bw * 0.7f, pauseBtn.centerY() + bh, paint)
        c.drawRect(pauseBtn.centerX() + bw * 0.7f, pauseBtn.centerY() - bh, pauseBtn.centerX() + bw * 2f, pauseBtn.centerY() + bh, paint)

        // sound
        paint.color = 0x33FFFFFF
        c.drawCircle(soundBtn.centerX(), soundBtn.centerY(), soundBtn.width() / 2f, paint)
        val cx = soundBtn.centerX()
        val cy = soundBtn.centerY()
        val u = soundBtn.width() * 0.11f
        paint.color = Color.WHITE
        path.reset()
        path.moveTo(cx - u * 2.6f, cy - u)
        path.lineTo(cx - u * 1.2f, cy - u)
        path.lineTo(cx + u * 0.4f, cy - u * 2.4f)
        path.lineTo(cx + u * 0.4f, cy + u * 2.4f)
        path.lineTo(cx - u * 1.2f, cy + u)
        path.lineTo(cx - u * 2.6f, cy + u)
        path.close()
        c.drawPath(path, paint)
        stroke.color = Color.WHITE
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
        paint.color = 0xFF263238.toInt()
        c.drawRoundRect(rect, hudH * 0.1f, hudH * 0.1f, paint)
        rect.set(bx0, by0, bx0 + (bx1 - bx0) * sf, by1)
        paint.color = when {
            sf > 0.6f -> 0xFF4FC3F7.toInt()
            sf > 0.3f -> 0xFFFFB74D.toInt()
            else -> if (sin(clock * 12f) > 0) 0xFFFF5252.toInt() else 0xFFB71C1C.toInt()
        }
        c.drawRoundRect(rect, hudH * 0.1f, hudH * 0.1f, paint)
        text.textSize = (by1 - by0) * 0.72f
        text.color = Color.WHITE
        c.drawText("SHIELD ${world.shield.roundToInt()}", w / 2f, by1 - (by1 - by0) * 0.2f, text)
    }

    // ---------------------------------------------------------------- overlays

    private fun dim(c: Canvas, a: Int) {
        paint.shader = null
        paint.color = Color.argb(a, 8, 10, 14)
        c.drawRect(0f, 0f, w, h, paint)
    }

    private fun drawTitle(c: Canvas) {
        dim(c, 175)
        text.textAlign = Paint.Align.CENTER
        text.color = 0xFFFFB74D.toInt()
        text.textSize = w * 0.17f
        outlinedText(c, "SLING", w / 2f, h * 0.24f)
        text.color = Color.WHITE
        outlinedText(c, "SLAM", w / 2f, h * 0.24f + w * 0.17f)
        drawBoulder(c, w * 0.8f, h * 0.17f + sin(clock * 3f) * h * 0.01f, w * 0.06f, clock, 1f)

        val lines = listOf(
            "Put your finger in the circle,",
            "pull back to aim and set power,",
            "let go to lob a boulder.",
            "",
            "Watch the SIDE VIEW up top to see",
            "where your shot lands, then adjust.",
            "",
            "Smash barrels, rocks and snapping plants",
            "before they hit your shield.",
            "Hit orbs, gears and coins for",
            "shield, sling upgrades and points.",
        )
        text.textSize = w * 0.042f
        text.color = Color.argb(230, 255, 255, 255)
        var y = h * 0.45f
        for (l in lines) {
            c.drawText(l, w / 2f, y, text)
            y += text.textSize * 1.35f
        }
        // pickup legend
        val ly0 = y + h * 0.02f
        val u = w * 0.045f
        val items = listOf(Kind.ENERGY to "SHIELD", Kind.UPGRADE to "SLING", Kind.GOLD to "POINTS")
        for ((i, it) in items.withIndex()) {
            val x = w * (0.22f + 0.28f * i)
            val fake = Hazard(it.first, 0f, 0f, 0f, FloatArray(9) { 1f })
            fake.spin = clock * 2f
            fake.age = clock
            drawHazard(c, fake, x, ly0 + u * 2f, u * 0.55f, false)
            text.textSize = w * 0.032f
            text.color = Color.WHITE
            c.drawText(it.second, x, ly0 + u * 2.9f, text)
        }

        text.textSize = w * 0.07f
        text.color = Color.argb((180 + 75 * sin(clock * 4f)).toInt(), 255, 235, 59)
        outlinedText(c, "TAP TO START", w / 2f, h * 0.9f)
        if (best > 0) {
            text.textSize = w * 0.04f
            text.color = Color.WHITE
            c.drawText("BEST  $best", w / 2f, h * 0.95f, text)
        }
    }

    private fun drawPaused(c: Canvas) {
        dim(c, 160)
        text.textAlign = Paint.Align.CENTER
        text.textSize = w * 0.12f
        text.color = Color.WHITE
        outlinedText(c, "PAUSED", w / 2f, h * 0.45f)
        text.textSize = w * 0.05f
        text.color = Color.argb(220, 255, 255, 255)
        c.drawText("Tap to resume", w / 2f, h * 0.52f, text)
    }

    private fun drawGameOver(c: Canvas) {
        dim(c, (min(1f, overT / 0.6f) * 180).toInt())
        text.textAlign = Paint.Align.CENTER
        text.textSize = w * 0.11f
        text.color = 0xFFFF5252.toInt()
        outlinedText(c, "SHIELD DOWN!", w / 2f, h * 0.33f)
        text.textSize = w * 0.16f
        text.color = Color.WHITE
        outlinedText(c, "${world.score}", w / 2f, h * 0.45f)
        text.textSize = w * 0.055f
        if (newBest) {
            text.color = 0xFFFFEB3B.toInt()
            outlinedText(c, "NEW BEST!", w / 2f, h * 0.51f)
        } else {
            text.color = Color.argb(200, 255, 255, 255)
            c.drawText("Best  $best", w / 2f, h * 0.51f, text)
        }
        val acc = if (world.thrown > 0) 100 * world.hits / world.thrown else 0
        text.textSize = w * 0.045f
        text.color = Color.argb(220, 255, 255, 255)
        c.drawText("Level ${world.level}   •   Smashed ${world.smashed}", w / 2f, h * 0.58f, text)
        c.drawText("Accuracy $acc%   •   Best streak ${world.bestStreak}", w / 2f, h * 0.62f, text)
        if (overT > 0.9f) {
            text.textSize = w * 0.065f
            text.color = Color.argb((180 + 75 * sin(clock * 4f)).toInt(), 255, 235, 59)
            outlinedText(c, "TAP TO PLAY AGAIN", w / 2f, h * 0.74f)
        }
    }

    // ===================================================================== sprites

    private fun drawBoulder(c: Canvas, cx: Float, cy: Float, r: Float, spin: Float, alpha: Float) {
        if (alpha <= 0f) return
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        paint.shader = null
        paint.color = 0xFF8A8580.toInt()
        paint.alpha = a
        c.drawCircle(cx, cy, r, paint)
        paint.color = 0xFF6E6964.toInt()
        paint.alpha = a
        for (k in 0 until 3) {
            val ang = spin + k * 2.1f
            c.drawCircle(cx + cos(ang) * r * 0.45f, cy + sin(ang) * r * 0.4f, r * 0.22f, paint)
        }
        paint.color = Color.argb((a * 0.35f).toInt(), 255, 255, 255)
        c.drawCircle(cx - r * 0.32f, cy - r * 0.35f, r * 0.3f, paint)
        stroke.pathEffect = null
        stroke.color = 0xFF4A4642.toInt()
        stroke.alpha = a
        stroke.strokeWidth = max(1f, r * 0.12f)
        c.drawCircle(cx, cy, r, stroke)
        paint.alpha = 255
        stroke.alpha = 255
    }

    /**
     * Draws a hazard standing on (cx, bottom). [u] is pixels per world unit at that spot.
     * [side] chooses the side-on look used in the top panel.
     */
    private fun drawHazard(c: Canvas, hz: Hazard, cx: Float, bottom: Float, u: Float, side: Boolean) {
        val r = hz.kind.radius * u
        paint.shader = null
        stroke.pathEffect = null
        when (hz.kind) {
            Kind.BARREL -> drawBarrel(c, hz, cx, bottom, r, side)
            Kind.ROCK -> drawRock(c, hz, cx, bottom - r, r)
            Kind.PLANT -> drawPlant(c, hz, cx, bottom, u)
            Kind.ENERGY -> drawOrb(c, hz, cx, bottom - r, r)
            Kind.UPGRADE -> drawGear(c, hz, cx, bottom - r, r)
            Kind.GOLD -> drawCoin(c, hz, cx, bottom - r, r)
        }
        if (hz.flash > 0f) {
            paint.color = Color.argb((hz.flash * 170).toInt(), 255, 255, 255)
            c.drawCircle(cx, bottom - r, r * 1.15f, paint)
        }
    }

    private fun drawBarrel(c: Canvas, hz: Hazard, cx: Float, bottom: Float, r: Float, side: Boolean) {
        val cy = bottom - r
        if (side) {
            // seen from the side: the round end, turning as it rolls toward you
            paint.color = 0xFF8B5A2B.toInt()
            c.drawCircle(cx, cy, r, paint)
            stroke.color = 0xFF3E3E3E.toInt()
            stroke.strokeWidth = r * 0.16f
            c.drawCircle(cx, cy, r * 0.92f, stroke)
            stroke.color = 0xFF5D3A1A.toInt()
            stroke.strokeWidth = r * 0.08f
            c.drawCircle(cx, cy, r * 0.55f, stroke)
            val a = -hz.spin
            c.drawLine(cx - cos(a) * r * 0.85f, cy - sin(a) * r * 0.85f, cx + cos(a) * r * 0.85f, cy + sin(a) * r * 0.85f, stroke)
        } else {
            // seen head-on: the barrel's body rolling at you, hoops sweeping past
            val hw = r * 1.05f
            rect.set(cx - hw, cy - r, cx + hw, cy + r)
            paint.color = 0xFF8B5A2B.toInt()
            c.drawRoundRect(rect, r * 0.3f, r * 0.3f, paint)
            paint.color = 0x22000000
            rect.set(cx - hw, cy - r, cx - hw * 0.55f, cy + r)
            c.drawRect(rect, paint)
            rect.set(cx + hw * 0.55f, cy - r, cx + hw, cy + r)
            c.drawRect(rect, paint)
            paint.color = 0xFF3E3E3E.toInt()
            val phase = (hz.spin / (2f * PI.toFloat())) % 1f
            for (k in 0..1) {
                val t = (k * 0.5f + phase) % 1f
                val yy = cy - r + t * 2f * r
                val thick = r * 0.14f * (0.5f + sin(t * PI.toFloat()))
                c.drawRect(cx - hw, yy - thick / 2f, cx + hw, yy + thick / 2f, paint)
            }
            stroke.color = 0xFF4E3018.toInt()
            stroke.strokeWidth = max(1f, r * 0.08f)
            rect.set(cx - hw, cy - r, cx + hw, cy + r)
            c.drawRoundRect(rect, r * 0.3f, r * 0.3f, stroke)
        }
    }

    private fun drawRock(c: Canvas, hz: Hazard, cx: Float, cy: Float, r: Float) {
        path.reset()
        val n = hz.shape.size
        for (i in 0 until n) {
            val a = hz.spin + i * 2f * PI.toFloat() / n
            val rr = r * hz.shape[i]
            val x = cx + cos(a) * rr
            val y = cy + sin(a) * rr * 0.9f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        paint.color = 0xFF7B6F63.toInt()
        c.drawPath(path, paint)
        paint.color = 0x33FFFFFF
        c.drawCircle(cx - r * 0.3f, cy - r * 0.3f, r * 0.35f, paint)
        stroke.color = 0xFF4A4038.toInt()
        stroke.strokeWidth = max(1f, r * 0.08f)
        c.drawPath(path, stroke)
        if (hz.hp < hz.kind.hp) {
            stroke.color = 0xFF2B2520.toInt()
            stroke.strokeWidth = max(1f, r * 0.07f)
            c.drawLine(cx - r * 0.5f, cy - r * 0.4f, cx, cy + r * 0.05f, stroke)
            c.drawLine(cx, cy + r * 0.05f, cx + r * 0.2f, cy + r * 0.6f, stroke)
            c.drawLine(cx, cy + r * 0.05f, cx + r * 0.55f, cy - r * 0.2f, stroke)
        }
    }

    private fun drawPlant(c: Canvas, hz: Hazard, cx: Float, bottom: Float, u: Float) {
        val sway = sin(hz.age * 3f) * u * 0.5f
        val headY = bottom - u * 4.3f
        val headX = cx + sway
        // stem
        stroke.color = 0xFF2E7D32.toInt()
        stroke.strokeWidth = u * 0.45f
        path.reset()
        path.moveTo(cx, bottom)
        path.quadTo(cx - u * 0.8f, bottom - u * 2f, headX, headY)
        c.drawPath(path, stroke)
        // leaves
        paint.color = 0xFF43A047.toInt()
        rect.set(cx - u * 2.2f, bottom - u * 1.6f, cx - u * 0.2f, bottom - u * 0.6f)
        c.drawOval(rect, paint)
        rect.set(cx + u * 0.2f, bottom - u * 2.2f, cx + u * 2.2f, bottom - u * 1.2f)
        c.drawOval(rect, paint)
        // snapping jaws
        val open = 0.35f + 0.35f * abs(sin(hz.age * 4f))
        val jr = u * 1.5f
        paint.color = 0xFF7CB342.toInt()
        rect.set(headX - jr, headY - jr, headX + jr, headY + jr)
        c.drawArc(rect, 180f + open * 50f, 180f - open * 50f, true, paint)
        c.drawArc(rect, 0f, 180f - open * 50f, true, paint)
        paint.color = 0xFFC62828.toInt()
        rect.set(headX - jr * 0.75f, headY - jr * 0.75f, headX + jr * 0.75f, headY + jr * 0.75f)
        c.drawArc(rect, 180f + open * 50f, 180f - open * 50f, true, paint)
        c.drawArc(rect, 0f, 180f - open * 50f, true, paint)
        // teeth
        stroke.color = Color.WHITE
        stroke.strokeWidth = max(1f, u * 0.15f)
        for (k in -2..2) {
            val tx = headX + k * jr * 0.35f
            c.drawLine(tx, headY - jr * 0.15f - open * jr * 0.3f, tx, headY - open * jr * 0.1f, stroke)
            c.drawLine(tx, headY + jr * 0.15f + open * jr * 0.3f, tx, headY + open * jr * 0.1f, stroke)
        }
    }

    private fun drawOrb(c: Canvas, hz: Hazard, cx: Float, cy: Float, r: Float) {
        val pulse = 0.85f + 0.15f * sin(hz.age * 6f)
        for (k in 3 downTo 1) {
            paint.color = Color.argb(40, 77, 208, 225)
            c.drawCircle(cx, cy, r * (0.9f + k * 0.25f) * pulse, paint)
        }
        paint.color = 0xFF26C6DA.toInt()
        c.drawCircle(cx, cy, r * 0.9f, paint)
        paint.color = 0xFFB2EBF2.toInt()
        c.drawCircle(cx - r * 0.25f, cy - r * 0.3f, r * 0.25f, paint)
        // lightning bolt
        paint.color = Color.WHITE
        path.reset()
        path.moveTo(cx + r * 0.1f, cy - r * 0.6f)
        path.lineTo(cx - r * 0.3f, cy + r * 0.05f)
        path.lineTo(cx, cy + r * 0.05f)
        path.lineTo(cx - r * 0.1f, cy + r * 0.6f)
        path.lineTo(cx + r * 0.32f, cy - r * 0.1f)
        path.lineTo(cx + r * 0.02f, cy - r * 0.1f)
        path.close()
        c.drawPath(path, paint)
    }

    private fun drawGear(c: Canvas, hz: Hazard, cx: Float, cy: Float, r: Float) {
        paint.color = 0xFFFBC02D.toInt()
        c.save()
        c.rotate(hz.spin * 40f, cx, cy)
        for (k in 0 until 8) {
            c.save()
            c.rotate(k * 45f, cx, cy)
            c.drawRect(cx - r * 0.18f, cy - r, cx + r * 0.18f, cy - r * 0.6f, paint)
            c.restore()
        }
        c.drawCircle(cx, cy, r * 0.72f, paint)
        c.restore()
        paint.color = 0xFF5D4037.toInt()
        c.drawCircle(cx, cy, r * 0.42f, paint)
        // up arrow
        paint.color = 0xFFFFEB3B.toInt()
        path.reset()
        path.moveTo(cx, cy - r * 0.32f)
        path.lineTo(cx + r * 0.26f, cy)
        path.lineTo(cx + r * 0.1f, cy)
        path.lineTo(cx + r * 0.1f, cy + r * 0.28f)
        path.lineTo(cx - r * 0.1f, cy + r * 0.28f)
        path.lineTo(cx - r * 0.1f, cy)
        path.lineTo(cx - r * 0.26f, cy)
        path.close()
        c.drawPath(path, paint)
    }

    private fun drawCoin(c: Canvas, hz: Hazard, cx: Float, cy: Float, r: Float) {
        val sx = max(0.15f, abs(cos(hz.spin)))
        rect.set(cx - r * sx, cy - r, cx + r * sx, cy + r)
        paint.color = 0xFFF9A825.toInt()
        c.drawOval(rect, paint)
        rect.set(cx - r * sx * 0.78f, cy - r * 0.78f, cx + r * sx * 0.78f, cy + r * 0.78f)
        paint.color = 0xFFFFD54F.toInt()
        c.drawOval(rect, paint)
        if (sx > 0.4f) {
            // star
            paint.color = 0xFFF57F17.toInt()
            path.reset()
            for (k in 0 until 10) {
                val a = -PI.toFloat() / 2f + k * PI.toFloat() / 5f
                val rr = if (k % 2 == 0) r * 0.5f else r * 0.2f
                val x = cx + cos(a) * rr * sx
                val y = cy + sin(a) * rr
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            c.drawPath(path, paint)
        }
    }
}
