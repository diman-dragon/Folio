package app.folio.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.folio.annotations.Tool
import app.folio.annotations.encode
import app.folio.data.*
import app.folio.engine.PdfExporter
import app.folio.engine.SafeEngine
import app.folio.engine.TocItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import kotlin.math.roundToInt

data class ReaderState(
    val loading: Boolean = true,
    val error: String? = null,
    val needsPassword: Boolean = false,
    val engine: SafeEngine? = null,
    val hash: String = "",
    val name: String = "",
    val pageCount: Int = 0,
    val layoutKey: String = "",
    val toc: List<TocItem> = emptyList(),
    val restorePage: Int = 0,
    val em: Float = 16f,
    val isPdf: Boolean = false,
    val isReflowable: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(app: Application) : AndroidViewModel(app) {
    private val db = Graph.db
    private val _state = MutableStateFlow(ReaderState())
    val state = _state.asStateFlow()
    val toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val hits = MutableStateFlow<Map<Int, List<FloatArray>>>(emptyMap())

    private var opened: Opened? = null
    private var book: BookEntity? = null
    private var progress = 0f
    private var lastW = 360f
    private var lastH = 640f
    private val undoStack = ArrayDeque<Long>()

    val annotations: StateFlow<List<AnnotationEntity>> = state.map { it.hash }.distinctUntilChanged()
        .flatMapLatest { if (it.isEmpty()) flowOf(emptyList()) else db.annotations().observe(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun open(b: BookEntity) {
        if (book != null) return
        book = b; progress = b.progress
        viewModelScope.launch {
            try {
                val o = DocumentOpener(getApplication()).open(Uri.parse(b.uri), b.name)
                opened = o
                if (o.engine.needsPassword) {
                    // заводим ЕДИНСТВЕННУЮ обёртку сразу: unlock() переиспользует её,
                    // второй SafeEngine поверх того же документа больше не создаётся (фикс утечки)
                    _state.update { it.copy(loading = false, needsPassword = true, engine = SafeEngine(o.engine)) }
                } else finishOpen(o, b)
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, error = t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    fun unlock(pw: String) = viewModelScope.launch {
        val o = opened ?: return@launch
        // ВАЖНО (фикс утечки): раньше здесь создавался ВТОРОЙ SafeEngine поверх уже
        // открытого — два кэша, два набора локов, первый никогда не закрывался.
        // Теперь переиспользуем тот же engine из состояния.
        val se = _state.value.engine
        if (se != null && se.unlock(pw)) finishOpen(o, book!!)
        else toast.tryEmit("Неверный пароль")
    }

    private suspend fun finishOpen(o: Opened, b: BookEntity) {
        // переиспользуем существующую обёртку, если она уже есть (unlock-путь)
        val se = _state.value.engine ?: SafeEngine(o.engine)
        db.books().touch(b.uri, System.currentTimeMillis())
        val reflow = o.engine.isReflowable
        val n = if (reflow) 0 else o.engine.pageCount
        _state.value = ReaderState(
            loading = false, engine = se, hash = o.hash, name = b.displayTitle,
            pageCount = n, layoutKey = if (reflow) "" else "fixed",
            toc = if (reflow) emptyList() else se.toc(),
            restorePage = pageFromProgress(n), isPdf = o.engine.isPdf, isReflowable = reflow
        )
    }

    private fun pageFromProgress(n: Int) = if (n <= 1) 0 else (progress * (n - 1)).roundToInt()

    fun relayout(w: Float, h: Float, em: Float = _state.value.em) {
        val se = _state.value.engine ?: return
        if (!se.raw.isReflowable) return
        lastW = w; lastH = h
        val key = "${w.toInt()}x${h.toInt()}@$em"
        if (key == _state.value.layoutKey) return
        viewModelScope.launch {
            val n = se.layout(w, h, em)
            val toc = se.toc()
            hits.value = emptyMap()
            _state.update { it.copy(pageCount = n, layoutKey = key, toc = toc, em = em, restorePage = pageFromProgress(n)) }
        }
    }

    fun bumpFont(delta: Float) = relayout(lastW, lastH, (_state.value.em + delta).coerceIn(10f, 36f))

    fun saveProgress(page: Int) {
        val n = _state.value.pageCount
        val b = book ?: return
        if (n <= 0) return
        progress = if (n <= 1) 0f else page.toFloat() / (n - 1)
        viewModelScope.launch { db.books().setProgress(b.uri, progress, System.currentTimeMillis()) }
    }

    // ---- аннотации ----
    fun addStroke(page: Int, tool: Tool, color: Int, pts: FloatArray) = viewModelScope.launch {
        val s = _state.value
        val id = db.annotations().insert(
            AnnotationEntity(docHash = s.hash, page = page, layoutKey = s.layoutKey, type = tool.name,
                color = color, width = tool.width, points = pts.encode(), created = System.currentTimeMillis())
        )
        undoStack.addLast(id)
    }

    fun addNote(page: Int, x: Float, y: Float, text: String) = viewModelScope.launch {
        val s = _state.value
        val id = db.annotations().insert(
            AnnotationEntity(docHash = s.hash, page = page, layoutKey = s.layoutKey, type = "NOTE",
                color = 0xFFFB8C00.toInt(), width = 0f, points = floatArrayOf(x, y, 1f).encode(),
                text = text, created = System.currentTimeMillis())
        )
        undoStack.addLast(id)
    }

    fun editNote(id: Long, text: String) = viewModelScope.launch { db.annotations().setText(id, text) }
    fun erase(ids: List<Long>) = viewModelScope.launch { db.annotations().delete(ids) }
    fun undo() = viewModelScope.launch {
        val id = undoStack.removeLastOrNull() ?: return@launch
        db.annotations().delete(listOf(id))
    }

    // ---- поиск ----
    private var searchJob: Job? = null

    /**
     * Отменяемый пошаговый поиск: результат прилетает постранично (UI не ждёт весь документ),
     * новый запрос отменяет предыдущий скан.
     */
    fun search(q: String) {
        val se = _state.value.engine ?: return
        searchJob?.cancel()
        if (q.isBlank()) { hits.value = emptyMap(); return }
        searchJob = viewModelScope.launch {
            val acc = HashMap<Int, List<FloatArray>>()
            try {
                se.searchPaged(q, limit = 300) { h ->
                    acc[h.page] = h.rects
                    hits.value = acc.toMap()   // partial-обновление по мере сканирования
                }
                if (acc.isEmpty()) toast.tryEmit("Ничего не найдено")
            } catch (_: CancellationException) { /* пользователь ввёл новый запрос — тихо выходим */ }
        }
    }
    fun clearSearch() { searchJob?.cancel(); hits.value = emptyMap() }

    // ---- экспорт ----
    fun exportPdf(target: Uri) {
        val o = opened ?: return
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val items = db.annotations().all(_state.value.hash)
                val tmp = File(ctx.cacheDir, "export.pdf")
                PdfExporter.export(o.file, tmp, items)
                ctx.contentResolver.openOutputStream(target)!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                tmp.delete()
            }.onSuccess { toast.tryEmit("PDF сохранён") }
                .onFailure { toast.tryEmit("Ошибка экспорта: ${it.message}") }
        }
    }

    override fun onCleared() { _state.value.engine?.close() ?: opened?.close() }
}
