package com.aireader.app

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView

enum class SelectionAction(val title: String) { EXPLAIN("Explain"), TRANSLATE("Translate"), HIGHLIGHT("Highlight"), SAVE_WORD("Save word"), ASK("Ask AI") }

/** A WebView whose text-selection menu starts with the reader's own actions. */
class ReaderWebView(context: Context, attrs: AttributeSet?) : WebView(context, attrs) {
    var onSelectionAction: ((SelectionAction) -> Unit)? = null

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionMode(callback?.let(::wrap), type)

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        super.startActionMode(callback?.let(::wrap))

    private fun addItems(menu: Menu): Boolean {
        hideOtherApps(menu)
        var added = false
        SelectionAction.entries.forEachIndexed { order, action ->
            val id = FIRST_ID + order
            if (menu.findItem(id) == null) {
                menu.add(Menu.NONE, id, order, action.title).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                added = true
            }
        }
        return added
    }

    private fun wrap(inner: ActionMode.Callback) = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val result = inner.onCreateActionMode(mode, menu)
            addItems(menu)
            return result
        }

        // WebView rebuilds its items here, so ours are added again when missing.
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val result = inner.onPrepareActionMode(mode, menu)
            return addItems(menu) || result
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val action = SelectionAction.entries.getOrNull(item.itemId - FIRST_ID)
                ?: return inner.onActionItemClicked(mode, item)
            onSelectionAction?.invoke(action)
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = inner.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (inner is ActionMode.Callback2) inner.onGetContentRect(mode, view, outRect)
            else super.onGetContentRect(mode, view, outRect)
        }
    }

    companion object {
        private const val FIRST_ID = 0x7f0a7001

        /**
         * Other apps (ChatGPT, Claude, translators…) add "process text" items to every selection menu, and
         * those take the reader out of the book. The reader's own actions answer in place, so hide them.
         */
        fun hideOtherApps(menu: Menu) {
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                if (item.intent?.action == android.content.Intent.ACTION_PROCESS_TEXT) item.isVisible = false
            }
        }
    }
}
