package app.folio.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Обложка = рендер первой страницы документа. Пишет путь, хеш, название и автора в БД. */
object CoverMaker {
    suspend fun make(ctx: Context, b: BookEntity) {
        val dao = Graph.db.books()
        try {
            DocumentOpener(ctx).open(Uri.parse(b.uri), b.name).use { o ->
                val e = o.engine
                if (e.needsPassword) { dao.setMeta(b.uri, o.hash, null, null, ""); return }
                val bmp = withContext(Dispatchers.Default) { e.renderPage(0, 420) }
                val f = File(ctx.filesDir, "covers").apply { mkdirs() }.resolve("${o.hash}.jpg")
                withContext(Dispatchers.IO) { f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
                dao.setMeta(b.uri, o.hash, e.title(), e.author(), f.path)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            dao.setMeta(b.uri, null, null, null, "")
        }
    }
}
