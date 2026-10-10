package app.folio.engine

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * MuPDF не потокобезопасен на одном документе: все вызовы идут через Mutex.
 * Страницы и фрагменты зума имеют приоритет над миниатюрами; поиск идёт постранично и отменяем.
 */
class SafeEngine(val raw: DocumentEngine) {
    private val mutex = Mutex()
    private val sizes = ConcurrentHashMap<Int, PageSize>()
    private val waiters = AtomicInteger(0)
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

    suspend fun pageSize(i: Int): PageSize {
        sizes[i]?.let { return it }
        return mutex.withLock {
            sizes[i] ?: withContext(Dispatchers.Default) { raw.pageSize(i) }.also { sizes[i] = it }
        }
    }

    suspend fun render(i: Int, widthPx: Int): Bitmap {
        val key = "$gen:$i:$widthPx"
        cache.get(key)?.let { return it }
        waiters.incrementAndGet()
        try {
            return mutex.withLock {
                cache.get(key) ?: withContext(Dispatchers.Default) { raw.renderPage(i, widthPx) }.also { cache.put(key, it) }
            }
        } finally {
            waiters.decrementAndGet()
        }
    }

    /** Миниатюра: ждёт, пока не закончатся запросы реальных страниц. */
    suspend fun thumb(i: Int, widthPx: Int): Bitmap {
        val key = "t$gen:$i:$widthPx"
        cache.get(key)?.let { return it }
        while (waiters.get() > 0) delay(40)
        return mutex.withLock {
            cache.get(key) ?: withContext(Dispatchers.Default) { raw.renderPage(i, widthPx) }.also { cache.put(key, it) }
        }
    }

    suspend fun patch(i: Int, fullW: Int, x0: Int, y0: Int, x1: Int, y1: Int): Bitmap {
        waiters.incrementAndGet()
        try {
            return mutex.withLock {
                withContext(Dispatchers.Default) { raw.renderPatch(i, fullW, x0, y0, x1, y1) }
            }
        } finally {
            waiters.decrementAndGet()
        }
    }

    suspend fun toc() = mutex.withLock { withContext(Dispatchers.Default) { raw.toc() } }
    suspend fun unlock(pw: String) = mutex.withLock { withContext(Dispatchers.Default) { raw.unlock(pw) } }
    suspend fun selectText(i: Int, ax: Float, ay: Float, bx: Float, by: Float, mode: Int) =
        mutex.withLock { withContext(Dispatchers.Default) { raw.selectText(i, ax, ay, bx, by, mode) } }

    /** Постраничный поиск: между страницами лок отпускается, корутина отменяема. */
    suspend fun search(q: String, onHit: (Hit) -> Unit) {
        var total = 0
        for (i in 0 until raw.pageCount) {
            currentCoroutineContext().ensureActive()
            val h = mutex.withLock { withContext(Dispatchers.Default) { raw.searchPage(i, q) } }
            if (h != null) {
                onHit(h)
                total += h.rects.size
                if (total >= 500) break
            }
        }
    }

    /** Закрытие документа только когда никто не рендерит (иначе use-after-free в нативном коде). */
    @OptIn(DelicateCoroutinesApi::class)
    fun closeLater() {
        GlobalScope.launch(Dispatchers.Default) { mutex.withLock { raw.close() } }
    }
}
