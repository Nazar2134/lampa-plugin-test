package com.aireader.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

data class Bookmark(
    val id: Long = 0,
    val bookId: String,
    val chapter: Int,
    val fraction: Float,
    val anchorText: String,
    val anchorOccurrence: Int,
    val label: String,
    val created: Long = System.currentTimeMillis(),
)

data class Highlight(
    val id: Long = 0,
    val bookId: String,
    val chapter: Int,
    val text: String,
    val occurrence: Int,
    val color: String = "yellow",
    val note: String = "",
    val created: Long = System.currentTimeMillis(),
)

enum class VocabStatus { NEW, LEARNING, KNOWN }

data class VocabEntry(
    val id: Long = 0,
    val word: String,
    val sentence: String = "",
    val translation: String = "",
    val explanation: String = "",
    /** Null once the source book has been removed from the library; the title is kept. */
    val bookId: String? = null,
    val bookTitle: String = "",
    val chapter: Int = -1,
    val chapterTitle: String = "",
    val anchorOccurrence: Int = 0,
    val notes: String = "",
    val created: Long = System.currentTimeMillis(),
    val status: VocabStatus = VocabStatus.NEW,
    /** Spaced repetition: next review time, current interval and ease. */
    val due: Long = 0,
    val intervalDays: Double = 0.0,
    val ease: Double = 2.5,
    val reviews: Int = 0,
)

data class ChatMessage(val fromUser: Boolean, val text: String, val created: Long = System.currentTimeMillis())

data class UsageSummary(val requests: Int, val inputTokens: Long, val outputTokens: Long)

/**
 * Notes, bookmarks, vocabulary, saved chats, the answer cache and AI usage. Books themselves stay files;
 * reading positions and settings stay in preferences.
 *
 * Schema changes must be additive migrations in [onUpgrade]; never drop tables holding user data.
 */
class ReaderDb private constructor(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(false)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, book_id TEXT NOT NULL, chapter INTEGER NOT NULL,
               fraction REAL NOT NULL, anchor_text TEXT NOT NULL, anchor_occ INTEGER NOT NULL, label TEXT NOT NULL, created INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX bookmarks_book ON bookmarks(book_id, chapter)")
        db.execSQL(
            """CREATE TABLE highlights (id INTEGER PRIMARY KEY AUTOINCREMENT, book_id TEXT NOT NULL, chapter INTEGER NOT NULL,
               text TEXT NOT NULL, occurrence INTEGER NOT NULL, color TEXT NOT NULL, note TEXT NOT NULL, created INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX highlights_book ON highlights(book_id, chapter)")
        db.execSQL(
            """CREATE TABLE vocab (id INTEGER PRIMARY KEY AUTOINCREMENT, word TEXT NOT NULL, sentence TEXT NOT NULL,
               translation TEXT NOT NULL, explanation TEXT NOT NULL, book_id TEXT, book_title TEXT NOT NULL, chapter INTEGER NOT NULL,
               chapter_title TEXT NOT NULL, anchor_occ INTEGER NOT NULL, notes TEXT NOT NULL, created INTEGER NOT NULL,
               status TEXT NOT NULL, due INTEGER NOT NULL, interval_days REAL NOT NULL, ease REAL NOT NULL, reviews INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX vocab_book ON vocab(book_id)")
        db.execSQL("CREATE INDEX vocab_due ON vocab(due)")
        db.execSQL("CREATE TABLE chat (id INTEGER PRIMARY KEY AUTOINCREMENT, book_id TEXT NOT NULL, from_user INTEGER NOT NULL, text TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX chat_book ON chat(book_id)")
        db.execSQL("CREATE TABLE ai_cache (key TEXT PRIMARY KEY, response TEXT NOT NULL, used INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE usage (id INTEGER PRIMARY KEY AUTOINCREMENT, provider TEXT NOT NULL, model TEXT NOT NULL, input_tokens INTEGER NOT NULL, output_tokens INTEGER NOT NULL, created INTEGER NOT NULL)")
        createVersion2(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive steps only, so existing notes, vocabulary and chats are kept.
        if (oldVersion < 2) createVersion2(db)
    }

    /** Version 2: learning mode. Word statuses per language, and cached word look-ups. */
    private fun createVersion2(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS words (lang TEXT NOT NULL, form TEXT NOT NULL, status INTEGER NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY (lang, form))")
        db.execSQL("CREATE TABLE IF NOT EXISTS lexicon (lang TEXT NOT NULL, form TEXT NOT NULL, lemma TEXT NOT NULL, json TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY (lang, form))")
    }

    // --- Learning: word statuses and look-ups ---

    /** Every word with a status in [lang]: lower-case form → status. */
    fun wordStatuses(lang: String): Map<String, Int> =
        query("SELECT form, status FROM words WHERE lang = ?", lang) { it.str("form") to it.int("status") }.toMap()

    /** Sets [status] for [forms]; [WordStatus.NEW] removes them. */
    fun setWordStatus(lang: String, forms: Collection<String>, status: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (form in forms) {
                if (status == WordStatus.NEW) {
                    db.delete("words", "lang = ? AND form = ?", arrayOf(lang, form))
                } else {
                    db.insertWithOnConflict("words", null, ContentValues().apply {
                        put("lang", lang); put("form", form); put("status", status); put("updated", System.currentTimeMillis())
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Marks [form] known in every language where it is being learnt. */
    fun promoteLearningWord(form: String) {
        writableDatabase.update("words", ContentValues().apply { put("status", WordStatus.KNOWN); put("updated", System.currentTimeMillis()) },
            "form = ? AND status = ?", arrayOf(form, WordStatus.LEARNING.toString()))
    }

    fun lexicon(lang: String, form: String): String? =
        query("SELECT json FROM lexicon WHERE lang = ? AND form = ?", lang, form) { it.str("json") }.firstOrNull()

    fun saveLexicon(lang: String, form: String, lemma: String, json: String) {
        writableDatabase.insertWithOnConflict("lexicon", null, ContentValues().apply {
            put("lang", lang); put("form", form); put("lemma", lemma); put("json", json); put("created", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Word form → dictionary form, for every looked-up word in [lang]. */
    fun lemmas(lang: String): Map<String, String> =
        query("SELECT form, lemma FROM lexicon WHERE lang = ? AND lemma != ''", lang) { it.str("form") to it.str("lemma") }.toMap()

    // --- Bookmarks ---

    fun addBookmark(b: Bookmark): Long = writableDatabase.insert("bookmarks", null, ContentValues().apply {
        put("book_id", b.bookId); put("chapter", b.chapter); put("fraction", b.fraction); put("anchor_text", b.anchorText)
        put("anchor_occ", b.anchorOccurrence); put("label", b.label); put("created", b.created)
    })

    fun deleteBookmark(id: Long) = writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString()))

    fun bookmarks(bookId: String, chapter: Int? = null): List<Bookmark> = query(
        "SELECT * FROM bookmarks WHERE book_id = ?" + (if (chapter != null) " AND chapter = $chapter" else "") + " ORDER BY chapter, fraction",
        bookId,
    ) { c -> Bookmark(c.long("id"), c.str("book_id"), c.int("chapter"), c.getFloat(c.getColumnIndexOrThrow("fraction")), c.str("anchor_text"), c.int("anchor_occ"), c.str("label"), c.long("created")) }

    // --- Highlights ---

    fun addHighlight(h: Highlight): Long = writableDatabase.insert("highlights", null, ContentValues().apply {
        put("book_id", h.bookId); put("chapter", h.chapter); put("text", h.text); put("occurrence", h.occurrence)
        put("color", h.color); put("note", h.note); put("created", h.created)
    })

    fun updateHighlight(id: Long, color: String, note: String) = writableDatabase.update(
        "highlights", ContentValues().apply { put("color", color); put("note", note) }, "id = ?", arrayOf(id.toString()),
    )

    fun deleteHighlight(id: Long) = writableDatabase.delete("highlights", "id = ?", arrayOf(id.toString()))

    fun highlight(id: Long): Highlight? = highlights("SELECT * FROM highlights WHERE id = ?", id.toString()).firstOrNull()

    fun highlights(bookId: String, chapter: Int? = null): List<Highlight> = highlights(
        "SELECT * FROM highlights WHERE book_id = ?" + (if (chapter != null) " AND chapter = $chapter" else "") + " ORDER BY chapter, created",
        bookId,
    )

    private fun highlights(sql: String, arg: String) = query(sql, arg) { c ->
        Highlight(c.long("id"), c.str("book_id"), c.int("chapter"), c.str("text"), c.int("occurrence"), c.str("color"), c.str("note"), c.long("created"))
    }

    // --- Vocabulary ---

    fun addVocab(v: VocabEntry): Long = writableDatabase.insert("vocab", null, vocabValues(v))

    fun updateVocab(v: VocabEntry) = writableDatabase.update("vocab", vocabValues(v), "id = ?", arrayOf(v.id.toString()))

    fun deleteVocab(id: Long) = writableDatabase.delete("vocab", "id = ?", arrayOf(id.toString()))

    fun vocab(id: Long): VocabEntry? = query("SELECT * FROM vocab WHERE id = ?", id.toString(), map = ::vocabFrom).firstOrNull()

    /** Saved words, newest first, filtered by [search] (word, translation, sentence or notes), [status] and [bookId]. */
    fun vocabulary(search: String = "", status: VocabStatus? = null, bookId: String? = null, dueBefore: Long? = null): List<VocabEntry> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        if (search.isNotBlank()) {
            where += "(word LIKE ? ESCAPE '\\' OR translation LIKE ? ESCAPE '\\' OR sentence LIKE ? ESCAPE '\\' OR notes LIKE ? ESCAPE '\\')"
            val like = "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            repeat(4) { args += like }
        }
        if (status != null) { where += "status = ?"; args += status.name }
        if (bookId != null) { where += "book_id = ?"; args += bookId }
        if (dueBefore != null) { where += "due <= ?"; args += dueBefore.toString() }
        val sql = "SELECT * FROM vocab" + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            if (dueBefore != null) " ORDER BY due" else " ORDER BY created DESC"
        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { c -> generateSequence { if (c.moveToNext()) vocabFrom(c) else null }.toList() }
    }

    fun findVocab(word: String, bookId: String?): VocabEntry? = query(
        "SELECT * FROM vocab WHERE word = ? COLLATE NOCASE AND IFNULL(book_id, '') = ? LIMIT 1", word, bookId.orEmpty(), map = ::vocabFrom,
    ).firstOrNull()

    private fun vocabValues(v: VocabEntry) = ContentValues().apply {
        put("word", v.word); put("sentence", v.sentence); put("translation", v.translation); put("explanation", v.explanation)
        put("book_id", v.bookId); put("book_title", v.bookTitle); put("chapter", v.chapter); put("chapter_title", v.chapterTitle)
        put("anchor_occ", v.anchorOccurrence); put("notes", v.notes); put("created", v.created); put("status", v.status.name)
        put("due", v.due); put("interval_days", v.intervalDays); put("ease", v.ease); put("reviews", v.reviews)
    }

    private fun vocabFrom(c: Cursor) = VocabEntry(
        c.long("id"), c.str("word"), c.str("sentence"), c.str("translation"), c.str("explanation"),
        c.getString(c.getColumnIndexOrThrow("book_id")), c.str("book_title"), c.int("chapter"), c.str("chapter_title"),
        c.int("anchor_occ"), c.str("notes"), c.long("created"),
        runCatching { VocabStatus.valueOf(c.str("status")) }.getOrDefault(VocabStatus.NEW),
        c.long("due"), c.getDouble(c.getColumnIndexOrThrow("interval_days")), c.getDouble(c.getColumnIndexOrThrow("ease")), c.int("reviews"),
    )

    // --- Book chat ---

    fun chat(bookId: String): List<ChatMessage> = query("SELECT * FROM chat WHERE book_id = ? ORDER BY id", bookId) { c ->
        ChatMessage(c.int("from_user") == 1, c.str("text"), c.long("created"))
    }

    fun addChat(bookId: String, m: ChatMessage) = writableDatabase.insert("chat", null, ContentValues().apply {
        put("book_id", bookId); put("from_user", if (m.fromUser) 1 else 0); put("text", m.text); put("created", m.created)
    })

    fun clearChat(bookId: String) = writableDatabase.delete("chat", "book_id = ?", arrayOf(bookId))

    // --- Answer cache ---

    fun cached(key: String): String? = query("SELECT response FROM ai_cache WHERE key = ?", key) { it.str("response") }.firstOrNull()
        ?.also { writableDatabase.update("ai_cache", ContentValues().apply { put("used", System.currentTimeMillis()) }, "key = ?", arrayOf(key)) }

    fun cache(key: String, response: String) {
        writableDatabase.insertWithOnConflict("ai_cache", null, ContentValues().apply {
            put("key", key); put("response", response); put("used", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        writableDatabase.execSQL("DELETE FROM ai_cache WHERE key NOT IN (SELECT key FROM ai_cache ORDER BY used DESC LIMIT $CACHE_SIZE)")
    }

    // --- Usage ---

    fun addUsage(provider: AiProvider, model: String, input: Int, output: Int) = writableDatabase.insert("usage", null, ContentValues().apply {
        put("provider", provider.name); put("model", model); put("input_tokens", input); put("output_tokens", output); put("created", System.currentTimeMillis())
    })

    fun usage(provider: AiProvider, since: Long): UsageSummary = readableDatabase.rawQuery(
        "SELECT COUNT(*), IFNULL(SUM(input_tokens), 0), IFNULL(SUM(output_tokens), 0) FROM usage WHERE provider = ? AND created >= ?",
        arrayOf(provider.name, since.toString()),
    ).use { c -> c.moveToFirst(); UsageSummary(c.getInt(0), c.getLong(1), c.getLong(2)) }

    // --- Books ---

    /**
     * Removes a book's bookmarks, highlights and chat. Vocabulary is the user's own learning data, so it is kept,
     * detached from the book but still showing its title.
     */
    fun forgetBook(bookId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("bookmarks", "book_id = ?", arrayOf(bookId))
            db.delete("highlights", "book_id = ?", arrayOf(bookId))
            db.delete("chat", "book_id = ?", arrayOf(bookId))
            db.execSQL("UPDATE vocab SET book_id = NULL WHERE book_id = ?", arrayOf(bookId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun <T> query(sql: String, vararg args: String, map: (Cursor) -> T): List<T> =
        readableDatabase.rawQuery(sql, args).use { c -> generateSequence { if (c.moveToNext()) map(c) else null }.toList() }

    private fun Cursor.str(name: String) = getString(getColumnIndexOrThrow(name)).orEmpty()
    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))

    companion object {
        const val NAME = "reader.db"
        const val VERSION = 2
        private const val CACHE_SIZE = 400

        @Volatile private var instance: ReaderDb? = null

        fun get(context: Context): ReaderDb =
            instance ?: synchronized(this) { instance ?: ReaderDb(context.applicationContext).also { instance = it } }

        /** Tests use a fresh database per test. */
        internal fun reset() = synchronized(this) {
            instance?.close()
            instance = null
            WordStore.reset()
        }

        fun cacheKey(vararg parts: String): String =
            MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0001").toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
