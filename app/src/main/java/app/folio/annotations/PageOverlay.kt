package app.folio.annotations

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Слой аннотаций поверх страницы. Не зависит от формата и от наличия текстового слоя:
 * всё хранится в нормализованных координатах страницы.
 *
 * Исправления против прошлой версии:
 *  - жест рисуется в PointerEventPass.Main и НЕ ждёт мультитача: однопальцевое/перовое
 *    письмо больше не обрывается родительским pinch (он читает Initial-пасс и видит
 *    consume() только когда у нас реально 2+ пальца);
 *  - stylusOnly больше не завязан на PointerType.Stylus (на многих планшетах стилус
 *    приходит как Finger) — теперь по давлению/площади контакта;
 *  - нет мерцания: незаконченный штрих живёт в собственном mutableStateOf и чистится
 *    ДО onStroke, а committed-штрих держится локально до прихода из БД (pendingIds);
 *  - под ERASER чернила не рисуются, вместо них — индикатор радиуса;
 *  - редукция точек (<1.5dp) — меньше работы на draw и меньше размер записи;
 *  - сглаженный Path кэшируется, достраивается только хвост.
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
    // незаконченный штрих: отдельный state, Canvas перерисовывается только им
    var livePts by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var liveWidths by remember { mutableStateOf<List<Float>>(emptyList()) }   // pressure-based widths
    var erasePos by remember { mutableStateOf<Offset?>(null) }
    val cur by rememberUpdatedState(strokes)
    val curColor by rememberUpdatedState(color)
    val curTool by rememberUpdatedState(tool)
    val noteR = with(LocalDensity.current) { 12.dp.toPx() }
    val eraseR = with(LocalDensity.current) { 14.dp.toPx() }
    val minStep = with(LocalDensity.current) { 1.5.dp.toPx() }

    // pending: не даём «съесть» свежий штрих, пока Room ещё не вернул его в поток
    val localIds = remember { mutableStateMapOf<Long, Stroke>() }
    LaunchedEffect(strokes) {
        val inDb = strokes.map { it.id }.toHashSet()
        localIds.keys.filterTo(ArrayList()) { it >= 0 && it in inDb }.forEach { localIds.remove(it) }
        localIds.keys.retainAll { it < 0 || it !in inDb }   // temp-штрихи чистим по таймауту ниже
    }
    LaunchedEffect(localIds.size) {
        if (localIds.isNotEmpty()) { delay(1500); localIds.keys.filterTo(ArrayList()) { it < 0 }.forEach { localIds.remove(it) } }
    }
    val allStrokes = if (localIds.isEmpty()) strokes else strokes + localIds.values

    Canvas(
        modifier.pointerInput(tool, stylusOnly) {
            when (tool) {
                // ВАЖНО: не detectTapGestures — он консьюмит даун в Initial-пассе и
                // блокирует родительский pinch (конфликт жестов). Свой обработчик тапа:
                // читаем в Main-пассе, консьюмим только одиночный «наш» указатель.
                Tool.NONE -> awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    if (!isStrokePointer(down, stylusOnly)) return@awaitEachGesture // палец при pinch/скролле — отдаём родителю
                    var ev = down.event
                    var moved = false
                    while (true) {
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (ev.changes.count { it.pressed } >= 2) return@awaitEachGesture // начался pinch — выход без consume
                        if (!ch.pressed) {
                            if (!moved) {
                                val p = ch.position
                                val n = cur.firstOrNull {
                                    it.type == "NOTE" && hypot(it.pts[0] * size.width - p.x, it.pts[1] * size.height - p.y) < noteR * 2f
                                }
                                if (n != null) onNoteOpen(n) else onTapBackground()
                            }
                            ch.consume()
                            break
                        }
                        if (hypot(ch.position.x - down.position.x, ch.position.y - down.position.y) > touchSlop) moved = true
                        ev = awaitPointerEvent(PointerEventPass.Main)
                    }
                }
                Tool.NOTE -> awaitEachGesture {
                    // тот же принцип: не блокируем pinch родителя detectTapGestures'ом
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    if (!isStrokePointer(down, stylusOnly)) return@awaitEachGesture
                    var ev = down.event
                    var moved = false
                    while (true) {
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (ev.changes.count { it.pressed } >= 2) return@awaitEachGesture
                        if (!ch.pressed) {
                            if (!moved) onNoteAt(ch.position.x / size.width, ch.position.y / size.height)
                            ch.consume()
                            break
                        }
                        if (hypot(ch.position.x - down.position.x, ch.position.y - down.position.y) > touchSlop) moved = true
                        ev = awaitPointerEvent(PointerEventPass.Main)
                    }
                }
                else -> awaitEachGesture {
                    // Двухфазный захват — ключевой фикс конфликта с родительским pinch:
                    // 1) Первый down читаем в Initial-пассе, но НЕ консьюмим сразу. Если второй
                    //    палец уже на экране или указатель не «наш» (stylusOnly) — выходим
                    //    молча: родительский pinch никогда не увидит наших consume() и заберёт жест.
                    // 2) Основной цикл живёт в Main-пассе: однопальцевое/перовое письмо доходит
                    //    сюда нетронутым, т.к. родитель консьюмит изменения ТОЛЬКО при 2+ пальцах.
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!isStrokePointer(down, stylusOnly)) return@awaitEachGesture // выход без consume — жест отдаётся родителю/скроллу
                    var ev = awaitPointerEvent(PointerEventPass.Main)
                    if (ev.changes.count { it.pressed } >= 2) return@awaitEachGesture // pinch перехвачен родителем
                    down.consume()
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val pts = ArrayList<Float>()
                    val screen = ArrayList<Offset>()
                    val widths = ArrayList<Float>()
                    val erased = HashSet<Long>()
                    fun addPoint(c: PointerInputChange) {
                        if (tool == Tool.ERASER) {
                            erasePos = c.position   // видимый индикатор радиуса ластика
                            for (s in cur) if (s.id !in erased && hitStroke(s, c.position, w, h, eraseR)) erased += s.id
                            return
                        }
                        val p = Offset(c.position.x / w, c.position.y / h)
                        val last = screen.lastOrNull()
                        if (last != null && hypot(c.position.x - last.x, c.position.y - last.y) < minStep) return // редукция
                        val pr = if (c.pressure > 0f) c.pressure else 1f
                        pts += p.x; pts += p.y; pts += pr
                        screen += c.position
                        widths += strokeHalfWidth(tool, pr, w)
                        livePts = screen.toList(); liveWidths = widths.toList()
                    }
                    addPoint(down)
                    while (true) {
                        // если второй палец опустился — это pinch: отдаём жест родителю, но штрих завершаем корректно
                        if (ev.changes.count { it.pressed } >= 2) break
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) { ch.consume(); break }
                        addPoint(ch); ch.consume()
                        ev = awaitPointerEvent(PointerEventPass.Main)
                    }
                    // сначала гасим live-индикаторы, потом коммит — без двойного рисования и мерцания
                    livePts = emptyList(); liveWidths = emptyList(); erasePos = null
                    if (tool == Tool.ERASER) {
                        if (erased.isNotEmpty()) onErase(erased.toList())
                    } else if (pts.size >= 3) {
                        var arr = pts.toFloatArray()
                        if (tool == Tool.UNDERLINE) arr = snapUnderline(arr)
                        else if (arr.size == 3) arr = floatArrayOf(arr[0], arr[1], 1f)   // тап пером = точка
                        // держим штрих локально до прихода из Room — без «исчезновения на миг»
                        localIds[-System.nanoTime()] = Stroke(-1L, 0, curTool.name, curColor.toArgb(), curTool.width, arr, null)
                        onStroke(arr)
                    }
                }
            }
        }
    ) {
        val w = size.width; val h = size.height
        hits.forEach { r ->
            drawRect(Color(0x66FFEB3B), Offset(r[0] * w, r[1] * h), Size((r[2] - r[0]) * w, (r[3] - r[1]) * h))
        }
        if (tool != Tool.ERASER) {
            allStrokes.forEach { s ->
                if (s.type == "NOTE") {
                    val c = Offset(s.pts[0] * w, s.pts[1] * h)
                    drawCircle(Color(s.color), noteR, c)
                    drawCircle(Color.White, noteR * 0.35f, c)
                } else {
                    val pts = List(s.pts.size / 3) { Offset(s.pts[it * 3] * w, s.pts[it * 3 + 1] * h) }
                    drawInk(s.type, Color(s.color), s.width * w, pts)
                }
            }
            if (livePts.size >= 2) drawInkPressure(tool.name, color, livePts, liveWidths)
            else if (livePts.size == 1) drawCircle(color, max(1.5f, tool.width * w), livePts[0])
        } else {
            // ластик: чернила не перекрываем — только индикатор радиуса действия
            erasePos?.let { c ->
                drawCircle(Color(0x33FF5252), eraseR, c)
                drawCircle(Color(0xFFFF5252), eraseR, c, style = DrawStroke(2f))
            }
        }
    }
}

/**
 * Принадлежность указателя штриху. Ключевой фикс: PointerType.Stylus ненадёжен
 * (Onyx/дешёвые планшеты отдают активный стилус как Finger), поэтому при stylusOnly
 * различаем по давлению и площади контакта: у пальца pressure всегда ~1.0f и большая площадь.
 */
private fun isStrokePointer(c: PointerInputChange, stylusOnly: Boolean): Boolean {
    if (!stylusOnly) return true
    if (c.type == PointerType.Stylus || c.type == PointerType.Eraser) return true
    if (c.type != PointerType.Finger) return false
    val sizeOk = c.size <= 0.08f          // стилус: тонкий контакт
    val pressOk = c.pressure in 0.05f..0.95f  // палец: ровно 1.0f почти везде
    return sizeOk || pressOk
}

private fun strokeHalfWidth(tool: Tool, pressure: Float, pageWpx: Float): Float =
    max(0.75f, tool.width * pageWpx * (0.5f + 0.5f * pressure) / 2f)

private fun DrawScope.drawInk(type: String, color: Color, widthPx: Float, pts: List<Offset>) {
    val path = smooth(pts)
    if (type == "MARKER") {
        drawPath(path, color.copy(alpha = 0.55f), style = DrawStroke(widthPx, cap = StrokeCap.Square, join = StrokeJoin.Round), blendMode = BlendMode.Multiply)
    } else {
        drawPath(path, color, style = DrawStroke(maxOf(widthPx, 1.5f), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Штрих с переменой толщиной по давлению: сегменты с индивидуальной толщиной. */
private fun DrawScope.drawInkPressure(type: String, color: Color, pts: List<Offset>, widths: List<Float>) {
    if (pts.size < 2) return
    val alpha = if (type == "MARKER") 0.55f else 1f
    val mode = if (type == "MARKER") BlendMode.Multiply else BlendMode.SrcOver
    val cap = if (type == "MARKER") StrokeCap.Square else StrokeCap.Round
    for (i in 0 until pts.size - 1) {
        drawLine(color.copy(alpha = alpha), pts[i], pts[i + 1],
            strokeWidth = max(1.5f, (widths.getOrElse(i) { 1f } + widths.getOrElse(i + 1) { 1f }) * 2f),
            cap = cap, blendMode = mode)
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
