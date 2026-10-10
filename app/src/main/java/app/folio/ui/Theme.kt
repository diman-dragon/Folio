package app.folio.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun FolioTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    Settings.init(ctx) // однократно загрузить настройки из SharedPreferences
    val systemDark = isSystemInDarkTheme()
    val dark = when (Settings.theme) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    val useDynamic = Settings.dynamicColors && Build.VERSION.SDK_INT >= 31
    val scheme = when {
        useDynamic && dark -> dynamicDarkColorScheme(ctx)
        useDynamic -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Синхронная проверка темы для мест вне Compose (например, выбор стиля обложек). */
fun Context.isDarkTheme(systemDark: Boolean): Boolean = when (Settings.theme) {
    "light" -> false
    "dark" -> true
    else -> systemDark
}
