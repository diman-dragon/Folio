package app.folio.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.folio.annotations.*
import app.folio.data.BookEntity
import app.folio.engine.PageSize
import app.folio.engine.SafeEngine
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private class NoteDraft(val page: Int, val x: Float, val y: Float, val existing: Stroke?)
private const val MAX_RENDER_PX = 2400
private const val MAX_SCALE = 5f

@Composable
fun ReaderScreen(book: BookEntity, onBack: () -> Unit) {
    val vm: ReaderViewModel = viewModel(key = book.uri)
    LaunchedEffect(book.uri) { vm.open(book) }
    val st by vm.state.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.toast.collect { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() } }
    val err = st.error
    when {
        st.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        err != null -> Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("Не удалось открыть: $err"); Spacer(Modifier.height(12.dp)); Button(onClick = onBack) { Text("Назад") }
        }
        st.needsPassword -> PasswordDialog(onOk = { vm.unlock(it) }, onCancel = onBack)
        else -> ReaderContent(vm, st, onBack)
    }
}

@OptIn(FlowPreview::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReaderContent(vm: ReaderViewModel, st: ReaderState, onBack: () -> Unit) {
    val engine = st.engine!!
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("folio", 0) }

    var paged by remember { mutableStateOf(prefs.getBoolean("paged", true)) }   // по страницам / лента
    var tool by remember { mutableStateOf(Tool.NONE) }
    var color by remember { mutableIntStateOf(Palette[0]) }
    var stylusOnly by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    var showThumbs by remember { mutableStateOf(false) }   // миниатюры скрыты по умолчанию
    var showToc by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<NoteDraft?>(null) }

    // трансформация вьюпорта: экран = содержимое * scale + (tx, ty); якорь — левый верхний угол
    var scale by remember { mutableFloatStateOf(1f) }
    var tx by remember { mutableFloatStateOf(0f) }
    var ty by remember { mutableFloatStateOf(0f) }
    var renderScale by remember { mutableFloatStateOf(1f) }
    var targetPage by remember { mutableIntStateOf(-1) }
    var restored by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val pagerState = rememberPagerState { st.pageCount }
    val anns by vm.annotations.collectAsState()
    val hits by vm.hits.collectAsState()
    val byPage = remember(anns, st.layoutKey) {
        anns.filter { it.layoutKey == st.layoutKey }.map { it.toStroke() }.groupBy { it.page }
    }
    val current by remember { derivedStateOf { if (paged) pagerState.currentPage else listState.firstVisibleItemIndex } }

    fun resetZoom() { scale = 1f; tx = 0f; ty = 0f }
    fun goTo(i: Int) { resetZoom(); scope.launch { if (paged) pagerState.scrollToPage(i) else listState.scrollToItem(i) } }

    /** Сдвиг содержимого. В ленте остаток по вертикали уходит в прокрутку списка. */
    fun panBy(d: Offset, w: Float, h: Float) {
        val s = scale
        tx = (tx + d.x).coerceIn(w * (1f - s), 0f)
        val want = ty + d.y
        val clamped = want.coerceIn(h * (1f - s), 0f)
        ty = clamped
        if (!paged) {
            val rem = want - clamped
            if (rem != 0f) listState.dispatchRawDelta(-rem / s)
        }
    }

    /** Зум в точке c (координаты вьюпорта): точка под пальцами остаётся на месте. */
    fun zoomAt(factor: Float, c: Offset, w: Float, h: Float) {
        val ns = (scale * factor).coerceIn(1f, MAX_SCALE)
        val k = ns / scale
        tx = (c.x - (c.x - tx) * k).coerceIn(w * (1f - ns), 0f)
        ty = (c.y - (c.y - ty) * k).coerceIn(h * (1f - ns), 0f)
        scale = ns
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { u ->
        u?.let { vm.exportPdf(it) }
    }

    BackHandler(enabled = showThumbs) { showThumbs = false }
    LaunchedEffect(scale) { delay(250); renderScale = scale }
    LaunchedEffect(st.layoutKey, st.restorePage) {
        if (st.pageCount > 0) {
            restored = false
            val t = st.restorePage.coerceIn(0, st.pageCount - 1)
            if (paged) pagerState.scrollToPage(t) else listState.scrollToItem(t)
            restored = true
        }
    }
    LaunchedEffect(paged) {   // переключение режима: остаёмся на той же странице
        val t = targetPage
        if (t >= 0 && st.pageCount > 0) {
            val p = t.coerceIn(0, st.pageCount - 1)
            if (paged) pagerState.scrollToPage(p) else listState.scrollToItem(p)
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { current }.debounce(700).collect { if (restored) vm.saveProgress(it) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF1E1F22))) {
        val viewW = maxWidth
        val viewH = maxHeight
        val vwPx = with(density) { viewW.toPx() }
        val vhPx = with(density) { viewH.toPx() }
        if (st.isReflowable) LaunchedEffect(viewW, viewH) { vm.relayout(viewW.value, viewH.value) }

        Box(Modifier.fillMaxSize()) {
            // ---------- страницы + жесты зума ----------
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(tool, stylusOnly, paged) {
                        var lastTapTime = 0L
                        var lastTapPos = Offset.Zero
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            var moved = false
                            var multi = false
                            var lastUp = first.uptimeMillis
                            do {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                lastUp = ev.changes[0].uptimeMillis
                                val pressed = ev.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    multi = true
                                    val z = ev.calculateZoom()
                                    val c = ev.calculateCentroid()
                                    val p = ev.calculatePan()
                                    if (z != 1f || p != Offset.Zero) {
                                        zoomAt(z, c, w, h)
                                        panBy(p, w, h)
                                        ev.changes.forEach { it.consume() }
                                    }
                                } else if (!multi && scale > 1f && pressed.size == 1) {
                                    val ch = pressed[0]
                                    val canPan = tool == Tool.NONE || (stylusOnly && ch.type != PointerType.Stylus)
                                    val d = ch.positionChange()
                                    if (canPan && d != Offset.Zero) { panBy(d, w, h); ch.consume(); moved = true }
                                }
                                if (!moved && (ev.changes[0].position - first.position).getDistance() > slop) moved = true
                            } while (ev.changes.any { it.pressed })

                            // двойной тап: зум в точку / сброс
                            if (tool == Tool.NONE && !multi && !moved && lastUp - first.uptimeMillis < 300) {
                                if (first.uptimeMillis - lastTapTime < 350 && (first.position - lastTapPos).getDistance() < 120f) {
                                    lastTapTime = 0L
                                    if (scale > 1f) resetZoom() else zoomAt(2.5f, first.position, w, h)
                                } else { lastTapTime = lastUp; lastTapPos = first.position }
                            }
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures { chrome = !chrome } }
            ) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale; scaleY = scale
                        translationX = tx; translationY = ty
                    }
                ) {
                    if (paged) {
                        HorizontalPager(
                            state = pagerState, modifier = Modifier.fillMaxSize(),
                            userScrollEnabled = scale <= 1.01f,
                            beyondViewportPageCount = 1,
                            key = { "${st.layoutKey}:$it" }
                        ) { i ->
                            PageItem(
                                engine, i, st.layoutKey, true, vwPx, vhPx, renderScale,
                                byPage[i].orEmpty(), hits[i].orEmpty(), tool, Color(color), stylusOnly,
                                onStroke = { vm.addStroke(i, tool, color, it) }, onErase = vm::erase,
                                onNoteAt = { x, y -> draft = NoteDraft(i, x, y, null) },
                                onNoteOpen = { draft = NoteDraft(i, it.pts[0], it.pts[1], it) },
                                onTapBackground = { chrome = !chrome }
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState, modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(vertical = 6.dp)
                        ) {
                            items(st.pageCount, key = { "${st.layoutKey}:$it" }) { i ->
                                PageItem(
                                    engine, i, st.layoutKey, false, vwPx, vhPx, renderScale,
                                    byPage[i].orEmpty(), hits[i].orEmpty(), tool, Color(color), stylusOnly,
                                    onStroke = { vm.addStroke(i, tool, color, it) }, onErase = vm::erase,
                                    onNoteAt = { x, y -> draft = NoteDraft(i, x, y, null) },
                                    onNoteOpen = { draft = NoteDraft(i, it.pts[0], it.pts[1], it) },
                                    onTapBackground = { chrome = !chrome }
                                )
                            }
                        }
                    }
                }
            }

            // ---------- номер страницы ----------
            if (st.pageCount > 0) Surface(
                Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = if (chrome) 84.dp else 16.dp),
                shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.6f)
            ) { Text("${current + 1} / ${st.pageCount}", Modifier.padding(10.dp, 4.dp), color = Color.White) }

            // ---------- верхняя панель ----------
            AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
                TopAppBar(
                    title = { Text(st.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                    actions = {
                        IconButton({ showThumbs = !showThumbs }) { Icon(Icons.Default.GridView, "Миниатюры") }
                        if (st.toc.isNotEmpty()) IconButton({ showToc = true }) { Icon(Icons.AutoMirrored.Filled.List, "Оглавление") }
                        IconButton({ showSearch = true }) { Icon(Icons.Default.Search, "Поиск") }
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Ещё") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (paged) "Режим: лента" else "Режим: по страницам") },
                                    onClick = {
                                        menu = false
                                        targetPage = current
                                        resetZoom()
                                        paged = !paged
                                        prefs.edit().putBoolean("paged", paged).apply()
                                    })
                                if (st.isPdf) DropdownMenuItem(
                                    text = { Text("Сохранить PDF с аннотациями") },
                                    onClick = { menu = false; exportLauncher.launch(st.name.take(60) + "-annotated.pdf") })
                                if (st.isReflowable) {
                                    DropdownMenuItem(text = { Text("Шрифт крупнее") }, onClick = { vm.bumpFont(+2f) })
                                    DropdownMenuItem(text = { Text("Шрифт мельче") }, onClick = { vm.bumpFont(-2f) })
                                }
                                DropdownMenuItem(text = { Text("Сбросить зум") }, onClick = { resetZoom(); menu = false })
                            }
                        }
                    }
                )
            }

            // ---------- панель аннотаций ----------
            AnimatedVisibility(chrome, Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
                    ) {
                        Tool.entries.forEach { t -> FilterChip(tool == t, { tool = t }, { Text(t.label) }) }
                        Spacer(Modifier.width(4.dp))
                        Palette.forEach { c ->
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                                    .border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                                    .clickable { color = c }
                            )
                        }
                        IconButton({ vm.undo() }) { Icon(Icons.AutoMirrored.Filled.Undo, "Отменить") }
                        FilterChip(stylusOnly, { stylusOnly = !stylusOnly }, { Text("Только стилус") })
                    }
                }
            }

            // ---------- миниатюры (скрываются) ----------
            AnimatedVisibility(showThumbs, Modifier.align(Alignment.CenterStart), enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
                Surface(Modifier.width(108.dp).fillMaxHeight(), tonalElevation = 6.dp) {
                    val ts = rememberLazyListState()
                    LaunchedEffect(Unit) { ts.scrollToItem(current) }
                    LazyColumn(
                        state = ts, contentPadding = PaddingValues(8.dp, 72.dp, 8.dp, 88.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(st.pageCount, key = { "${st.layoutKey}:t$it" }) { i ->
                            Thumb(engine, i, st.layoutKey, i == current, byPage[i]?.isNotEmpty() == true) { goTo(i) }
                        }
                    }
                }
            }
        }
    }

    // ---------- диалоги ----------
    if (showToc) AlertDialog(
        onDismissRequest = { showToc = false }, confirmButton = { TextButton({ showToc = false }) { Text("Закрыть") } },
        title = { Text("Оглавление") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(st.toc) { t ->
                    Text(
                        t.title.ifBlank { "—" },
                        Modifier.fillMaxWidth().clickable { showToc = false; goTo(t.page.coerceAtLeast(0)) }
                            .padding(start = (t.level * 16).dp, top = 10.dp, bottom = 10.dp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        })

    if (showSearch) SearchDialog(
        hits, onSearch = vm::search, onClear = vm::clearSearch,
        onGo = { p -> showSearch = false; goTo(p) },
        onDismiss = { showSearch = false })

    draft?.let { d ->
        NoteDialog(
            initial = d.existing?.text ?: "",
            canDelete = d.existing != null,
            onSave = { text ->
                if (d.existing != null) vm.editNote(d.existing.id, text) else vm.addNote(d.page, d.x, d.y, text)
                draft = null
            },
            onDelete = { d.existing?.let { vm.erase(listOf(it.id)) }; draft = null },
            onDismiss = { draft = null })
    }
}

/**
 * fit = true  — режим «по страницам»: страница целиком вписана во вьюпорт и центрирована.
 * fit = false — режим «лента»: страница на всю ширину.
 */
@Composable
private fun PageItem(
    engine: SafeEngine, index: Int, layoutKey: String, fit: Boolean, vw: Float, vh: Float, renderScale: Float,
    strokes: List<Stroke>, hits: List<FloatArray>, tool: Tool, color: Color, stylusOnly: Boolean,
    onStroke: (FloatArray) -> Unit, onErase: (List<Long>) -> Unit,
    onNoteAt: (Float, Float) -> Unit, onNoteOpen: (Stroke) -> Unit, onTapBackground: () -> Unit
) {
    val size by produceState<PageSize?>(null, index, layoutKey) { value = engine.pageSize(index) }
    val ratio = size?.let { it.h / it.w } ?: 1.41f
    val basePx = if (fit) minOf(vw, vh / ratio) else vw
    val px = minOf((basePx * renderScale).roundToInt().coerceAtLeast(64), MAX_RENDER_PX)
    // при смене px старый битмап остаётся на экране, пока не придёт чёткий
    val bmp by produceState<Bitmap?>(null, index, px, layoutKey) { value = engine.render(index, px) }

    val body: @Composable (Modifier) -> Unit = { m ->
        Box(m.background(Color.White)) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
            PageOverlay(
                strokes, hits, tool, color, stylusOnly, onStroke, onErase, onNoteAt, onNoteOpen, onTapBackground,
                Modifier.fillMaxSize()
            )
        }
    }
    if (fit) Box(Modifier.fillMaxSize(), Alignment.Center) { body(Modifier.aspectRatio(1f / ratio)) }
    else body(Modifier.fillMaxWidth().aspectRatio(1f / ratio))
}

@Composable
private fun Thumb(engine: SafeEngine, i: Int, layoutKey: String, selected: Boolean, hasAnn: Boolean, onClick: () -> Unit) {
    val size by produceState<PageSize?>(null, i, layoutKey) { value = engine.pageSize(i) }
    val bmp by produceState<Bitmap?>(null, i, layoutKey) { value = engine.render(i, 160) }
    val ratio = size?.let { it.h / it.w } ?: 1.41f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.width(82.dp).aspectRatio(1f / ratio).background(Color.White)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray, RoundedCornerShape(4.dp))
                .clickable(onClick = onClick)
        ) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
            if (hasAnn) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(10.dp).clip(CircleShape).background(Color(0xFFFB8C00)))
        }
        Text("${i + 1}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PasswordDialog(onOk: (String) -> Unit, onCancel: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel, title = { Text("Документ защищён паролем") },
        text = { OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text("Пароль") }) },
        confirmButton = { TextButton({ onOk(pw) }) { Text("Открыть") } },
        dismissButton = { TextButton(onCancel) { Text("Отмена") } })
}

@Composable
private fun NoteDialog(initial: String, canDelete: Boolean, onSave: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Заметка") },
        text = { OutlinedTextField(text, { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton({ onSave(text) }) { Text("Сохранить") } },
        dismissButton = { Row { if (canDelete) TextButton(onDelete) { Text("Удалить") }; TextButton(onDismiss) { Text("Отмена") } } })
}

@Composable
private fun SearchDialog(
    hits: Map<Int, List<FloatArray>>, onSearch: (String) -> Unit, onClear: () -> Unit,
    onGo: (Int) -> Unit, onDismiss: () -> Unit
) {
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Поиск") },
        text = {
            Column {
                OutlinedTextField(
                    q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(q) })
                )
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(hits.keys.sorted()) { p ->
                        Text("Стр. ${p + 1} · ${hits[p]!!.size}", Modifier.fillMaxWidth().clickable { onGo(p) }.padding(vertical = 10.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton({ onSearch(q) }) { Text("Найти") } },
        dismissButton = { TextButton({ onClear(); onDismiss() }) { Text("Сбросить") } })
}
