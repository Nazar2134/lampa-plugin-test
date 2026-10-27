package app.taptranslate

import java.util.Locale

object Languages {
    val LIST = arrayOf(
        "English", "Ukrainian", "Russian", "Polish", "German", "French", "Spanish", "Italian",
        "Portuguese", "Dutch", "Czech", "Slovak", "Romanian", "Hungarian", "Bulgarian", "Serbian",
        "Croatian", "Greek", "Turkish", "Arabic", "Hebrew", "Persian", "Hindi", "Bengali", "Urdu",
        "Chinese (Simplified)", "Chinese (Traditional)", "Japanese", "Korean", "Vietnamese", "Thai",
        "Indonesian", "Malay", "Filipino", "Swedish", "Norwegian", "Danish", "Finnish", "Estonian",
        "Latvian", "Lithuanian", "Georgian", "Armenian", "Kazakh", "Uzbek",
    )

    /** "en" -> "English"; null for codes Android doesn't know. */
    fun nameOf(code: String?): String? {
        if (code.isNullOrBlank()) return null
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
        return name.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
    }
}
