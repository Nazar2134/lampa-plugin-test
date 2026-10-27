package app.taptranslate

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var apiKey: EditText
    private lateinit var keyStatus: TextView
    private lateinit var myLang: AutoCompleteTextView
    private lateinit var otherLang: AutoCompleteTextView
    private lateinit var model: RadioGroup
    private lateinit var baseUrl: EditText
    private lateinit var tapStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        setContentView(R.layout.activity_main)

        val content = findViewById<View>(R.id.content)
        val top = content.paddingTop
        val bottom = content.paddingBottom
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            content.setPadding(content.paddingLeft, top + insets.topBar(), content.paddingRight, bottom + insets.bottomBarsAndIme())
            insets
        }

        apiKey = findViewById(R.id.api_key)
        keyStatus = findViewById(R.id.key_status)
        myLang = findViewById(R.id.my_lang)
        otherLang = findViewById(R.id.other_lang)
        model = findViewById(R.id.model)
        baseUrl = findViewById(R.id.base_url)
        tapStatus = findViewById(R.id.tap_status)

        apiKey.setText(prefs.apiKey)
        myLang.setText(prefs.myLanguage)
        otherLang.setText(prefs.otherLanguage)
        baseUrl.setText(prefs.baseUrl)
        model.check(if (prefs.model == Prefs.MODEL_REASONER) R.id.model_reasoner else R.id.model_chat)
        for (field in listOf(myLang, otherLang)) {
            field.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, Languages.LIST))
            field.setOnClickListener { field.showDropDown() }
        }

        findViewById<Button>(R.id.get_key).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com/api_keys")))
        }
        findViewById<Button>(R.id.test_key).setOnClickListener { testKey() }
        findViewById<Button>(R.id.try_button).setOnClickListener {
            save()
            startActivity(
                Intent(this, TranslateActivity::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_TEXT, findViewById<EditText>(R.id.try_text).text.toString())
            )
        }
        findViewById<Button>(R.id.open_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.open_app_info).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        findViewById<Switch>(R.id.bubble_switch).apply {
            isChecked = prefs.bubbleEnabled
            setOnCheckedChangeListener { _, checked -> prefs.bubbleEnabled = checked }
        }
    }

    override fun onResume() {
        super.onResume()
        val on = isTapServiceEnabled()
        tapStatus.setText(if (on) R.string.tap_on else R.string.tap_off)
        tapStatus.setTextColor(getColor(if (on) R.color.ok else R.color.text_muted))
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun save() {
        prefs.apiKey = apiKey.text.toString()
        prefs.myLanguage = myLang.text.toString()
        prefs.otherLanguage = otherLang.text.toString()
        prefs.baseUrl = baseUrl.text.toString()
        prefs.model = if (model.checkedRadioButtonId == R.id.model_reasoner) Prefs.MODEL_REASONER else Prefs.MODEL_CHAT
    }

    private fun testKey() {
        save()
        val key = prefs.apiKey
        if (key.isEmpty()) {
            showKeyStatus("Paste your API key first.", ok = false)
            return
        }
        showKeyStatus("Checking…", ok = null)
        val url = prefs.baseUrl
        Thread {
            val result = runCatching { DeepSeekClient(url, key).balance() }
            runOnUiThread {
                result.fold(
                    onSuccess = { showKeyStatus("✓ Key works · $it", ok = true) },
                    onFailure = { showKeyStatus(it.message ?: "Could not reach DeepSeek.", ok = false) },
                )
            }
        }.start()
    }

    private fun showKeyStatus(message: String, ok: Boolean?) {
        keyStatus.text = message
        keyStatus.setTextColor(
            getColor(
                when (ok) {
                    true -> R.color.ok
                    false -> R.color.error
                    null -> R.color.text_muted
                }
            )
        )
    }

    private fun isTapServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(this, TapService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}
