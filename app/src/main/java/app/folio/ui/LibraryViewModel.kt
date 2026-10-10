package app.folio.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.folio.data.BookEntity
import app.folio.data.CoverMaker
import app.folio.data.Graph
import app.folio.data.ScanWorker
import app.folio.engine.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SortBy(val label: String) { RECENT("Недавние"), TITLE("По названию"), ADDED("По добавлению"), PROGRESS("По прогрессу") }

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = Graph.db.books()

    /** 0 = «Недавние», 1 = «Библиотека». Хранится здесь, чтобы переживать открытие книги. */
    val tab = MutableStateFlow(0)
    val query = MutableStateFlow("")
    val sort = MutableStateFlow(SortBy.TITLE)
    val filter = MutableStateFlow<String?>(null)
    val onlyFav = MutableStateFlow(false)

    /** Вся библиотека (без блока «недавние»). */
    val books: StateFlow<List<BookEntity>> =
        combine(dao.observeAll(), query, sort, filter, onlyFav) { all, q, s, f, fav ->
            val list = all.filter {
                (f == null || Formats.group(it.ext) == f) && (!fav || it.favorite) &&
                    (q.isBlank() || it.displayTitle.contains(q, true) || it.author?.contains(q, true) == true)
            }
            when (s) {
                SortBy.RECENT -> list.sortedByDescending { maxOf(it.lastOpened, it.added) }
                SortBy.TITLE -> list.sortedBy { it.displayTitle.lowercase() }
                SortBy.ADDED -> list.sortedByDescending { it.added }
                SortBy.PROGRESS -> list.sortedByDescending { it.progress }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Вкладка «Недавние»: всё, что открывалось, по времени последнего открытия. */
    val recent: StateFlow<List<BookEntity>> = dao.observeAll()
        .map { l -> l.filter { it.lastOpened > 0 }.sortedByDescending { it.lastOpened }.take(60) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val scanning: StateFlow<Boolean> = WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow("scan")
        .map { l -> l.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // обложки для видимых карточек: если воркер не успел или упал — делаем лениво, по одной
    private val coverLock = Mutex()
    private val tried = HashSet<String>()
    fun ensureCover(b: BookEntity) {
        if (!tried.add(b.uri)) return
        viewModelScope.launch(Dispatchers.IO) { coverLock.withLock { CoverMaker.make(getApplication(), b) } }
    }

    fun addRoot(uri: Uri) {
        val ctx = getApplication<Application>()
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Graph.roots = Graph.roots + uri.toString()
        ScanWorker.enqueue(ctx)
    }

    fun rescan() = ScanWorker.enqueue(getApplication())
    fun toggleFav(b: BookEntity) = viewModelScope.launch { dao.setFavorite(b.uri, !b.favorite) }

    /** Одиночный файл (кнопка «Открыть файл» или внешний VIEW intent). */
    fun openUri(uri: Uri, cb: (BookEntity) -> Unit) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val b = dao.get(uri.toString()) ?: withContext(Dispatchers.IO) {
            var name = uri.lastPathSegment ?: "document"
            var size = 0L
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                }
            }
            BookEntity(uri.toString(), name, name.substringAfterLast('.', "").lowercase(), size, 0, "", System.currentTimeMillis())
                .also { dao.insertIgnore(listOf(it)); ScanWorker.enqueue(ctx) }
        }
        cb(b)
    }
}
