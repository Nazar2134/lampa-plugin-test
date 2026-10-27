package app.taptranslate

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Shows a floating button. Tapping it opens a transparent "pick" layer; the next tap on the screen is
 * resolved to the word under the finger, which is then translated. Long-press translates the clipboard.
 */
class TapService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private var bubble: View? = null
    private var picker: View? = null
    private val pickTimeout = Runnable { hidePicker() }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_BUBBLE) updateBubble()
    }

    override fun onServiceConnected() {
        wm = getSystemService(WindowManager::class.java)
        prefs = Prefs(this)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefsListener)
        updateBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (!::prefs.isInitialized) return
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefsListener)
        hidePicker()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun screen(): Rect =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds
        else resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }

    private fun overlayParams(width: Int, height: Int, flags: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or flags, PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun updateBubble() {
        if (prefs.bubbleEnabled) showBubble()
        else {
            bubble?.let { runCatching { wm.removeView(it) } }
            bubble = null
        }
    }

    private fun showBubble() {
        if (bubble != null) return
        val size = dp(48)
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_translate)
            setColorFilter(Color.WHITE)
            background = getDrawable(R.drawable.bubble_bg)
            val p = dp(12)
            setPadding(p, p, p, p)
            alpha = 0.9f
            elevation = dp(6).toFloat()
            contentDescription = getString(R.string.bubble_description)
        }
        val s = screen()
        val lp = overlayParams(size, size, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS).apply {
            x = prefs.bubbleX.takeIf { it in 0..(s.width() - size) } ?: (s.width() - size)
            y = prefs.bubbleY.takeIf { it in 0..(s.height() - size) } ?: (s.height() / 3)
        }
        view.setOnTouchListener(BubbleTouch(lp))
        wm.addView(view, lp)
        bubble = view
    }

    private inner class BubbleTouch(private val lp: WindowManager.LayoutParams) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@TapService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private var view: View? = null
        private val longPress = Runnable {
            longPressed = true
            view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            openPopup(Intent().setAction(TranslateActivity.ACTION_CLIPBOARD))
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view = v
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    longPressed = false
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        main.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        wm.updateViewLayout(v, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (dragging) snapToEdge(v) else if (!longPressed) {
                        v.performClick()
                        startPicking()
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            return true
        }

        private fun snapToEdge(v: View) {
            val s = screen()
            lp.x = if (lp.x + v.width / 2 < s.width() / 2) 0 else s.width() - v.width
            lp.y = lp.y.coerceIn(dp(32), s.height() - v.height - dp(32))
            wm.updateViewLayout(v, lp)
            prefs.bubbleX = lp.x
            prefs.bubbleY = lp.y
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun startPicking() {
        if (picker != null) return
        bubble?.visibility = View.INVISIBLE
        val layer = FrameLayout(this).apply { setBackgroundColor(0x26000000) }
        val hint = TextView(this).apply {
            text = getString(R.string.pick_hint)
            setTextColor(Color.WHITE)
            textSize = 15f
            background = getDrawable(R.drawable.hint_bg)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { hidePicker() }
        }
        layer.addView(
            hint,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(72) },
        )
        layer.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                hidePicker()
                lookUp(e.rawX.roundToInt(), e.rawY.roundToInt())
            }
            true
        }
        val lp = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        wm.addView(layer, lp)
        picker = layer
        main.postDelayed(pickTimeout, 10_000)
    }

    private fun hidePicker() {
        main.removeCallbacks(pickTimeout)
        picker?.let { runCatching { wm.removeView(it) } }
        picker = null
        bubble?.visibility = View.VISIBLE
    }

    private fun lookUp(x: Int, y: Int) {
        val slop = dp(24)
        Thread {
            val hit = runCatching { TextLocator.find(this, x, y, slop) }.getOrNull()
            main.post {
                if (hit == null) {
                    Toast.makeText(this, R.string.no_text_here, Toast.LENGTH_SHORT).show()
                } else {
                    openPopup(
                        Intent().setAction(TranslateActivity.ACTION_TAP)
                            .putExtra(TranslateActivity.EXTRA_TEXT, hit.text)
                            .putExtra(TranslateActivity.EXTRA_START, hit.start)
                            .putExtra(TranslateActivity.EXTRA_END, hit.end)
                    )
                }
            }
        }.start()
    }

    private fun openPopup(intent: Intent) {
        intent.setClass(this, FloatingTranslateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
}
