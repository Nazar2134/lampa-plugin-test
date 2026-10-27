package app.taptranslate

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.icu.text.BreakIterator
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs
import kotlin.math.min

/** Finds the on-screen text, and ideally the exact word, under a screen point. */
object TextLocator {

    /** [start]/[end] mark the tapped word inside [text], or are -1 when only the whole text is known. */
    class Hit(val text: String, val start: Int, val end: Int)

    private const val MAX_NODES = 5000
    private const val MAX_TEXT = 4000
    private const val MAX_CHAR_LOOKUP = 10_000
    private const val KEY = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY

    fun find(service: AccessibilityService, x: Int, y: Int, slopPx: Int): Hit? {
        val bounds = Rect()
        val windows = service.windows
            .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .sortedByDescending { it.layer }
        for (window in windows) {
            window.getBoundsInScreen(bounds)
            if (!bounds.contains(x, y)) continue
            val root = window.root ?: continue
            if (root.packageName == service.packageName) continue
            val node = textNodeAt(root, x, y) ?: continue
            return hitFor(node, x, y, slopPx)
        }
        return service.rootInActiveWindow?.let { textNodeAt(it, x, y) }?.let { hitFor(it, x, y, slopPx) }
    }

    private fun labelOf(node: AccessibilityNodeInfo): CharSequence? {
        val text = node.text?.takeIf { it.isNotBlank() && !node.isShowingHintText }
        return text ?: node.contentDescription?.takeIf { it.isNotBlank() }
    }

    /** The smallest visible node with text that contains the point. */
    private fun textNodeAt(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val r = Rect()
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited++ < MAX_NODES) {
            val node = stack.removeLast()
            node.getBoundsInScreen(r)
            val contains = r.contains(x, y)
            if (!r.isEmpty && !contains) continue // the point can't be inside this subtree
            if (contains && node.isVisibleToUser && labelOf(node) != null) {
                val area = r.width().toLong() * r.height()
                if (area <= bestArea) {
                    best = node
                    bestArea = area
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(stack::add)
        }
        return best
    }

    private fun hitFor(node: AccessibilityNodeInfo, x: Int, y: Int, slopPx: Int): Hit? {
        val text = node.text?.toString()?.takeIf { it.isNotBlank() && !node.isShowingHintText }
        if (text != null) {
            val index = charIndexAt(node, text, x, y, slopPx)
            if (index >= 0) wordAt(text, index)?.let { (s, e) -> return cap(text, s, e) }
            return cap(text, -1, -1)
        }
        val description = node.contentDescription?.toString()?.trim() ?: return null
        return cap(description, -1, -1)
    }

    /** Uses per-character bounds (supported by regular text views, Compose and Chrome) to find the tapped character. */
    private fun charIndexAt(node: AccessibilityNodeInfo, text: String, x: Int, y: Int, slopPx: Int): Int {
        if (!node.availableExtraData.contains(KEY)) return -1
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, min(text.length, MAX_CHAR_LOOKUP))
        }
        if (!node.refreshWithExtraData(KEY, args)) return -1
        val rects = if (Build.VERSION.SDK_INT >= 33) node.extras.getParcelableArray(KEY, RectF::class.java)
        else @Suppress("DEPRECATION") node.extras.getParcelableArray(KEY)
        if (rects == null) return -1

        val fx = x.toFloat()
        val fy = y.toFloat()
        var bestIndex = -1
        var bestDistance = Float.MAX_VALUE
        for ((i, item) in rects.withIndex()) {
            val rect = item as? RectF ?: continue
            if (rect.contains(fx, fy)) return i
            if (fy >= rect.top && fy <= rect.bottom) { // same line: take the nearest character
                val d = min(abs(fx - rect.left), abs(fx - rect.right))
                if (d < bestDistance) {
                    bestDistance = d
                    bestIndex = i
                }
            }
        }
        return if (bestDistance <= slopPx) bestIndex else -1
    }

    /** Word boundaries around [index], also trying its neighbours in case a space or punctuation was hit. */
    fun wordAt(text: String, index: Int): Pair<Int, Int>? {
        val bi = BreakIterator.getWordInstance()
        bi.setText(text)
        for (i in intArrayOf(index, index - 1, index + 1)) {
            if (i !in text.indices) continue
            val end = bi.following(i)
            if (end == BreakIterator.DONE) continue
            val start = bi.previous()
            if (start in 0 until end && text.substring(start, end).any { it.isLetterOrDigit() }) return start to end
        }
        return null
    }

    private fun cap(text: String, start: Int, end: Int): Hit {
        if (text.length <= MAX_TEXT) return Hit(text, start, end)
        val from = if (start >= 0) (start - MAX_TEXT / 2).coerceIn(0, text.length - MAX_TEXT) else 0
        val shift = { i: Int -> if (i >= 0) i - from else -1 }
        return Hit(text.substring(from, from + MAX_TEXT), shift(start), shift(end))
    }
}
