package app.folio.data

import android.content.Context
import android.net.Uri
import app.folio.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class Opened(val hash: String, val file: File, val engine: DocumentEngine) : AutoCloseable {
    override fun close() = engine.close()
}

object Hashing {
    /** SHA-256(размер + первый МБ): быстро и стабильно при переименовании файла. */
    fun of(ctx: Context, uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-256")
        val size = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        md.update(size.toString().toByteArray())
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val buf = ByteArray(1 shl 20)
            var n = 0
            while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }
}

class DocumentOpener(private val ctx: Context) {
    private val dir = File(ctx.cacheDir, "docs").apply { mkdirs() }

    suspend fun open(uri: Uri, name: String): Opened = withContext(Dispatchers.IO) {
        val ext = name.substringAfterLast('.', "").lowercase().ifEmpty { "pdf" }
        if (ext in Formats.planned) throw UnsupportedFormat("DjVu пока не поддерживается (см. README)")
        if (ext !in Formats.all) throw UnsupportedFormat("Формат .$ext не поддерживается")
        val hash = Hashing.of(ctx, uri)
        var file = File(dir, "$hash.$ext")
        if (!file.exists()) {
            ctx.contentResolver.openInputStream(uri)!!.use { i -> file.outputStream().use { i.copyTo(it) } }
        }
        if (ext == "cbr") {
            val cbz = File(dir, "$hash.cbz")
            if (!cbz.exists()) ComicConverter.cbrToCbz(file, cbz)
            file = cbz
        }
        file.setLastModified(System.currentTimeMillis())
        Opened(hash, file, MuPdfEngine(file.path))
    }

    fun trim(maxBytes: Long = 800L shl 20) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= maxBytes) break; total -= f.length(); f.delete() }
    }
}
