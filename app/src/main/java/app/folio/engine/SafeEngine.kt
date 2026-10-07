package app.folio.engine

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.collections.set
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Обёртка над движком: постраничный lock-stripe вместо одного глобального мьютекса —
 * разные страницы рендерятся параллельно (MuPDF безопасно грузит разные страницы одного
 * документа одновременно), тяжёлые операции (layout/search/toc) берут write-лок и экранируют всё.
 * Кэш битмапов — по памяти, а не по пиксельной сетке; плюс фоновая предвыборка соседних страниц.
 */
class SafeEngine(val raw: DocumentEngine) {
    companion object {
        /** Параллельный рендер разных страниц. На слабом железе лучше 3–4: больше ядер != быстрее. */
        const val RENDER_PARALLELISM = 4
        /** Черновые кадры (первый кадр при зуме/скролле) — RGB_565: вдвое меньше памяти и GC-нагрузки. */
        const val DRAFT_BUDGET_KB = 6 * 1024      // ~6 МБ черновых
        const val FINAL_BUDGET_KB = 24 * 1024     // ~24 МБ чистых
        const val PREFETCH_RANGE = 3              // ±3 страницы от видимой
    }

    private val pageLocks = Array(RENDER_PARALLELISM) { Mutex() }
    private val permits = Semaphore(RENDER_PARALLELISM)
    private val rw = ReentrantReadWriteLock()
    private val sizes = HashMap<Int, PageSize>()
    private val sizesLock = Any()
    @Volatile private var gen = 0
    @Volatile private var closed = false

    /** Кэш по байтам, две категории веса: draft (дешёвый RGB_565) и final (ARGB_8888). */
    private class BitmapCache(maxKb: Int) : LruCache<String, Bitmap>(maxKb) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            if (value.config == Bitmap.Config.RGB_565) value.byteCount / 2048 else value.byteCount / 1024
    }

    private val draftCache = BitmapCache(DRAFT_BUDGET_KB)
    private val finalCache = BitmapCache(FINAL_BUDGET_KB)

    private inline fun <T> read(block: () -> T): T = rw.read().use { block() }
    private inline fun <T> write(block: () -> T): T = rw.write().use { block() }
    private inline fun <R> java.util.concurrent.locks.Lock.use(block: () -> R): R = try { lock(); block() } finally { unlock() }

    suspend fun pageSize(i: Int): PageSize = read {
        synchronized(sizesLock) { sizes[i] } ?: run {
            val p = withContext(Dispatchers.Default) { raw.pageSize(i) }
            synchronized(sizesLock) { sizes[i] = p }
            p
        }
    }

    /**
     * mainPx — целевой размер под экран (final), draftPx — быстрый черновой кадр (обычно ~700 px).
     * Если main == draft — один кадр final. Возвращает готовый bitmap; draft приходит сразу, final апдейтом.
     */
    suspend fun render(i: Int, mainPx: Int, draftPx: Int = 0): Bitmap {
        val g = gen
        val draftKey = "d$g:$i:$draftPx"
        val mainKey = "m$g:$i:$mainPx"
        if (draftPx > 0 && draftPx < mainPx) {
            finalCache.get(mainKey)?.let { return it }       // уже чисто — не показываем черновик
            draftCache.get(draftKey)?.let { return it }
        } else {
            finalCache.get(mainKey)?.let { return it }
        }
        return renderLocked(i, mainPx, draftPx, g)
    }

    private suspend fun renderLocked(i: Int, mainPx: Int, draftPx: Int, g: Int): Bitmap =
        permits.withPermit {                     // параллельно до N страниц
            pageLocks[i % RENDER_PARALLELISM].withLock {   // но одна страница — один рендер
                val mainKey = "m$g:$i:$mainPx"
                val cached = finalCache.get(mainKey)
                if (cached != null) return cached
                read {
                    if (closed || g != gen) throw CancellationException("layout changed")
                    // draft: быстрый RGB_565 low-res, потом final ARGB_8888
                    if (draftPx > 0 && draftPx < mainPx) {
                        val d = withContext(Dispatchers.Default) { raw.renderPageDraft(i, draftPx) }
                        draftCache.put("d$g:$i:$draftPx", d)
                        val m = withContext(Dispatchers.Default) { raw.renderPage(i, mainPx) }
                        finalCache.put(mainKey, m)
                        m
                    } else {
                        val m = withContext(Dispatchers.Default) { raw.renderPage(i, mainPx) }
                        finalCache.put(mainKey, m)
                        m
                    }
                }
            }
        }

    /** Фоновая предвыборка ±PREFETCH_RANGE страниц на низких разрешениях (draft-кэш). */
    fun prefetch(scope: CoroutineScope, visible: Int, total: Int, px: Int): Job = scope.launch(Dispatchers.Default) {
        val lo = (visible - PREFETCH_RANGE).coerceAtLeast(0)
        val hi = (visible + PREFETCH_RANGE).coerceAtMost(total - 1)
        val smallPx = minOf(px, 900)
        for (i in hi downTo lo) {          // сначала ближайшая к краю просмотра
            if (!isActive) break
            val key = "d$gen:$i:$smallPx"
            if (draftCache.get(key) != null || finalCache.get("m$gen:$i:$px") != null) continue
            runCatching { render(i, px, smallPx) }
        }
    }

    suspend fun layout(w: Float, h: Float, em: Float): Int = write {
        withContext(Dispatchers.Default) {
            val n = raw.layout(w, h, em)
            draftCache.evictAll(); finalCache.evictAll()
            synchronized(sizesLock) { sizes.clear() }
            gen++
            n
        }
    }

    /**
     * Постраничный отменяемый поиск: выдаёт partial-результаты по мере сканирования,
     * держит read-лок только на время страницы (не на весь документ!).
     */
    suspend fun searchPaged(q: String, limit: Int, onPartial: (Hit) -> Unit) = withContext(Dispatchers.Default) {
        var total = 0
        var i = 0
        while (i < pageCount() && total < limit) {
            if (!isActive) break
            val hit = read { runCatching { raw.searchPage(i, q) }.getOrNull() }
            hit?.let { onPartial(it); total += it.rects.size }
            i++
        }
    }

    suspend fun toc(): List<TocItem> = write { withContext(Dispatchers.Default) { raw.toc() } }
    suspend fun unlock(pw: String): Boolean = write { withContext(Dispatchers.Default) { raw.unlock(pw) } }
    fun pageCount(): Int = read { raw.pageCount }
    fun close() = write { closed = true; raw.close() }
}
