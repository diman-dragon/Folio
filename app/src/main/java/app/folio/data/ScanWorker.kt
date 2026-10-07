package app.folio.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import app.folio.engine.Formats
import java.io.File

/** Фоновое сканирование выбранных папок (SAF) + генерация обложек и метаданных. */
class ScanWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    private val dao = Graph.db.books()

    override suspend fun doWork(): Result {
        for (root in Graph.roots) scan(root)
        for (b in dao.withoutMeta()) { if (isStopped) break; fillMeta(b) }
        DocumentOpener(applicationContext).trim()
        return Result.success()
    }

    private suspend fun scan(root: String) {
        val tree = DocumentFile.fromTreeUri(applicationContext, Uri.parse(root)) ?: return
        val found = ArrayList<BookEntity>()
        val now = System.currentTimeMillis()
        fun walk(d: DocumentFile) {
            for (f in d.listFiles()) {
                if (f.isDirectory) { walk(f); continue }
                val n = f.name ?: continue
                val ext = n.substringAfterLast('.', "").lowercase()
                if (ext in Formats.all) found += BookEntity(f.uri.toString(), n, ext, f.length(), f.lastModified(), root, now)
            }
        }
        walk(tree)
        dao.insertIgnore(found)
        val seen = found.map { it.uri }.toSet()
        (dao.urisByRoot(root).toSet() - seen).chunked(500).forEach { dao.deleteAll(it) }
    }

    private suspend fun fillMeta(b: BookEntity) {
        val ctx = applicationContext
        try {
            DocumentOpener(ctx).open(Uri.parse(b.uri), b.name).use { o ->
                val e = o.engine
                if (e.needsPassword) { dao.setMeta(b.uri, o.hash, null, null, ""); return }
                val bmp = e.renderPage(0, 360)
                val f = File(ctx.filesDir, "covers").apply { mkdirs() }.resolve("${o.hash}.jpg")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                dao.setMeta(b.uri, o.hash, e.title(), e.author(), f.path)
            }
        } catch (t: Throwable) {
            dao.setMeta(b.uri, null, null, null, "")
        }
    }

    companion object {
        fun enqueue(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniqueWork(
            "scan", ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<ScanWorker>().build()
        )
    }
}
