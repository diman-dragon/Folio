package app.folio.engine

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.android.AndroidDrawDevice

/** PDF, EPUB, MOBI, FB2, CBZ, XPS, SVG, TXT, HTML, изображения, DOCX/XLSX/PPTX (MuPDF >= 1.25). */
class MuPdfEngine(path: String) : DocumentEngine {
    private val doc: Document = Document.openDocument(path)
    @Volatile private var count = 0

    init { if (!doc.needsPassword()) initPages() }

    private fun initPages() {
        if (doc.isReflowable) layout(360f, 640f, 16f) else count = doc.countPages()
    }

    override val isReflowable get() = doc.isReflowable
    override val isPdf get() = doc.isPDF
    override val pageCount get() = count
    override val needsPassword get() = doc.needsPassword()

    override fun unlock(password: String): Boolean {
        val ok = doc.authenticatePassword(password)
        if (ok) initPages()
        return ok
    }

    override fun layout(width: Float, height: Float, em: Float): Int {
        doc.layout(width, height, em)
        count = doc.countPages()
        return count
    }

    private inline fun <T> withPage(i: Int, block: (Page) -> T): T {
        val p = doc.loadPage(i)
        try { return block(p) } finally { p.destroy() }
    }

    override fun pageSize(index: Int): PageSize = withPage(index) {
        val b = it.bounds
        PageSize(b.x1 - b.x0, b.y1 - b.y0)
    }

    override fun renderPage(index: Int, widthPx: Int): Bitmap = withPage(index) {
        AndroidDrawDevice.drawPageFitWidth(it, widthPx)
    }

    override fun toc(): List<TocItem> {
        val out = ArrayList<TocItem>()
        fun walk(items: Array<Outline>?, level: Int) {
            items?.forEach { o ->
                val page = runCatching { doc.pageNumberFromLocation(doc.resolveLink(o)) }.getOrDefault(0)
                out += TocItem(o.title ?: "", page, level)
                walk(o.down, level + 1)
            }
        }
        walk(runCatching { doc.loadOutline() }.getOrNull(), 0)
        return out
    }

    override fun search(query: String, limit: Int): List<Hit> {
        val out = ArrayList<Hit>()
        var total = 0
        for (i in 0 until count) {
            if (total >= limit) break
            withPage(i) { p ->
                val b = p.bounds
                val w = b.x1 - b.x0
                val h = b.y1 - b.y0
                val res = p.search(query)
                if (res != null && res.isNotEmpty()) {
                    val rects = res.flatMap { quads ->
                        quads.map { q ->
                            floatArrayOf(
                                (minOf(q.ul_x, q.ll_x) - b.x0) / w, (minOf(q.ul_y, q.ur_y) - b.y0) / h,
                                (maxOf(q.ur_x, q.lr_x) - b.x0) / w, (maxOf(q.ll_y, q.lr_y) - b.y0) / h
                            )
                        }
                    }
                    out += Hit(i, rects)
                    total += res.size
                }
            }
        }
        return out
    }

    override fun title(): String? = doc.getMetaData(Document.META_INFO_TITLE)?.takeIf { it.isNotBlank() }
    override fun author(): String? = doc.getMetaData(Document.META_INFO_AUTHOR)?.takeIf { it.isNotBlank() }
    override fun close() { doc.destroy() }
}
