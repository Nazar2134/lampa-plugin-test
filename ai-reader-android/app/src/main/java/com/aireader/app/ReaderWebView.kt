package com.aireader.app

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView

/** A WebView whose text-selection menu starts with Explain / Translate / Ask AI. */
class ReaderWebView(context: Context, attrs: AttributeSet?) : WebView(context, attrs) {
    var onAiAction: ((AiAction) -> Unit)? = null

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionMode(callback?.let(::wrap), type)

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        super.startActionMode(callback?.let(::wrap))

    private fun addItems(menu: Menu): Boolean {
        var added = false
        ITEMS.forEachIndexed { order, (id, title, _) ->
            if (menu.findItem(id) == null) {
                menu.add(Menu.NONE, id, order, title).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
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
            val action = ITEMS.firstOrNull { it.first == item.itemId }?.third
                ?: return inner.onActionItemClicked(mode, item)
            onAiAction?.invoke(action)
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = inner.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (inner is ActionMode.Callback2) inner.onGetContentRect(mode, view, outRect)
            else super.onGetContentRect(mode, view, outRect)
        }
    }

    private companion object {
        val ITEMS = listOf(
            Triple(0x7f0a7001, "Explain", AiAction.EXPLAIN),
            Triple(0x7f0a7002, "Translate", AiAction.TRANSLATE),
            Triple(0x7f0a7003, "Ask AI", AiAction.ASK),
        )
    }
}
