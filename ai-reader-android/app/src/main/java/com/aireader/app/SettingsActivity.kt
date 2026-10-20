package com.aireader.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import kotlin.concurrent.thread

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var language: EditText
    private lateinit var detail: RadioGroup
    private lateinit var key: EditText
    private lateinit var model: RadioGroup
    private lateinit var checkResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)

        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        language = findViewById(R.id.language)
        detail = findViewById(R.id.detail)
        key = findViewById(R.id.geminiKey)
        model = findViewById(R.id.model)
        checkResult = findViewById(R.id.checkResult)

        val s = prefs.load()
        language.setText(s.language)
        detail.check(if (s.detailed) R.id.detailFull else R.id.detailBrief)
        key.setText(s.geminiKey)
        model.check(if ("lite" in s.geminiModel) R.id.modelLite else R.id.modelFlash)

        findViewById<View>(R.id.getKey).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }
        findViewById<Button>(R.id.check).setOnClickListener { checkKey() }
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    private fun save() {
        val current = prefs.load()
        val wantsLite = model.checkedRadioButtonId == R.id.modelLite
        // Keep a model name resolved earlier if it is still the same kind (Flash vs Flash-Lite).
        val chosenModel = if (("lite" in current.geminiModel) == wantsLite) current.geminiModel else if (wantsLite) Gemini.MODEL_LITE else Gemini.MODEL_FLASH
        prefs.save(
            current.copy(
                language = language.text.toString().ifBlank { "English" },
                detailed = detail.checkedRadioButtonId == R.id.detailFull,
                geminiKey = key.text.toString(),
                geminiModel = chosenModel,
            )
        )
    }

    private fun checkKey() {
        save()
        val s = prefs.load()
        checkResult.visibility = View.VISIBLE
        if (s.geminiKey.isBlank()) {
            checkResult.text = "Paste a key first."
            return
        }
        checkResult.text = "Checking…"
        thread {
            val result = runCatching { Gemini.check(s.geminiKey, s.geminiModel) }
            runOnUiThread {
                result.onSuccess { resolved ->
                    if (resolved != s.geminiModel) prefs.saveModel(resolved)
                    checkResult.text = "✓ The key works (model: $resolved)."
                }.onFailure { checkResult.text = Gemini.describe(it) }
            }
        }
    }
}
