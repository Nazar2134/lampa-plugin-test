# Tap Translate (Android)

Translate any word or sentence in **any app** on your phone with the DeepSeek API. You get the
translation, pronunciation, an explanation and example sentences in a popup that slides up over the app
you're using. Built for the Samsung Galaxy S24 (One UI, Android 14+); works on Android 10+.

## Three ways to translate

| How | Works in |
| --- | --- |
| **Select text → "AI Translate"** in the selection menu (on Samsung, tap ⋮ in the menu if you don't see it) | Any app where you can select text: Chrome, Samsung Internet, Telegram, PDF readers, … |
| **Floating button** (Full version only): tap it, then tap any word on screen | Also text you *can't* select: buttons, menus, app UIs, many games' menus |
| **Copy → "Translate clipboard" Quick Settings tile**, or **Share → AI Translate** | Everywhere else |

Inside the popup:
- Tap any word of the original text to look up just that word, using the sentence around it as context.
- Tap the language row (`English → Ukrainian ▾`) to translate into a different language.
- 🔊 speaks the original (long-press: the translation). The copy icon copies the translation (long-press: everything).
- **Replace** swaps the selected text for its translation, if the app lets you edit it (handy for writing messages in another language).

## Two versions

| APK | What you get | Install |
| --- | --- | --- |
| **`app-lite-release.apk`** (start here) | Selection menu, Share, clipboard tile | Installs normally |
| `app-full-release.apk` | All of Lite, plus the floating tap-any-word button | Play Protect blocks it (see below) |

Google Play Protect blocks every app installed from a file that declares an accessibility service
("App blocked to protect your device… can request access to sensitive data"), whatever the app does.
Lite has no accessibility service, so it installs normally. Both versions use the same package and key,
so you can install Full over Lite later and keep your settings.

## Install on your phone

1. Download `app-lite-release.apk` (from the chat, or from this repo's **Actions** tab: open the latest
   "Tap Translate APK" run and download *TapTranslate-apk*).
2. Open it on the phone. Samsung will ask to allow installing from this source (My Files / Chrome): allow it.
   If **Auto Blocker** stops it, turn it off for a moment: *Settings → Security and privacy → Auto Blocker*.
3. Open **Tap Translate**, paste your DeepSeek API key (from https://platform.deepseek.com/api_keys) and tap
   **Save & test**. It shows your balance if the key works.
4. Set the language to translate into (it defaults to your phone's language).

### Full version: the floating button
Play Protect's block has no "Install anyway" button, so pick one of these:
- **Pause Play Protect for the install:** Play Store → your profile picture → *Play Protect* → ⚙ →
  turn off *Scan apps with Play Protect*. Install `app-full-release.apk`, then turn scanning back on.
- **Or install from a computer** with USB debugging on: `adb install -r app-full-release.apk`.

Then turn the button on:
1. In the app, tap **Turn on tap-to-translate** → *Installed apps* → **Tap Translate – tap any word** → on.
2. If Android says **"Restricted setting"** (normal for apps installed from an APK): open
   *Settings → Apps → Tap Translate → ⋮ (top right) → Allow restricted settings*, then repeat step 1.
3. Drag the button to wherever it's comfortable; it snaps to the screen edge.

The accessibility permission is used only to read the text under your finger, and only right after you
tap the button. No events are collected in the background, and the text goes nowhere except to DeepSeek.

## How it keeps lookups fast and cheap
- Responses are **streamed**, so the translation (the first line) appears while the rest is being written.
- One request per lookup with a compact, fixed system prompt, which DeepSeek's automatic context caching reuses.
- The last 300 results are **cached** on the phone, so repeat lookups are instant and free.
- `deepseek-chat` is the default. `deepseek-reasoner` is available in settings, but it is slower.

## Build it yourself
Requires JDK 17+ and the Android SDK (platform 35).

```bash
cd taptranslate-android
./gradlew testLiteReleaseUnitTest testFullReleaseUnitTest assembleRelease
# → app/build/outputs/apk/lite/release/app-lite-release.apk
# → app/build/outputs/apk/full/release/app-full-release.apk
```

The APK is signed with `app/sideload.keystore`, a throwaway key with the standard public Android debug
password (`android`), so every build installs over the previous one. It is not a secret. Use your own key
if you ever publish the app.

## Project layout
```
app/src/main/java/app/taptranslate/
  TranslateActivity.kt   popup: selection menu, share, tap and clipboard entry points; streaming UI
  DeepSeekClient.kt      streaming chat-completions client (SSE) and balance check
  Prompt.kt              dictionary-style prompt (translation, pronunciation, meaning, examples, notes)
  MiniMarkdown.kt        renders the model's Markdown into styled text, even while it is streaming
  ClipboardTileService.kt Quick Settings tile
  MainActivity.kt        setup and settings screen
  ResultCache.kt         persistent LRU cache of answers
app/src/full/            full version only: the accessibility service
  java/.../TapService.kt   floating button and the "tap a word" layer
  java/.../TextLocator.kt  finds the exact word under a screen point via per-character bounds
```
