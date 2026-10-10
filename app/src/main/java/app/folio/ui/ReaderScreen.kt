package app.folio.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.folio.annotations.*
import app.folio.data.BookEntity
import app.folio.engine.FormKind
import app.folio.engine.PageSize
import app.folio.engine.SafeEngine
import app.folio.engine.TextSelect
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

private class NoteDraft(val page: Int, val x: Float, val y: Float, val existing: Stroke?)
private class Patch(val bmp: Bitmap, val x: Float, val y: Float, val w: Float, val h: Float)
private const val MAX_RENDER_PX = 2400
private const val MAX_SCALE = 6f

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
    var stylusOnly by remember { mutableStateOf(Settings.stylusOnly) }
    var pinned by remember { mutableStateOf(prefs.getBoolean("tools_pinned", false)) }        // панель пера закреплена
    var toolsOpen by remember { mutableStateOf(prefs.getBoolean("tools_pinned", false)) }     // по умолчанию спрятана
    var thumbsPinned by remember { mutableStateOf(prefs.getBoolean("thumbs_pinned", false)) } // миниатюры закреплены
    var showThumbs by remember { mutableStateOf(prefs.getBoolean("thumbs_pinned", false)) }
    var selMode by remember { mutableIntStateOf(TextSelect.WORD) }                            // слово / предложение / свободно
    var showToc by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<NoteDraft?>(null) }
    var formProbe by remember { mutableStateOf<FormProbe?>(null) }
    var headerPx by remember { mutableIntStateOf(0) }

    // трансформация вьюпорта: экран = содержимое * scale + (tx, ty); точка отсчёта — левый верхний угол
    var scale by remember { mutableFloatStateOf(1f) }
    var tx by remember { mutableFloatStateOf(0f) }
    var ty by remember { mutableFloatStateOf(0f) }
    // «успокоившаяся» трансформация: по ней рендерим чёткие фрагменты (патчи) страниц
    var sScale by remember { mutableFloatStateOf(1f) }
    var sTx by remember { mutableFloatStateOf(0f) }
    var sTy by remember { mutableFloatStateOf(0f) }
    var tick by remember { mutableIntStateOf(0) }
    var targetPage by remember { mutableIntStateOf(-1) }
    var restored by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val pagerState = rememberPagerState { st.pageCount }
    val anns by vm.annotations.collectAsState()
    val hits by vm.hits.collectAsState()
    val searching by vm.searching.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()
    val byPage = remember(anns, st.layoutKey) {
        anns.filter { it.layoutKey == st.layoutKey }.map { it.toStroke() }.groupBy { it.page }
    }
    val notes = remember(byPage) {
        byPage.values.flatten().filter { it.type == "NOTE" || it.type == "TEXT_HL" || it.type == "TEXT_UL" }.sortedBy { it.page }
    }
    val annotatedPages = remember(byPage) { byPage.filterValues { it.isNotEmpty() }.keys }
    val current by remember { derivedStateOf { if (paged) pagerState.currentPage else listState.firstVisibleItemIndex } }

    /** Клик «мимо»: всё незакреплённое (панель пера, миниатюры) убирается. */
    fun dismissUnpinned() {
        if (!pinned) toolsOpen = false
        if (!thumbsPinned) showThumbs = false
    }

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
    val insertLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let { vm.insertPdf(current, it) }
    }

    BackHandler(enabled = showThumbs) { showThumbs = false }
    LaunchedEffect(st.layoutKey, st.restorePage, st.epoch) {
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
    LaunchedEffect(paged) {   // трансформация/прокрутка «успокоились» на 200 мс -> перерисовать чёткие фрагменты
        snapshotFlow {
            val sig = if (paged) pagerState.currentPage * 1_000_000 else listState.firstVisibleItemIndex * 1_000_000 + listState.firstVisibleItemScrollOffset
            listOf<Any>(scale, tx, ty, sig)
        }.debounce(200).collect {
            sScale = scale; sTx = tx; sTy = ty
            tick++
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF1E1F22))) {
        val screenW = maxWidth
        val screenH = maxHeight
        val navPx = WindowInsets.navigationBars.getBottom(density)
        // вёрстка EPUB/FB2 считается по ПОСТОЯННОЙ области (экран минус шапка минус навигация):
        // панели пера и миниатюры не должны вызывать перевёрстку и скрывать аннотации
        if (st.isReflowable && headerPx > 0) {
            val reflowH = (screenH - with(density) { headerPx.toDp() } - with(density) { navPx.toDp() }).value
            LaunchedEffect(screenW, reflowH) { vm.relayout(screenW.value, reflowH) }
        }

        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // ---------- шапка: видна всегда ----------
                ReaderHeader(
                    title = st.name, onBack = onBack,
                    canUndo = canUndo, canRedo = canRedo, onUndo = { vm.undo() }, onRedo = { vm.redo() },
                    toolsActive = toolsOpen || tool != Tool.NONE, onTools = { toolsOpen = !toolsOpen },
                    onThumbs = { showThumbs = !showThumbs },
                    onSearch = { showSearch = true },
                    modifier = Modifier.onSizeChanged { headerPx = it.height },
                    menu = {
                        Box {
                            IconButton({ menu = true }, Modifier.size(40.dp)) { Icon(Icons.Default.MoreVert, "Ещё") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Заметки и выделения (${notes.size})") }, onClick = { menu = false; showNotes = true })
                                if (st.toc.isNotEmpty()) {
                                    DropdownMenuItem(text = { Text("Оглавление") }, onClick = { menu = false; showToc = true })
                                }
                                DropdownMenuItem(
                                    text = { Text(if (paged) "Режим: лента" else "Режим: по страницам") },
                                    onClick = {
                                        menu = false
                                        targetPage = current
                                        resetZoom()
                                        paged = !paged
                                        prefs.edit().putBoolean("paged", paged).apply()
                                    })
                                if (st.isPdf) {
                                    DropdownMenuItem(text = { Text("Страницы…") }, onClick = { menu = false; showPages = true })
                                    DropdownMenuItem(
                                        text = { Text("Сохранить PDF с аннотациями") },
                                        onClick = { menu = false; exportLauncher.launch(st.name.take(60) + "-annotated.pdf") })
                                }
                                if (st.isReflowable) {
                                    DropdownMenuItem(text = { Text("Шрифт крупнее") }, onClick = { vm.bumpFont(+2f) })
                                    DropdownMenuItem(text = { Text("Шрифт мельче") }, onClick = { vm.bumpFont(-2f) })
                                }
                                DropdownMenuItem(text = { Text("Сбросить зум") }, onClick = { resetZoom(); menu = false })
                                DropdownMenuItem(text = { Text("Настройки") }, onClick = { menu = false; showSettings = true })
                            }
                        }
                    }
                )

                // ---------- панель пера: выезжает под шапкой и СДВИГАЕТ текст вниз ----------
                AnimatedVisibility(toolsOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    ToolsPanel(
                        tool = tool, onTool = { tool = it },
                        color = color, onColor = { color = it },
                        selMode = selMode, onSelMode = { selMode = it },
                        stylusOnly = stylusOnly, onStylusOnly = { stylusOnly = !stylusOnly },
                        pinned = pinned,
                        onPin = { pinned = !pinned; prefs.edit().putBoolean("tools_pinned", pinned).apply() }
                    )
                }

                Row(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
                    // ---------- миниатюры: тоже сдвигают текст, а не перекрывают ----------
                    AnimatedVisibility(showThumbs, enter = expandHorizontally(), exit = shrinkHorizontally()) {
                        ThumbsPanel(
                            engine, st.pageCount, st.layoutKey, st.epoch, current, annotatedPages, thumbsPinned,
                            onPin = { thumbsPinned = !thumbsPinned; prefs.edit().putBoolean("thumbs_pinned", thumbsPinned).apply() },
                            onPick = { goTo(it) }
                        )
                    }

                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        val vwPx = with(density) { maxWidth.toPx() }
                        val vhPx = with(density) { maxHeight.toPx() }
                        val viewW = maxWidth

                        val pageUi: @Composable (Int, Boolean) -> Unit = { i, fit ->
                            PageItem(
                                engine, i, st.layoutKey, st.epoch, fit, vwPx, vhPx, tick, sScale, sTx, sTy,
                                byPage[i].orEmpty(), hits[i].orEmpty(), tool, Color(color), stylusOnly,
                                onStroke = { dismissUnpinned(); vm.addStroke(i, tool, color, it) },
                                onErase = { d, r -> dismissUnpinned(); vm.erase(d, r) },
                                onNoteAt = { x, y -> dismissUnpinned(); draft = NoteDraft(i, x, y, null) },
                                onNoteOpen = { draft = NoteDraft(i, it.pts[0], it.pts[1], it) },
                                onTextSelect = { ax, ay, bx, by -> dismissUnpinned(); vm.addTextMarkup(i, tool, color, ax, ay, bx, by, selMode) },
                                onFormTap = { x, y ->
                                    dismissUnpinned()
                                    vm.formProbe(i, x, y) { p ->
                                        if (p == null) {
                                            Toast.makeText(ctx, "Здесь нет поля формы", Toast.LENGTH_SHORT).show()
                                        } else {
                                            formProbe = p
                                        }
                                    }
                                },
                                onLongPressText = { x, y ->
                                    dismissUnpinned()
                                    vm.longPressText(i, x, y, color, selMode) { draft = NoteDraft(i, x, y, null) }
                                },
                                onTapBackground = { dismissUnpinned() }
                            )
                        }

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
                                            // стилус замечен -> дальше палец не рисует (защита от ладони); после жеста, чтобы не прервать штрих
                                            if (first.type == PointerType.Stylus && tool != Tool.NONE && !stylusOnly) stylusOnly = true
                                        }
                                    }
                                    .pointerInput(Unit) { detectTapGestures { dismissUnpinned() } }
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
                                            key = { "${st.layoutKey}:${st.epoch}:$it" }
                                        ) { i -> pageUi(i, true) }
                                    } else {
                                        LazyColumn(
                                            state = listState, modifier = Modifier.fillMaxSize(),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                            contentPadding = PaddingValues(vertical = 6.dp)
                                        ) {
                                            items(st.pageCount, key = { "${st.layoutKey}:${st.epoch}:$it" }) { i -> pageUi(i, false) }
                                        }
                                    }
                                }
                            }

                            // ---------- номер страницы ----------
                            if (st.pageCount > 0) Surface(
                                Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp),
                                shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.6f)
                            ) { Text("${current + 1} / ${st.pageCount}", Modifier.padding(10.dp, 4.dp), color = Color.White) }
                        }
                    }
                }
            }

            if (st.busy) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), Alignment.Center) {
                CircularProgressIndicator()
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
        hits, searching, onSearch = vm::search, onClear = vm::clearSearch,
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
            onDelete = { d.existing?.let { vm.deleteAnnotations(listOf(it.id)) }; draft = null },
            onDismiss = { draft = null })
    }

    if (showSettings) SettingsDialog { showSettings = false }

    if (showNotes) NotesDialog(
        list = notes,
        onGo = { p -> showNotes = false; goTo(p) },
        onEdit = { n -> showNotes = false; goTo(n.page); draft = NoteDraft(n.page, n.pts[0], n.pts[1], n) },
        onDelete = { n -> vm.deleteAnnotations(listOf(n.id)) },
        onDismiss = { showNotes = false })

    if (showPages) AlertDialog(
        onDismissRequest = { showPages = false },
        title = { Text("Страница ${current + 1} из ${st.pageCount}") },
        text = {
            Column {
                TextButton({ showPages = false; vm.rotatePage(current, true) }) { Text("Повернуть по часовой стрелке") }
                TextButton({ showPages = false; vm.rotatePage(current, false) }) { Text("Повернуть против часовой") }
                TextButton({ showPages = false; vm.insertBlank(current) }) { Text("Вставить пустую страницу после") }
                TextButton({ showPages = false; insertLauncher.launch(arrayOf("application/pdf")) }) { Text("Вставить страницы из PDF…") }
                TextButton({ showPages = false; confirmDelete = true }) { Text("Удалить эту страницу") }
                TextButton({ showPages = false; confirmReset = true }) { Text("Вернуть исходный документ…") }
                Text(
                    "Исходный файл не меняется. Результат сохраняется через «Сохранить PDF с аннотациями».",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = { TextButton({ showPages = false }) { Text("Закрыть") } })

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, title = { Text("Удалить страницу ${current + 1}?") },
        text = { Text("Аннотации на этой странице тоже будут удалены.") },
        confirmButton = { TextButton({ confirmDelete = false; vm.deletePage(current) }) { Text("Удалить") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Отмена") } })

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false }, title = { Text("Вернуть исходный документ?") },
        text = { Text("Правки страниц и все аннотации этого документа будут удалены.") },
        confirmButton = { TextButton({ confirmReset = false; vm.resetEdits() }) { Text("Вернуть") } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("Отмена") } })

    formProbe?.let { p ->
        FormDialog(
            p,
            onSet = { v -> vm.formSet(p, v); formProbe = null },
            onDismiss = { formProbe = null })
    }
}

@Composable
private fun ReaderHeader(
    title: String, onBack: () -> Unit,
    canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit,
    toolsActive: Boolean, onTools: () -> Unit,
    onThumbs: () -> Unit, onSearch: () -> Unit,
    menu: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 2.dp) {
        Row(
            Modifier.statusBarsPadding().height(48.dp).padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onBack, Modifier.size(40.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text(
                title, Modifier.weight(1f).padding(horizontal = 4.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium
            )
            IconButton(onUndo, Modifier.size(40.dp), enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Отменить") }
            IconButton(onRedo, Modifier.size(40.dp), enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Вернуть") }
            IconButton(onTools, Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.Edit, "Инструменты рисования",
                    tint = if (toolsActive) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
            IconButton(onThumbs, Modifier.size(40.dp)) { Icon(Icons.Default.GridView, "Миниатюры") }
            IconButton(onSearch, Modifier.size(40.dp)) { Icon(Icons.Default.Search, "Поиск") }
            menu()
        }
    }
}

@Composable
private fun ThumbsPanel(
    engine: SafeEngine, pageCount: Int, layoutKey: String, epoch: Int, current: Int, annotated: Set<Int>,
    pinned: Boolean, onPin: () -> Unit, onPick: (Int) -> Unit
) {
    Surface(Modifier.width(112.dp).fillMaxHeight(), tonalElevation = 6.dp) {
        Column {
            TextButton(onPin, Modifier.fillMaxWidth()) {
                Text(if (pinned) "Закреплено" else "Закрепить", style = MaterialTheme.typography.labelMedium)
            }
            val ts = rememberLazyListState()
            LaunchedEffect(Unit) { ts.scrollToItem(current) }
            LazyColumn(
                state = ts, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(pageCount, key = { "$layoutKey:$epoch:t$it" }) { i ->
                    Thumb(engine, i, layoutKey, epoch, i == current, i in annotated) { onPick(i) }
                }
            }
        }
    }
}

/**
 * fit = true  — режим «по страницам»: страница целиком вписана во вьюпорт и центрирована.
 * fit = false — режим «лента»: страница на всю ширину.
 * При зуме поверх базового битмапа рисуется чёткий фрагмент только видимой части страницы (память ограничена размером экрана).
 */
@Composable
private fun PageItem(
    engine: SafeEngine, index: Int, layoutKey: String, epoch: Int, fit: Boolean,
    vw: Float, vh: Float, tick: Int, sScale: Float, sTx: Float, sTy: Float,
    strokes: List<Stroke>, hits: List<FloatArray>, tool: Tool, color: Color, stylusOnly: Boolean,
    onStroke: (FloatArray) -> Unit, onErase: (List<Long>, Map<Long, List<FloatArray>>) -> Unit,
    onNoteAt: (Float, Float) -> Unit, onNoteOpen: (Stroke) -> Unit,
    onTextSelect: (Float, Float, Float, Float) -> Unit, onFormTap: (Float, Float) -> Unit,
    onLongPressText: (Float, Float) -> Unit, onTapBackground: () -> Unit
) {
    val density = LocalDensity.current
    val size by produceState<PageSize?>(null, index, layoutKey, epoch) { value = engine.pageSize(index) }
    val ratio = size?.let { it.h / it.w } ?: 1.41f
    val basePx = if (fit) minOf(vw, vh / ratio) else vw
    val px = minOf(basePx.roundToInt().coerceAtLeast(64), MAX_RENDER_PX)
    val bmp by produceState<Bitmap?>(null, index, px, layoutKey, epoch) { value = engine.render(index, px) }

    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var patch by remember { mutableStateOf<Patch?>(null) }
    LaunchedEffect(tick, index, epoch, layoutKey) {
        if (sScale <= 1.15f) { patch = null; return@LaunchedEffect }
        val c = holder[0]
        if (c == null || !c.isAttached) return@LaunchedEffect
        val pos = c.positionInParent()
        val pw = c.size.width.toFloat()
        val ph = c.size.height.toFloat()
        if (pw <= 0f || ph <= 0f) return@LaunchedEffect
        val left = -sTx / sScale
        val top = -sTy / sScale
        val ix0 = maxOf(left, pos.x)
        val iy0 = maxOf(top, pos.y)
        val ix1 = minOf(left + vw / sScale, pos.x + pw)
        val iy1 = minOf(top + vh / sScale, pos.y + ph)
        if (ix1 - ix0 < 8f || iy1 - iy0 < 8f) { patch = null; return@LaunchedEffect }
        val fullW = (pw * sScale).roundToInt().coerceAtLeast(1)
        val f = fullW / pw
        val bx0 = ((ix0 - pos.x) * f).toInt().coerceAtLeast(0)
        val by0 = ((iy0 - pos.y) * f).toInt().coerceAtLeast(0)
        val bx1 = ceil((ix1 - pos.x) * f).toInt()
        val by1 = ceil((iy1 - pos.y) * f).toInt()
        val bmpPatch = engine.patch(index, fullW, bx0, by0, bx1, by1)
        patch = Patch(bmpPatch, bx0 / f, by0 / f, (bx1 - bx0) / f, (by1 - by0) / f)
    }

    val body: @Composable (Modifier) -> Unit = { m ->
        Box(m.background(Color.White).onGloballyPositioned { holder[0] = it }) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
            patch?.let { p ->
                Image(
                    p.bmp.asImageBitmap(), null,
                    Modifier.offset { IntOffset(p.x.roundToInt(), p.y.roundToInt()) }
                        .size(with(density) { p.w.toDp() }, with(density) { p.h.toDp() }),
                    contentScale = ContentScale.FillBounds
                )
            }
            PageOverlay(
                strokes, hits, tool, color, stylusOnly, onStroke, onErase, onNoteAt, onNoteOpen,
                onTextSelect, onFormTap, onLongPressText, onTapBackground, Modifier.fillMaxSize()
            )
        }
    }
    if (fit) Box(Modifier.fillMaxSize(), Alignment.Center) { body(Modifier.aspectRatio(1f / ratio)) }
    else body(Modifier.fillMaxWidth().aspectRatio(1f / ratio))
}

@Composable
private fun Thumb(engine: SafeEngine, i: Int, layoutKey: String, epoch: Int, selected: Boolean, hasAnn: Boolean, onClick: () -> Unit) {
    val size by produceState<PageSize?>(null, i, layoutKey, epoch) { value = engine.pageSize(i) }
    val bmp by produceState<Bitmap?>(null, i, layoutKey, epoch) { value = engine.thumb(i, 160) }
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
private fun FormDialog(p: FormProbe, onSet: (String?) -> Unit, onDismiss: () -> Unit) {
    val f = p.field
    var text by remember { mutableStateOf(f.value) }
    when {
        f.readOnly -> AlertDialog(
            onDismissRequest = onDismiss, title = { Text("Поле только для чтения") },
            confirmButton = { TextButton(onDismiss) { Text("Закрыть") } })
        f.kind == FormKind.TOGGLE -> AlertDialog(
            onDismissRequest = onDismiss, title = { Text("Переключить флажок?") },
            confirmButton = { TextButton({ onSet(null) }) { Text("Переключить") } },
            dismissButton = { TextButton(onDismiss) { Text("Отмена") } })
        f.kind == FormKind.TEXT -> AlertDialog(
            onDismissRequest = onDismiss, title = { Text("Текстовое поле") },
            text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton({ onSet(text) }) { Text("Сохранить") } },
            dismissButton = { TextButton(onDismiss) { Text("Отмена") } })
        f.kind == FormKind.CHOICE -> AlertDialog(
            onDismissRequest = onDismiss, title = { Text("Выберите значение") },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(f.options) { o -> Text(o, Modifier.fillMaxWidth().clickable { onSet(o) }.padding(vertical = 12.dp)) }
                }
            },
            confirmButton = { TextButton(onDismiss) { Text("Закрыть") } })
        else -> AlertDialog(
            onDismissRequest = onDismiss, title = { Text("Этот тип поля не поддерживается") },
            confirmButton = { TextButton(onDismiss) { Text("Закрыть") } })
    }
}

@Composable
private fun SearchDialog(
    hits: Map<Int, List<FloatArray>>, searching: Boolean, onSearch: (String) -> Unit, onClear: () -> Unit,
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
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolsPanel(
    tool: Tool, onTool: (Tool) -> Unit, color: Int, onColor: (Int) -> Unit,
    selMode: Int, onSelMode: (Int) -> Unit, stylusOnly: Boolean, onStylusOnly: () -> Unit,
    pinned: Boolean, onPin: () -> Unit, modifier: Modifier = Modifier
) {
    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 4.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Tool.entries.forEach { t -> FilterChip(tool == t, { onTool(t) }, { Text(t.label) }) }
            }
            if (tool.isTextTool) {
                Text("Что выделять:", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FilterChip(selMode == TextSelect.WORD, { onSelMode(TextSelect.WORD) }, { Text("Слово") })
                    FilterChip(selMode == TextSelect.SENTENCE, { onSelMode(TextSelect.SENTENCE) }, { Text("Предложение") })
                    FilterChip(selMode == TextSelect.FREE, { onSelMode(TextSelect.FREE) }, { Text("Свободно (от точки до точки)") })
                }
                Text(
                    "Коснитесь текста — выделится слово или предложение; проведите вдоль текста — выделится весь фрагмент.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Palette.forEach { c ->
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(Color(c))
                            .border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                            .clickable { onColor(c) }
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FilterChip(stylusOnly, { onStylusOnly() }, { Text("Только стилус") })
                FilterChip(pinned, { onPin() }, { Text("Закрепить панель") })
            }
        }
    }
}

@Composable
private fun NotesDialog(
    list: List<Stroke>, onGo: (Int) -> Unit, onEdit: (Stroke) -> Unit, onDelete: (Stroke) -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Заметки и выделения") },
        text = {
            if (list.isEmpty()) Text("Пока ничего нет. Выберите инструмент «Заметка» и коснитесь страницы.")
            else LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(list, key = { it.id }) { n ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        val kind = when (n.type) { "NOTE" -> "Заметка"; "TEXT_HL" -> "Выделение"; else -> "Подчёркивание" }
                        Text("Страница ${n.page + 1} · $kind", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(
                            n.text?.takeIf { it.isNotBlank() } ?: "(без текста)",
                            maxLines = 4, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Row {
                            TextButton({ onGo(n.page) }) { Text("Перейти") }
                            if (n.type == "NOTE") TextButton({ onEdit(n) }) { Text("Изменить") }
                            TextButton({ onDelete(n) }) { Text("Удалить") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Закрыть") } })
}
