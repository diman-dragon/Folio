package app.folio.engine

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** MuPDF не потокобезопасен на одном документе: все вызовы через Mutex + кэш битмапов. */
class SafeEngine(val raw: DocumentEngine) {
    private val mutex = Mutex()
    private val sizes = HashMap<Int, PageSize>()
    @Volatile private var gen = 0
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    suspend fun layout(w: Float, h: Float, em: Float): Int = mutex.withLock {
        withContext(Dispatchers.Default) {
            val n = raw.layout(w, h, em)
            cache.evictAll(); sizes.clear(); gen++
            n
        }
    }

    suspend fun pageSize(i: Int): PageSize = mutex.withLock {
        sizes[i] ?: withContext(Dispatchers.Default) { raw.pageSize(i) }.also { sizes[i] = it }
    }

    suspend fun render(i: Int, widthPx: Int): Bitmap {
        val key = "$gen:$i:$widthPx"
        cache.get(key)?.let { return it }
        return mutex.withLock {
            cache.get(key) ?: withContext(Dispatchers.Default) { raw.renderPage(i, widthPx) }.also { cache.put(key, it) }
        }
    }

    suspend fun toc() = mutex.withLock { withContext(Dispatchers.Default) { raw.toc() } }
    suspend fun search(q: String) = mutex.withLock { withContext(Dispatchers.Default) { raw.search(q) } }
    suspend fun unlock(pw: String) = mutex.withLock { withContext(Dispatchers.Default) { raw.unlock(pw) } }
    fun close() = raw.close()
}
