package app.folio.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val ext: String,
    val size: Long,
    val modified: Long,
    val source: String,          // корневая папка библиотеки, "" если открыт одиночный файл
    val added: Long,
    val title: String? = null,
    val author: String? = null,
    val hash: String? = null,
    val coverPath: String? = null, // null = ещё не обработан, "" = обложки нет
    val progress: Float = 0f,
    val lastOpened: Long = 0,
    val favorite: Boolean = false,
    val collection: String? = null
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.')
}

/** Аннотация. Координаты нормализованы (0..1) относительно страницы -> не зависят от зума/поворота/движка. */
@Entity(tableName = "annotations", indices = [Index("docHash", "page")])
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docHash: String,
    val page: Int,
    val layoutKey: String,       // "fixed" для PDF/CBZ; для reflowable — параметры вёрстки
    val type: String,            // PEN, MARKER, UNDERLINE, NOTE
    val color: Int,
    val width: Float,            // доля ширины страницы
    val points: String,          // x,y,pressure,x,y,pressure,...
    val text: String? = null,
    val created: Long
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books") fun observeAll(): Flow<List<BookEntity>>
    @Query("SELECT * FROM books WHERE uri=:uri") suspend fun get(uri: String): BookEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(list: List<BookEntity>)
    @Query("SELECT uri FROM books WHERE source=:root") suspend fun urisByRoot(root: String): List<String>
    @Query("DELETE FROM books WHERE uri IN (:uris)") suspend fun deleteAll(uris: List<String>)
    @Query("SELECT * FROM books WHERE coverPath IS NULL") suspend fun withoutMeta(): List<BookEntity>
    @Query("UPDATE books SET hash=:hash, title=:title, author=:author, coverPath=:cover WHERE uri=:uri")
    suspend fun setMeta(uri: String, hash: String?, title: String?, author: String?, cover: String)
    @Query("UPDATE books SET progress=:p, lastOpened=:t WHERE uri=:uri") suspend fun setProgress(uri: String, p: Float, t: Long)
    @Query("UPDATE books SET lastOpened=:t WHERE uri=:uri") suspend fun touch(uri: String, t: Long)
    @Query("UPDATE books SET favorite=:f WHERE uri=:uri") suspend fun setFavorite(uri: String, f: Boolean)
}

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotations WHERE docHash=:h") fun observe(h: String): Flow<List<AnnotationEntity>>
    @Query("SELECT * FROM annotations WHERE docHash=:h") suspend fun all(h: String): List<AnnotationEntity>
    @Insert suspend fun insert(a: AnnotationEntity): Long
    @Query("UPDATE annotations SET text=:text WHERE id=:id") suspend fun setText(id: Long, text: String)
    @Query("DELETE FROM annotations WHERE id IN (:ids)") suspend fun delete(ids: List<Long>)
    @Query("SELECT * FROM annotations WHERE docHash=:h AND page=:p") suspend fun onPage(h: String, p: Int): List<AnnotationEntity>
    @Query("DELETE FROM annotations WHERE docHash=:h AND page=:p") suspend fun deletePage(h: String, p: Int)
    @Query("DELETE FROM annotations WHERE docHash=:h") suspend fun deleteAll(h: String)
    @Query("SELECT * FROM annotations WHERE id IN (:ids)") suspend fun byIds(ids: List<Long>): List<AnnotationEntity>
    @Query("UPDATE annotations SET page = page + :d WHERE docHash=:h AND page > :p") suspend fun shiftAfter(h: String, p: Int, d: Int)
    @Query("UPDATE annotations SET points=:pts, width=:w WHERE id=:id") suspend fun setGeometry(id: Long, pts: String, w: Float)
}

@Database(entities = [BookEntity::class, AnnotationEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun annotations(): AnnotationDao
}

object Graph {
    lateinit var db: AppDb
    private lateinit var app: Context
    fun init(ctx: Context) {
        app = ctx.applicationContext
        db = Room.databaseBuilder(app, AppDb::class.java, "folio.db").build()
    }
    private val prefs by lazy { app.getSharedPreferences("folio", 0) }
    var roots: Set<String>
        get() = prefs.getStringSet("roots", emptySet())!!.toSet()
        set(v) { prefs.edit().putStringSet("roots", v).apply() }
}
