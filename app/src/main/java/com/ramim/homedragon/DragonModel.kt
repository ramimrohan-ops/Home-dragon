package com.ramim.homedragon

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun deg(r: Float) = r * 57.29578f
private fun withAlpha(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24)

/**
 * Procedural realistic dragon (side view, facing +x, origin on the ground under the feet, y down).
 * Same geometry as the browser demo.
 *
 * Perched (sp = 0): seated upright, S-curved neck, frilled head, wings raised and open behind, tail curled.
 * Flying (sp = 1): horizontal body, flapping wings.
 * Colours follow the reference: teal back, orange sides, purple tail underside, teal-purple-orange wings.
 */
class DragonModel {

    class State {
        var x = 0f; var y = 0f
        var face = 1f; var ds = 1f
        var sp = 0f            // 0 perched, 1 flying
        var pitch = 0f
        var head = 0.28f       // absolute head angle
        var mouth = 0f
        var eye = 1f
        var time = 0f
        var wingPh = 0f
        var bob = 0f
        var crouch = 0f
        var walk = 0f          // 0 seated, 1 crawling on a large icon
        var gait = 0f          // walk cycle in whole cycles
    }

    private object Col {
        val hi = Color.parseColor("#F6B06A"); val mid = Color.parseColor("#E0713A")
        val lo = Color.parseColor("#A8402A"); val deep = Color.parseColor("#5A1F1F")
        val line = Color.argb(189, 40, 10, 14)
        val tealLo = Color.parseColor("#1A5A78")
        val plateHi = Color.parseColor("#F6CF9C"); val plateLo = Color.parseColor("#C9794A")
        val boneHi = Color.parseColor("#F7E2BD"); val boneLo = Color.parseColor("#B9703A")
        val eye = Color.parseColor("#FF7A2A")
        val legTeal = Color.parseColor("#4AA896"); val legTealDark = Color.parseColor("#2B7468")
    }

    // spine control points: x, y, radius. tail tip first, head base last (14 points)
    private val gr = arrayOf(
        floatArrayOf(-121f, -56f, 1.2f), floatArrayOf(-128f, -40f, 2.2f), floatArrayOf(-118f, -22f, 3.8f),
        floatArrayOf(-96f, -10f, 6f), floatArrayOf(-70f, -7f, 9f), floatArrayOf(-46f, -11f, 12.5f),
        floatArrayOf(-26f, -22f, 18f), floatArrayOf(-12f, -38f, 22f), floatArrayOf(-4f, -54f, 23f),
        floatArrayOf(5f, -68f, 18f), floatArrayOf(7f, -82f, 13f), floatArrayOf(11f, -95f, 11f),
        floatArrayOf(19f, -104f, 9.5f), floatArrayOf(30f, -107f, 8.5f)
    )
    private val fl = arrayOf(
        floatArrayOf(-158f, -50f, 1.2f), floatArrayOf(-140f, -46f, 2.2f), floatArrayOf(-118f, -44f, 3.6f),
        floatArrayOf(-96f, -43f, 5.5f), floatArrayOf(-72f, -43f, 8f), floatArrayOf(-52f, -43f, 11.5f),
        floatArrayOf(-34f, -43f, 14.5f), floatArrayOf(-18f, -43f, 16f), floatArrayOf(-2f, -44f, 16f),
        floatArrayOf(12f, -44f, 14.5f), floatArrayOf(24f, -46f, 12.5f), floatArrayOf(36f, -49f, 10f),
        floatArrayOf(47f, -52f, 8.5f), floatArrayOf(58f, -55f, 7.5f)
    )
    // crawling pose (large icons): low horizontal body, tail trailing, head raised
    private val wk = arrayOf(
        floatArrayOf(-148f, -40f, 1.2f), floatArrayOf(-134f, -27f, 2.2f), floatArrayOf(-114f, -19f, 3.8f),
        floatArrayOf(-92f, -20f, 6f), floatArrayOf(-70f, -30f, 9f), floatArrayOf(-52f, -40f, 12.5f),
        floatArrayOf(-34f, -47f, 17f), floatArrayOf(-16f, -49f, 19f), floatArrayOf(0f, -49f, 19f),
        floatArrayOf(14f, -52f, 15f), floatArrayOf(24f, -60f, 12f), floatArrayOf(34f, -70f, 10.5f),
        floatArrayOf(44f, -77f, 9.5f), floatArrayOf(55f, -79f, 8.5f)
    )
    private val n = gr.size
    private val tailN = 6
    private val headI = 13
    private val shI = 8

    // pose buffers
    private val pX = FloatArray(n); private val pY = FloatArray(n); private val pR = FloatArray(n)
    private var hx = 0f; private var hy = 0f; private var hang = 0f
    private val feet = FloatArray(8)   // fN, fF, hN, hF  (x,y each)
    private val hips = FloatArray(8)
    private var attX = 0f; private var attY = 0f
    private var wkv = 0f          // crawl blend actually applied (zero while flying)
    private val stepA = FloatArray(2); private val stepB = FloatArray(2)

    // spine samples
    private val per = 5
    private val m = (n - 1) * per + 1
    private val sX = FloatArray(m); private val sY = FloatArray(m); private val sR = FloatArray(m)
    private val dX = FloatArray(m); private val dY = FloatArray(m); private val nX = FloatArray(m); private val nY = FloatArray(m)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
    private val path = Path()
    private val body = Path()
    private val tmp = Path()
    private val oval = RectF()
    private val knee = FloatArray(2)
    private val tipX = FloatArray(4); private val tipY = FloatArray(4)
    private val midX = FloatArray(3); private val midY = FloatArray(3)
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bladeTip = FloatArray(2)

    init {
        // Scale texture drawn at 4x and shown small through the shader matrix.
        val bmp = Bitmap.createBitmap(64, 52, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
        fun arcs(color: Int, r: Float, dy: Float, a0: Float) {
            p.color = color
            for (k in 0 until 2) { oval.set(16f + k * 32f - r, 12.8f + dy - r, 16f + k * 32f + r, 12.8f + dy + r); c.drawArc(oval, a0, 180f - 2 * a0, false, p) }
            for (k in 0 until 3) { oval.set(k * 32f - r, 38.8f + dy - r, k * 32f + r, 38.8f + dy + r); c.drawArc(oval, a0, 180f - 2 * a0, false, p) }
        }
        arcs(Color.argb(184, 58, 14, 16), 16f, 0f, 6f)
        arcs(Color.argb(56, 255, 226, 180), 12f, -4f, 28f)
        val sh = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val mx = Matrix(); mx.setScale(0.25f, 0.25f); sh.setLocalMatrix(mx)
        scalePaint.shader = sh
        scalePaint.alpha = 140
    }

    fun restHead(sp: Float, walk: Float) = lerp(lerp(0.28f, 0.12f, walk * (1f - sp)), 0.05f, sp)

    /** Head base in model coordinates without bob (for aiming). out[0]=x, out[1]=y. */
    fun headLocal(sp: Float, walk: Float, out: FloatArray) {
        val w = walk * (1f - sp)
        out[0] = lerp(lerp(gr[headI][0], wk[headI][0], w), fl[headI][0], sp)
        out[1] = lerp(lerp(gr[headI][1], wk[headI][1], w), fl[headI][1], sp)
    }

    /** Foot path of one leg: stance slides back on the ground, swing lifts and moves forward. */
    private fun stepFoot(gait: Float, off: Float, out: FloatArray) {
        var u = (gait + off) % 1f; if (u < 0f) u += 1f
        val st = 14f; val lift = 11f
        if (u < 0.6f) { out[0] = st * (1f - 2f * u / 0.6f); out[1] = 0f; return }
        val v = (u - 0.6f) / 0.4f; val e = v * v * (3f - 2f * v)
        out[0] = -st + 2f * st * e; out[1] = -lift * sin(Math.PI.toFloat() * v)
    }

    // ---------- pose ----------
    private fun pose(s: State) {
        val sp = s.sp; val t = s.time; val cr = s.crouch
        val w = s.walk * (1f - sp)
        wkv = w
        val yoff = s.bob + cr * 12f * (1f - sp)
        for (i in 0 until n) {
            val gx = lerp(gr[i][0], wk[i][0], w); val gy = lerp(gr[i][1], wk[i][1], w)
            pX[i] = lerp(gx, fl[i][0], sp)
            pY[i] = lerp(gy, fl[i][1], sp) + s.bob + cr * 12f * (1f - sp) * (if (i <= tailN) i / tailN.toFloat() else 1f)
            pR[i] = lerp(gr[i][2], wk[i][2], w)
        }
        val amp = lerp(2.2f, 9f, sp); val fr = lerp(1.7f, 5.4f, sp)
        for (i in 0 until tailN) {
            val wv = 1f - i / (tailN - 1f)
            pY[i] += amp * sin(t * fr - i * 0.9f) * wv * wv
            pX[i] += 2.2f * sin(t * 1.1f - i * 0.7f) * wv * (1f - sp)
        }
        for (i in 10 until n) pY[i] += sin(t * 1.5f) * 0.8f * (i - 9) / 4f * (1f - sp)
        pR[8] *= 1f + 0.025f * sin(t * 1.9f) * (1f - sp)
        pR[9] *= 1f + 0.02f * sin(t * 1.9f) * (1f - sp)
        hx = pX[headI]; hy = pY[headI]
        hang = s.head + sin(t * 0.7f) * 0.03f * (1f - sp)
        stepFoot(s.gait, 0f, stepA); stepFoot(s.gait, 0.5f, stepB)
        feet[0] = lerp(lerp(27f, 26f + stepA[0], w), 30f, sp); feet[1] = lerp(lerp(0f, stepA[1], w), -24f, sp)
        feet[2] = lerp(lerp(15f, 15f + stepB[0], w), 24f, sp); feet[3] = lerp(lerp(0f, stepB[1], w), -26f, sp)
        feet[4] = lerp(lerp(-2f, -22f + stepB[0], w), -44f, sp); feet[5] = lerp(lerp(0f, stepB[1], w), -26f, sp)
        feet[6] = lerp(lerp(-11f, -30f + stepA[0], w), -38f, sp); feet[7] = lerp(lerp(0f, stepA[1], w), -28f, sp)
        val hy1 = lerp(lerp(-50f, -40f, w), -33f, sp) + yoff; val hy2 = lerp(lerp(-30f, -40f, w), -35f, sp) + yoff
        hips[0] = lerp(lerp(16f, 14f, w), 22f, sp); hips[1] = hy1
        hips[2] = lerp(lerp(9f, 8f, w), 16f, sp); hips[3] = hy1
        hips[4] = lerp(lerp(-27f, -28f, w), -24f, sp); hips[5] = hy2
        hips[6] = lerp(lerp(-21f, -22f, w), -18f, sp); hips[7] = hy2
        attX = pX[6] + 4f; attY = pY[6] + 1f
    }

    private fun sample() {
        var k = 0
        for (i in 0 until n - 1) {
            val i0 = max(i - 1, 0); val i3 = min(i + 2, n - 1)
            for (j in 0 until per) {
                val t = j.toFloat() / per; val t2 = t * t; val t3 = t2 * t
                sX[k] = 0.5f * ((2 * pX[i]) + (-pX[i0] + pX[i + 1]) * t + (2 * pX[i0] - 5 * pX[i] + 4 * pX[i + 1] - pX[i3]) * t2 + (-pX[i0] + 3 * pX[i] - 3 * pX[i + 1] + pX[i3]) * t3)
                sY[k] = 0.5f * ((2 * pY[i]) + (-pY[i0] + pY[i + 1]) * t + (2 * pY[i0] - 5 * pY[i] + 4 * pY[i + 1] - pY[i3]) * t2 + (-pY[i0] + 3 * pY[i] - 3 * pY[i + 1] + pY[i3]) * t3)
                sR[k] = lerp(pR[i], pR[i + 1], t)
                k++
            }
        }
        sX[k] = pX[n - 1]; sY[k] = pY[n - 1]; sR[k] = pR[n - 1]
        for (i in 0 until m) {
            val a = max(0, i - 1); val b = min(m - 1, i + 1)
            val dx = sX[b] - sX[a]; val dy = sY[b] - sY[a]; val l = max(1e-3f, hypot(dx, dy))
            dX[i] = dx / l; dY[i] = dy / l; nX[i] = dy / l; nY[i] = -dx / l
        }
    }

    private fun bodyPath(p: Path) {
        p.reset()
        p.moveTo(sX[0] + nX[0] * sR[0], sY[0] + nY[0] * sR[0])
        for (i in 1 until m) p.lineTo(sX[i] + nX[i] * sR[i], sY[i] + nY[i] * sR[i])
        for (i in m - 1 downTo 0) p.lineTo(sX[i] - nX[i] * sR[i] * 0.95f, sY[i] - nY[i] * sR[i] * 0.95f)
        p.close()
    }

    /** Strip of the tube between two offsets (fractions of the radius, +1 = back, -0.95 = belly). */
    private fun band(p: Path, i0: Int, i1: Int, a: Float, b: Float) {
        p.reset()
        for (i in i0..i1) {
            val x = sX[i] + nX[i] * sR[i] * a; val y = sY[i] + nY[i] * sR[i] * a
            if (i == i0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        for (i in i1 downTo i0) p.lineTo(sX[i] + nX[i] * sR[i] * b, sY[i] + nY[i] * sR[i] * b)
        p.close()
    }

    private fun fillBand(c: Canvas, i0: Int, i1: Int, a: Float, b: Float, color: Int) {
        band(tmp, i0, i1, a, b)
        fill.shader = null; fill.color = color
        c.drawPath(tmp, fill)
    }

    // ---------- small helpers ----------
    private fun lin(x0: Float, y0: Float, x1: Float, y1: Float, vararg stops: Pair<Float, Int>): LinearGradient {
        return LinearGradient(x0, y0, x1, y1, IntArray(stops.size) { stops[it].second }, FloatArray(stops.size) { stops[it].first }, Shader.TileMode.CLAMP)
    }

    private fun tri(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, paint: Paint) {
        path.reset(); path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close()
        c.drawPath(path, paint)
    }

    private fun ell(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int, rot: Float = 0f) {
        fill.shader = null; fill.color = color
        c.save(); c.rotate(deg(rot), cx, cy)
        oval.set(cx - rx, cy - max(0.01f, ry), cx + rx, cy + max(0.01f, ry))
        c.drawOval(oval, fill)
        c.restore()
    }

    private fun ellShader(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, sh: Shader, rot: Float = 0f) {
        fill.shader = sh
        c.save(); c.rotate(deg(rot), cx, cy)
        oval.set(cx - rx, cy - ry, cx + rx, cy + ry)
        c.drawOval(oval, fill)
        c.restore()
        fill.shader = null
    }

    /** Scaled muscle mass (thigh, shoulder). */
    private fun mass(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, rot: Float, c1: Int, c2: Int) {
        c.save(); c.rotate(deg(rot), cx, cy)
        oval.set(cx - rx, cy - ry, cx + rx, cy + ry)
        fill.shader = lin(cx, cy - ry, cx, cy + ry, 0f to c1, 1f to c2)
        c.drawOval(oval, fill); fill.shader = null
        tmp.reset(); tmp.addOval(oval, Path.Direction.CW)
        c.save(); c.clipPath(tmp)
        c.drawRect(cx - rx - 2f, cy - ry - 2f, cx + rx + 2f, cy + ry + 2f, scalePaint)
        c.restore()
        stroke.color = Col.line; stroke.strokeWidth = 1.3f
        c.drawOval(oval, stroke)
        c.restore()
    }

    private fun ik(hx: Float, hy: Float, ax: Float, ay: Float, l1: Float, l2: Float, bendX: Int) {
        val dx = ax - hx; val dy = ay - hy; val full = max(1e-3f, hypot(dx, dy))
        var d = min(full, l1 + l2 - 0.5f); d = max(d, abs(l1 - l2) + 0.5f)
        val a = (l1 * l1 - l2 * l2 + d * d) / (2 * d); val h = sqrt(max(0f, l1 * l1 - a * a))
        val ux = dx / full; val uy = dy / full
        val mx = hx + ux * a; val my = hy + uy * a
        val k1x = mx - uy * h; val k1y = my + ux * h; val k2x = mx + uy * h; val k2y = my - ux * h
        val first = if (bendX > 0) k1x > k2x else k1x < k2x
        knee[0] = if (first) k1x else k2x; knee[1] = if (first) k1y else k2y
    }

    // ---------- limbs ----------
    private val limbX = FloatArray(3); private val limbY = FloatArray(3); private val limbR = FloatArray(3)
    private var limbN = 3

    /** Draws segments [from, to) of the current limb. */
    private fun limbShape(c: Canvas, p: Paint, from: Int, to: Int) {
        for (i in from until to) {
            val dx = limbX[i + 1] - limbX[i]; val dy = limbY[i + 1] - limbY[i]; val l = max(1e-3f, hypot(dx, dy))
            val nx = -dy / l; val ny = dx / l
            tmp.reset()
            tmp.moveTo(limbX[i] + nx * limbR[i], limbY[i] + ny * limbR[i])
            tmp.lineTo(limbX[i + 1] + nx * limbR[i + 1], limbY[i + 1] + ny * limbR[i + 1])
            tmp.lineTo(limbX[i + 1] - nx * limbR[i + 1], limbY[i + 1] - ny * limbR[i + 1])
            tmp.lineTo(limbX[i] - nx * limbR[i], limbY[i] - ny * limbR[i])
            tmp.close()
            c.drawPath(tmp, p)
            c.drawCircle(limbX[i], limbY[i], limbR[i], p)
            c.drawCircle(limbX[i + 1], limbY[i + 1], limbR[i + 1], p)
        }
    }

    /** col0 / col1 colour the first and second segment (null = default orange). */
    private fun limb(c: Canvas, dark: Boolean, col0: Int?, col1: Int?) {
        val segs = limbN - 1
        fill.shader = null
        stroke.color = Col.line; stroke.strokeWidth = 2.2f; limbShape(c, stroke, 0, segs)
        val def = if (dark) Col.lo else Col.mid
        for (i in 0 until segs) {
            fill.color = (if (i == 0) col0 else col1) ?: def
            limbShape(c, fill, i, i + 1)
        }
        limbShape(c, scalePaint, 0, segs)
        stroke.color = if (dark) Color.argb(41, 255, 220, 170) else Color.argb(102, 255, 226, 180)
        for (i in 0 until segs) {
            stroke.strokeWidth = max(1f, (limbR[i] + limbR[i + 1]) * 0.3f)
            c.drawLine(limbX[i], limbY[i] - limbR[i] * 0.35f, limbX[i + 1], limbY[i + 1] - limbR[i + 1] * 0.35f, stroke)
        }
    }

    /** Four toes with curved claws: flat on the ground when seated (flat = 1), hanging when flying. */
    private fun foot(c: Canvas, ax: Float, ay: Float, fx: Float, fy: Float, ang: Float, dark: Boolean, color: Int?, big: Float, flat: Float) {
        val offs = floatArrayOf(-0.5f, -0.17f, 0.17f, 0.5f); val lens = floatArrayOf(8f, 11f, 11f, 8f); val yo = floatArrayOf(-4.2f, -2.6f, -1.4f, 0f)
        val toeCol = color ?: (if (dark) Col.lo else Col.mid)
        for (k in 0 until 4) {
            val a = ang + offs[k] * (0.5f + 0.5f * (1f - flat)); val len = lens[k] * big
            val ex = fx + cos(a) * len; val ey = fy + yo[k] * flat + sin(a) * len * lerp(1f, 0.45f, flat)
            val cxk = lerp(ax, fx, 0.6f); val cyk = lerp(ay, fy + yo[k] * flat, 0.8f) - 1.5f * flat
            path.reset(); path.moveTo(ax, ay); path.quadTo(cxk, cyk, ex, ey)
            stroke.color = Col.line; stroke.strokeWidth = 5.4f; c.drawPath(path, stroke)
            stroke.color = toeCol; stroke.strokeWidth = 3.6f; c.drawPath(path, stroke)
            fill.shader = null; fill.color = toeCol
            c.drawCircle(lerp(cxk, ex, 0.5f), lerp(cyk, ey, 0.5f), 2.1f, fill)
            // curved claw
            val cl = 6f * big; val dx = cos(a); val dy = sin(a)
            path.reset()
            path.moveTo(ex - dy * 1.7f, ey + dx * 1.7f)
            path.quadTo(ex + dx * cl * 0.8f - dy * 1.2f, ey + dy * cl * 0.8f + dx * 1.2f + 0.5f * flat, ex + dx * cl + 0.5f, ey + dy * cl + 3.2f * flat + 1.5f)
            path.quadTo(ex + dx * cl * 0.5f + dy * 1.4f, ey + dy * cl * 0.5f - dx * 1.4f, ex + dy * 1.7f, ey - dx * 1.7f)
            path.close()
            fill.color = if (dark) Col.boneLo else Col.boneHi; c.drawPath(path, fill)
            stroke.color = Color.argb(140, 40, 14, 10); stroke.strokeWidth = 0.6f; c.drawPath(path, stroke)
        }
    }

    private fun leg(c: Canvas, s: State, idx: Int, front: Boolean, dark: Boolean) {
        val sp = s.sp
        val hxx = hips[idx * 2]; val hyy = hips[idx * 2 + 1]; val fx = feet[idx * 2]; val fy = feet[idx * 2 + 1]
        val offx = if (front) -3f else lerp(lerp(-16f, -6f, wkv), -10f, sp); val offy = -9f
        val ax = fx + offx * (if (front) 1f - sp * 0.6f else 1f); val ay = fy + offy * (1f - sp * 0.7f)
        val l1 = if (front) lerp(21.2f, 16f, sp) else 19f
        ik(hxx, hyy, ax, ay, l1, l1, if (front) -1 else 1)
        val r0 = if (front) lerp(8f, 5f, sp) else lerp(9f, 7.5f, sp)
        val r1 = if (front) lerp(5.4f, 3.4f, sp) else lerp(6f, 4.6f, sp)
        val r2 = if (front) lerp(3.6f, 2.8f, sp) else lerp(4f, 3f, sp)
        val teal = if (dark) Col.legTealDark else Col.legTeal
        if (!front && sp < 0.3f && wkv < 0.3f) {
            // seated: the thigh is the haunch mass, only the folded teal shin is drawn
            limbN = 2
            limbX[0] = knee[0]; limbY[0] = knee[1]; limbX[1] = ax; limbY[1] = ay
            limbR[0] = lerp(8.5f, r1, max(sp, wkv) / 0.3f); limbR[1] = r2
            limb(c, dark, teal, null)
        } else {
            limbN = 3
            limbX[0] = hxx; limbY[0] = hyy; limbX[1] = knee[0]; limbY[1] = knee[1]; limbX[2] = ax; limbY[2] = ay
            limbR[0] = r0; limbR[1] = r1; limbR[2] = r2
            if (front) limb(c, dark, null, null) else limb(c, dark, null, teal)
        }
        val big = if (front) lerp(1.15f, 1f, sp) else lerp(1.3f, 1f, sp)
        foot(c, ax, ay, fx + (if (front) 3f else 4f), fy, lerp(0f, if (front) 0.9f else 1.1f, sp), dark, if (front) null else teal, big, 1f - sp)
    }

    // ---------- head ----------
    private fun horn(c: Canvas, bx: Float, by: Float, cx: Float, cy: Float, tx: Float, ty: Float, w: Float, far: Boolean) {
        path.reset()
        path.moveTo(bx - w * 0.2f, by + w)
        path.quadTo(cx, cy + w * 0.5f, tx, ty)
        path.quadTo(cx + w * 0.3f, cy - w * 0.9f, bx + w * 0.9f, by - w * 0.3f)
        path.close()
        fill.shader = lin(bx, by, tx, ty, 0f to Color.parseColor(if (far) "#C27A45" else "#F3C58A"), 1f to Color.parseColor(if (far) "#6D3A22" else "#A95A2D"))
        c.drawPath(path, fill); fill.shader = null
        stroke.color = Color.argb(153, 46, 16, 10); stroke.strokeWidth = 0.9f; c.drawPath(path, stroke)
        stroke.color = Color.argb(102, 70, 26, 12); stroke.strokeWidth = 0.7f
        for (q in 1 until 5) {
            val u = q / 5.5f
            val ux = (1 - u) * (1 - u) * bx + 2 * (1 - u) * u * cx + u * u * tx
            val uy = (1 - u) * (1 - u) * by + 2 * (1 - u) * u * cy + u * u * ty
            c.drawLine(ux - 0.5f, uy - w * (1 - u) * 0.9f, ux + 0.5f, uy + w * (1 - u) * 0.8f, stroke)
        }
    }

    /** Curved tapering blade (crest and frill spines). */
    private fun blade(c: Canvas, bx: Float, by: Float, a: Float, len: Float, w: Float, curl: Float, c0: Int, c1: Int) {
        val tx = bx + cos(a) * len; val ty = by + sin(a) * len
        val px = -sin(a); val py = cos(a)
        val cx = bx + cos(a) * len * 0.5f + px * curl * len; val cy = by + sin(a) * len * 0.5f + py * curl * len
        path.reset()
        path.moveTo(bx + px * w, by + py * w)
        path.quadTo(cx + px * w * 0.55f, cy + py * w * 0.55f, tx, ty)
        path.quadTo(cx - px * w * 0.55f, cy - py * w * 0.55f, bx - px * w, by - py * w)
        path.close()
        fill.shader = lin(bx, by, tx, ty, 0f to c0, 1f to c1)
        c.drawPath(path, fill); fill.shader = null
        stroke.color = Color.argb(102, 50, 16, 30); stroke.strokeWidth = 0.6f; c.drawPath(path, stroke)
        bladeTip[0] = tx; bladeTip[1] = ty
    }

    private val frill = arrayOf(
        floatArrayOf(-2.55f, 38f, 3.2f, 0.22f), floatArrayOf(-2.85f, 44f, 3.5f, 0.2f), floatArrayOf(-3.15f, 47f, 3.7f, 0.12f),
        floatArrayOf(3.0f, 46f, 3.7f, -0.05f), floatArrayOf(2.75f, 42f, 3.5f, -0.18f), floatArrayOf(2.5f, 36f, 3.1f, -0.28f),
        floatArrayOf(2.25f, 28f, 2.7f, -0.34f)
    )
    private val fTipX = FloatArray(7); private val fTipY = FloatArray(7)

    private fun drawHead(c: Canvas, s: State) {
        val mouth = s.mouth; val t = s.time
        c.save(); c.translate(hx, hy); c.rotate(deg(hang))

        // crest: long swept-back blades
        val fw = sin(t * 2.1f) * 0.025f
        blade(c, 4f, -12f, -2.0f + fw, 24f, 2.4f, 0.18f, Color.parseColor("#2F9BB0"), Color.parseColor("#A9C4EA"))
        blade(c, 2f, -12f, -2.35f + fw, 40f, 3f, 0.2f, Color.parseColor("#2F9BB0"), Color.parseColor("#C8B4E8"))
        blade(c, 0f, -11f, -2.7f + fw, 44f, 3.2f, 0.18f, Color.parseColor("#3A8FB0"), Color.parseColor("#D0B2E6"))
        blade(c, -2f, -10f, -3.0f + fw, 38f, 3f, 0.14f, Color.parseColor("#4A86B4"), Color.parseColor("#D9B0DC"))

        horn(c, 6f, -12f, -8f, -34f, -46f, -36f, 4.2f, true)

        // neck frill fan: webbing then spines
        val bx0 = -5f; val by0 = -1f
        for (q in frill.indices) {
            val a = frill[q][0] + sin(t * 1.4f + q * 0.7f) * 0.03f; val len = frill[q][1]
            val px = -sin(a); val py = cos(a)
            fTipX[q] = bx0 + cos(a) * len + px * frill[q][3] * len * 0.55f
            fTipY[q] = by0 + sin(a) * len + py * frill[q][3] * len * 0.55f
        }
        path.reset(); path.moveTo(bx0, by0 - 3f)
        for (q in frill.indices) {
            val pvx = if (q == 0) bx0 - 12f else fTipX[q - 1]; val pvy = if (q == 0) by0 - 14f else fTipY[q - 1]
            path.quadTo((pvx + fTipX[q]) / 2f + 3f, (pvy + fTipY[q]) / 2f - 1f, fTipX[q], fTipY[q])
        }
        path.lineTo(bx0, by0 + 5f); path.close()
        fill.shader = lin(bx0, by0, bx0 - 46f, by0 + 10f,
            0f to withAlpha(Color.parseColor("#E07A42"), 0.7f), 0.6f to withAlpha(Color.parseColor("#CF7AA0"), 0.7f), 1f to withAlpha(Color.parseColor("#A98AD0"), 0.7f))
        c.drawPath(path, fill); fill.shader = null
        for (q in frill.indices) {
            blade(c, bx0, by0 + (q - 3) * 1.3f, frill[q][0] + sin(t * 1.4f + q * 0.7f) * 0.03f, frill[q][1], frill[q][2], frill[q][3],
                Color.parseColor("#E3743A"), Color.parseColor(if (q < 3) "#C7A4E6" else "#E6A0B4"))
        }

        // small cheek spines
        val cheek = arrayOf(floatArrayOf(-8f, 6f, -15f, 12f), floatArrayOf(-3f, 8f, -8f, 16f))
        for (f in cheek) {
            fill.shader = lin(f[0], f[1], f[2], f[3], 0f to Col.plateHi, 1f to Col.plateLo)
            tri(c, f[0], f[1] - 2.2f, f[0], f[1] + 2.2f, f[2], f[3], fill); fill.shader = null
            stroke.color = Color.argb(128, 46, 16, 10); stroke.strokeWidth = 0.7f; c.drawPath(path, stroke)
        }

        // mouth interior and lower jaw
        val open = mouth * 0.62f
        path.reset(); path.moveTo(2f, 4f); path.lineTo(36f, 3f); path.lineTo(34f + open * 5f, 4f + 28f * open); path.lineTo(2f - open * 2f, 4f + 14f * open); path.close()
        fill.shader = null; fill.color = Color.parseColor("#4A0C14"); c.drawPath(path, fill)

        c.save(); c.translate(2f, 5f); c.rotate(deg(open))
        path.reset()
        path.moveTo(0f, -1f); path.lineTo(33f, -1f)
        path.cubicTo(33f, 3f, 28f, 4f, 20f, 5f)
        path.cubicTo(12f, 6f, 4f, 7f, -5f, 5f)
        path.cubicTo(-7f, 2f, -4f, 0f, 0f, -1f)
        path.close()
        fill.shader = lin(0f, -1f, 0f, 7f, 0f to Color.parseColor("#D9693A"), 0.65f to Color.parseColor("#E8955A"), 1f to Col.plateHi)
        c.drawPath(path, fill); fill.shader = null
        stroke.color = Col.line; stroke.strokeWidth = 1f; c.drawPath(path, stroke)
        if (mouth > 0.06f) {
            path.reset(); path.moveTo(4f, -1f); path.cubicTo(12f, -4.5f, 24f, -3.5f, 32f, -1.2f); path.lineTo(4f, -1f); path.close()
            fill.color = Color.parseColor("#E0606A"); c.drawPath(path, fill)
            fill.color = Col.boneHi
            for (q in 0 until 6) tri(c, 11f + q * 4f, -0.8f, 13.5f + q * 4f, -0.8f, 12.4f + q * 4f, -4.4f, fill)
        }
        c.restore()

        // skull and upper jaw
        body.reset()
        body.moveTo(-10f, -8f)
        body.cubicTo(-8f, -14f, 2f, -15f, 8f, -13f)
        body.cubicTo(12f, -12f, 15f, -9f, 22f, -8f)
        body.cubicTo(28f, -7f, 34f, -6f, 38f, -3f)
        body.cubicTo(40f, -1f, 39f, 2f, 36f, 3.5f)
        body.lineTo(24f, 4.5f)
        body.cubicTo(16f, 5.5f, 8f, 5f, 2f, 5f)
        body.cubicTo(-4f, 6f, -8f, 8f, -9f, 7f)
        body.cubicTo(-13f, 2f, -12f, -4f, -10f, -8f)
        body.close()
        fill.shader = lin(0f, -15f, 0f, 8f, 0f to Color.parseColor("#2F9BB0"), 0.36f to Color.parseColor("#3A8FA0"), 0.5f to Color.parseColor("#E3843F"), 1f to Color.parseColor("#B04A2C"))
        c.drawPath(body, fill); fill.shader = null
        c.save(); c.clipPath(body)
        c.drawRect(-14f, -18f, 42f, 12f, scalePaint)
        fill.color = Color.argb(71, 30, 8, 14)
        c.save(); c.rotate(-14f, 14f, -9f); oval.set(6f, -12.4f, 22f, -5.6f); c.drawOval(oval, fill); c.restore()
        c.restore()
        stroke.color = Col.line; stroke.strokeWidth = 1.1f; c.drawPath(body, stroke)
        stroke.color = Color.argb(140, 190, 240, 240); stroke.strokeWidth = 1.4f
        path.reset(); path.moveTo(4f, -13.5f); path.cubicTo(10f, -12.5f, 15f, -10.5f, 21f, -9f); c.drawPath(path, stroke)

        // eye
        val ey = max(0.12f, s.eye)
        c.save(); c.translate(14f, -6.2f); c.rotate(-17f)
        ell(c, 0f, 0f, 5.2f, 3.3f * ey + 0.4f, Color.argb(224, 30, 6, 6))
        val eg = RadialGradient(0.8f, 0f, 4.4f, intArrayOf(Color.parseColor("#FFE9A8"), Col.eye, Color.parseColor("#A8200C")), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        ellShader(c, 0f, 0f, 4.4f, 2.5f * ey + 0.2f, eg)
        ell(c, 0.4f, 0f, 0.9f, 2.3f * ey + 0.1f, Color.parseColor("#1B0505"))
        c.restore()
        ell(c, 34.5f, -2.4f, 1.7f, 1.1f, Color.parseColor("#2A0C10"), 0.5f)

        fill.shader = null; fill.color = Col.boneHi
        for (q in 0 until 7) {
            val tx = 9f + q * 4.2f; val hgt = if (q == 6) 5f else 3.4f
            tri(c, tx, 4.6f, tx + 2.3f, 4.6f, tx + 1.1f, 4.6f + hgt, fill)
        }
        if (mouth > 0.1f) {
            glowPaint.shader = RadialGradient(30f, 5f, 14f,
                intArrayOf(Color.argb((230 * mouth).toInt(), 220, 245, 255), Color.argb((140 * mouth).toInt(), 70, 170, 255), Color.argb(0, 30, 80, 255)),
                floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(10f, -10f, 50f, 20f, glowPaint)
            glowPaint.shader = null
        }
        c.restore()
    }

    // ---------- wings ----------
    private val edgeX = FloatArray(8); private val edgeY = FloatArray(8)

    /** part: 0 = membrane only, 1 = bones only, 2 = both. */
    private fun wing(c: Canvas, shx: Float, shy: Float, a1: Float, a2: Float, dB: Float, fan: Float, sc: Float, far: Boolean, part: Int) {
        val doMem = part != 1; val doBone = part != 0
        val l1 = 28f * sc; val l2 = 42f * sc
        val fingers = floatArrayOf(80f * sc, 74f * sc, 64f * sc, 52f * sc)
        val ex = shx + l1 * cos(a1); val ey = shy + l1 * sin(a1)
        val wx = ex + l2 * cos(a2); val wy = ey + l2 * sin(a2)
        val offs = floatArrayOf(fan, fan / 3f, -fan / 3f, -fan)
        for (k in 0 until 4) { val a = dB + offs[k]; tipX[k] = wx + fingers[k] * cos(a); tipY[k] = wy + fingers[k] * sin(a) }
        val attx = attX; val atty = attY

        path.reset()
        path.moveTo(shx, shy); path.lineTo(ex, ey); path.lineTo(wx, wy); path.lineTo(tipX[0], tipY[0])
        for (k in 0 until 3) {
            val mx = (tipX[k] + tipX[k + 1]) / 2; val my = (tipY[k] + tipY[k + 1]) / 2
            val cx = mx + (wx - mx) * 0.3f; val cy = my + (wy - my) * 0.3f
            midX[k] = cx; midY[k] = cy
            path.quadTo(cx, cy, tipX[k + 1], tipY[k + 1])
        }
        val mx2 = (tipX[3] + attx) / 2; val my2 = (tipY[3] + atty) / 2
        path.quadTo(mx2 + (wx - mx2) * 0.25f, my2 + (wy - my2) * 0.25f, attx, atty)
        path.close()

        if (doMem) {
            val top = min(min(min(shy, ey), min(wy, tipY[0])), tipY[1]) - 2f
            val bot = max(max(atty, tipY[3]), max(tipY[2], tipY[1])) + 2f
            val y1 = max(top + 20f, bot)
            fill.shader = if (far) lin(0f, top, 0f, y1, 0f to Color.parseColor("#1D566C"), 0.35f to Color.parseColor("#3A2C66"), 0.7f to Color.parseColor("#7A3436"), 1f to Color.parseColor("#A8602E"))
            else lin(0f, top, 0f, y1, 0f to Color.parseColor("#2F8AA2"), 0.3f to Color.parseColor("#55408F"), 0.65f to Color.parseColor("#B94A3C"), 1f to Color.parseColor("#F09040"))
            c.drawPath(path, fill); fill.shader = null
            c.save(); c.clipPath(path)
            // streaky membrane texture radiating from the wrist
            edgeX[0] = tipX[0]; edgeY[0] = tipY[0]; edgeX[1] = midX[0]; edgeY[1] = midY[0]
            edgeX[2] = tipX[1]; edgeY[2] = tipY[1]; edgeX[3] = midX[1]; edgeY[3] = midY[1]
            edgeX[4] = tipX[2]; edgeY[4] = tipY[2]; edgeX[5] = midX[2]; edgeY[5] = midY[2]
            edgeX[6] = tipX[3]; edgeY[6] = tipY[3]; edgeX[7] = attx; edgeY[7] = atty
            for (e in 0 until 8) {
                val xx = edgeX[e]; val yy = edgeY[e]
                stroke.color = if (far) Color.argb(26, 255, 170, 110) else Color.argb(51, 255, 196, 130); stroke.strokeWidth = 2.2f
                tmp.reset(); tmp.moveTo(wx, wy); tmp.quadTo((wx + xx) / 2 + 3f, (wy + yy) / 2 + 3f, xx, yy); c.drawPath(tmp, stroke)
                stroke.color = if (far) Color.argb(51, 20, 6, 30) else Color.argb(51, 40, 10, 50); stroke.strokeWidth = 1.2f
                tmp.reset(); tmp.moveTo(wx + 1, wy + 1); tmp.quadTo((wx + xx) / 2 + 6f, (wy + yy) / 2 + 1f, xx + 3f, yy - 2f); c.drawPath(tmp, stroke)
            }
            // darker band along the leading edge
            stroke.color = if (far) Color.argb(102, 10, 30, 50) else Color.argb(102, 10, 50, 70); stroke.strokeWidth = 7f
            tmp.reset(); tmp.moveTo(shx, shy); tmp.lineTo(ex, ey); tmp.lineTo(wx, wy); c.drawPath(tmp, stroke)
            c.restore()
            stroke.color = if (far) Color.argb(178, 30, 8, 16) else Color.argb(191, 40, 10, 20); stroke.strokeWidth = 1.1f
            c.drawPath(path, stroke)
        }
        if (!doBone) return

        val bone = Color.parseColor(if (far) "#7A4026" else "#B9683A")
        val armC = Color.parseColor(if (far) "#17485C" else "#1F7590")
        val fhi = if (far) Color.argb(46, 255, 200, 150) else Color.argb(128, 255, 226, 180)
        val ahi = if (far) Color.argb(38, 150, 230, 230) else Color.argb(115, 160, 236, 236)
        fun bar(x0: Float, y0: Float, x1: Float, y1: Float, w: Float, col: Int, hi: Int) {
            stroke.color = Col.line; stroke.strokeWidth = w + 1.4f; c.drawLine(x0, y0, x1, y1, stroke)
            stroke.color = col; stroke.strokeWidth = w; c.drawLine(x0, y0, x1, y1, stroke)
            stroke.color = hi; stroke.strokeWidth = w * 0.35f; c.drawLine(x0, y0 - w * 0.25f, x1, y1 - w * 0.25f, stroke)
        }
        for (k in 0 until 4) {
            val fw = 2.5f - k * 0.2f; val bx = wx + (tipX[k] - wx) * 0.62f; val by = wy + (tipY[k] - wy) * 0.62f
            bar(wx, wy, bx, by, fw, bone, fhi); bar(bx, by, tipX[k], tipY[k], fw * 0.6f, bone, fhi)
        }
        bar(shx, shy, ex, ey, 5.4f, armC, ahi); bar(ex, ey, wx, wy, 4.2f, armC, ahi)
        val spikeCol = if (far) Color.parseColor("#B08860") else Col.boneHi
        for (j in 0 until 2) {
            val x0 = if (j == 0) shx else ex; val y0 = if (j == 0) shy else ey
            val x1 = if (j == 0) ex else wx; val y1 = if (j == 0) ey else wy
            val dx = x1 - x0; val dy = y1 - y0; val l = max(1e-3f, hypot(dx, dy)); val nx = dy / l; val ny = -dx / l
            for (k in 1 until 4) {
                val px = x0 + dx * k / 4; val py = y0 + dy * k / 4
                fill.shader = null; fill.color = spikeCol
                tri(c, px - dx / l * 1.8f, py - dy / l * 1.8f, px + dx / l * 1.8f, py + dy / l * 1.8f, px + nx * 5.5f - dx / l * 1.5f, py + ny * 5.5f - dy / l * 1.5f, fill)
                stroke.color = Color.argb(128, 40, 16, 10); stroke.strokeWidth = 0.6f; c.drawPath(path, stroke)
            }
        }
        ell(c, ex, ey, 3.2f, 3.2f, armC); ell(c, wx, wy, 3.6f, 3.6f, armC)
        val ta = a2 - 1.2f
        fill.shader = null; fill.color = spikeCol
        tri(c, wx - 1.6f, wy - 1f, wx + 1.6f, wy + 1f, wx + cos(ta) * 11f, wy + sin(ta) * 11f, fill)
        stroke.color = Color.argb(140, 40, 16, 10); stroke.strokeWidth = 0.7f; c.drawPath(path, stroke)
    }

    // ---------- main ----------
    fun draw(c: Canvas, s: State) {
        pose(s); sample()
        val sp = s.sp; val t = s.time
        c.save()
        c.translate(s.x, s.y)
        c.scale(s.face * s.ds, s.ds)
        if (s.pitch != 0f) { c.translate(0f, -40f); c.rotate(deg(s.pitch)); c.translate(0f, 40f) }

        val ph = s.wingPh
        fun u(o: Float) = 0.5f + 0.5f * sin(ph + o)
        val shx = pX[shI] - 4f; val shy = pY[shI] - pR[shI] * 0.78f

        // wing pose: seated-open values blended with the flapping flight values
        val cw = wkv
        fun wa1(rest: Float, phase: Float, uu: Float) = lerp(lerp(rest, -1.72f, cw) + 0.05f * sin(t * 1.3f + phase), lerp(-1.9f, 0.7f, uu), sp)
        fun wa2(rest: Float, phase: Float, lg: Float) = lerp(lerp(rest, -2.95f, cw) + 0.04f * sin(t * 1.3f + phase), lerp(-2.5f, -4.73f, lg), sp)
        fun wdb(rest: Float, phase: Float, lg2: Float) = lerp(lerp(rest, -3.28f, cw) + 0.03f * sin(t * 1.3f + phase), lerp(-2.9f, -4.55f, lg2), sp)

        // near wing parameters
        val nA1 = wa1(-1.35f, 0f, u(0f)); val nA2 = wa2(-2.3f, 0f, u(-0.85f)); val nDb = wdb(-3.3f, 0f, u(-1.2f))
        val nFan = lerp(lerp(0.8f, 0.14f, cw), 0.85f, sp); val nSc = lerp(lerp(1.1f, 0.66f, cw), 1f, sp)
        // far wing parameters
        val fA1 = wa1(-1.55f, 1.7f, u(0.3f)); val fA2 = wa2(-2.5f, 1.7f, u(-0.55f)); val fDb = wdb(-3.5f, 1.7f, u(-0.9f))
        val fFan = lerp(lerp(0.78f, 0.14f, cw), 0.85f, sp); val fSc = lerp(lerp(1.0f, 0.66f, cw), 1f, sp) * 0.94f

        wing(c, shx + 3f, shy + 2f, fA1, fA2, fDb, fFan, fSc, true, 2)
        leg(c, s, 1, true, true)
        leg(c, s, 3, false, true)
        wing(c, shx, shy, nA1, nA2, nDb, nFan, nSc, false, 0)

        // body tube
        var minY = 1e9f; var maxY = -1e9f
        for (i in 0 until m) { minY = min(minY, sY[i] - sR[i]); maxY = max(maxY, sY[i] + sR[i]) }
        bodyPath(body)
        fill.shader = lin(0f, minY, 0f, maxY, 0f to Col.hi, 0.4f to Col.mid, 0.85f to Col.lo, 1f to Col.deep)
        c.drawPath(body, fill); fill.shader = null
        c.save(); c.clipPath(body)
        // teal back, feathered into the orange sides
        val layOff = floatArrayOf(0.05f, 0.3f, 0.55f, 0.8f); val layA = floatArrayOf(0.3f, 0.45f, 0.8f, 1f)
        for (k in 0 until 4) fillBand(c, 0, m - 1, 1.3f, layOff[k], withAlpha(Color.rgb(44, 147, 168), layA[k]))
        // purple-orange tail underside
        fillBand(c, 0, per * 6, -1.3f, -0.2f, Color.argb(128, 132, 78, 150))
        fillBand(c, 0, per * 6, -1.3f, -0.65f, Color.argb(140, 150, 70, 130))
        // green-teal neck front
        fillBand(c, per * 9, m - 1, -1.3f, -0.1f, Color.argb(115, 88, 184, 154))
        fillBand(c, per * 9, m - 1, -1.3f, -0.5f, Color.argb(178, 88, 184, 154))
        // scale texture
        c.drawRect(-175f, minY - 8f, 95f, maxY + 8f, scalePaint)
        // belly plates (chest and belly)
        val b0 = per * 6; val b1 = per * 10
        band(tmp, b0, b1, -1.3f, -0.4f)
        fill.shader = lin(0f, minY, 0f, maxY, 0f to Col.plateHi, 1f to Col.plateLo)
        c.drawPath(tmp, fill); fill.shader = null
        stroke.color = Color.argb(153, 90, 40, 24); stroke.strokeWidth = 0.9f
        var i = b0
        while (i <= b1) {
            c.drawLine(sX[i] - nX[i] * sR[i] * 0.95f, sY[i] - nY[i] * sR[i] * 0.95f,
                sX[i] - nX[i] * sR[i] * 0.4f + dX[i] * 0.8f, sY[i] - nY[i] * sR[i] * 0.4f + dY[i] * 0.8f, stroke)
            i += 2
        }
        // roundness: dark belly edge
        fillBand(c, 0, m - 1, -1.3f, -0.78f, Color.argb(71, 50, 10, 24))
        c.restore()
        // rim light and outline
        stroke.color = Color.argb(153, 190, 245, 240); stroke.strokeWidth = 1.5f
        path.reset()
        for (k in 4 until m) {
            val rx = sX[k] + nX[k] * sR[k] * 0.92f; val ry = sY[k] + nY[k] * sR[k] * 0.92f
            if (k == 4) path.moveTo(rx, ry) else path.lineTo(rx, ry)
        }
        c.drawPath(path, stroke)
        stroke.color = Col.line; stroke.strokeWidth = 1.3f; c.drawPath(body, stroke)

        // dorsal plates and spikes
        i = 2
        while (i < m - 3) {
            val rr = sR[i]; val hgt = 2.8f + 6.4f * min(1f, rr / 14f)
            val sx = sX[i] + nX[i] * rr * 0.96f; val sy = sY[i] + nY[i] * rr * 0.96f
            var dx = nX[i] - dX[i] * 0.85f; var dy = nY[i] - dY[i] * 0.85f; val dl = hypot(dx, dy)
            dx /= dl; dy /= dl
            val hw = 1.3f + rr * 0.11f
            fill.shader = lin(sx, sy, sx + dx * hgt, sy + dy * hgt, 0f to Col.tealLo, 1f to Color.parseColor("#A8E4E0"))
            tri(c, sx - dX[i] * hw, sy - dY[i] * hw, sx + dX[i] * hw, sy + dY[i] * hw, sx + dx * hgt, sy + dy * hgt, fill)
            fill.shader = null
            stroke.color = Color.argb(166, 14, 30, 40); stroke.strokeWidth = 0.6f; c.drawPath(path, stroke)
            i += 3
        }

        // near legs with thigh and shoulder mass
        mass(c, hips[4] + lerp(lerp(3f, 0f, wkv), -1f, sp), hips[5] + lerp(lerp(4f, 2f, wkv), 3f, sp), lerp(lerp(21f, 16f, wkv), 11f, sp), lerp(lerp(20f, 16f, wkv), 13f, sp), 0.15f, Col.hi, Col.lo)
        leg(c, s, 2, false, false)
        val shm = max(sp, wkv)
        if (shm > 0.05f) mass(c, hips[0] - 1f, hips[1] + 1f, 8f * shm, 11f * shm, -0.2f, Col.hi, Col.lo)
        leg(c, s, 0, true, false)

        // near wing bones and spikes (membrane was drawn behind the body)
        wing(c, shx, shy, nA1, nA2, nDb, nFan, nSc, false, 1)
        drawHead(c, s)
        c.restore()
    }

    /** Mouth tip in view coordinates. out[0]=x, out[1]=y. */
    fun mouthPos(s: State, out: FloatArray) {
        pose(s)
        val lx = 36f; val ly = 2.5f
        var mx = hx + cos(hang) * lx - sin(hang) * ly
        var my = hy + sin(hang) * lx + cos(hang) * ly
        if (s.pitch != 0f) {
            val dx = mx; val dy = my + 40f; val cc = cos(s.pitch); val ss = sin(s.pitch)
            mx = dx * cc - dy * ss; my = dx * ss + dy * cc - 40f
        }
        out[0] = s.x + s.face * s.ds * mx; out[1] = s.y + s.ds * my
    }
}
