package com.aireader.app

import android.content.Context
import java.util.Locale

enum class ReaderTheme(val bg: Int, val fg: Int, val muted: Int, val accent: Int, val isDark: Boolean) {
    LIGHT(0xFFFFFFFF.toInt(), 0xFF1F1E1C.toInt(), 0xFF8A867E.toInt(), 0xFFC2552E.toInt(), false),
    SEPIA(0xFFF4ECD8.toInt(), 0xFF3B2F22.toInt(), 0xFF8C7B63.toInt(), 0xFFA8481F.toInt(), false),
    DARK(0xFF141414.toInt(), 0xFFD8D4CC.toInt(), 0xFF7D7870.toInt(), 0xFFE07A52.toInt(), true);
}

fun cssColor(color: Int) = "#%06X".format(color and 0xFFFFFF)

enum class ReaderFont { SERIF, SANS, PUBLISHER }

/** Side margin of the text column, in CSS pixels. */
enum class ReaderMargin(val px: Int) { NARROW(12), NORMAL(22), WIDE(36) }

data class AppSettings(
    val provider: AiProvider = AiProvider.GEMINI,
    val geminiKey: String = "",
    val geminiModel: String = Gemini.MODEL_FLASH,
    val deepseekKey: String = "",
    val deepseekModel: String = DeepSeek.MODEL_FLASH,
    /** Language answers are written in. */
    val language: String = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH),
    val detailed: Boolean = false,
    /** Target level for Simplify, A1–C2. */
    val cefr: String = "B1",
    val fontSize: Int = 19,
    val font: ReaderFont = ReaderFont.SERIF,
    val lineHeight: Float = 1.55f,
    val margin: ReaderMargin = ReaderMargin.NORMAL,
    val justify: Boolean = true,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
)

/** A position in a chapter: a short piece of text to find again, plus the chapter fraction as a fallback. */
data class ReadingPosition(val chapter: Int, val fraction: Float, val anchorText: String = "", val anchorOccurrence: Int = 0)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            provider = enumOr("provider", defaults.provider),
            geminiKey = secret("gemini_key"),
            geminiModel = sp.getString("gemini_model", null) ?: defaults.geminiModel,
            deepseekKey = secret("deepseek_key"),
            deepseekModel = sp.getString("deepseek_model", null) ?: defaults.deepseekModel,
            language = sp.getString("language", null)?.ifBlank { null } ?: defaults.language,
            detailed = sp.getBoolean("detailed", defaults.detailed),
            cefr = sp.getString("cefr", null) ?: defaults.cefr,
            fontSize = sp.getInt("font_size", defaults.fontSize),
            // Version 1 stored only a serif/sans flag.
            font = enumOr("font", if (sp.getBoolean("serif", true)) ReaderFont.SERIF else ReaderFont.SANS),
            lineHeight = sp.getFloat("line_height", defaults.lineHeight),
            margin = enumOr("margin", defaults.margin),
            justify = sp.getBoolean("justify", defaults.justify),
            theme = enumOr("theme", defaults.theme),
        )
    }

    fun save(s: AppSettings) {
        sp.edit()
            .putString("provider", s.provider.name)
            .putString("gemini_key", Secrets.encrypt(s.geminiKey.trim()))
            .putString("gemini_model", s.geminiModel)
            .putString("deepseek_key", Secrets.encrypt(s.deepseekKey.trim()))
            .putString("deepseek_model", s.deepseekModel)
            .putString("language", s.language.trim())
            .putBoolean("detailed", s.detailed)
            .putString("cefr", s.cefr)
            .putInt("font_size", s.fontSize)
            .putString("font", s.font.name)
            .putFloat("line_height", s.lineHeight)
            .putString("margin", s.margin.name)
            .putBoolean("justify", s.justify)
            .putString("theme", s.theme.name)
            .apply()
    }

    /** Reads an API key, encrypting it in place if an older version left it in plain text. */
    private fun secret(name: String): String {
        val stored = sp.getString(name, "").orEmpty()
        if (stored.isEmpty() || Secrets.isEncrypted(stored)) return Secrets.decrypt(stored)
        val encrypted = Secrets.encrypt(stored)
        if (encrypted != stored) sp.edit().putString(name, encrypted).apply()
        return stored
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String, default: T): T =
        sp.getString(name, null)?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

    fun setLanguage(language: String) = sp.edit().putString("language", language).apply()

    fun setCefr(level: String) = sp.edit().putString("cefr", level).apply()

    fun saveGeminiModel(model: String) = sp.edit().putString("gemini_model", model).apply()

    fun saveDeepseekModel(model: String) = sp.edit().putString("deepseek_model", model).apply()

    fun position(bookId: String) = ReadingPosition(
        chapter = sp.getInt("pos_chapter_$bookId", 0),
        fraction = sp.getFloat("pos_fraction_$bookId", 0f),
        anchorText = sp.getString("pos_anchor_$bookId", "").orEmpty(),
        anchorOccurrence = sp.getInt("pos_anchor_occ_$bookId", 0),
    )

    fun setPosition(bookId: String, position: ReadingPosition, progress: Float) {
        sp.edit()
            .putInt("pos_chapter_$bookId", position.chapter)
            .putFloat("pos_fraction_$bookId", position.fraction)
            .putString("pos_anchor_$bookId", position.anchorText)
            .putInt("pos_anchor_occ_$bookId", position.anchorOccurrence)
            .putFloat("progress_$bookId", progress)
            .apply()
    }

    fun progress(bookId: String) = sp.getFloat("progress_$bookId", 0f)

    fun lastOpened(bookId: String) = sp.getLong("opened_$bookId", 0L)

    fun markOpened(bookId: String) = sp.edit().putLong("opened_$bookId", System.currentTimeMillis()).apply()

    fun forget(bookId: String) {
        sp.edit()
            .remove("pos_chapter_$bookId").remove("pos_fraction_$bookId")
            .remove("pos_anchor_$bookId").remove("pos_anchor_occ_$bookId")
            .remove("progress_$bookId").remove("opened_$bookId")
            .apply()
    }
}
