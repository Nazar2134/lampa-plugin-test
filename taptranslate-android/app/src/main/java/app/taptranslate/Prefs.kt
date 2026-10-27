package app.taptranslate

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

class Prefs(context: Context) {
    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString(KEY_API, "").orEmpty().trim()
        set(v) = sp.edit().putString(KEY_API, v.trim()).apply()

    var myLanguage: String
        get() = sp.getString(KEY_MY_LANG, null)?.takeIf { it.isNotBlank() } ?: defaultLanguage()
        set(v) = sp.edit().putString(KEY_MY_LANG, v.trim()).apply()

    var otherLanguage: String
        get() = sp.getString(KEY_OTHER_LANG, null)?.takeIf { it.isNotBlank() } ?: "English"
        set(v) = sp.edit().putString(KEY_OTHER_LANG, v.trim()).apply()

    var model: String
        get() = sp.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: MODEL_CHAT
        set(v) = sp.edit().putString(KEY_MODEL, v).apply()

    var baseUrl: String
        get() = sp.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
        set(v) = sp.edit().putString(KEY_BASE_URL, v.trim()).apply()

    var bubbleEnabled: Boolean
        get() = sp.getBoolean(KEY_BUBBLE, true)
        set(v) = sp.edit().putBoolean(KEY_BUBBLE, v).apply()

    var bubbleX: Int
        get() = sp.getInt(KEY_BUBBLE_X, -1)
        set(v) = sp.edit().putInt(KEY_BUBBLE_X, v).apply()

    var bubbleY: Int
        get() = sp.getInt(KEY_BUBBLE_Y, -1)
        set(v) = sp.edit().putInt(KEY_BUBBLE_Y, v).apply()

    companion object {
        const val KEY_API = "api_key"
        const val KEY_MY_LANG = "my_language"
        const val KEY_OTHER_LANG = "other_language"
        const val KEY_MODEL = "model"
        const val KEY_BASE_URL = "base_url"
        const val KEY_BUBBLE = "bubble_enabled"
        const val KEY_BUBBLE_X = "bubble_x"
        const val KEY_BUBBLE_Y = "bubble_y"

        const val MODEL_CHAT = "deepseek-chat"
        const val MODEL_REASONER = "deepseek-reasoner"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"

        fun defaultLanguage(): String =
            Locale.getDefault().getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }
    }
}
