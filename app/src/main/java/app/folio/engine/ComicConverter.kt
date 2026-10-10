package app.folio.engine

import com.github.junrar.Archive
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ComicConverter {
    /** CBR (RAR) -> CBZ, чтобы открыть тем же движком. */
    fun cbrToCbz(src: File, dst: File) {
        Archive(src).use { rar ->
            ZipOutputStream(dst.outputStream().buffered()).use { zip ->
                rar.fileHeaders.filter { !it.isDirectory }.sortedBy { it.fileName }.forEach { h ->
                    zip.putNextEntry(ZipEntry(h.fileName.replace('\\', '/')))
                    rar.extractFile(h, zip)
                    zip.closeEntry()
                }
            }
        }
    }
}
