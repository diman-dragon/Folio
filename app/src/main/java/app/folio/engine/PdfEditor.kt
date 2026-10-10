package app.folio.engine

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.PDFWidget
import com.artifex.mupdf.fitz.Rect
import java.io.File

enum class FormKind { TEXT, TOGGLE, CHOICE, UNSUPPORTED }
class FormField(val kind: FormKind, val value: String, val options: List<String>, val readOnly: Boolean)

/** Операции над PDF-файлом (рабочей копией): страницы и поля форм. Каждая операция атомарно перезаписывает файл. */
object PdfEditor {
    private fun edit(file: File, block: (PDFDocument) -> Unit) {
        val doc = Document.openDocument(file.path)
        val pdf = doc as? PDFDocument
        if (pdf == null) { doc.destroy(); throw IllegalArgumentException("Правка доступна только для PDF") }
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            block(pdf)
            pdf.save(tmp.path, "")
        } finally {
            pdf.destroy()
        }
        tmp.copyTo(file, overwrite = true)
        tmp.delete()
    }

    fun rotate(file: File, page: Int, deltaDegrees: Int) = edit(file) { pdf ->
        val pg = pdf.findPage(page)
        val cur = pg.get("Rotate")?.let { if (it.isNumber) it.asInteger() else 0 } ?: 0
        pg.put("Rotate", ((cur + deltaDegrees) % 360 + 360) % 360)
    }

    fun delete(file: File, page: Int) = edit(file) { pdf -> pdf.deletePage(page) }

    fun insertBlank(file: File, after: Int) = edit(file) { pdf ->
        val ref = pdf.loadPage(after)
        val b = ref.bounds
        ref.destroy()
        val pg = pdf.addPage(Rect(0f, 0f, b.x1 - b.x0, b.y1 - b.y0), 0, pdf.newDictionary(), "")
        pdf.insertPage(after + 1, pg)
    }

    /** Вставляет все страницы src после страницы after. Возвращает их число. */
    fun insertPdf(file: File, src: File, after: Int): Int {
        var added = 0
        edit(file) { pdf ->
            val s = Document.openDocument(src.path)
            try {
                val sp = s as? PDFDocument ?: throw IllegalArgumentException("Выбранный файл не PDF")
                added = sp.countPages()
                for (k in 0 until added) pdf.graftPage(after + 1 + k, sp, k)
            } finally {
                s.destroy()
            }
        }
        return added
    }

    private fun kindOf(w: PDFWidget) = when (w.fieldType) {
        PDFWidget.TYPE_CHECKBOX, PDFWidget.TYPE_RADIOBUTTON -> FormKind.TOGGLE
        PDFWidget.TYPE_TEXT -> FormKind.TEXT
        PDFWidget.TYPE_COMBOBOX, PDFWidget.TYPE_LISTBOX -> FormKind.CHOICE
        else -> FormKind.UNSUPPORTED
    }

    private fun findWidget(pg: PDFPage, nx: Float, ny: Float): PDFWidget? {
        val b = pg.bounds
        val x = b.x0 + nx * (b.x1 - b.x0)
        val y = b.y0 + ny * (b.y1 - b.y0)
        val list = pg.getWidgets() ?: return null
        for (wg in list) {
            val r = wg.bounds
            if (x >= r.x0 && x <= r.x1 && y >= r.y0 && y <= r.y1) return wg
        }
        return null
    }

    fun fieldAt(file: File, page: Int, nx: Float, ny: Float): FormField? {
        val doc = Document.openDocument(file.path)
        try {
            val pdf = doc as? PDFDocument ?: return null
            val pg = pdf.loadPage(page) as PDFPage
            try {
                val wg = findWidget(pg, nx, ny) ?: return null
                return FormField(
                    kindOf(wg), wg.value ?: "", wg.options?.toList() ?: emptyList(),
                    (wg.fieldFlags and PDFWidget.FIELD_IS_READ_ONLY) != 0
                )
            } finally {
                pg.destroy()
            }
        } finally {
            doc.destroy()
        }
    }

    /** value == null для переключателей (checkbox/radio). */
    fun setField(file: File, page: Int, nx: Float, ny: Float, value: String?) = edit(file) { pdf ->
        val pg = pdf.loadPage(page) as PDFPage
        try {
            val wg = findWidget(pg, nx, ny) ?: throw IllegalStateException("Поле не найдено")
            when (kindOf(wg)) {
                FormKind.TOGGLE -> wg.toggle()
                FormKind.TEXT -> wg.setTextValue(value ?: "")
                FormKind.CHOICE -> wg.setChoiceValue(value ?: "")
                FormKind.UNSUPPORTED -> throw IllegalStateException("Этот тип поля не поддерживается")
            }
        } finally {
            pg.destroy()
        }
    }
}
