package app.folio.annotations

import kotlin.math.hypot

fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val l2 = dx * dx + dy * dy
    val t = if (l2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}

/**
 * Частичное стирание штриха.
 * pts — x,y,p тройками (нормализованные), w/h — размер страницы в пикселях, er — точки ластика x,y в пикселях, r — радиус.
 * null = штрих не задет; пустой список = стёрт целиком; иначе — уцелевшие куски (каждый минимум 2 точки).
 */
fun splitByEraser(pts: FloatArray, w: Float, h: Float, er: FloatArray, r: Float): List<FloatArray>? {
    val n = pts.size / 3
    if (n == 0 || er.size < 2) return null

    fun near(x: Float, y: Float): Boolean {
        var i = 0
        while (i + 1 < er.size) {
            if (hypot(x - er[i], y - er[i + 1]) < r) return true
            i += 2
        }
        return false
    }

    fun cut(i: Int): Boolean {
        val ax = pts[i * 3] * w
        val ay = pts[i * 3 + 1] * h
        val bx = pts[(i + 1) * 3] * w
        val by = pts[(i + 1) * 3 + 1] * h
        var k = 0
        while (k + 1 < er.size) {
            if (distToSegment(er[k], er[k + 1], ax, ay, bx, by) < r) return true
            k += 2
        }
        return false
    }

    val keep = BooleanArray(n) { !near(pts[it * 3] * w, pts[it * 3 + 1] * h) }
    val cuts = BooleanArray(maxOf(n - 1, 0)) { cut(it) }
    if (keep.all { it } && cuts.none { it }) return null

    val out = ArrayList<FloatArray>()
    var run = ArrayList<Float>()
    fun flush() {
        if (run.size >= 6) out += run.toFloatArray()
        run = ArrayList()
    }
    for (i in 0 until n) {
        if (!keep[i]) { flush(); continue }
        if (i > 0 && cuts[i - 1]) flush()
        run.add(pts[i * 3]); run.add(pts[i * 3 + 1]); run.add(pts[i * 3 + 2])
    }
    flush()
    return out
}

/** Поворот геометрии аннотации при повороте страницы на quarterTurnsCw * 90° по часовой стрелке. */
fun rotateGeometry(type: String, pts: FloatArray, quarterTurnsCw: Int): FloatArray {
    val q = ((quarterTurnsCw % 4) + 4) % 4
    if (q == 0) return pts.copyOf()
    fun rx(x: Float, y: Float): Float = when (q) { 1 -> 1f - y; 2 -> 1f - x; else -> y }
    fun ry(x: Float, y: Float): Float = when (q) { 1 -> x; 2 -> 1f - y; else -> 1f - x }
    val out = pts.copyOf()
    if (type == "TEXT_HL" || type == "TEXT_UL") {
        var i = 0
        while (i + 3 < pts.size) {
            val ax = rx(pts[i], pts[i + 1])
            val ay = ry(pts[i], pts[i + 1])
            val bx = rx(pts[i + 2], pts[i + 3])
            val by = ry(pts[i + 2], pts[i + 3])
            out[i] = minOf(ax, bx); out[i + 1] = minOf(ay, by)
            out[i + 2] = maxOf(ax, bx); out[i + 3] = maxOf(ay, by)
            i += 4
        }
    } else {
        var i = 0
        while (i + 2 < pts.size) {
            out[i] = rx(pts[i], pts[i + 1])
            out[i + 1] = ry(pts[i], pts[i + 1])
            i += 3
        }
    }
    return out
}
