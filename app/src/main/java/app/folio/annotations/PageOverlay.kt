package app.folio.annotations

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onErase: (List<Long>, Map<Long, List<FloatArray>>) -> Unit,
    onNoteAt: (Float, Float) -> Unit,
    onNoteOpen: (Stroke) -> Unit,
    onTextSelect: (Float, Float, Float, Float) -> Unit,
    onFormTap: (Float, Float) -> Unit,
    onTapBackground: () -> Unit,
    modifier: Modifier = Modifier
) {
    val live = remember { mutableStateListOf<Offset>() }
    val liveP = remember { ArrayList<Float>() }
    // Оптимистичный штрих: показываем сразу, пока запись в БД не вернётся новым списком (иначе мерцание).
    var pending by remember { mutableStateOf<Stroke?>(null) }
    var pendingBase by remember { mutableStateOf<List<Stroke>?>(null) }
    LaunchedEffect(strokes) { pending = null }

    val cur by rememberUpdatedState(strokes)
    // Колбэки обязаны быть «свежими»: pointerInput перезапускается только при смене tool/stylusOnly.
    val onStrokeS by rememberUpdatedState(onStroke)
    val onEraseS by rememberUpdatedState(onErase)
    val onNoteAtS by rememberUpdatedState(onNoteAt)
    val onNoteOpenS by rememberUpdatedState(onNoteOpen)
    val onTextS by rememberUpdatedState(onTextSelect)
    val onFormS by rememberUpdatedState(onFormTap)
    val onTapS by rememberUpdatedState(onTapBackground)
    val colorS by rememberUpdatedState(color)
    val density = LocalDensity.current
    val tm = rememberTextMeasurer()
    val noteStyle = remember { TextStyle(fontSize = 12.sp, color = Color(0xFF3E2723)) }
    val notePad = with(density) { 6.dp.toPx() }
    val eraseR = with(density) { 14.dp.toPx() }

    Canvas(
        modifier.pointerInput(tool, stylusOnly) {
            when (tool) {
                Tool.NONE -> detectTapGestures { p ->
                    val n = noteAt(cur, p, size.width.toFloat(), size.height.toFloat(), tm, noteStyle, notePad)
                    if (n != null) onNoteOpenS(n) else onTapS()
                }
                Tool.NOTE -> detectTapGestures { p ->
                    val n = noteAt(cur, p, size.width.toFloat(), size.height.toFloat(), tm, noteStyle, notePad)
                    if (n != null) onNoteOpenS(n) else onNoteAtS(p.x / size.width, p.y / size.height)
                }
                Tool.FORM -> detectTapGestures { p -> onFormS(p.x / size.width, p.y / size.height) }
                else -> awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (stylusOnly && down.type != PointerType.Stylus) return@awaitEachGesture
                    down.consume()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val pts = ArrayList<Float>()
                    val er = ArrayList<Float>()
                    var cancelled = false
                    live.clear(); liveP.clear()
                    fun handle(c: PointerInputChange) {
                        val pr = if (c.type == PointerType.Stylus && c.pressure > 0f) c.pressure.coerceIn(0.1f, 1f) else 1f
                        if (tool == Tool.ERASER) { er += c.position.x; er += c.position.y }
                        else { pts += c.position.x / w; pts += c.position.y / h; pts += pr }
                        liveP.add(pr)
                        live += c.position
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
                        when (tool) {
                            Tool.ERASER -> if (er.isNotEmpty()) {
                                val deleted = ArrayList<Long>()
                                val replaced = HashMap<Long, List<FloatArray>>()
                                val erArr = er.toFloatArray()
                                for (s in cur) {
                                    if (s.type == "PEN" || s.type == "MARKER") {
                                        val parts = splitByEraser(s.pts, w, h, erArr, eraseR)
                                        if (parts != null) { if (parts.isEmpty()) deleted += s.id else replaced[s.id] = parts }
                                    } else if (hitAny(s, erArr, w, h, eraseR)) deleted += s.id
                                }
                                if (deleted.isNotEmpty() || replaced.isNotEmpty()) onEraseS(deleted, replaced)
                            }
                            Tool.TEXT_HL, Tool.TEXT_UL, Tool.TEXT_COPY -> if (pts.size >= 3) {
                                // тап даёт a == b: слово или предложение под пальцем; протяжка — диапазон
                                onTextS(pts[0], pts[1], pts[pts.size - 3], pts[pts.size - 2])
                            }
                            else -> if (pts.size >= 3) {
                                var arr = pts.toFloatArray()
                                if (tool == Tool.UNDERLINE) arr = snapUnderline(arr)
                                else if (arr.size == 3) arr += arr   // тап пером = точка
                                pendingBase = cur
                                pending = Stroke(-1L, 0, tool.name, colorS.toArgb(), tool.width, arr, null)
                                onStrokeS(arr)
                            }
                        }
                    }
                    live.clear(); liveP.clear()
                }
            }
        }
    ) {
        val w = size.width
        val h = size.height
        hits.forEach { r ->
            drawRect(Color(0x66FFEB3B), Offset(r[0] * w, r[1] * h), Size((r[2] - r[0]) * w, (r[3] - r[1]) * h))
        }
        val p = pending
        val shown = if (p != null && pendingBase === strokes) strokes + p else strokes
        shown.forEach { s -> drawStroke(s, w, h, tm, noteStyle, notePad) }
        if (live.isNotEmpty()) {
            when (tool) {
                Tool.ERASER -> drawInk("PEN", Color(0x55888888), eraseR * 2f, live.toList(), null)
                Tool.TEXT_HL, Tool.TEXT_UL, Tool.TEXT_COPY -> drawInk("PEN", color.copy(alpha = 0.7f), 2f, live.toList(), null)
                else -> drawInk(tool.name, color, tool.width * w, live.toList(), if (tool == Tool.PEN) liveP.toFloatArray() else null)
            }
        }
    }
}

private fun DrawScope.drawStroke(s: Stroke, w: Float, h: Float, tm: TextMeasurer, style: TextStyle, pad: Float) {
    when (s.type) {
        "NOTE" -> {
            // заметка = «стикер»-прямоугольник с текстом
            val nb = noteBox(s, w, h, tm, style, pad)
            drawRoundRect(Color(0xFFFFF59D), nb.rect.topLeft, nb.rect.size, CornerRadius(8f, 8f))
            drawRoundRect(Color(0xFFF9A825), nb.rect.topLeft, nb.rect.size, CornerRadius(8f, 8f), style = DrawStroke(2f))
            drawText(nb.layout, topLeft = Offset(nb.rect.left + pad, nb.rect.top + pad))
        }
        "TEXT_HL" -> {
            var i = 0
            while (i + 3 < s.pts.size) {
                drawRect(
                    Color(s.color).copy(alpha = 0.5f),
                    Offset(s.pts[i] * w, s.pts[i + 1] * h),
                    Size((s.pts[i + 2] - s.pts[i]) * w, (s.pts[i + 3] - s.pts[i + 1]) * h),
                    blendMode = BlendMode.Multiply
                )
                i += 4
            }
        }
        "TEXT_UL" -> {
            var i = 0
            while (i + 3 < s.pts.size) {
                drawLine(
                    Color(s.color), Offset(s.pts[i] * w, s.pts[i + 3] * h), Offset(s.pts[i + 2] * w, s.pts[i + 3] * h),
                    strokeWidth = maxOf(1.5f, 0.0025f * w)
                )
                i += 4
            }
        }
        else -> {
            val n = s.pts.size / 3
            val pts = List(n) { Offset(s.pts[it * 3] * w, s.pts[it * 3 + 1] * h) }
            val pr = FloatArray(n) { s.pts[it * 3 + 2] }
            drawInk(s.type, Color(s.color), s.width * w, pts, if (s.type == "PEN" && pr.any { it < 0.97f }) pr else null)
        }
    }
}

private fun DrawScope.drawInk(type: String, color: Color, widthPx: Float, pts: List<Offset>, pr: FloatArray?) {
    if (type == "MARKER") {
        drawPath(
            smooth(pts), color.copy(alpha = 0.55f),
            style = DrawStroke(widthPx, cap = StrokeCap.Square, join = StrokeJoin.Round), blendMode = BlendMode.Multiply
        )
        return
    }
    if (pr != null && pts.size >= 2 && pr.size == pts.size) {
        // перо: толщина зависит от давления стилуса
        for (i in 1 until pts.size) {
            val p = (pr[i] + pr[i - 1]) / 2f
            drawLine(color, pts[i - 1], pts[i], strokeWidth = maxOf(1f, widthPx * (0.35f + 1.3f * p)), cap = StrokeCap.Round)
        }
    } else {
        drawPath(smooth(pts), color, style = DrawStroke(maxOf(widthPx, 1.5f), cap = StrokeCap.Round, join = StrokeJoin.Round))
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

private fun hitAny(s: Stroke, er: FloatArray, w: Float, h: Float, r: Float): Boolean {
    var k = 0
    while (k + 1 < er.size) {
        if (hitPoint(s, er[k], er[k + 1], w, h, r)) return true
        k += 2
    }
    return false
}

private fun hitPoint(s: Stroke, x: Float, y: Float, w: Float, h: Float, r: Float): Boolean {
    if (s.type == "NOTE") {
        val nx = s.pts[0] * w
        val ny = s.pts[1] * h
        return x >= nx - r && x <= nx + w * 0.34f + r && y >= ny - r && y <= ny + 56f + r
    }
    if (s.type == "TEXT_HL" || s.type == "TEXT_UL") {
        var i = 0
        while (i + 3 < s.pts.size) {
            if (x >= s.pts[i] * w - r && x <= s.pts[i + 2] * w + r && y >= s.pts[i + 1] * h - r && y <= s.pts[i + 3] * h + r) return true
            i += 4
        }
        return false
    }
    val n = s.pts.size / 3
    if (n == 0) return false
    if (n == 1) return hypot(s.pts[0] * w - x, s.pts[1] * h - y) < r
    val reach = r + s.width * w / 2
    for (i in 0 until n - 1) {
        if (distToSegment(x, y, s.pts[i * 3] * w, s.pts[i * 3 + 1] * h, s.pts[(i + 1) * 3] * w, s.pts[(i + 1) * 3 + 1] * h) < reach) return true
    }
    return false
}

private class NoteBox(val rect: Rect, val layout: TextLayoutResult)

/** Прямоугольник заметки: левый верхний угол — точка, куда тапнули; ширина ~34% страницы, высота по тексту. */
private fun noteBox(s: Stroke, w: Float, h: Float, tm: TextMeasurer, style: TextStyle, pad: Float): NoteBox {
    val boxW = (w * 0.34f).coerceIn(120f, maxOf(120f, w * 0.8f))
    val txt = s.text?.takeIf { it.isNotBlank() } ?: "Заметка"
    val layout = tm.measure(
        txt, style = style, overflow = TextOverflow.Ellipsis, maxLines = 6,
        constraints = Constraints(maxWidth = (boxW - 2 * pad).toInt().coerceAtLeast(1))
    )
    val boxH = layout.size.height + 2 * pad
    val x = (s.pts[0] * w).coerceIn(0f, maxOf(0f, w - boxW))
    val y = (s.pts[1] * h).coerceIn(0f, maxOf(0f, h - boxH))
    return NoteBox(Rect(x, y, x + boxW, y + boxH), layout)
}

private fun noteAt(list: List<Stroke>, p: Offset, w: Float, h: Float, tm: TextMeasurer, style: TextStyle, pad: Float): Stroke? {
    // последняя нарисованная заметка лежит сверху
    for (i in list.indices.reversed()) {
        val st = list[i]
        if (st.type == "NOTE" && noteBox(st, w, h, tm, style, pad).rect.inflate(8f).contains(p)) return st
    }
    return null
}
