package com.aireader.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class LibraryActivity : Activity() {
    private lateinit var library: Library
    private lateinit var list: ListView
    private lateinit var empty: View
    private lateinit var importing: View
    private var books: List<BookEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        library = Library(this)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        importing = findViewById(R.id.importing)

        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<View>(R.id.add).setOnClickListener { pickBook() }
        findViewById<View>(R.id.openBook).setOnClickListener { pickBook() }
        findViewById<View>(R.id.settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.vocabulary).setOnClickListener { startActivity(Intent(this, VocabularyActivity::class.java)) }

        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> open(books[position]) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val book = books[position]
            AlertDialog.Builder(this)
                .setTitle(book.title)
                .setMessage("Remove this book from the library? Its bookmarks, highlights and notes are deleted too. Saved words stay in your vocabulary.")
                .setPositiveButton("Remove") { _, _ ->
                    library.remove(book)
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_BOOK && resultCode == RESULT_OK) data?.data?.let { import(it) }
    }

    private fun pickBook() {
        // No MIME filter: file managers often label FB2 files application/octet-stream. The content is checked on import.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        @Suppress("DEPRECATION")
        startActivityForResult(intent, PICK_BOOK)
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        } ?: return
        intent.action = null // don't import again after rotation
        import(uri)
    }

    private fun import(uri: Uri) {
        importing.visibility = View.VISIBLE
        thread {
            val result = runCatching { library.import(uri) }
            runOnUiThread {
                importing.visibility = View.GONE
                result.onSuccess { open(it) }.onFailure {
                    val message = when (it) {
                        is UnsupportedBookException -> "This file isn't an EPUB or FB2 book, or it is damaged."
                        is BookTooLargeException -> "This book is too large (the limit is 200 MB)."
                        else -> "Couldn't add this book: ${it.message}"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        }
    }

    private fun open(book: BookEntry) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id))
    }

    private fun refresh() {
        books = library.all()
        empty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = books.size
        override fun getItem(position: Int) = books[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_book, parent, false)
            val book = books[position]
            view.findViewById<TextView>(R.id.title).text = book.title
            view.findViewById<TextView>(R.id.author).text = book.author
            val progress = (Prefs(this@LibraryActivity).progress(book.id) * 100).toInt()
            view.findViewById<TextView>(R.id.progress).text = if (progress > 0) "$progress% read" else "New"
            val cover = view.findViewById<ImageView>(R.id.cover)
            val bitmap = library.cover(book)?.let { BitmapFactory.decodeFile(it.path) }
            if (bitmap != null) {
                cover.scaleType = ImageView.ScaleType.CENTER_CROP
                cover.setImageBitmap(bitmap)
            } else {
                cover.scaleType = ImageView.ScaleType.CENTER
                cover.setImageResource(R.drawable.ic_book)
                cover.setColorFilter(getColor(R.color.muted))
            }
            if (bitmap != null) cover.clearColorFilter()
            return view
        }
    }

    private companion object {
        const val PICK_BOOK = 1
    }
}
