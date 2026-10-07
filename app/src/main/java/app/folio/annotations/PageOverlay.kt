package app.folio.annotations

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/**
 * Слой аннотаций поверх страницы. Не зависит от формата и от наличия текстового слоя:
 * всё хранится в нормализованных координатах страницы.
 */
@Composable
fun PageOverlay(
    strokes: List<Stroke>,
    hits: List<FloatArray>,
    tool: Tool,
    color: Color,
    stylusOnly: Boolean,
    onStroke: (FloatArray) -> Unit,
    onErase: (List<Long>) -> Unit,
    onNoteAt: (Float, Float) -> Unit,
    onNoteOpen: (Stroke) -> Unit,
    onTapBackground: () -> Unit,
    modifier: Modifier = Modifier
) {
    val live = remember { mutableStateListOf<Offset>() }
    val cur by rememberUpdatedState(strokes)
    // Колбэки обязаны быть «свежими»: pointerInput перезапускается только при смене tool/stylusOnly,
    // поэтому без rememberUpdatedState в штрих попадал устаревший цвет.
    val onStrokeS by rememberUpdatedState(onStroke)
    val onEraseS by rememberUpdatedState(onErase)
    val onNoteAtS by rememberUpdatedState(onNoteAt)
    val onNoteOpenS by rememberUpdatedState(onNoteOpen)
    val onTapS by rememberUpdatedState(onTapBackground)
    val noteR = with(LocalDensity.current) { 12.dp.toPx() }
    val eraseR = with(LocalDensity.current) { 14.dp.toPx() }

    Canvas(
        modifier.pointerInput(tool, stylusOnly) {
            when (tool) {
                Tool.NONE -> detectTapGestures { p ->
                    val n = cur.firstOrNull {
                        it.type == "NOTE" && hypot(it.pts[0] * size.width - p.x, it.pts[1] * size.height - p.y) < noteR * 2f
                    }
                    if (n != null) onNoteOpenS(n) else onTapS()
                }
                Tool.NOTE -> detectTapGestures { p -> onNoteAtS(p.x / size.width, p.y / size.height) }
                else -> awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (stylusOnly && down.type != PointerType.Stylus) return@awaitEachGesture
                    down.consume()
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val pts = ArrayList<Float>()
                    val erased = HashSet<Long>()
                    var cancelled = false
                    live.clear()
                    fun handle(c: PointerInputChange) {
                        if (tool == Tool.ERASER) {
                            for (s in cur) if (s.id !in erased && hitStroke(s, c.position, w, h, eraseR)) erased += s.id
                        } else {
                            pts += c.position.x / w; pts += c.position.y / h
                            pts += if (c.pressure > 0f) c.pressure else 1f
                            live += c.position
                        }
                    }
                    handle(down)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        // событие забрал родитель (щипок/панорама двумя пальцами) — это не штрих
                        if (ch.isConsumed) { cancelled = true; break }
                        if (!ch.pressed) break
                        handle(ch); ch.consume()
                    }
                    if (!cancelled) {
                        if (tool == Tool.ERASER) { if (erased.isNotEmpty()) onEraseS(erased.toList()) }
                        else if (pts.size >= 3) {
                            var arr = pts.toFloatArray()
                            if (tool == Tool.UNDERLINE) arr = snapUnderline(arr)
                            else if (arr.size == 3) arr += arr   // тап пером = точка
                            onStrokeS(arr)
                        }
                    }
                    live.clear()
                }
            }
        }
    ) {
        val w = size.width; val h = size.height
        hits.forEach { r ->
            drawRect(Color(0x66FFEB3B), Offset(r[0] * w, r[1] * h), Size((r[2] - r[0]) * w, (r[3] - r[1]) * h))
        }
        strokes.forEach { s ->
            if (s.type == "NOTE") {
                val c = Offset(s.pts[0] * w, s.pts[1] * h)
                drawCircle(Color(s.color), noteR, c)
                drawCircle(Color.White, noteR * 0.35f, c)
            } else {
                val pts = List(s.pts.size / 3) { Offset(s.pts[it * 3] * w, s.pts[it * 3 + 1] * h) }
                drawInk(s.type, Color(s.color), s.width * w, pts)
            }
        }
        if (live.isNotEmpty()) drawInk(tool.name, color, tool.width * w, live.toList())
    }
}

private fun DrawScope.drawInk(type: String, color: Color, widthPx: Float, pts: List<Offset>) {
    val path = smooth(pts)
    if (type == "MARKER") {
        drawPath(path, color.copy(alpha = 0.55f), style = DrawStroke(widthPx, cap = StrokeCap.Square, join = StrokeJoin.Round), blendMode = BlendMode.Multiply)
    } else {
        drawPath(path, color, style = DrawStroke(maxOf(widthPx, 1.5f), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun smooth(p: List<Offset>): Path = Path().apply {
    if (p.isEmpty()) return@apply
    moveTo(p[0].x, p[0].y)
    if (p.size == 1) { lineTo(p[0].x + 0.1f, p[0].y); return@apply }
    for (i in 1 until p.size - 1) {
        quadraticBezierTo(p[i].x, p[i].y, (p[i].x + p[i + 1].x) / 2, (p[i].y + p[i + 1].y) / 2)
    }
    lineTo(p.last().x, p.last().y)
}

private fun hitStroke(s: Stroke, p: Offset, w: Float, h: Float, r: Float): Boolean {
    val n = s.pts.size / 3
    if (n == 0) return false
    fun px(i: Int) = Offset(s.pts[i * 3] * w, s.pts[i * 3 + 1] * h)
    if (n == 1) return hypot(px(0).x - p.x, px(0).y - p.y) < r
    val reach = r + s.width * w / 2
    for (i in 0 until n - 1) if (distToSeg(p, px(i), px(i + 1)) < reach) return true
    return false
}

private fun distToSeg(p: Offset, a: Offset, b: Offset): Float {
    val dx = b.x - a.x; val dy = b.y - a.y
    val l2 = dx * dx + dy * dy
    val t = if (l2 == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0f, 1f)
    return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}
