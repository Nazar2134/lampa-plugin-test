# Reading Helper (Android)

Select a word or sentence in PocketBook (or any reader), tap **Explain**, and a popup
slides up with a translation and a grammar explanation from Claude. You can ask
follow-up questions in the same popup ("why is it *dem* and not *den*?").

## Three ways to send text

1. **Text-selection menu** – apps that use Android's standard selection toolbar
   show an **Explain** item (on Samsung, sometimes under the `⋮` overflow).
2. **Share sheet** – select text → **Share** → **Explain**. Long-press the
   Explain icon in Samsung's share sheet and pin it to keep it at the front.
3. **Quick Settings tile** – for readers with their own selection menu: copy
   the text, swipe down, tap **Explain copied text**. Add the tile from the app's
   settings screen.

## Setup

1. Install the APK (allow "Install unknown apps" for your browser or Files app
   when Android asks).
2. Open **Reading Helper**, paste a Claude API key from
   <https://console.anthropic.com/settings/keys>, set the language you want
   explanations in, and tap **Save**.
3. Tap **Explain this** under *Try it* to check everything works.

The API key is stored only on the phone, in the app's private storage.

## Models

| Setting | Model | Notes |
|---|---|---|
| Default | `claude-opus-5-5` | Best explanations |
| Faster | `claude-sonnet-5-5` | |
| Fastest | `claude-haiku-5-5` | Cheapest |

"Brief" uses `low` effort and "Detailed" uses `medium`. Opus and Sonnet requests
enable server-side refusal fallbacks (`fallbacks: "default"`).

## Building

Requires JDK 17+ and the Android SDK (platform 35).

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

Release builds are signed with the local debug key, so an APK built on a
different machine can't install over an existing one. Uninstall first.
