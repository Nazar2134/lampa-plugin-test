package com.aireader.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Saved words: search, filter by status, edit, open in the book, and review as flashcards. */
class VocabularyActivity : Activity() {
    private lateinit var db: ReaderDb
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var query: EditText
    private var entries: List<VocabEntry> = emptyList()
    private var filter: VocabStatus? = null

    private lateinit var reviewPanel: View
    private var queue = ArrayDeque<VocabEntry>()
    private var reviewed = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vocabulary)
        db = ReaderDb.get(this)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        query = findViewById(R.id.query)
        reviewPanel = findViewById(R.id.reviewPanel)
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.review).setOnClickListener { startReview() }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> edit(entries[position]) }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        mapOf(R.id.filterAll to null, R.id.filterNew to VocabStatus.NEW, R.id.filterLearning to VocabStatus.LEARNING, R.id.filterKnown to VocabStatus.KNOWN)
            .forEach { (id, status) -> findViewById<View>(id).setOnClickListener { filter = status; refresh() } }

        findViewById<View>(R.id.reviewClose).setOnClickListener { endReview() }
        findViewById<View>(R.id.showAnswer).setOnClickListener { revealAnswer() }
        mapOf(R.id.gradeAgain to Vocabulary.Grade.AGAIN, R.id.gradeHard to Vocabulary.Grade.HARD, R.id.gradeGood to Vocabulary.Grade.GOOD, R.id.gradeEasy to Vocabulary.Grade.EASY)
            .forEach { (id, grade) -> findViewById<View>(id).setOnClickListener { grade(grade) } }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        entries = db.vocabulary(search = query.text.toString(), status = filter)
        for ((id, status) in listOf(R.id.filterAll to null, R.id.filterNew to VocabStatus.NEW, R.id.filterLearning to VocabStatus.LEARNING, R.id.filterKnown to VocabStatus.KNOWN)) {
            findViewById<TextView>(id).apply {
                setTextColor(getColor(if (filter == status) R.color.accent else R.color.muted))
                setTypeface(null, if (filter == status) Typeface.BOLD else Typeface.NORMAL)
                isSelected = filter == status
            }
        }
        val due = db.vocabulary(dueBefore = System.currentTimeMillis()).size
        findViewById<Button>(R.id.review).text = if (due > 0) "Review $due" else "Review"
        empty.text = when {
            query.text.isNotBlank() || filter != null -> "Nothing matches."
            else -> "No saved words yet.\n\nIn a book, select a word or phrase and choose Save word, or tap ☆ Save word in the AI panel."
        }
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
    }

    private fun source(e: VocabEntry) = listOf(e.bookTitle, e.chapterTitle).filter { it.isNotBlank() }.distinct().joinToString(" · ")

    private fun statusLabel(s: VocabStatus) = s.name.lowercase().replaceFirstChar { it.uppercase() }

    private fun edit(e: VocabEntry) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(getColor(R.color.muted))
            setPadding(0, pad / 2, 0, 2)
        }
        val translation = EditText(this).apply { setText(e.translation); hint = "Translation"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        val notes = EditText(this).apply { setText(e.notes); hint = "Your notes"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        val status = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        VocabStatus.entries.forEach { s ->
            status.addView(RadioButton(this).apply { id = View.generateViewId(); text = statusLabel(s); tag = s; isChecked = s == e.status })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(label("Translation")); addView(translation)
            if (e.explanation.isNotBlank()) {
                addView(label("Explanation"))
                addView(TextView(this@VocabularyActivity).apply {
                    text = SimpleMarkdown.render(e.explanation, getColor(R.color.accent), getColor(R.color.muted))
                    setTextIsSelectable(true)
                })
            }
            if (e.sentence.isNotBlank()) {
                addView(label("In the book"))
                addView(TextView(this@VocabularyActivity).apply { text = emphasize(e.sentence, e.word); setTextIsSelectable(true) })
            }
            val src = source(e)
            if (src.isNotBlank()) addView(label("$src · added ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(e.created))}"))
            addView(label("Notes")); addView(notes)
            addView(label("Status")); addView(status)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(e.word)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Save") { _, _ ->
                val s = status.findViewById<RadioButton>(status.checkedRadioButtonId)?.tag as? VocabStatus ?: e.status
                db.updateVocab(e.copy(translation = translation.text.toString().trim(), notes = notes.text.toString().trim(), status = s,
                    intervalDays = if (s == VocabStatus.KNOWN && e.status != VocabStatus.KNOWN) maxOf(e.intervalDays, 30.0) else e.intervalDays))
                refresh()
            }
            .setNegativeButton("Delete") { _, _ ->
                AlertDialog.Builder(this).setMessage("Delete “${e.word}”?")
                    .setPositiveButton("Delete") { _, _ -> db.deleteVocab(e.id); refresh() }
                    .setNegativeButton("Cancel", null).show()
            }
        val bookId = e.bookId
        if (bookId != null && Library(this).get(bookId) != null) {
            dialog.setNeutralButton("Open in book") { _, _ ->
                startActivity(Intent(this, ReaderActivity::class.java)
                    .putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
                    .putExtra(ReaderActivity.EXTRA_CHAPTER, e.chapter)
                    .putExtra(ReaderActivity.EXTRA_TARGET, JSONObject().put("text", e.word).put("occurrence", e.anchorOccurrence).put("flash", true).toString()))
            }
        }
        dialog.show()
    }

    private fun emphasize(sentence: String, word: String): CharSequence {
        val out = SpannableStringBuilder(sentence)
        val i = sentence.indexOf(word, ignoreCase = true)
        if (i >= 0) {
            out.setSpan(StyleSpan(Typeface.BOLD), i, i + word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(getColor(R.color.on_surface)), i, i + word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    // --- Review ---

    private fun startReview() {
        val due = db.vocabulary(dueBefore = System.currentTimeMillis())
        // Nothing due: practise the newest words anyway.
        queue = ArrayDeque(due.ifEmpty { db.vocabulary().take(20) })
        if (queue.isEmpty()) {
            AlertDialog.Builder(this).setMessage("Save some words from your books first.").setPositiveButton("OK", null).show()
            return
        }
        reviewed = 0
        reviewPanel.visibility = View.VISIBLE
        showCard()
    }

    private fun showCard() {
        val e = queue.firstOrNull() ?: return endReview()
        findViewById<TextView>(R.id.reviewCount).text = "${queue.size} to go · $reviewed done"
        findViewById<TextView>(R.id.cardWord).text = e.word
        findViewById<TextView>(R.id.cardSentence).text = emphasize(e.sentence, e.word)
        findViewById<TextView>(R.id.cardAnswer).visibility = View.GONE
        findViewById<View>(R.id.showAnswer).visibility = View.VISIBLE
        findViewById<View>(R.id.grades).visibility = View.GONE
    }

    private fun revealAnswer() {
        val e = queue.firstOrNull() ?: return
        val answer = listOf(e.translation, e.explanation.take(600), e.notes).filter { it.isNotBlank() }.joinToString("\n\n")
        findViewById<TextView>(R.id.cardAnswer).apply {
            text = SimpleMarkdown.render(answer.ifBlank { "No translation saved yet. Open the word in the list to add one." }, getColor(R.color.accent), getColor(R.color.muted))
            visibility = View.VISIBLE
        }
        findViewById<View>(R.id.showAnswer).visibility = View.GONE
        findViewById<View>(R.id.grades).visibility = View.VISIBLE
    }

    private fun grade(g: Vocabulary.Grade) {
        val e = queue.removeFirstOrNull() ?: return
        val updated = Vocabulary.review(e, g)
        db.updateVocab(updated)
        reviewed++
        if (g == Vocabulary.Grade.AGAIN) queue.addLast(updated)
        showCard()
    }

    private fun endReview() {
        reviewPanel.visibility = View.GONE
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (reviewPanel.visibility == View.VISIBLE) endReview() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = entries[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_vocab, parent, false)
            val e = entries[position]
            view.findViewById<TextView>(R.id.vWord).text = SpannableStringBuilder().apply {
                append(e.word, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (e.translation.isNotBlank()) append("  —  ").append(e.translation)
            }
            view.findViewById<TextView>(R.id.vSentence).apply {
                text = e.sentence
                visibility = if (e.sentence.isBlank()) View.GONE else View.VISIBLE
            }
            view.findViewById<TextView>(R.id.vMeta).text = listOf(statusLabel(e.status), source(e)).filter { it.isNotBlank() }.joinToString(" · ")
            return view
        }
    }
}
