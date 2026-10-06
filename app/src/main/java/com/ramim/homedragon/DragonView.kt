package com.ramim.homedragon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

private const val TAU = (2.0 * PI).toFloat()
private fun rnd(a: Float, b: Float) = a + Random.nextFloat() * (b - a)
private fun clampF(v: Float, a: Float, b: Float) = max(a, min(b, v))
private fun deg(r: Float) = r * 57.29578f

/**
 * Behaviour and effects. Icons are the only ground: the dragon perches on an icon, then either jumps
 * to a neighbouring icon, flies a free random path through the screen and lands on an icon, or
 * hovers and breathes blue fire at an icon (which gets scorched and slowly cools down).
 *
 * Frames come from Choreographer. pause() removes the callback and keeps everything in memory.
 */
class DragonView(context: Context) : View(context) {

    private enum class Mode { IDLE, CROUCH, FLY, FIRE, WALK }

    private class Icon(val r: RectF, val row: Int) {
        var burn = 0f; var heat = 0f; var ember = 0f; var sm = 0f
        var big = false          // widget or large folder: bigger ground the dragon can crawl on
        val scorch = Array(4) { floatArrayOf(rnd(-.28f, .28f), rnd(-.28f, .28f), rnd(.28f, .5f), rnd(0f, TAU)) }
        val cx: Float get() = r.centerX()
        val cy: Float get() = r.centerY()
    }

    private class FlightPath(val x: FloatArray, val y: FloatArray, val cum: FloatArray) {
        val len: Float get() = cum[cum.size - 1]
    }

    // ---------- icons ----------
    private var icons: List<Icon> = emptyList()
    private var size = 120f
    private var sc = 2f
    private var ds = 1.2f
    private var offX = 0
    private var offY = 0
    private var usingRegistry = false
    private var placed = false

    // ---------- dragon ----------
    private val model = DragonModel()
    private val hl = FloatArray(2)
    private val st = DragonModel.State()
    private var mode = Mode.IDLE
    private var air = false
    private var iconIdx = 0
    private var faceT = 1f
    private var t = 0f
    private var dur = 1f
    private var path: FlightPath? = null
    private var then: (() -> Unit)? = null
    private var timer = 1.5f
    private var blinkT = 0f
    private var nextBlink = 2f
    private var look = 0f
    private var lookT = 0f
    private var nextLook = 2f
    private var target = 0
    private var pendKind: String? = null
    private var pendIdx = -1
    private var acc = 0f
    private var walkTo = 0f
    private var fireDur = 1.7f
    private var vx = 0f
    private var vy = 0f
    private var headGoal = 0.3f
    private var time = 0f
    private val mp = FloatArray(2)
    private val pp = FloatArray(2)

    // ---------- particles (parallel arrays) ----------
    private val maxF = 300
    private val fX = FloatArray(maxF); private val fY = FloatArray(maxF)
    private val fVx = FloatArray(maxF); private val fVy = FloatArray(maxF)
    private val fPx = FloatArray(maxF); private val fPy = FloatArray(maxF)
    private val fAge = FloatArray(maxF); private val fLife = FloatArray(maxF)
    private val fSize = FloatArray(maxF); private val fSeed = FloatArray(maxF); private val fKind = IntArray(maxF)
    private var nf = 0

    private val maxS = 100
    private val sX = FloatArray(maxS); private val sY = FloatArray(maxS)
    private val sVx = FloatArray(maxS); private val sVy = FloatArray(maxS)
    private val sAge = FloatArray(maxS); private val sLife = FloatArray(maxS); private val sSize = FloatArray(maxS)
    private val sRot = FloatArray(maxS); private val sRv = FloatArray(maxS); private val sLit = FloatArray(maxS)
    private val sSeed = FloatArray(maxS); private val sVar = IntArray(maxS)
    private var ns = 0

    private val maxP = 70
    private val kX = FloatArray(maxP); private val kY = FloatArray(maxP)
    private val kVx = FloatArray(maxP); private val kVy = FloatArray(maxP)
    private val kAge = FloatArray(maxP); private val kLife = FloatArray(maxP)
    private var nk = 0

    // ---------- paints and sprites ----------
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val plusPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.PLUS }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; blendMode = BlendMode.PLUS }
    private val tint = Paint()
    private val dest = RectF()
    private val clip = Path()

    private fun glow(r: Int, g: Int, b: Int): Bitmap {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            32f, 32f, 32f,
            intArrayOf(Color.argb(255, r, g, b), Color.argb(199, r, g, b), Color.argb(71, r, g, b), Color.argb(0, r, g, b)),
            floatArrayOf(0f, 0.25f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        Canvas(bmp).drawRect(0f, 0f, 64f, 64f, p)
        return bmp
    }

    private fun puff(r: Int, g: Int, b: Int, a: Float): Bitmap {
        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 14) {
            val ang = rnd(0f, TAU); val d = rnd(0f, 30f)
            val cx = 64f + cos(ang) * d; val cy = 64f + sin(ang) * d; val rad = rnd(18f, 36f)
            p.shader = RadialGradient(
                cx, cy, rad,
                intArrayOf(Color.argb((255 * a).toInt(), r, g, b), Color.argb((255 * a * 0.45f).toInt(), r, g, b), Color.argb(0, r, g, b)),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, 128f, 128f, p)
        }
        return bmp
    }

    private val sCore = glow(238, 249, 255)
    private val sCyan = glow(95, 214, 255)
    private val sBlue = glow(48, 110, 255)
    private val sDeep = glow(70, 48, 225)
    private val sBlack = glow(0, 0, 0)
    private val smkDark = Array(3) { puff(34, 36, 44, 0.34f) }
    private val smkMid = Array(3) { puff(112, 118, 130, 0.3f) }
    private val smkBlue = Array(2) { puff(70, 120, 200, 0.34f) }

    // ---------- frame loop ----------
    private var running = false
    private var lastNs = 0L
    private val prevBox = Rect()
    private val curBox = Rect()

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastNs == 0L) 0f else min(0.05f, (frameTimeNanos - lastNs) / 1e9f)
            lastNs = frameTimeNanos
            if (dt > 0f) {
                step(dt)
                invalidateDirty()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun resume() {
        if (running) return
        running = true
        lastNs = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    fun pause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    // ---------- layout and icons ----------
    override fun onLayout(changed: Boolean, l: Int, top: Int, r: Int, b: Int) {
        super.onLayout(changed, l, top, r, b)
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        offX = loc[0]
        offY = loc[1]
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (!usingRegistry) applyIcons(fallbackRects())
    }

    fun reloadPrefs() {
        if (!usingRegistry) applyIcons(fallbackRects()) else applyScale()
    }

    /** Called with screen-pixel rectangles from the icon finder. Empty list means use the grid fallback. */
    fun setIcons(screenRects: List<RectF>) {
        if (screenRects.isEmpty()) {
            usingRegistry = false
            if (width > 0) applyIcons(fallbackRects())
            return
        }
        usingRegistry = true
        applyIcons(screenRects.map { RectF(it.left - offX, it.top - offY, it.right - offX, it.bottom - offY) })
    }

    private fun fallbackRects(): List<RectF> {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return emptyList()
        val cols = Prefs.cols(context)
        val rows = Prefs.rows(context)
        val top = h * 0.10f
        val bottom = h * 0.84f
        val cw = w / cols
        val ch = (bottom - top) / rows
        val s = min(cw * 0.62f, ch * 0.55f)
        val list = ArrayList<RectF>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val cx = (c + 0.5f) * cw
            val yy = top + r * ch + (ch - s * 1.25f) / 2f
            list.add(RectF(cx - s / 2, yy, cx + s / 2, yy + s))
        }
        for (c in 0 until cols) {
            val cx = (c + 0.5f) * cw
            list.add(RectF(cx - s / 2, h * 0.89f, cx + s / 2, h * 0.89f + s))
        }
        return list
    }

    private fun medianWidth(list: List<Float>): Float {
        val s = list.sorted()
        return s[s.size / 2]
    }

    private fun applyScale() {
        size = medianWidth(icons.map { it.r.width() })
        for (c in icons) c.big = c.r.width() > size * 1.5f
        sc = size / 58f
        ds = sc * 0.55f * (Prefs.scalePct(context) / 100f)
        st.ds = ds
    }

    private fun applyIcons(rects: List<RectF>) {
        if (rects.isEmpty()) return
        val sorted = rects.sortedWith(compareBy({ it.centerY() }, { it.centerX() }))
        // The icon finder reports the same layout many times, so skip identical lists.
        if (sorted.size == icons.size && sorted.indices.all {
                abs(sorted[it].left - icons[it].r.left) < 1.5f && abs(sorted[it].top - icons[it].r.top) < 1.5f
            }
        ) return

        val avg = medianWidth(rects.map { it.width() })
        var row = 0
        var rowCy = sorted[0].centerY()
        val newIcons = ArrayList<Icon>()
        for (r in sorted) {
            if (r.centerY() - rowCy > avg * 0.5f) { row++; rowCy = r.centerY() }
            val ic = Icon(RectF(r), row)
            icons.firstOrNull { abs(it.cx - ic.cx) < avg * 0.3f && abs(it.cy - ic.cy) < avg * 0.3f }?.let {
                ic.burn = it.burn; ic.heat = it.heat; ic.ember = it.ember
            }
            newIcons.add(ic)
        }
        icons = newIcons
        applyScale()
        if (!placed) {
            placed = true
            land(Random.nextInt(icons.size), true)
        } else if (mode == Mode.IDLE || mode == Mode.CROUCH) {
            val ni = nearestIcon(st.x, st.y)
            val nc = ic(ni)
            land(ni, true, if (nc.big) clampF(st.x, nc.r.left + 16f, max(nc.r.left + 17f, nc.r.right - 16f)) else null)
        }
    }

    private fun nearestIcon(px: Float, py: Float): Int {
        var best = 0
        var bd = Float.MAX_VALUE
        for (i in icons.indices) {
            val r = icons[i].r
            val d = hypot(max(0f, max(r.left - px, px - r.right)), max(0f, max(r.top - py, py - r.bottom)))
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    private fun ic(i: Int) = icons[i.coerceIn(0, icons.size - 1)]
    private fun sitX(i: Int) = ic(i).cx
    /** Where on an icon the dragon lands: the middle of a normal icon, anywhere along a big one. */
    private fun landX(i: Int): Float {
        val c = ic(i)
        if (!c.big) return c.cx
        val lo = max(c.r.left + 16f, 100f * ds * 0.9f); val hi = min(c.r.right - 16f, width - 100f * ds * 0.9f)
        return if (hi > lo) rnd(lo, hi) else c.cx
    }
    private fun sitY(i: Int) = ic(i).r.top + size * 0.06f

    // ---------- flight paths (Catmull-Rom through waypoints, then distance lookup) ----------
    private fun buildPath(px: List<Float>, py: List<Float>): FlightPath {
        val n = px.size
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for (i in 0 until n - 1) {
            val i0 = max(i - 1, 0); val i3 = min(i + 2, n - 1)
            for (j in 0 until 16) {
                val tt = j / 16f; val t2 = tt * tt; val t3 = t2 * tt
                xs.add(0.5f * ((2 * px[i]) + (-px[i0] + px[i + 1]) * tt + (2 * px[i0] - 5 * px[i] + 4 * px[i + 1] - px[i3]) * t2 + (-px[i0] + 3 * px[i] - 3 * px[i + 1] + px[i3]) * t3))
                ys.add(0.5f * ((2 * py[i]) + (-py[i0] + py[i + 1]) * tt + (2 * py[i0] - 5 * py[i] + 4 * py[i + 1] - py[i3]) * t2 + (-py[i0] + 3 * py[i] - 3 * py[i + 1] + py[i3]) * t3))
            }
        }
        xs.add(px[n - 1]); ys.add(py[n - 1])
        val cum = FloatArray(xs.size)
        for (i in 1 until xs.size) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        return FlightPath(xs.toFloatArray(), ys.toFloatArray(), cum)
    }

    private fun pathAt(p: FlightPath, s0: Float, out: FloatArray) {
        val s = clampF(s0, 0f, p.len)
        var i = 1
        while (i < p.cum.size - 1 && p.cum[i] < s) i++
        val a = p.cum[i - 1]; val b = p.cum[i]; val u = if (b > a) (s - a) / (b - a) else 0f
        out[0] = p.x[i - 1] + (p.x[i] - p.x[i - 1]) * u
        out[1] = p.y[i - 1] + (p.y[i] - p.y[i - 1]) * u
    }

    private fun waypoint(prevX: Float, prevY: Float): FloatArray {
        val mx = 158f * ds
        val top = height * 0.12f + 150f * ds
        val bot = height * 0.86f
        var best = floatArrayOf(width / 2f, height / 2f)
        var bd = -1f
        for (k in 0 until 8) {
            val p = floatArrayOf(rnd(mx, max(mx + 1f, width - mx)), rnd(top, max(top + 1f, bot)))
            val d = hypot(p[0] - prevX, p[1] - prevY)
            if (d > height * 0.28f) return p
            if (d > bd) { bd = d; best = p }
        }
        return best
    }

    // ---------- behaviour ----------
    private fun land(i: Int, silent: Boolean, x: Float? = null) {
        iconIdx = i.coerceIn(0, icons.size - 1)
        st.x = x ?: (if (silent && ic(iconIdx).big) landX(iconIdx) else sitX(iconIdx)); st.y = sitY(iconIdx)
        air = false; mode = Mode.IDLE; then = null; path = null
        timer = rnd(1.4f, 3.6f)
        // face the screen edge on outer columns so the tail stays on screen
        val cx = if (ic(iconIdx).big) st.x else ic(iconIdx).cx
        val tailM = 160f * ds
        faceT = if (ic(iconIdx).big) {
            if (cx < tailM) -1f else if (cx > width - tailM) 1f else (if (Random.nextBoolean()) 1f else -1f)
        } else if (cx < width * 0.3f) -1f else if (cx > width * 0.7f) 1f else (if (Random.nextBoolean()) 1f else -1f)
        if (silent) st.face = faceT else st.crouch = 0.9f
    }

    private fun launch(ex: Float, ey: Float, after: () -> Unit, hop: Boolean, wander: Boolean) {
        val sx = st.x; val sy = st.y
        val ceil = min(height * 0.12f + 105f * ds, min(sy, ey))
        val px = ArrayList<Float>(); val py = ArrayList<Float>()
        px.add(sx); py.add(sy)
        if (hop) {
            px.add((sx + ex) / 2f); py.add(max(ceil, min(sy, ey) - size * 1.05f))
        } else if (wander) {
            var qx = sx; var qy = sy
            val cnt = 1 + Random.nextInt(3)
            for (i in 0 until cnt) { val w = waypoint(qx, qy); px.add(w[0]); py.add(w[1]); qx = w[0]; qy = w[1] }
        } else {
            px.add((sx + ex) / 2f + rnd(-size, size)); py.add(max(ceil, min(sy, ey) - size * rnd(0.8f, 1.8f)))
        }
        px.add(ex); py.add(ey)
        val p = buildPath(px, py)
        path = p
        val speed = (if (hop) 330f else rnd(250f, 340f)) * sc
        dur = max(if (hop) 0.58f else 0.95f, p.len / speed)
        t = 0f; mode = Mode.FLY; air = true; then = after; st.wingPh = -(PI / 2).toFloat()
    }

    private fun takeOff(ex: Float, ey: Float, after: () -> Unit, hop: Boolean, wander: Boolean) {
        if (air) launch(ex, ey, after, hop, wander)
        else { mode = Mode.CROUCH; t = 0f; dur = 0.22f; then = { launch(ex, ey, after, hop, wander) } }
    }

    private fun neighbors(i: Int): List<Int> {
        val c = ic(i)
        fun gap(o: RectF) = hypot(max(0f, max(o.left - c.r.right, c.r.left - o.right)), max(0f, max(o.top - c.r.bottom, c.r.top - o.bottom)))
        return icons.indices.filter { it != i && gap(icons[it].r) < size * 1.7f }
    }

    private fun flyToIcon(j: Int, hop: Boolean) { val lx = landX(j); takeOff(lx, sitY(j), { land(j, false, lx) }, hop, !hop) }

    private fun startFire(j: Int, after: () -> Unit) {
        mode = Mode.FIRE; target = j; t = 0f; acc = 0f; fireDur = 1.7f
        faceT = if (ic(j).cx >= st.x) 1f else -1f
        then = after
    }

    private fun randIcon(except: Int): Int {
        if (icons.size < 2) return 0
        var j: Int
        do { j = Random.nextInt(icons.size) } while (j == except)
        return j
    }

    private fun afterAirFire(j: Int): () -> Unit = { flyToIcon(if (Random.nextFloat() < 0.55f) j else randIcon(j), false) }

    private fun fireAt(j: Int) {
        val c = ic(j)
        val near = !air && max(0f, max(c.r.left - st.x, st.x - c.r.right)) < size * 3.3f && c.cy - st.y > -size * 1.3f && c.cy - st.y < size * 3.2f
        if (near) {
            startFire(j) { mode = Mode.IDLE; timer = rnd(1.4f, 3f) }
        } else {
            val side = if (c.cx > width / 2f) -1f else 1f
            val f = -side
            val tailM = 160f * ds; val headM = 100f * ds
            val lo = if (f > 0) tailM else headM; val hi = width - (if (f > 0) headM else tailM)
            val hx = clampF(c.cx + side * size * 2.5f, lo, max(lo + 1f, hi))
            val hy = clampF(c.cy - size * 1.15f, height * 0.12f + 150f * ds, height * 0.86f)
            takeOff(hx, hy, { startFire(j, afterAirFire(j)) }, false, true)
        }
    }

    private fun startWalk(): Boolean {
        val c = ic(iconIdx)
        val lo = max(c.r.left + 16f, 100f * ds * 0.9f); val hi = min(c.r.right - 16f, width - 100f * ds * 0.9f)
        if (!(hi - lo > 80f * ds)) return false
        var tx = st.x; var tries = 0
        while (abs(tx - st.x) < 60f * ds && tries++ < 8) tx = rnd(lo, hi)
        walkTo = clampF(tx, lo, hi); faceT = if (walkTo > st.x) 1f else -1f; mode = Mode.WALK
        return true
    }

    private fun doAction(kind: String, idx: Int) {
        when (kind) {
            "walk" -> { if (!startWalk()) timer = rnd(1f, 2f) }
            "jump" -> {
                val nb = neighbors(iconIdx)
                if (nb.isEmpty()) return doAction("fly", -1)
                flyToIcon(if (idx >= 0) idx else nb.random(), true)
            }
            "fly" -> flyToIcon(if (idx >= 0) idx else randIcon(iconIdx), false)
            else -> fireAt(if (idx >= 0) idx else randIcon(iconIdx))
        }
    }

    private fun chooseNext() {
        val k = pendKind
        if (k != null) { pendKind = null; doAction(k, pendIdx); return }
        val r = Random.nextFloat()
        if (ic(iconIdx).big && r < 0.5f) { doAction("walk", -1); return }
        doAction(if (r < 0.35f) "jump" else if (r < 0.65f) "fly" else "fire", -1)
    }

    // ---------- effects ----------
    private fun emitFlame(mx: Float, my: Float, tx: Float, ty: Float) {
        if (nf >= maxF) return
        val dx = tx - mx; val dy = ty - my; val d = max(1f, hypot(dx, dy)); val ux = dx / d; val uy = dy / d
        val kind = if (Random.nextFloat() < 0.35f) 0 else 1
        val sp = clampF(d / 0.4f, 300f * sc, 720f * sc) * rnd(0.85f, 1.15f)
        val a = (Random.nextFloat() - 0.5f) * (if (kind == 0) 0.1f else 0.34f)
        val cs = cos(a); val sn = sin(a)
        fX[nf] = mx; fY[nf] = my
        fVx[nf] = (ux * cs - uy * sn) * sp; fVy[nf] = (ux * sn + uy * cs) * sp
        fPx[nf] = -uy; fPy[nf] = ux
        fAge[nf] = 0f; fLife[nf] = if (kind == 0) rnd(0.3f, 0.46f) else rnd(0.45f, 0.8f)
        fSize[nf] = (if (kind == 0) rnd(7f, 11f) else rnd(11f, 19f)) * ds * 1.35f
        fSeed[nf] = rnd(0f, TAU); fKind[nf] = kind
        nf++
    }

    private fun addSplash(x: Float, y: Float) {
        if (nf >= maxF) return
        val side = if (Random.nextBoolean()) -1f else 1f
        fX[nf] = x; fY[nf] = y
        fVx[nf] = side * rnd(60f, 190f) * sc; fVy[nf] = -rnd(30f, 140f) * sc
        fPx[nf] = 0f; fPy[nf] = 1f
        fAge[nf] = 0f; fLife[nf] = rnd(0.22f, 0.4f); fSize[nf] = rnd(7f, 12f) * ds * 1.3f
        fSeed[nf] = rnd(0f, TAU); fKind[nf] = 2
        nf++
    }

    private fun addSmoke(x: Float, y: Float, lit: Float) {
        if (ns >= maxS) return
        sX[ns] = x; sY[ns] = y
        sVx[ns] = rnd(-10f, 10f) * sc; sVy[ns] = -rnd(28f, 56f) * sc
        sAge[ns] = 0f; sLife[ns] = rnd(1.3f, 2.4f); sSize[ns] = rnd(10f, 17f) * sc
        sRot[ns] = rnd(0f, TAU); sRv[ns] = rnd(-0.7f, 0.7f); sLit[ns] = lit
        sSeed[ns] = rnd(0f, TAU); sVar[ns] = Random.nextInt(3)
        ns++
    }

    private fun addSpark(x: Float, y: Float, ang: Float, spd: Float) {
        if (nk >= maxP) return
        kX[nk] = x; kY[nk] = y; kVx[nk] = cos(ang) * spd; kVy[nk] = sin(ang) * spd
        kAge[nk] = 0f; kLife[nk] = rnd(0.25f, 0.6f)
        nk++
    }

    // ---------- per-frame update ----------
    private fun step(dt: Float) {
        if (icons.isEmpty()) return
        time += dt
        for (c in icons) {
            c.heat = max(0f, c.heat - dt * 1.4f)
            c.ember = max(0f, c.ember - dt * 0.25f)
            if (c.heat < 0.05f) c.burn = max(0f, c.burn - dt * 0.035f)
            if (c.burn > 0.2f) {
                c.sm += dt * (0.9f + 5f * c.heat) * c.burn
                while (c.sm >= 1f) { c.sm -= 1f; addSmoke(c.r.left + rnd(.2f, .8f) * c.r.width(), c.r.top + rnd(.05f, .4f) * c.r.height(), c.heat) }
            }
        }
        nextBlink -= dt
        if (nextBlink < 0f) { blinkT = 0.14f; nextBlink = rnd(2f, 5f) }
        if (blinkT > 0f) blinkT -= dt
        st.eye = if (blinkT > 0f) 0.1f else 1f
        nextLook -= dt
        if (nextLook < 0f) { lookT = rnd(-0.14f, 0.12f); nextLook = rnd(1.2f, 3.2f) }
        look += (lookT - look) * min(1f, dt * 3f)

        st.sp += ((if (air) 1f else 0f) - st.sp) * min(1f, dt * 7f)
        st.walk += ((if (mode == Mode.WALK) 1f else 0f) - st.walk) * min(1f, dt * 6f)
        val flapHz = if (air) 2.5f + 0.9f * sin(time * 0.8f) + (if (mode == Mode.FIRE) 0.5f else 0f) else 0.5f
        st.wingPh += dt * TAU * flapHz
        st.crouch = if (mode == Mode.CROUCH) clampF(t / dur * 1.15f, 0f, 1f) else max(0f, st.crouch - dt * (if (air) 6f else 4.5f))

        val ox = st.x; val oy = st.y
        var firing = false
        when (mode) {
            Mode.IDLE -> { timer -= dt; if (timer <= 0f) chooseNext() }
            Mode.WALK -> {
                val spd = 32f * sc * st.walk * st.walk
                val dx = walkTo - st.x; val stp = min(abs(dx), spd * dt)
                st.x += (if (dx > 0f) stp else -stp); st.gait += stp / ds / 46.7f
                if (abs(walkTo - st.x) < 0.5f) { mode = Mode.IDLE; timer = rnd(1.4f, 3.2f) }
            }
            Mode.CROUCH -> {
                t += dt
                if (t >= dur) { val cb = then; then = null; cb?.invoke() }
            }
            Mode.FLY -> {
                t += dt
                val e = min(1f, t / dur); val s = e * e * (3f - 2f * e)
                path?.let { pathAt(it, s * it.len, pp); st.x = pp[0]; st.y = pp[1] }
                if (t >= dur) { val cb = then; then = null; cb?.invoke() }
            }
            Mode.FIRE -> {
                firing = true
                t += dt
                val c = ic(target)
                st.mouth = clampF(min(t / 0.25f, (fireDur - t) / 0.25f), 0f, 1f)
                model.headLocal(st.sp, st.walk, hl); val hx = hl[0]; val hy = hl[1]
                val ang = atan2(c.cy - (st.y + (hy + st.bob) * ds), max(8f, abs(c.cx - (st.x + st.face * hx * ds))))
                headGoal = clampF(ang, -0.6f, 1.0f)
                if (t > 0.3f && t < fireDur - 0.25f) {
                    model.mouthPos(st, mp)
                    acc += dt * 170f
                    while (acc >= 1f) { acc -= 1f; emitFlame(mp[0], mp[1], c.cx, c.cy) }
                    if (Random.nextFloat() < dt * 14f) addSpark(mp[0], mp[1], atan2(c.cy - mp[1], c.cx - mp[0]) + rnd(-0.5f, 0.5f), rnd(180f, 420f) * sc)
                    c.burn = min(1f, c.burn + dt * 0.62f * (size / max(size, c.r.width() * 0.5f))); c.heat = min(1f, c.heat + dt * 4f); c.ember = 1f
                }
                if (t >= fireDur) { st.mouth = 0f; val cb = then; then = null; cb?.invoke() }
            }
        }
        if (!firing) st.mouth = max(0f, st.mouth - dt * 6f)
        val hg = if (firing) headGoal else model.restHead(st.sp, st.walk) + look * (1f - st.sp)
        st.head += (hg - st.head) * min(1f, dt * 12f)

        val cvx = (st.x - ox) / dt; val cvy = (st.y - oy) / dt
        vx += (cvx - vx) * min(1f, dt * 10f); vy += (cvy - vy) * min(1f, dt * 10f)
        if (mode == Mode.FLY && abs(vx) > 30f * sc) faceT = if (vx > 0f) 1f else -1f
        st.face += (faceT - st.face) * min(1f, dt * 11f)
        val wp = if (mode == Mode.FLY) clampF(atan2(vy, max(90f * sc, abs(vx))) * 0.6f, -0.55f, 0.55f) else 0f
        st.pitch += (wp - st.pitch) * min(1f, dt * 7f)
        st.bob = if (air) sin(st.wingPh) * 3f - 4f * st.sp else sin(time * 2.3f) * 0.5f + sin(st.gait * TAU * 2f) * 1.3f * st.walk
        st.time = time; st.ds = ds

        // flame particles
        val tgt = if (mode == Mode.FIRE) ic(target) else null
        var i = nf - 1
        while (i >= 0) {
            fAge[i] += dt
            if (tgt != null && fX[i] > tgt.r.left - 4f && fX[i] < tgt.r.right + 4f && fY[i] > tgt.r.top - 4f && fY[i] < tgt.r.bottom + 4f) {
                fAge[i] += dt * 2.2f
                if (Random.nextFloat() < 0.22f) {
                    addSplash(fX[i], tgt.r.top + size * 0.1f)
                    if (Random.nextFloat() < 0.35f) addSpark(fX[i], tgt.r.top, -(PI / 2).toFloat() + rnd(-1.2f, 1.2f), rnd(120f, 380f) * sc)
                }
            }
            if (fAge[i] >= fLife[i]) {
                if (fKind[i] == 1 && Random.nextFloat() < 0.3f) addSmoke(fX[i], fY[i], 1f)
                nf--
                fX[i] = fX[nf]; fY[i] = fY[nf]; fVx[i] = fVx[nf]; fVy[i] = fVy[nf]; fPx[i] = fPx[nf]; fPy[i] = fPy[nf]
                fAge[i] = fAge[nf]; fLife[i] = fLife[nf]; fSize[i] = fSize[nf]; fSeed[i] = fSeed[nf]; fKind[i] = fKind[nf]
            } else {
                val tt = fAge[i] / fLife[i]
                val wob = sin(fAge[i] * 26f + fSeed[i]) * 300f * sc * tt * (if (fKind[i] == 1) 1f else 0.4f)
                fVx[i] += fPx[i] * wob * dt; fVy[i] += fPy[i] * wob * dt - 90f * sc * dt
                fX[i] += fVx[i] * dt; fY[i] += fVy[i] * dt
                val k = 0.4f.pow(dt); fVx[i] *= k; fVy[i] *= k
            }
            i--
        }
        i = nk - 1
        while (i >= 0) {
            kAge[i] += dt
            if (kAge[i] >= kLife[i]) {
                nk--
                kX[i] = kX[nk]; kY[i] = kY[nk]; kVx[i] = kVx[nk]; kVy[i] = kVy[nk]; kAge[i] = kAge[nk]; kLife[i] = kLife[nk]
            } else {
                kVy[i] += 520f * sc * dt; kX[i] += kVx[i] * dt; kY[i] += kVy[i] * dt
            }
            i--
        }
        i = ns - 1
        while (i >= 0) {
            sAge[i] += dt
            if (sAge[i] >= sLife[i]) {
                ns--
                sX[i] = sX[ns]; sY[i] = sY[ns]; sVx[i] = sVx[ns]; sVy[i] = sVy[ns]; sAge[i] = sAge[ns]; sLife[i] = sLife[ns]
                sSize[i] = sSize[ns]; sRot[i] = sRot[ns]; sRv[i] = sRv[ns]; sLit[i] = sLit[ns]; sSeed[i] = sSeed[ns]; sVar[i] = sVar[ns]
            } else {
                val tt = sAge[i] / sLife[i]
                sVx[i] += sin(sAge[i] * 2.3f + sSeed[i]) * 38f * sc * dt * (0.4f + tt)
                sVy[i] += (-(34f * sc) * (1f - tt * 0.5f) - sVy[i]) * min(1f, dt * 1.2f)
                sX[i] += sVx[i] * dt; sY[i] += sVy[i] * dt; sRot[i] += sRv[i] * dt
            }
            i--
        }
    }

    /** Redraw only the area that changed. */
    private fun invalidateDirty() {
        val pad = (175f * ds).toInt()
        curBox.set((st.x - pad).toInt(), (st.y - pad).toInt(), (st.x + pad).toInt(), (st.y + pad / 3).toInt())
        for (i in 0 until nf) { val s = (fSize[i] * 3.5f).toInt(); curBox.union((fX[i] - s).toInt(), (fY[i] - s).toInt(), (fX[i] + s).toInt(), (fY[i] + s).toInt()) }
        for (i in 0 until ns) { val s = (sSize[i] * 4.4f).toInt(); curBox.union((sX[i] - s).toInt(), (sY[i] - s).toInt(), (sX[i] + s).toInt(), (sY[i] + s).toInt()) }
        for (i in 0 until nk) curBox.union((kX[i] - 20).toInt(), (kY[i] - 20).toInt(), (kX[i] + 20).toInt(), (kY[i] + 20).toInt())
        for (c in icons) {
            if (c.burn > 0.01f || c.heat > 0.02f) {
                val g = (size * 0.7f).toInt()
                curBox.union(c.r.left.toInt() - g, c.r.top.toInt() - g, c.r.right.toInt() + g, c.r.bottom.toInt() + g)
            }
        }
        val inv = Rect(curBox)
        inv.union(prevBox)
        prevBox.set(curBox)
        invalidate(inv)
    }

    // ---------- drawing ----------
    override fun onDraw(c: Canvas) {
        if (icons.isEmpty()) return
        for (ic in icons) if (ic.burn > 0.01f || ic.heat > 0.02f) drawBurn(c, ic)
        drawSmoke(c)
        model.draw(c, st)
        drawFlame(c)
    }

    private fun sprite(c: Canvas, b: Bitmap, cx: Float, cy: Float, half: Float, paint: Paint) {
        dest.set(cx - half, cy - half, cx + half, cy + half)
        c.drawBitmap(b, null, dest, paint)
    }

    private fun drawBurn(c: Canvas, ic: Icon) {
        val r = ic.r
        if (ic.heat > 0.02f) {
            plusPaint.alpha = (ic.heat * 0.85f * 255f).toInt()
            dest.set(r.left - size * 0.6f, r.top - size * 0.6f, r.right + size * 0.6f, r.bottom + size * 0.6f)
            c.drawBitmap(sBlue, null, dest, plusPaint)
            plusPaint.alpha = (ic.heat * 0.5f * 255f).toInt()
            dest.set(r.left - size * 0.1f, r.top - size * 0.1f, r.right + size * 0.1f, r.bottom + size * 0.1f)
            c.drawBitmap(sCyan, null, dest, plusPaint)
        }
        if (ic.burn > 0.01f) {
            c.save()
            clip.reset()
            clip.addRoundRect(r, size * 0.24f, size * 0.24f, Path.Direction.CW)
            c.clipPath(clip)
            tint.color = Color.argb((ic.burn * 0.55f * 255f).toInt(), 14, 10, 12)
            c.drawRect(r, tint)
            spritePaint.alpha = min(255, (ic.burn * 1.1f * 255f).toInt())
            for (s in ic.scorch) {
                val rad = s[2] * size * (0.6f + ic.burn * 0.6f)
                sprite(c, sBlack, ic.cx + s[0] * r.width(), ic.cy + s[1] * r.height(), rad, spritePaint)
            }
            if (ic.ember > 0.02f) {
                for (s in ic.scorch) {
                    val fl = 0.5f + 0.5f * sin(time * 9f + s[3])
                    plusPaint.alpha = (ic.ember * min(1f, ic.burn * 1.5f) * fl * 0.55f * 255f).toInt()
                    sprite(c, sCyan, ic.cx + s[0] * r.width(), ic.cy + s[1] * r.height(), s[2] * size * 0.45f, plusPaint)
                }
            }
            c.restore()
        }
    }

    private fun drawSmoke(c: Canvas) {
        for (i in 0 until ns) {
            val tt = sAge[i] / sLife[i]
            val s = sSize[i] * (1f + tt * 3.2f)
            val env = min(1f, tt * 7f) * (1f - tt).pow(1.5f)
            c.save(); c.translate(sX[i], sY[i]); c.rotate(deg(sRot[i]))
            spritePaint.alpha = (env * 0.55f * (1f - tt * 0.7f) * 255f).toInt()
            dest.set(-s, -s, s, s); c.drawBitmap(smkDark[sVar[i]], null, dest, spritePaint)
            spritePaint.alpha = (env * 0.6f * tt * 255f).toInt()
            c.drawBitmap(smkMid[sVar[i]], null, dest, spritePaint)
            val lit = sLit[i] * max(0f, 1f - sAge[i] / 0.45f)
            if (lit > 0.02f) {
                plusPaint.alpha = (env * lit * 0.55f * 255f).toInt()
                c.drawBitmap(smkBlue[sVar[i] % 2], null, dest, plusPaint)
            }
            c.restore()
        }
    }

    private fun drawFlame(c: Canvas) {
        for (i in 0 until nf) {
            val tt = fAge[i] / fLife[i]
            val kind = fKind[i]
            val s = fSize[i] * (0.55f + tt * (if (kind == 0) 1.0f else 1.5f))
            val spd = hypot(fVx[i], fVy[i])
            val stretch = 1f + min(1.6f, spd / (520f * sc)) * (if (kind == 2) 0.2f else 1f)
            val spr = if (kind == 0) (if (tt < 0.4f) sCore else if (tt < 0.75f) sCyan else sBlue)
            else (if (tt < 0.12f) sCore else if (tt < 0.4f) sCyan else if (tt < 0.75f) sBlue else sDeep)
            c.save(); c.translate(fX[i], fY[i]); c.rotate(deg(atan2(fVy[i], fVx[i]))); c.scale(stretch, 1f)
            plusPaint.alpha = ((1f - tt) * 0.32f * 255f).toInt()
            sprite(c, sBlue, 0f, 0f, s * 1.7f, plusPaint)
            plusPaint.alpha = (min(1f, (1f - tt) * 1.1f) * 0.9f * 255f).toInt()
            sprite(c, spr, 0f, 0f, s, plusPaint)
            c.restore()
        }
        sparkPaint.strokeWidth = max(1f, 1.6f * sc)
        for (i in 0 until nk) {
            val a = 1f - kAge[i] / kLife[i]
            sparkPaint.color = Color.argb((a * 255f).toInt(), 190, 230, 255)
            c.drawLine(kX[i], kY[i], kX[i] - kVx[i] * 0.035f, kY[i] - kVy[i] * 0.035f, sparkPaint)
        }
        if (mode == Mode.FIRE && st.mouth > 0.05f) {
            model.mouthPos(st, mp)
            val fl = 0.85f + 0.15f * sin(time * 40f)
            val s = size * 1.1f * st.mouth * fl
            plusPaint.alpha = (0.7f * st.mouth * 255f).toInt(); sprite(c, sCyan, mp[0], mp[1], s, plusPaint)
            plusPaint.alpha = (0.9f * st.mouth * 255f).toInt(); sprite(c, sCore, mp[0], mp[1], s * 0.4f, plusPaint)
        }
    }
}
