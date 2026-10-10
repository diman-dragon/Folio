package app.folio.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Настройки приложения. Значения наблюдаемы из Compose и сохраняются в SharedPreferences "folio". */
object Settings {
    var theme by mutableStateOf("system")        // system | light | dark
    var dynamicColors by mutableStateOf(true)    // Material You (Android 12+)
    var stylusOnly by mutableStateOf(false)      // «только стилус» при открытии книги

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("folio", 0)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        theme = p.getString("theme", "system") ?: "system"
        dynamicColors = p.getBoolean("dynamic", true)
        stylusOnly = p.getBoolean("stylus_default", false)
    }

    fun setTheme(ctx: Context, v: String) { theme = v; prefs(ctx).edit().putString("theme", v).apply() }
    fun setDynamic(ctx: Context, v: Boolean) { dynamicColors = v; prefs(ctx).edit().putBoolean("dynamic", v).apply() }
    fun setStylusOnly(ctx: Context, v: Boolean) { stylusOnly = v; prefs(ctx).edit().putBoolean("stylus_default", v).apply() }

    fun isDark(systemDark: Boolean): Boolean = when (theme) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
}
