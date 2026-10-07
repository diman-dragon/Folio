package app.folio.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.folio.data.BookEntity
import app.folio.engine.Formats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel, onOpen: (BookEntity) -> Unit) {
    val books by vm.books.collectAsState()
    val recent by vm.recent.collectAsState()
    val query by vm.query.collectAsState()
    val sort by vm.sort.collectAsState()
    val filter by vm.filter.collectAsState()
    val onlyFav by vm.onlyFav.collectAsState()
    val scanning by vm.scanning.collectAsState()
    var searching by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::addRoot) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { vm.openUri(it, onOpen) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) TextField(
                        value = query, onValueChange = { vm.query.value = it }, singleLine = true,
                        placeholder = { Text("Поиск по названию и автору") },
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent)
                    ) else Text("Библиотека", fontWeight = FontWeight.SemiBold)
                },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) vm.query.value = "" }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Поиск")
                    }
                    IconButton(onClick = { vm.onlyFav.value = !onlyFav }) {
                        Icon(if (onlyFav) Icons.Default.Star else Icons.Default.StarBorder, "Избранное")
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.MoreVert, "Сортировка") }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            SortBy.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label) },
                                    leadingIcon = { if (s == sort) Icon(Icons.Default.Check, null) },
                                    onClick = { vm.sort.value = s; sortMenu = false })
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Пересканировать") }, onClick = { vm.rescan(); sortMenu = false })
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallFloatingActionButton(onClick = { pickFile.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, "Открыть файл") }
                ExtendedFloatingActionButton(onClick = { pickFolder.launch(null) }, icon = { Icon(Icons.Default.Folder, null) }, text = { Text("Папка") })
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(filter == null, { vm.filter.value = null }, { Text("Все") }) }
                items(Formats.groups) { g -> FilterChip(filter == g, { vm.filter.value = if (filter == g) null else g }, { Text(g) }) }
            }
            if (books.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        if (scanning) "Сканирую…" else "Пока пусто.\nДобавьте папку с книгами или откройте файл.",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else LazyVerticalGrid(
                columns = GridCells.Adaptive(116.dp),
                contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 120.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                if (query.isBlank() && filter == null && !onlyFav && recent.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Text("Продолжить чтение", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(recent, key = { "r" + it.uri }) { b -> BookCard(b, Modifier.width(116.dp), { onOpen(b) }, { vm.toggleFav(b) }) }
                            }
                        }
                    }
                }
                items(books, key = { it.uri }) { b -> BookCard(b, Modifier, { onOpen(b) }, { vm.toggleFav(b) }) }
            }
        }
    }
}

@Composable
private fun BookCard(b: BookEntity, modifier: Modifier, onClick: () -> Unit, onFav: () -> Unit) {
    val cover by produceState<ImageBitmap?>(null, b.coverPath) {
        value = b.coverPath?.takeIf { it.isNotEmpty() }?.let { p ->
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(p)?.asImageBitmap() }
        }
    }
    val hue = (b.name.hashCode() and 0xFF) / 255f * 360f
    val placeholder = Color.hsv(hue, 0.35f, 0.55f)
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(14.dp)).background(placeholder)
        ) {
            cover?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Text(b.ext.uppercase(), Modifier.align(Alignment.Center), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Surface(Modifier.align(Alignment.TopStart).padding(6.dp), shape = RoundedCornerShape(6.dp), color = Color.Black.copy(alpha = 0.55f)) {
                Text(b.ext.uppercase(), Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = Color.White, fontSize = 10.sp)
            }
            IconButton(onFav, Modifier.align(Alignment.TopEnd).size(36.dp)) {
                Icon(if (b.favorite) Icons.Default.Star else Icons.Default.StarBorder, "Избранное", tint = if (b.favorite) Color(0xFFFFC107) else Color.White)
            }
            if (b.progress > 0f) LinearProgressIndicator(
                progress = { b.progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(b.displayTitle, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        b.author?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
