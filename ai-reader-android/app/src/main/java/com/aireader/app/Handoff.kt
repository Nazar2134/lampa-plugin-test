package com.aireader.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Opens the free Claude or ChatGPT app (or website) with a question already written. */
object Handoff {
    enum class Target(val label: String, val packageName: String, val webUrl: String) {
        CLAUDE("Claude", "com.anthropic.claude", "https://claude.ai/new?q="),
        CHATGPT("ChatGPT", "com.openai.chatgpt", "https://chatgpt.com/?q="),
    }

    fun send(activity: Activity, target: Target, prompt: String) {
        // Also on the clipboard, in case the app opens without the text filled in.
        activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Question", prompt))
        val share = Intent(Intent.ACTION_SEND)
            .setPackage(target.packageName)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, prompt)
        try {
            activity.startActivity(share)
            Toast.makeText(activity, "Opening ${target.label}. The question is also copied if you need to paste it.", Toast.LENGTH_LONG).show()
        } catch (e: ActivityNotFoundException) {
            val url = target.webUrl + Uri.encode(prompt.take(1800))
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, "Install the ${target.label} app, then paste the copied question.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
