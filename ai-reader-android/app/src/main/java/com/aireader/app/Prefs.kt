package com.aireader.app

import android.content.Context
import java.util.Locale

enum class ReaderTheme(val bg: Int, val fg: Int, val muted: Int, val accent: Int, val isDark: Boolean) {
    LIGHT(0xFFFFFFFF.toInt(), 0xFF1F1E1C.toInt(), 0xFF8A867E.toInt(), 0xFFC2552E.toInt(), false),
    SEPIA(0xFFF4ECD8.toInt(), 0xFF3B2F22.toInt(), 0xFF8C7B63.toInt(), 0xFFA8481F.toInt(), false),
    DARK(0xFF141414.toInt(), 0xFFD8D4CC.toInt(), 0xFF7D7870.toInt(), 0xFFE07A52.toInt(), true);
}

fun cssColor(color: Int) = "#%06X".format(color and 0xFFFFFF)

data class AppSettings(
    val geminiKey: String = "",
    val geminiModel: String = Gemini.MODEL_FLASH,
    /** Language answers are written in. */
    val language: String = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH),
    val detailed: Boolean = false,
    val fontSize: Int = 19,
    val serif: Boolean = true,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            geminiKey = sp.getString("gemini_key", "").orEmpty(),
            geminiModel = sp.getString("gemini_model", null) ?: defaults.geminiModel,
            language = sp.getString("language", null)?.ifBlank { null } ?: defaults.language,
            detailed = sp.getBoolean("detailed", defaults.detailed),
            fontSize = sp.getInt("font_size", defaults.fontSize),
            serif = sp.getBoolean("serif", defaults.serif),
            theme = runCatching { ReaderTheme.valueOf(sp.getString("theme", null)!!) }.getOrDefault(defaults.theme),
        )
    }

    fun save(s: AppSettings) {
        sp.edit()
            .putString("gemini_key", s.geminiKey.trim())
            .putString("gemini_model", s.geminiModel)
            .putString("language", s.language.trim())
            .putBoolean("detailed", s.detailed)
            .putInt("font_size", s.fontSize)
            .putBoolean("serif", s.serif)
            .putString("theme", s.theme.name)
            .apply()
    }

    fun saveModel(model: String) = sp.edit().putString("gemini_model", model).apply()

    /** Chapter and fraction of the chapter read. */
    fun position(bookId: String): Pair<Int, Float> =
        sp.getInt("pos_chapter_$bookId", 0) to sp.getFloat("pos_fraction_$bookId", 0f)

    fun setPosition(bookId: String, chapter: Int, fraction: Float, progress: Float) {
        sp.edit()
            .putInt("pos_chapter_$bookId", chapter)
            .putFloat("pos_fraction_$bookId", fraction)
            .putFloat("progress_$bookId", progress)
            .apply()
    }

    fun progress(bookId: String) = sp.getFloat("progress_$bookId", 0f)

    fun lastOpened(bookId: String) = sp.getLong("opened_$bookId", 0L)

    fun markOpened(bookId: String) = sp.edit().putLong("opened_$bookId", System.currentTimeMillis()).apply()

    fun forget(bookId: String) {
        sp.edit()
            .remove("pos_chapter_$bookId").remove("pos_fraction_$bookId")
            .remove("progress_$bookId").remove("opened_$bookId")
            .apply()
    }
}
