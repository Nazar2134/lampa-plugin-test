package com.aireader.app

import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

data class TocEntry(val title: String, val chapter: Int, val anchor: String?, val depth: Int = 0)

/** A file served to the reader WebView. Chapter documents get the reader CSS/JS injected. */
class Resource(val mime: String, val bytes: ByteArray, val isChapter: Boolean)

/** An opened book: metadata, reading order and the files the WebView asks for. */
interface BookContent : Closeable {
    val title: String
    val author: String
    /** BCP 47 code from the book's metadata, if any. */
    val language: String?
    val chapterCount: Int
    val toc: List<TocEntry>

    fun chapterTitle(index: Int): String
    fun chapterUrl(index: Int): String
    fun chapterIndexForPath(path: String): Int?
    /** Chapter that contains the element with this id (FB2 footnotes); null when unknown. */
    fun chapterOfAnchor(id: String): Int?
    fun load(path: String): Resource?
    fun cover(): ByteArray?

    val languageName: String?
        get() = language?.takeIf { it.isNotBlank() }?.let {
            Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).takeIf { name -> name.isNotBlank() && name != it }
        }
}

enum class BookFormat(val extension: String) { EPUB("epub"), FB2("fb2"), FB2_ZIP("fb2.zip") }

class UnsupportedBookException(cause: Throwable? = null) : Exception("Not an EPUB or FB2 book", cause)

class BookTooLargeException : IOException("This file is too large to open")

/** Caps for untrusted book files, so a malformed or hostile archive can't exhaust memory. */
object Limits {
    const val MAX_BOOK_BYTES = 200L shl 20
    const val MAX_ENTRY_BYTES = 64L shl 20
    const val MAX_FB2_BYTES = 120L shl 20
}

/** Throws [BookTooLargeException] once more than [max] bytes have been read. */
class CappedInputStream(input: InputStream, private val max: Long) : FilterInputStream(input) {
    private var count = 0L

    private fun add(n: Int): Int {
        if (n > 0) {
            count += n
            if (count > max) throw BookTooLargeException()
        }
        return n
    }

    override fun read(): Int = super.read().also { if (it >= 0) add(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = add(super.read(b, off, len))
}

object BookOpener {
    fun detect(file: File): BookFormat? {
        val head = file.inputStream().use { input -> ByteArray(1024).let { it.copyOf(input.read(it).coerceAtLeast(0)) } }
        if (head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            return runCatching {
                ZipFile(file).use { zip ->
                    val names = zip.entries().asSequence().map { it.name }.toList()
                    when {
                        "META-INF/container.xml" in names -> BookFormat.EPUB
                        names.any { it.lowercase().endsWith(".fb2") } -> BookFormat.FB2_ZIP
                        else -> null
                    }
                }
            }.getOrNull()
        }
        return if ("<FictionBook" in String(head, Charsets.ISO_8859_1)) BookFormat.FB2 else null
    }

    fun open(file: File, fallbackTitle: String = file.nameWithoutExtension): BookContent {
        if (file.length() > Limits.MAX_BOOK_BYTES) throw BookTooLargeException()
        val format = detect(file) ?: throw UnsupportedBookException()
        return try {
            when (format) {
                BookFormat.EPUB -> EpubBook(file, fallbackTitle)
                BookFormat.FB2 -> Fb2Book(file, zipped = false, fallbackTitle)
                BookFormat.FB2_ZIP -> Fb2Book(file, zipped = true, fallbackTitle)
            }
        } catch (e: BookTooLargeException) {
            throw e
        } catch (e: Exception) {
            throw UnsupportedBookException(e)
        }
    }
}
