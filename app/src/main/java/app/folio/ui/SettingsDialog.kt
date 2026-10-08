package app.folio.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

private fun dirSize(f: File): Long = if (f.exists()) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

private fun fmt(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f ГБ".format(b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "%.1f МБ".format(b / (1024.0 * 1024))
    b >= 1L shl 10 -> "%.0f КБ".format(b / 1024.0)
    else -> "$b Б"
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("folio", 0) }
    var paged by remember { mutableStateOf(prefs.getBoolean("paged", true)) }
    var cacheBytes by remember { mutableLongStateOf(dirSize(File(ctx.cacheDir, "docs"))) }
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "—"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionTitle("Тема оформления")
                RadioRow("Как в системе", Settings.theme == "system") { Settings.setTheme(ctx, "system") }
                RadioRow("Светлая", Settings.theme == "light") { Settings.setTheme(ctx, "light") }
                RadioRow("Тёмная", Settings.theme == "dark") { Settings.setTheme(ctx, "dark") }
                if (Build.VERSION.SDK_INT >= 31) {
                    SwitchRow("Динамические цвета (Material You)", Settings.dynamicColors) { Settings.setDynamic(ctx, it) }
                }

                SectionTitle("Чтение")
                SwitchRow("Просмотр по страницам (иначе — лента)", paged) {
                    paged = it
                    prefs.edit().putBoolean("paged", it).apply()
                }
                SwitchRow("Рисовать только стилусом (при открытии книги)", Settings.stylusOnly) { Settings.setStylusOnly(ctx, it) }

                SectionTitle("Данные")
                Text("Кэш документов: ${fmt(cacheBytes)}")
                TextButton(onClick = {
                    File(ctx.cacheDir, "docs").listFiles()?.forEach { it.delete() }
                    cacheBytes = dirSize(File(ctx.cacheDir, "docs"))
                }) { Text("Очистить кэш") }
                Text(
                    "Аннотации, прогресс и правки страниц не удаляются.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SectionTitle("О приложении")
                Text("Folio — открытый просмотрщик документов")
                Text("Версия $version", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Лицензия AGPL-3.0-or-later. Движок документов — MuPDF (Artifex Software), AGPL-3.0.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } }
    )
}
