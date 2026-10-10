package app.folio.annotations

import app.folio.data.AnnotationEntity

enum class Tool(val label: String, val width: Float) {
    NONE("Чтение", 0f),
    PEN("Перо", 0.0035f),
    MARKER("Маркер", 0.028f),
    UNDERLINE("Подчёрк.", 0.003f),
    TEXT_HL("Выдел. текста", 0f),
    TEXT_UL("Подч. текста", 0f),
    ERASER("Ластик", 0.02f),
    NOTE("Заметка", 0f),
    FORM("Форма", 0f)
}

val Palette = listOf(0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5, 0xFF000000).map { it.toInt() }

/**
 * Для PEN/MARKER/UNDERLINE/NOTE: pts = x,y,pressure тройками.
 * Для TEXT_HL/TEXT_UL: pts = x0,y0,x1,y1 четвёрками (прямоугольники строк текста).
 * Все координаты нормализованы 0..1 относительно страницы.
 */
class Stroke(val id: Long, val page: Int, val type: String, val color: Int, val width: Float, val pts: FloatArray, val text: String?)

fun AnnotationEntity.toStroke() = Stroke(
    id, page, type, color, width,
    points.split(',').filter { it.isNotEmpty() }.map { it.toFloat() }.toFloatArray(), text
)

fun FloatArray.encode(): String = joinToString(",")

/** Подчёркивание: превращаем мазок в ровную горизонтальную линию. */
fun snapUnderline(a: FloatArray): FloatArray {
    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var sy = 0f; val n = a.size / 3
    for (i in 0 until n) { minX = minOf(minX, a[i * 3]); maxX = maxOf(maxX, a[i * 3]); sy += a[i * 3 + 1] }
    val y = sy / n
    return floatArrayOf(minX, y, 1f, maxX, y, 1f)
}
