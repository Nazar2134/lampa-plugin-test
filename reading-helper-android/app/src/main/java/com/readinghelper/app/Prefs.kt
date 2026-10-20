package com.readinghelper.app

import android.content.Context
import java.util.Locale

data class Settings(
    val apiKey: String,
    val language: String,
    val model: String,
    val detailed: Boolean,
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = Settings(
        apiKey = sp.getString(KEY_API_KEY, "").orEmpty(),
        language = sp.getString(KEY_LANGUAGE, null) ?: Locale.getDefault().getDisplayLanguage(Locale.ENGLISH),
        model = sp.getString(KEY_MODEL, null) ?: Claude.MODEL_OPUS,
        detailed = sp.getBoolean(KEY_DETAILED, false),
    )

    fun save(settings: Settings) {
        sp.edit()
            .putString(KEY_API_KEY, settings.apiKey.trim())
            .putString(KEY_LANGUAGE, settings.language.trim())
            .putString(KEY_MODEL, settings.model)
            .putBoolean(KEY_DETAILED, settings.detailed)
            .apply()
    }

    private companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_LANGUAGE = "language"
        const val KEY_MODEL = "model"
        const val KEY_DETAILED = "detailed"
    }
}
