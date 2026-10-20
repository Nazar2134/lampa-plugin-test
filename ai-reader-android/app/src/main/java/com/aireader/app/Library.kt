package com.aireader.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class BookEntry(
    val id: String,
    val title: String,
    val author: String,
    val fileName: String,
    val addedAt: Long,
)

/** Imported books live in the app's private storage, listed in library.json. */
class Library(private val context: Context) {
    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
    private val index = File(context.filesDir, "library.json")

    fun all(): List<BookEntry> {
        val prefs = Prefs(context)
        return read().sortedByDescending { maxOf(prefs.lastOpened(it.id), it.addedAt) }
    }

    fun get(id: String) = read().firstOrNull { it.id == id }

    fun file(entry: BookEntry) = File(booksDir, entry.fileName)

    fun cover(entry: BookEntry) = File(coversDir, "${entry.id}.jpg").takeIf { it.exists() }

    /** Copies the document into the library. Returns the existing entry if the same book is already there. */
    fun import(uri: Uri): BookEntry {
        val tmp = File(booksDir, "import-${System.nanoTime()}.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { CappedInputStream(input, Limits.MAX_BOOK_BYTES).copyTo(it) }
            } ?: throw UnsupportedBookException()
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        val format = BookOpener.detect(tmp) ?: run {
            tmp.delete()
            throw UnsupportedBookException()
        }
        val id = UUID.randomUUID().toString()
        val file = File(booksDir, "$id.${format.extension}")
        tmp.renameTo(file)

        val book = try {
            BookOpener.open(file, displayName(uri)?.substringBefore('.') ?: "Untitled")
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        book.use {
            read().firstOrNull { it.title == book.title && it.author == book.author }?.let { existing ->
                file.delete()
                return existing
            }
            runCatching { book.cover()?.let { saveCover(it, File(coversDir, "$id.jpg")) } }
            val entry = BookEntry(id, book.title, book.author, file.name, System.currentTimeMillis())
            write(read() + entry)
            return entry
        }
    }

    fun remove(entry: BookEntry) {
        file(entry).delete()
        cover(entry)?.delete()
        Prefs(context).forget(entry.id)
        ReaderDb.get(context).forgetBook(entry.id)
        write(read().filter { it.id != entry.id })
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun saveCover(bytes: ByteArray, target: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= 360) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
    }

    private fun read(): List<BookEntry> {
        if (!index.exists()) return emptyList()
        val array = runCatching { JSONArray(index.readText()) }.getOrElse { return emptyList() }
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            BookEntry(o.getString("id"), o.getString("title"), o.optString("author"), o.getString("file"), o.optLong("added"))
        }
    }

    private fun write(entries: List<BookEntry>) {
        val array = JSONArray()
        for (e in entries) {
            array.put(JSONObject().put("id", e.id).put("title", e.title).put("author", e.author).put("file", e.fileName).put("added", e.addedAt))
        }
        val tmp = File(index.path + ".tmp")
        tmp.writeText(array.toString())
        tmp.renameTo(index)
    }
}
