package com.readinghelper.app

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var apiKey: EditText
    private lateinit var language: EditText
    private lateinit var model: RadioGroup
    private lateinit var detail: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        apiKey = findViewById(R.id.apiKey)
        language = findViewById(R.id.language)
        model = findViewById(R.id.model)
        detail = findViewById(R.id.detail)

        val settings = Prefs(this).load()
        apiKey.setText(settings.apiKey)
        language.setText(settings.language)
        model.check(
            when (settings.model) {
                Claude.MODEL_SONNET -> R.id.modelSonnet
                Claude.MODEL_HAIKU -> R.id.modelHaiku
                else -> R.id.modelOpus
            }
        )
        detail.check(if (settings.detailed) R.id.detailFull else R.id.detailBrief)

        findViewById<View>(R.id.getKey).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys")))
        }
        findViewById<Button>(R.id.save).setOnClickListener {
            save()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.tryIt).setOnClickListener {
            save()
            val sample = findViewById<EditText>(R.id.sample).text.toString()
            startActivity(
                Intent(this, ExplainActivity::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, sample)
            )
        }
        findViewById<Button>(R.id.addTile).setOnClickListener { requestTile() }
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    private fun save() {
        Prefs(this).save(
            Settings(
                apiKey = apiKey.text.toString(),
                language = language.text.toString().ifBlank { "English" },
                model = when (model.checkedRadioButtonId) {
                    R.id.modelSonnet -> Claude.MODEL_SONNET
                    R.id.modelHaiku -> Claude.MODEL_HAIKU
                    else -> Claude.MODEL_OPUS
                },
                detailed = detail.checkedRadioButtonId == R.id.detailFull,
            )
        )
    }

    private fun requestTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Swipe down Quick Settings, tap the pencil and drag in “Explain copied text”.", Toast.LENGTH_LONG).show()
            return
        }
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, ExplainTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_tile),
            mainExecutor,
        ) { result ->
            val message = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tile added"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "The tile is already in Quick Settings"
                else -> null
            }
            if (message != null) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
