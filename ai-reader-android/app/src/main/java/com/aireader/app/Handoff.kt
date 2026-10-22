package com.aireader.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens ChatGPT or Claude in a window over the book (a Chrome Custom Tab shown as a bottom sheet), with the
 * question already written, using the reader's own account in the browser. Swiping it down returns to the
 * page; nothing switches to another app.
 */
object Handoff {
    enum class Target(val label: String, val webUrl: String) {
        CLAUDE("Claude", "https://claude.ai/new?q="),
        CHATGPT("ChatGPT", "https://chatgpt.com/?q="),
    }

    const val REQUEST_CODE = 41

    /** Browsers whose Custom Tabs can open as a bottom sheet (Chrome 107+), preferred over the default browser. */
    private val sheetBrowsers = listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev")

    fun url(target: Target, prompt: String): Uri = Uri.parse(target.webUrl + Uri.encode(prompt.take(1800)))

    fun send(activity: Activity, target: Target, prompt: String, theme: ReaderTheme = ReaderTheme.LIGHT) {
        // Also on the clipboard: if the site opens without the question filled in, it's one paste away.
        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Question", prompt))
        val uri = url(target, prompt)
        val tab = CustomTabsIntent.Builder()
            .setInitialActivityHeightPx((activity.resources.displayMetrics.heightPixels * 0.88).toInt())
            .setToolbarCornerRadiusDp(16)
            .setCloseButtonPosition(CustomTabsIntent.CLOSE_BUTTON_POSITION_END)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .setShowTitle(true)
            .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(theme.bg).build())
            .setColorScheme(if (theme.isDark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
            .build()
        tab.intent.data = uri
        // A browser that supports Custom Tabs, so the page opens in a window over the reader rather than
        // being handed to the ChatGPT or Claude app.
        CustomTabsClient.getPackageName(activity, sheetBrowsers, true)?.let { tab.intent.setPackage(it) }
            ?: CustomTabsClient.getPackageName(activity, null)?.let { tab.intent.setPackage(it) }
        try {
            // Launching for a result is what lets Chrome show the tab as a resizable bottom sheet.
            @Suppress("DEPRECATION")
            activity.startActivityForResult(tab.intent, REQUEST_CODE)
            Toast.makeText(activity, "${target.label} opens over the book; swipe it down to keep reading. The question is also copied.", Toast.LENGTH_LONG).show()
        } catch (e: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, "No browser found. The question is copied; paste it into ${target.label}.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
