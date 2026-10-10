package app.folio.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Глобальные настройки приложения (SharedPreferences "folio").
 * Значения кэшируются в observable-свойствах, чтобы Compose перерисовывал UI при изменении.
 */
object Settings {
    private const val PREFS = "folio"
    private const val KEY_THEME = "theme"
    private const val KEY_DYNAMIC = "dynamic_colors"
    private const val KEY_STYLUS_ONLY = "stylus_only"

    /** "system" | "light" | "dark" */
    var theme: String by mutableStateOf("system")
        private set

    /** Динамические цвета Material You (Android 12+). */
    var dynamicColors: Boolean by mutableStateOf(true)
        private set

    /** Рисовать только стилусом (значение по умолчанию для новой книги). */
    var stylusOnly: Boolean by mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        theme = p.getString(KEY_THEME, "system") ?: "system"
        dynamicColors = p.getBoolean(KEY_DYNAMIC, true)
        stylusOnly = p.getBoolean(KEY_STYLUS_ONLY, false)
    }

    fun setTheme(ctx: Context, value: String) {
        theme = value
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, value).apply()
    }

    fun setDynamic(ctx: Context, value: Boolean) {
        dynamicColors = value
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DYNAMIC, value).apply()
    }

    fun setStylusOnly(ctx: Context, value: Boolean) {
        stylusOnly = value
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_STYLUS_ONLY, value).apply()
    }
}
