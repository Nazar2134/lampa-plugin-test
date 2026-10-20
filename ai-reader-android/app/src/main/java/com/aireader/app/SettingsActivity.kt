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
    private lateinit var provider: RadioGroup
    private lateinit var geminiKey: EditText
    private lateinit var geminiModel: RadioGroup
    private lateinit var deepseekKey: EditText
    private lateinit var deepseekModel: RadioGroup
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
        provider = findViewById(R.id.provider)
        geminiKey = findViewById(R.id.geminiKey)
        geminiModel = findViewById(R.id.geminiModel)
        deepseekKey = findViewById(R.id.deepseekKey)
        deepseekModel = findViewById(R.id.deepseekModel)
        checkResult = findViewById(R.id.checkResult)

        val s = prefs.load()
        language.setText(s.language)
        detail.check(if (s.detailed) R.id.detailFull else R.id.detailBrief)
        provider.check(if (s.provider == AiProvider.DEEPSEEK) R.id.providerDeepseek else R.id.providerGemini)
        geminiKey.setText(s.geminiKey)
        geminiModel.check(if ("lite" in s.geminiModel) R.id.geminiLite else R.id.geminiFlash)
        deepseekKey.setText(s.deepseekKey)
        deepseekModel.check(if (s.deepseekModel == DeepSeek.MODEL_PRO) R.id.deepseekPro else R.id.deepseekFlash)
        showProviderSection()
        provider.setOnCheckedChangeListener { _, _ ->
            checkResult.visibility = View.GONE
            showProviderSection()
        }

        findViewById<View>(R.id.getGeminiKey).setOnClickListener { openUrl("https://aistudio.google.com/apikey") }
        findViewById<View>(R.id.getDeepseekKey).setOnClickListener { openUrl("https://platform.deepseek.com/api_keys") }
        findViewById<Button>(R.id.check).setOnClickListener { checkKey() }
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    private fun selectedProvider() =
        if (provider.checkedRadioButtonId == R.id.providerDeepseek) AiProvider.DEEPSEEK else AiProvider.GEMINI

    private fun showProviderSection() {
        val deepseek = selectedProvider() == AiProvider.DEEPSEEK
        findViewById<View>(R.id.geminiSection).visibility = if (deepseek) View.GONE else View.VISIBLE
        findViewById<View>(R.id.deepseekSection).visibility = if (deepseek) View.VISIBLE else View.GONE
    }

    private fun openUrl(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun save() {
        val current = prefs.load()
        val wantsLite = geminiModel.checkedRadioButtonId == R.id.geminiLite
        // Keep a Gemini model name resolved earlier if it is still the same kind (Flash vs Flash-Lite).
        val gemini = if (("lite" in current.geminiModel) == wantsLite) current.geminiModel else if (wantsLite) Gemini.MODEL_LITE else Gemini.MODEL_FLASH
        prefs.save(
            current.copy(
                language = language.text.toString().ifBlank { "English" },
                detailed = detail.checkedRadioButtonId == R.id.detailFull,
                provider = selectedProvider(),
                geminiKey = geminiKey.text.toString(),
                geminiModel = gemini,
                deepseekKey = deepseekKey.text.toString(),
                deepseekModel = if (deepseekModel.checkedRadioButtonId == R.id.deepseekPro) DeepSeek.MODEL_PRO else DeepSeek.MODEL_FLASH,
            )
        )
    }

    private fun checkKey() {
        save()
        val s = prefs.load()
        checkResult.visibility = View.VISIBLE
        if (Ai.key(s).isBlank()) {
            checkResult.text = "Paste a key first."
            return
        }
        checkResult.text = "Checking…"
        thread {
            val result = runCatching {
                when (s.provider) {
                    AiProvider.GEMINI -> {
                        val model = Gemini.check(s.geminiKey, s.geminiModel)
                        if (model != s.geminiModel) prefs.saveGeminiModel(model)
                        "✓ The key works (model: $model)."
                    }
                    AiProvider.DEEPSEEK -> {
                        val check = DeepSeek.check(s.deepseekKey, s.deepseekModel)
                        if (check.model != s.deepseekModel) prefs.saveDeepseekModel(check.model)
                        "✓ The key works (model: ${check.model})." + (check.balance?.let { " Balance: $it." } ?: "")
                    }
                }
            }
            runOnUiThread {
                checkResult.text = result.getOrElse { Ai.describe(s.provider, it) }
            }
        }
    }
}
