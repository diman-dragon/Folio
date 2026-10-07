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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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

private class NoteDraft(val page: Int, val x: Float, val y: Float, val existing: Stroke?)
// Потолок 2400 px оставлен как аварийный; основной путь — fixed-res рендер + GPU-масштаб (graphicsLayer),
// поэтому перерендер на зум почти не нужен и потолок редко вступает в силу.
private const val MAX_RENDER_PX = 2400
private const val DRAFT_RENDER_PX = 700     // черновой RGB_565 кадр
private const val THUMB_PX = 160            // миниатюры

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
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var tool by remember { mutableStateOf(Tool.NONE) }
    var color by remember { mutableIntStateOf(Palette[0]) }
    var stylusOnly by remember { mutableStateOf(true) }
    var chrome by remember { mutableStateOf(true) }
    var showThumbs by remember { mutableStateOf(false) }   // миниатюры скрыты по умолчанию
    var showToc by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }          // визуальный (GPU) зум — мгновенный
    var renderZoom by remember { mutableFloatStateOf(1f) }    // отложенный перерендер качества
    var draft by remember { mutableStateOf<NoteDraft?>(null) }
    val listState = rememberLazyListState()
    val anns by vm.annotations.collectAsState()
    val hits by vm.hits.collectAsState()
    val byPage = remember(anns, st.layoutKey) {
        anns.filter { it.layoutKey == st.layoutKey }.map { it.toStroke() }.groupBy { it.page }
    }
    val current by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    // стабильные лямбды: PageItem не перекомпозируется из-за новых замыканий на каждый кадр
    val onStroke = remember(vm, tool, color) { { page: Int, pts: FloatArray -> vm.addStroke(page, tool, color, pts) } }
    val onErase = remember(vm) { { ids: List<Long> -> vm.erase(ids) } }
    val onNoteAt = remember { { page: Int, x: Float, y: Float -> draft = NoteDraft(page, x, y, null) } }
    val onNoteOpen = remember { { page: Int, s: Stroke -> draft = NoteDraft(page, s.pts[0], s.pts[1], s) } }
    val onTapBackground = remember { { chrome = !chrome } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { u ->
        u?.let { vm.exportPdf(it) }
    }

    BackHandler(enabled = showThumbs) { showThumbs = false }
    // GPU-зум мгновенный; перерендер в более высокое разрешение отложен, чтобы не спамить рендер-очередь
    LaunchedEffect(zoom) { delay(250); renderZoom = zoom }
    LaunchedEffect(st.layoutKey, st.restorePage) {
        if (st.pageCount > 0) listState.scrollToItem(st.restorePage.coerceIn(0, st.pageCount - 1))
    }
    LaunchedEffect(Unit) { snapshotFlow { listState.firstVisibleItemIndex }.debounce(700).collect { vm.saveProgress(it) } }
    // предвыборка соседних страниц при скролле — скролл «летает» без белых кадров
    var prefetchPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(current, st.layoutKey, prefetchPx, st.pageCount) {
        if (prefetchPx > 0 && st.pageCount > 0) engine.prefetch(scope, current, st.pageCount, prefetchPx)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF1E1F22))) {
        val viewW = maxWidth
        val viewH = maxHeight
        if (st.isReflowable) LaunchedEffect(viewW, viewH) { vm.relayout(viewW.value, viewH.value) }
        // Ключевое изменение: страница всегда рендерится в фиксированное разрешение ~экрана.
        // Зум — чистая GPU-трансформация (graphicsLayer.scaleX/Y): мгновенно, без перерендера,
        // идеально для слабых планшетов (CPU не делает лишней работы вообще).
        // Перерендер (renderZoom) остаётся только для докачки качества после жеста.
        val basePx = with(density) { minOf((viewW * renderZoom).roundToPx(), MAX_RENDER_PX) }
        LaunchedEffect(basePx) { prefetchPx = basePx }
        val draftPx = with(density) { min(DRAFT_RENDER_PX, (viewW.value * density.density).toInt()) }

        Box(Modifier.fillMaxSize()) {
            // ---------- страницы ----------
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(tool) {   // щипок двумя пальцами: работает и в режиме рисования
                        // Только в Initial-пассе и consume() ТОЛЬКО когда реально 2+ пальца:
                        // одно-перевой жест доходит до PageOverlay (Main) нетронутым.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            do {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                if (ev.changes.count { it.pressed } >= 2) {
                                    val z = ev.calculateZoom()
                                    if (z != 1f) { zoom = (zoom * z).coerceIn(1f, 4f); ev.changes.forEach { it.consume() } }
                                }
                            } while (ev.changes.any { it.pressed })
                        }
                    }
        // chrome-toggle по тапу живёт ВНУТРИ overlay: одиночный тап по странице
        // скрывает панели, а во время письма/ластикования жест уже сконсьюмен
        // штрихом и до tap-детектора не доходит (фикс бага «панели прыгают при письме»).
        // Поэтому повторный global-tap здесь НЕ вешаем — он конфликтовал бы с overlay.
    ) {
                Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.width(viewW * zoom).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        items(st.pageCount, key = { "${st.layoutKey}:$it" }, contentType = { "page" }) { i ->
                            PageItem(
                                engine, i, st.layoutKey, basePx, draftPx,
                                byPage[i].orEmpty(), hits[i].orEmpty(), tool, Color(color), stylusOnly,
                                onStroke = { pts -> onStroke(i, pts) },
                                onErase = onErase,
                                onNoteAt = { x, y -> onNoteAt(i, x, y) },
                                onNoteOpen = { s -> onNoteOpen(i, s) },
                                onTapBackground = onTapBackground
                            )
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
                                if (st.isPdf) DropdownMenuItem(
                                    text = { Text("Сохранить PDF с аннотациями") },
                                    onClick = { menu = false; exportLauncher.launch(st.name.take(60) + "-annotated.pdf") })
                                if (st.isReflowable) {
                                    DropdownMenuItem(text = { Text("Шрифт крупнее") }, onClick = { vm.bumpFont(+2f) })
                                    DropdownMenuItem(text = { Text("Шрифт мельче") }, onClick = { vm.bumpFont(-2f) })
                                }
                                DropdownMenuItem(text = { Text("Сбросить зум") }, onClick = { zoom = 1f; menu = false })
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
                            Thumb(engine, i, st.layoutKey, i == current, byPage[i]?.isNotEmpty() == true) {
                                scope.launch { listState.scrollToItem(i) }
                            }
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
                        Modifier.fillMaxWidth().clickable { showToc = false; scope.launch { listState.scrollToItem(t.page.coerceAtLeast(0)) } }
                            .padding(start = (t.level * 16).dp, top = 10.dp, bottom = 10.dp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        })

    if (showSearch) SearchDialog(
        hits, onSearch = vm::search, onClear = vm::clearSearch,
        onGo = { p -> showSearch = false; scope.launch { listState.scrollToItem(p) } },
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

@Composable
private fun PageItem(
    engine: SafeEngine, index: Int, layoutKey: String, renderPx: Int, draftPx: Int,
    strokes: List<Stroke>, hits: List<FloatArray>, tool: Tool, color: Color, stylusOnly: Boolean,
    onStroke: (FloatArray) -> Unit, onErase: (List<Long>) -> Unit,
    onNoteAt: (Float, Float) -> Unit, onNoteOpen: (Stroke) -> Unit, onTapBackground: () -> Unit
) {
    val size by produceState<PageSize?>(null, index, layoutKey) { value = engine.pageSize(index) }
    // двухступенчатый рендер: дешёвый RGB_565-черновик сразу, чистый кадр апдейтом;
    // при смене renderPx старый битмап остаётся на экране, пока не придёт чёткий
    val bmp by produceState<Bitmap?>(null, index, renderPx, layoutKey) {
        value = engine.render(index, renderPx, if (renderPx > draftPx) draftPx else 0)
    }
    val ratio by remember { derivedStateOf { size?.let { it.h / it.w } ?: 1.41f } }
    Box(Modifier.fillMaxWidth().aspectRatio(1f / ratio).background(Color.White)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
        PageOverlay(
            strokes, hits, tool, color, stylusOnly, onStroke, onErase, onNoteAt, onNoteOpen, onTapBackground,
            Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun Thumb(engine: SafeEngine, i: Int, layoutKey: String, selected: Boolean, hasAnn: Boolean, onClick: () -> Unit) {
    val size by produceState<PageSize?>(null, i, layoutKey) { value = engine.pageSize(i) }
    // миниатюры — только в черновом кэше (RGB_565, 160 px): дёшево и не вытесняет чистые кадры
    val bmp by produceState<Bitmap?>(null, i, layoutKey) { value = engine.renderDraft(i, THUMB_PX) }
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
