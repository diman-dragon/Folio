package app.folio.engine

import android.graphics.Color
import app.folio.data.AnnotationEntity
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import java.io.File

/** Записывает аннотации Folio как стандартные PDF-аннотации (Ink / Highlight / Underline / Text): видны в любых читалках. */
object PdfExporter {
    fun export(src: File, dst: File, items: List<AnnotationEntity>) {
        val doc = Document.openDocument(src.path)
        require(doc.isPDF) { "Экспорт доступен только для PDF" }
        val pdf = doc as PDFDocument
        try {
            items.groupBy { it.page }.forEach { (pi, list) ->
                val page = pdf.loadPage(pi) as PDFPage
                val b = page.bounds
                val w = b.x1 - b.x0
                val h = b.y1 - b.y0
                for (a in list) {
                    val v = a.points.split(',').filter { it.isNotEmpty() }.map { it.toFloat() }
                    val rgb = floatArrayOf(Color.red(a.color) / 255f, Color.green(a.color) / 255f, Color.blue(a.color) / 255f)
                    when (a.type) {
                        "NOTE" -> {
                            val x = b.x0 + v[0] * w
                            val y = b.y0 + v[1] * h
                            val an = page.createAnnotation(PDFAnnotation.TYPE_TEXT)
                            an.setRect(Rect(x, y, x + 24f, y + 24f))
                            an.setContents(a.text ?: "")
                            an.setColor(rgb)
                            an.update()
                        }
                        "TEXT_HL", "TEXT_UL" -> {
                            val kind = if (a.type == "TEXT_HL") PDFAnnotation.TYPE_HIGHLIGHT else PDFAnnotation.TYPE_UNDERLINE
                            val an = page.createAnnotation(kind)
                            val quads = Array(v.size / 4) { i ->
                                val x0 = b.x0 + v[i * 4] * w
                                val y0 = b.y0 + v[i * 4 + 1] * h
                                val x1 = b.x0 + v[i * 4 + 2] * w
                                val y1 = b.y0 + v[i * 4 + 3] * h
                                Quad(x0, y0, x1, y0, x0, y1, x1, y1)
                            }
                            an.setQuadPoints(quads)
                            an.setColor(rgb)
                            an.update()
                        }
                        else -> {
                            val pts = Array(v.size / 3) { i -> Point(b.x0 + v[i * 3] * w, b.y0 + v[i * 3 + 1] * h) }
                            val an = page.createAnnotation(PDFAnnotation.TYPE_INK)
                            an.setInkList(arrayOf(pts))
                            an.setBorderWidth(a.width * w)
                            an.setColor(rgb)
                            if (a.type == "MARKER") an.setOpacity(0.4f)
                            an.update()
                        }
                    }
                }
                page.destroy()
            }
            pdf.save(dst.path, "")
        } finally { pdf.destroy() }
    }
}
