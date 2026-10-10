package app.folio.engine

import android.graphics.Bitmap

data class PageSize(val w: Float, val h: Float)
data class TocItem(val title: String, val page: Int, val level: Int)
/** rects: нормализованные координаты страницы x0,y0,x1,y1 в 0..1 */
data class Hit(val page: Int, val rects: List<FloatArray>)

class UnsupportedFormat(msg: String) : Exception(msg)

/** Единый интерфейс: UI ничего не знает о конкретных библиотеках форматов. */
interface DocumentEngine : AutoCloseable {
    val isReflowable: Boolean
    val isPdf: Boolean
    val pageCount: Int
    val needsPassword: Boolean
    fun unlock(password: String): Boolean
    /** Для reflowable (EPUB/FB2/MOBI/TXT): пересчитать вёрстку. Возвращает число страниц. */
    fun layout(width: Float, height: Float, em: Float): Int
    fun pageSize(index: Int): PageSize
    fun renderPage(index: Int, widthPx: Int): Bitmap
    /** Фрагмент страницы [x0,y0,x1,y1) в пикселях страницы шириной fullWidthPx: чёткий рендер при зуме без гигантских битмапов. */
    fun renderPatch(index: Int, fullWidthPx: Int, x0: Int, y0: Int, x1: Int, y1: Int): Bitmap
    fun toc(): List<TocItem>
    fun searchPage(index: Int, query: String): Hit?
    /** Строки текста между двумя точками (нормализованные координаты) -> прямоугольники x0,y0,x1,y1. Пусто, если текстового слоя нет. */
    fun textQuads(index: Int, ax: Float, ay: Float, bx: Float, by: Float): List<FloatArray>
    fun title(): String?
    fun author(): String?
}

object Formats {
    val mupdf = setOf(
        "pdf", "epub", "mobi", "azw", "azw3", "fb2", "cbz", "xps", "oxps", "svg",
        "txt", "html", "htm", "xhtml", "png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff",
        "docx", "xlsx", "pptx", "odt", "ods", "odp"
    )
    val converted = setOf("cbr")          // junrar -> cbz
    val planned = setOf("djvu", "djv")    // djvulibre через NDK, см. README
    val all = mupdf + converted + planned

    fun group(ext: String) = when (ext) {
        "pdf" -> "PDF"
        "epub", "mobi", "azw", "azw3", "fb2", "txt", "html", "htm", "xhtml" -> "Книги"
        "cbz", "cbr" -> "Комиксы"
        "docx", "xlsx", "pptx", "odt", "ods", "odp" -> "Офис"
        else -> "Прочее"
    }
    val groups = listOf("PDF", "Книги", "Комиксы", "Офис", "Прочее")
}
