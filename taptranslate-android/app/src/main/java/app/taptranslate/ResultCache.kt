package app.taptranslate

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Small persistent LRU cache so repeated lookups are instant and free. Call off the main thread. */
object ResultCache {
    private const val MAX_ENTRIES = 300
    private val writer = Executors.newSingleThreadExecutor()
    private val map = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MAX_ENTRIES
    }
    private var file: File? = null

    fun key(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0001").toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(40)
    }

    @Synchronized
    fun get(context: Context, key: String): String? {
        load(context)
        return map[key]
    }

    @Synchronized
    fun put(context: Context, key: String, value: String) {
        load(context)
        map[key] = value
        val snapshot = JSONObject(map as Map<*, *>).toString()
        val target = file ?: return
        writer.execute { runCatching { target.writeText(snapshot) } }
    }

    private fun load(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "translations.json")
        file = f
        runCatching {
            val json = JSONObject(f.readText())
            json.keys().forEach { map[it] = json.getString(it) }
        }
    }
}
