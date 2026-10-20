package com.aireader.app

import java.io.File

/**
 * The book currently open in the reader, shared with the contents/search screen so it is parsed once.
 * Its plain text and search index are built lazily, off the main thread.
 */
object BookSession {
    class Session(val bookId: String, val book: BookContent) {
        @Volatile var text: BookText? = null
            private set
        @Volatile var index: BookIndex? = null
            private set

        /** Builds the text and index if needed. Blocking. */
        @Synchronized
        fun prepare(): BookIndex {
            index?.let { return it }
            val t = text ?: BookText.from(book).also { text = it }
            return BookIndex(t).also { index = it }
        }
    }

    @Volatile private var current: Session? = null
    /** Readers using the current session; it closes when the last one does. */
    private var users = 0

    @Synchronized
    fun open(bookId: String, file: File, title: String): Session {
        current?.let {
            if (it.bookId == bookId) {
                users++
                return it
            }
        }
        current?.book?.close()
        users = 1
        return Session(bookId, BookOpener.open(file, title)).also { current = it }
    }

    fun get(bookId: String): Session? = current?.takeIf { it.bookId == bookId }

    @Synchronized
    fun close(bookId: String) {
        val s = current ?: return
        if (s.bookId != bookId || --users > 0) return
        s.book.close()
        current = null
    }
}
