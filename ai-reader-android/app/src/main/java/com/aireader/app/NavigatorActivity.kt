package com.aireader.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject
import kotlin.concurrent.thread

/** Contents, bookmarks, highlights with notes, and search for the open book. Returns where to go. */
class NavigatorActivity : Activity() {
    private class Row(
        val title: CharSequence,
        val subtitle: CharSequence,
        val chapter: Int,
        val target: JSONObject?,
        val fraction: Float = 0f,
        val bold: Boolean = false,
        val onDelete: (() -> Unit)? = null,
    )

    private enum class Tab { CONTENTS, BOOKMARKS, NOTES, SEARCH }

    private lateinit var session: BookSession.Session
    private lateinit var db: ReaderDb
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var query: EditText
    private lateinit var searchInfo: TextView
    private var rows: List<Row> = emptyList()
    private var tab = Tab.CONTENTS
    private var currentChapter = 0
    private val main = Handler(Looper.getMainLooper())
    private var searchGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        session = bookId?.let { BookSession.get(it) } ?: run {
            finish()
            return
        }
        setContentView(R.layout.activity_navigator)
        db = ReaderDb.get(this)
        currentChapter = intent.getIntExtra(EXTRA_CHAPTER, 0)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<TextView>(R.id.title).text = session.book.title
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        query = findViewById(R.id.query)
        searchInfo = findViewById(R.id.searchInfo)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> choose(rows[position]) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val row = rows[position]
            val delete = row.onDelete ?: return@setOnItemLongClickListener false
            AlertDialog.Builder(this)
                .setMessage(if (tab == Tab.BOOKMARKS) "Delete this bookmark?" else "Delete this highlight and its note?")
                .setPositiveButton("Delete") { _, _ -> delete(); show(tab) }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
        mapOf(R.id.tabContents to Tab.CONTENTS, R.id.tabBookmarks to Tab.BOOKMARKS, R.id.tabNotes to Tab.NOTES, R.id.tabSearch to Tab.SEARCH)
            .forEach { (id, t) -> findViewById<View>(id).setOnClickListener { show(t) } }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val gen = ++searchGeneration
                main.postDelayed({ if (gen == searchGeneration) search() }, 300)
            }
        })
        query.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false }
        show(if (intent.getBooleanExtra(EXTRA_SEARCH, false)) Tab.SEARCH else Tab.CONTENTS)
    }

    private fun show(t: Tab) {
        tab = t
        for ((id, tabFor) in listOf(R.id.tabContents to Tab.CONTENTS, R.id.tabBookmarks to Tab.BOOKMARKS, R.id.tabNotes to Tab.NOTES, R.id.tabSearch to Tab.SEARCH)) {
            findViewById<TextView>(id).apply {
                setTextColor(getColor(if (tabFor == t) R.color.accent else R.color.muted))
                setTypeface(null, if (tabFor == t) Typeface.BOLD else Typeface.NORMAL)
                isSelected = tabFor == t
            }
        }
        findViewById<View>(R.id.searchBar).visibility = if (t == Tab.SEARCH) View.VISIBLE else View.GONE
        val book = session.book
        when (t) {
            Tab.CONTENTS -> {
                val toc = book.toc.ifEmpty { (0 until book.chapterCount).map { TocEntry(book.chapterTitle(it), it, null) } }
                val current = toc.indexOfLast { it.chapter <= currentChapter }
                setRows(toc.mapIndexed { i, e ->
                    Row("    ".repeat(e.depth.coerceAtMost(3)) + e.title, "", e.chapter, e.anchor?.let { JSONObject().put("id", it) }, bold = i == current)
                }, "This book has no table of contents.")
                if (current > 0) list.setSelection((current - 2).coerceAtLeast(0))
            }
            Tab.BOOKMARKS -> setRows(db.bookmarks(session.bookId).map { b ->
                Row(b.label.ifBlank { "Bookmark" } + "…", book.chapterTitle(b.chapter), b.chapter,
                    b.anchorText.takeIf { it.isNotEmpty() }?.let { JSONObject().put("text", it).put("occurrence", b.anchorOccurrence) },
                    b.fraction, onDelete = { db.deleteBookmark(b.id) })
            }, "No bookmarks yet.\n\nTap the bookmark icon in the reader's toolbar to mark a page.")
            Tab.NOTES -> setRows(db.highlights(session.bookId).map { h ->
                val sub = listOf(h.note, book.chapterTitle(h.chapter)).filter { it.isNotBlank() }.joinToString("\n")
                Row("“${h.text}”", sub, h.chapter, JSONObject().put("text", h.text).put("occurrence", h.occurrence).put("flash", true),
                    onDelete = { db.deleteHighlight(h.id) })
            }, "No highlights yet.\n\nSelect text in the book and choose Highlight. Tap a highlight to add a note.")
            Tab.SEARCH -> {
                query.requestFocus()
                search()
            }
        }
    }

    private fun setRows(newRows: List<Row>, emptyText: String) {
        rows = newRows
        empty.text = emptyText
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
    }

    private fun search() {
        if (tab != Tab.SEARCH) return
        val q = query.text.toString().trim()
        if (q.length < 2) {
            searchInfo.text = ""
            setRows(emptyList(), "Type at least two letters to search the whole book.")
            return
        }
        val gen = ++searchGeneration
        searchInfo.text = "Searching…"
        thread(isDaemon = true) {
            val index = session.prepare()
            val text = session.text!!
            val matches = index.find(q)
            val found = matches.map { m ->
                val para = text.chapters[m.chapter].paragraphs[m.paragraph]
                val from = (m.start - 60).coerceAtLeast(0)
                val to = (m.start + m.text.length + 100).coerceAtMost(para.length)
                val snippet = SpannableStringBuilder()
                if (from > 0) snippet.append("…")
                val start = snippet.length + (m.start - from)
                snippet.append(para.substring(from, to))
                if (to < para.length) snippet.append("…")
                snippet.setSpan(StyleSpan(Typeface.BOLD), start, start + m.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                snippet.setSpan(BackgroundColorSpan(0x55F2C94C), start, start + m.text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                Row(snippet, text.chapters[m.chapter].title, m.chapter, JSONObject().put("text", m.text).put("occurrence", m.occurrence).put("flash", true))
            }
            main.post {
                if (gen != searchGeneration || isDestroyed) return@post
                searchInfo.text = when {
                    found.isEmpty() -> ""
                    found.size >= 300 -> "First 300 matches"
                    found.size == 1 -> "1 match"
                    else -> "${found.size} matches"
                }
                setRows(found, "No matches for “$q”.")
            }
        }
    }

    private fun choose(row: Row) {
        setResult(RESULT_OK, Intent()
            .putExtra(ReaderActivity.EXTRA_CHAPTER, row.chapter)
            .putExtra(ReaderActivity.EXTRA_FRACTION, row.fraction)
            .putExtra(ReaderActivity.EXTRA_TARGET, row.target?.toString()))
        finish()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_nav, parent, false)
            val row = rows[position]
            view.findViewById<TextView>(R.id.itemTitle).apply {
                text = row.title
                setTypeface(null, if (row.bold) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(getColor(if (row.bold) R.color.accent else R.color.on_surface))
            }
            view.findViewById<TextView>(R.id.itemSubtitle).apply {
                text = row.subtitle
                visibility = if (row.subtitle.isEmpty()) View.GONE else View.VISIBLE
            }
            return view
        }
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_CHAPTER = "chapter"
        const val EXTRA_SEARCH = "search"
    }
}
