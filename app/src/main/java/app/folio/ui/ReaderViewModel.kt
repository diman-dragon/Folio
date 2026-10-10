package app.folio.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.folio.annotations.Tool
import app.folio.annotations.encode
import app.folio.annotations.rotateGeometry
import app.folio.data.*
import app.folio.engine.FormField
import app.folio.engine.MuPdfEngine
import app.folio.engine.PdfEditor
import app.folio.engine.PdfExporter
import app.folio.engine.SafeEngine
import app.folio.engine.TextSelect
import androidx.room.withTransaction
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
    val isReflowable: Boolean = false,
    val epoch: Int = 0,          // растёт при перезагрузке документа после правки страниц/форм
    val busy: Boolean = false
)

class FormProbe(val page: Int, val x: Float, val y: Float, val field: FormField)

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(app: Application) : AndroidViewModel(app) {
    private val db = Graph.db
    private val _state = MutableStateFlow(ReaderState())
    val state = _state.asStateFlow()
    val toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val hits = MutableStateFlow<Map<Int, List<FloatArray>>>(emptyMap())
    val searching = MutableStateFlow(false)

    private var opened: Opened? = null
    private var book: BookEntity? = null
    private var progress = 0f
    private var lastW = 360f
    private var lastH = 640f
    private var searchJob: Job? = null

    val annotations: StateFlow<List<AnnotationEntity>> = state.map { it.hash }.distinctUntilChanged()
        .flatMapLatest { if (it.isEmpty()) flowOf(emptyList()) else db.annotations().observe(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun workDir() = File(getApplication<Application>().filesDir, "work").apply { mkdirs() }

    // ---------------- открытие ----------------
    fun open(b: BookEntity) {
        if (book != null) return
        book = b; progress = b.progress
        viewModelScope.launch {
            try {
                var o = DocumentOpener(getApplication()).open(Uri.parse(b.uri), b.name)
                // если страницы ранее правили — открываем рабочую копию (иначе индексы аннотаций не совпадут)
                val w = File(workDir(), "${o.hash}.pdf")
                if (o.engine.isPdf && w.exists()) {
                    val eng = withContext(Dispatchers.IO) { MuPdfEngine(w.path) }
                    val hash = o.hash
                    o.close()
                    o = Opened(hash, w, eng)
                }
                opened = o
                if (o.engine.needsPassword) _state.update { it.copy(loading = false, needsPassword = true) }
                else finishOpen(o, b)
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, error = t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    fun unlock(pw: String) = viewModelScope.launch {
        val o = opened ?: return@launch
        if (SafeEngine(o.engine).unlock(pw)) finishOpen(o, book!!) else toast.tryEmit("Неверный пароль")
    }

    private suspend fun finishOpen(o: Opened, b: BookEntity) {
        val se = SafeEngine(o.engine)
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

    // ---------------- аннотации + история (undo / redo) ----------------
    private class Action(val removed: List<AnnotationEntity>, val added: List<AnnotationEntity>)

    private val undoStack = ArrayDeque<Action>()
    private val redoStack = ArrayDeque<Action>()
    val canUndo = MutableStateFlow(false)
    val canRedo = MutableStateFlow(false)

    private fun refreshHistory() {
        canUndo.value = undoStack.isNotEmpty()
        canRedo.value = redoStack.isNotEmpty()
    }

    private fun clearHistory() { undoStack.clear(); redoStack.clear(); refreshHistory() }

    private fun record(removed: List<AnnotationEntity>, added: List<AnnotationEntity>) {
        undoStack.addLast(Action(removed, added))
        redoStack.clear()
        if (undoStack.size > 200) undoStack.removeFirst()
        refreshHistory()
    }

    private suspend fun swap(delete: List<AnnotationEntity>, insert: List<AnnotationEntity>) {
        val dao = db.annotations()
        db.withTransaction {
            if (delete.isNotEmpty()) dao.delete(delete.map { it.id })
            for (e in insert) dao.insert(e)
        }
    }

    private suspend fun insertOne(e: AnnotationEntity) {
        val id = db.annotations().insert(e)
        record(emptyList(), listOf(e.copy(id = id)))
    }

    fun undo() = viewModelScope.launch {
        val a = undoStack.removeLastOrNull() ?: return@launch
        swap(a.added, a.removed)
        redoStack.addLast(a)
        refreshHistory()
    }

    fun redo() = viewModelScope.launch {
        val a = redoStack.removeLastOrNull() ?: return@launch
        swap(a.removed, a.added)
        undoStack.addLast(a)
        refreshHistory()
    }

    fun addStroke(page: Int, tool: Tool, color: Int, pts: FloatArray) = viewModelScope.launch {
        val s = _state.value
        insertOne(
            AnnotationEntity(docHash = s.hash, page = page, layoutKey = s.layoutKey, type = tool.name,
                color = color, width = tool.width, points = pts.encode(), created = System.currentTimeMillis())
        )
    }

    fun addNote(page: Int, x: Float, y: Float, text: String) = viewModelScope.launch {
        val s = _state.value
        insertOne(
            AnnotationEntity(docHash = s.hash, page = page, layoutKey = s.layoutKey, type = "NOTE",
                color = 0xFFFB8C00.toInt(), width = 0f, points = floatArrayOf(x, y, 1f).encode(),
                text = text, created = System.currentTimeMillis())
        )
    }

    private suspend fun markText(page: Int, tool: Tool, color: Int, sel: app.folio.engine.TextSelection) {
        val s = _state.value
        val arr = FloatArray(sel.rects.size * 4) { sel.rects[it / 4][it % 4] }
        insertOne(
            AnnotationEntity(docHash = s.hash, page = page, layoutKey = s.layoutKey,
                type = if (tool == Tool.TEXT_HL) "TEXT_HL" else "TEXT_UL",
                color = color, width = 0f, points = arr.encode(), text = sel.text,
                created = System.currentTimeMillis())
        )
    }

    /**
     * Выделение / подчёркивание / копирование по тексту. mode: TextSelect.WORD / SENTENCE / FREE.
     * Тап (a == b) выделяет слово или предложение под пальцем, протяжка — диапазон.
     */
    fun addTextMarkup(page: Int, tool: Tool, color: Int, ax: Float, ay: Float, bx: Float, by: Float, mode: Int) = viewModelScope.launch {
        val se = _state.value.engine ?: return@launch
        val sel = try { se.selectText(page, ax, ay, bx, by, mode) } catch (t: Throwable) { null }
        if (sel == null || sel.rects.isEmpty()) {
            toast.tryEmit("Текст не найден. У сканов текстового слоя нет — используйте «Маркер» или «Перо»")
            return@launch
        }
        if (tool == Tool.TEXT_COPY) {
            val ctx = getApplication<Application>()
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Folio", sel.text))
            val preview = if (sel.text.length > 60) sel.text.take(60) + "…" else sel.text
            toast.tryEmit("Скопировано: $preview")
            return@launch
        }
        markText(page, tool, color, sel)
    }

    /** Долгий тап: слово (или предложение) под пальцем выделяется; если текста нет — вызывается onNoText (заметка в этой точке). */
    fun longPressText(page: Int, x: Float, y: Float, color: Int, mode: Int, onNoText: () -> Unit) = viewModelScope.launch {
        val se = _state.value.engine ?: return@launch
        val m = if (mode == TextSelect.SENTENCE) TextSelect.SENTENCE else TextSelect.WORD
        val sel = try { se.selectText(page, x, y, x, y, m) } catch (t: Throwable) { null }
        if (sel == null || sel.rects.isEmpty()) { onNoText(); return@launch }
        // чёрный маркер скрыл бы текст — для выделения берём жёлтый
        val c = if ((color and 0x00FFFFFF) == 0) 0xFFFDD835.toInt() else color
        markText(page, Tool.TEXT_HL, c, sel)
    }

    fun editNote(id: Long, text: String) = viewModelScope.launch {
        val dao = db.annotations()
        val old = dao.byIds(listOf(id)).firstOrNull() ?: return@launch
        dao.setText(id, text)
        record(listOf(old), listOf(old.copy(text = text)))
    }

    fun deleteAnnotations(ids: List<Long>) = viewModelScope.launch {
        val dao = db.annotations()
        val old = dao.byIds(ids)
        if (old.isEmpty()) return@launch
        dao.delete(ids)
        record(old, emptyList())
    }

    /** deleted — стёрты целиком; replaced — штрих заменяется уцелевшими кусками. */
    fun erase(deleted: List<Long>, replaced: Map<Long, List<FloatArray>>) = viewModelScope.launch {
        val dao = db.annotations()
        val ids = deleted + replaced.keys
        val old = dao.byIds(ids)
        if (old.isEmpty()) return@launch
        val added = ArrayList<AnnotationEntity>()
        db.withTransaction {
            for ((id, parts) in replaced) {
                val orig = old.firstOrNull { it.id == id } ?: continue
                for (p in parts) {
                    val e = orig.copy(id = 0, points = p.encode(), created = System.currentTimeMillis())
                    added += e.copy(id = dao.insert(e))
                }
            }
            dao.delete(ids)
        }
        record(old, added)
    }

    // ---------------- поиск ----------------
    fun search(q: String) {
        val se = _state.value.engine ?: return
        if (q.isBlank()) return
        searchJob?.cancel()
        hits.value = emptyMap()
        searchJob = viewModelScope.launch {
            searching.value = true
            try {
                val acc = LinkedHashMap<Int, List<FloatArray>>()
                se.search(q) { h -> acc[h.page] = h.rects; hits.value = LinkedHashMap(acc) }
                if (acc.isEmpty()) toast.tryEmit("Ничего не найдено")
            } finally {
                searching.value = false
            }
        }
    }

    fun clearSearch() { searchJob?.cancel(); hits.value = emptyMap() }

    // ---------------- экспорт ----------------
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

    // ---------------- правка страниц и форм (рабочая копия PDF) ----------------
    private suspend fun workFile(): File = withContext(Dispatchers.IO) {
        val o = opened!!
        val w = File(workDir(), "${o.hash}.pdf")
        if (!w.exists()) o.file.copyTo(w)
        w
    }

    private suspend fun reopen(file: File, page: Int) {
        val eng = withContext(Dispatchers.IO) { MuPdfEngine(file.path) }
        val se = SafeEngine(eng)
        val old = _state.value.engine
        val n = eng.pageCount
        val hash = opened!!.hash
        opened = Opened(hash, file, eng)
        old?.closeLater()
        val p = page.coerceIn(0, maxOf(n - 1, 0))
        progress = if (n <= 1) 0f else p.toFloat() / (n - 1)
        hits.value = emptyMap()
        _state.update { it.copy(engine = se, pageCount = n, toc = emptyList(), restorePage = p, epoch = it.epoch + 1) }
        _state.update { it.copy(toc = runCatching { se.toc() }.getOrDefault(emptyList())) }
    }

    private fun runEdit(keepPage: Int, edit: (File) -> Unit, fixAnnotations: suspend () -> Unit = {}) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                val w = workFile()
                withContext(Dispatchers.IO) { edit(w) }
                fixAnnotations()
                clearHistory()
                reopen(w, keepPage)
            } catch (t: Throwable) {
                toast.tryEmit("Ошибка: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun rotatePage(page: Int, clockwise: Boolean) {
        val se = _state.value.engine ?: return
        val hash = _state.value.hash
        viewModelScope.launch {
            val size = se.pageSize(page)
            val q = if (clockwise) 1 else -1
            runEdit(page, { PdfEditor.rotate(it, page, if (clockwise) 90 else -90) }) {
                val dao = db.annotations()
                for (a in dao.onPage(hash, page)) {
                    val pts = a.points.split(',').filter { it.isNotEmpty() }.map { it.toFloat() }.toFloatArray()
                    val np = rotateGeometry(a.type, pts, q)
                    // ширина хранится как доля ширины страницы; после поворота ширина и высота меняются местами
                    dao.setGeometry(a.id, np.encode(), a.width * (size.w / size.h))
                }
            }
        }
    }

    fun deletePage(page: Int) {
        val n = _state.value.pageCount
        if (n <= 1) { toast.tryEmit("Нельзя удалить единственную страницу"); return }
        val hash = _state.value.hash
        runEdit(minOf(page, n - 2), { PdfEditor.delete(it, page) }) {
            db.annotations().deletePage(hash, page)
            db.annotations().shiftAfter(hash, page, -1)
        }
    }

    fun insertBlank(page: Int) {
        val hash = _state.value.hash
        runEdit(page + 1, { PdfEditor.insertBlank(it, page) }) { db.annotations().shiftAfter(hash, page, 1) }
    }

    fun insertPdf(page: Int, src: Uri) {
        val hash = _state.value.hash
        val ctx = getApplication<Application>()
        var added = 0
        runEdit(page + 1, { w ->
            val tmp = File(ctx.cacheDir, "insert.pdf")
            ctx.contentResolver.openInputStream(src)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            try { added = PdfEditor.insertPdf(w, tmp, page) } finally { tmp.delete() }
        }) { db.annotations().shiftAfter(hash, page, added) }
    }

    /** Вернуть исходный документ: правки страниц и ВСЕ аннотации этого документа удаляются. */
    fun resetEdits() {
        val b = book ?: return
        val hash = _state.value.hash
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                withContext(Dispatchers.IO) { File(workDir(), "$hash.pdf").delete() }
                db.annotations().deleteAll(hash)
                clearHistory()
                val o = DocumentOpener(getApplication()).open(Uri.parse(b.uri), b.name)
                val se = SafeEngine(o.engine)
                _state.value.engine?.closeLater()
                opened = o
                hits.value = emptyMap()
                _state.update { it.copy(engine = se, pageCount = o.engine.pageCount, restorePage = 0, epoch = it.epoch + 1, toc = emptyList()) }
                _state.update { it.copy(toc = runCatching { se.toc() }.getOrDefault(emptyList())) }
            } catch (t: Throwable) {
                toast.tryEmit("Ошибка: ${t.message}")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun formProbe(page: Int, x: Float, y: Float, cb: (FormProbe?) -> Unit) {
        val file = opened?.file ?: return
        if (!_state.value.isPdf) { toast.tryEmit("Формы есть только в PDF"); cb(null); return }
        viewModelScope.launch {
            val f = try { withContext(Dispatchers.IO) { PdfEditor.fieldAt(file, page, x, y) } } catch (t: Throwable) { null }
            cb(if (f == null) null else FormProbe(page, x, y, f))
        }
    }

    fun formSet(p: FormProbe, value: String?) {
        runEdit(p.page, { PdfEditor.setField(it, p.page, p.x, p.y, value) })
    }

    override fun onCleared() {
        val eng = _state.value.engine
        if (eng != null) eng.closeLater() else opened?.close()
    }
}
