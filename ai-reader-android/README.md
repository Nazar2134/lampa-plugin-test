# AI Reader (Android)

An EPUB and FB2 e-book reader with an AI reading companion built in.

- **Select text → Explain / Translate / Ask AI.** A panel slides up over the page
  with a translation and grammar explanation (or an answer to your question) and
  takes follow-up questions. The AI sees the surrounding paragraph and the book's
  title, author and language.
- **✨ button in the toolbar** asks about the page you're on ("who is this
  character?", "what just happened?").
- **Answers come from Google Gemini's free tier**, using a free key from
  <https://aistudio.google.com/apikey> (no card needed; it has daily limits).
- **Ask Claude ↗ / Ask ChatGPT ↗** in the panel open those apps (or their
  websites) with the question already written, using your own free account.

## Reading

- Tap the right or left third of the page (or swipe, or use the volume keys) to
  turn pages. Tap the middle for the toolbar.
- **Aa** sets font size, serif/sans and the Light, Sepia or Dark theme.
- The list icon opens the table of contents. Footnote links work, and Back
  returns you to where you were.
- Your position is saved per book.

Add books with **+** in the library, or use "Open with → AI Reader" from a file
manager. FB2 files can be plain or zipped (`.fb2.zip`), in any encoding.

## Building

Requires JDK 17+ and the Android SDK (platform 35).

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew testReleaseUnitTest assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

The unit tests parse sample books in `app/src/test/resources`, check the Gemini
client against a local mock server, and export rendered chapters to
`app/build/reader-preview` for checking pagination in a browser.

Release builds are signed with the local debug key, so an APK built on a
different machine can't install over an existing one. Uninstall first.
