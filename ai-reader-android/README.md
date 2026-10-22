# AI Reader (Android)

An EPUB and FB2 e-book reader with a book-aware AI reading companion.

## Reading

- **Pages:** drag with your finger and the page follows; let go past about a fifth of the screen (or
  flick) to turn, otherwise it slides back. Quick taps on the outer quarters also turn pages; tap the
  middle for the toolbar. Volume keys and a keyboard (arrows, Page Up/Down, Space) work too. To avoid
  accidental turns:
  - presses longer than a tap don't turn the page;
  - drags that start after a long press don't turn it;
  - nothing turns the page while text is selected;
  - anything else that would scroll the page (such as selection handles dragged to the edge) is undone.
- **Where you stopped** is kept automatically; there is no need to bookmark it:
  - reopening a book marks the spot ("You stopped here" in the top margin, a bar beside the line, and the
    first words briefly tinted);
  - after jumping away (contents, search, a footnote, a saved word), **↩ Back to where you stopped**
    returns there, and the saved place isn't moved while you look around;
  - reading on for three pages, or tapping × on that button, makes the new spot your place;
  - "Where you stopped" is also the first entry under Bookmarks.
- **Selecting text:** long-press a word, then use the bar that appears (placed away from the selection)
  instead of dragging handles: **Word / Sentence / Paragraph**, or **‹ +** and **+ ›** to add a word on
  either side (long-press them to remove one). The same bar has Explain, Translate, Highlight, Save word,
  Ask AI and Copy.
- **Aa:** font size, font (serif, sans, or the book's own), line spacing, margins, justified or
  left-aligned text, and Light, Sepia or Dark themes. Wide screens (840 dp and up, for example tablets
  in landscape) get two columns; on tablets in portrait the line length is capped.
- **Bookmark icon:** bookmarks the current page. The icon is filled when the page has a bookmark.
- **List icon:** opens Contents, Bookmarks, Notes (highlights and their notes) and **Search**, which
  searches the whole book. Picking a result opens the page and briefly flashes the match. Back returns
  to where you were.
- **Highlight** (from the selection bar or menu): four colours, with an optional note. Tap a highlight to edit it.
- **Position:** saved per book as a short text anchor, so the same spot reopens even after the font or
  margins change. Progress is weighted by chapter length.

## AI companion

Select text and choose **Explain**, **Translate**, **Save word** or **Ask AI**. The panel that opens
has an action row:

| Action | What it does |
|---|---|
| Explain | Meaning in context, implications, references, difficult words |
| Translate | Translation that keeps tone, idioms and style, plus short notes |
| Vocabulary | Meaning here vs. other dictionary meanings, translation, lemma, examples, synonyms, register |
| Grammar | Verb forms, cases, word order, idioms; a one-line translation first |
| Simplify | Rewrites the passage in the book's language at a chosen CEFR level (A1–C2) |
| Ask | Your own question about the selection |

- **Answer language:** "In English ▾" switches between English, Ukrainian, German or any other language.
- **Follow-up questions** keep the conversation.
- **↻** asks again; repeated one-shot answers come from a local cache.
- **☆ Save word** adds the selection to your vocabulary.
- **✨ in the toolbar** opens a chat about the book ("Who is Jonas?", "Why did she go to the shed?"),
  saved per book. **New chat** starts over.
- **Answers stay in the reader**, as a chat: your messages on the right, answers on the left.
  - Tap the handle at the top to expand the chat to full screen.
  - If no key is saved yet, or a key is rejected, the panel asks for one right there (DeepSeek or free
    Gemini) and then answers.
  - Items that other apps add to the text-selection menu (ChatGPT, Claude, translators) are hidden in the
    reader, so selecting text never jumps to another app.
- **ChatGPT and Claude, inside the reader:** **ChatGPT** in the selection bar, and **Ask ChatGPT** /
  **Ask Claude** in the panel, open chatgpt.com or claude.ai in a window over the book (a Chrome Custom Tab
  shown as a bottom sheet, Chrome 107+; other browsers show it full screen).
  - The question is filled in and also copied to the clipboard.
  - It uses your own account in Chrome (sign in once there).
  - Swipe the window down to return to the page; nothing switches to the ChatGPT or Claude app.

### How the AI sees the book

The book is never sent as a whole. When a book opens, its text is extracted into chapters and
paragraphs (`BookText`) and indexed in memory for BM25 search (`BookIndex`; words are matched on a short
stem so inflected Ukrainian and German forms match). Each request carries only:

- the selection, its sentence, and its paragraph (± neighbours for Explain and Ask);
- for questions, the best-matching passages (and their neighbours) **from the part of the book already
  read**, so answers can't spoil later chapters; follow-ups add only passages not sent before;
- for the book chat, the last ~1,800 characters read.

Excerpts are labelled `[Chapter 3 “The Shed”, ¶2–4]`. The system prompt tells the model to cite
chapters, mark interpretations, say when the excerpts don't settle a question, and never invent
quotations or plot details.

## AI providers

Settings → *Answers inside the reader*:

- **Google Gemini** (free tier). Get a key at <https://aistudio.google.com/apikey>. The default model is
  the `gemini-flash-latest` alias. If Google renames a model, the app looks up the current Flash or
  Flash-Lite model and retries once.
- **DeepSeek** (your API key, pay per use). Create a key at <https://platform.deepseek.com/api_keys>,
  paste it in, choose **DeepSeek Flash** (`deepseek-flash`, thinking off, fastest and cheapest) or
  **DeepSeek V4 Pro** (`deepseek-v4-pro`, brief thinking), and tap **Save and test the key**. The test
  also shows your balance. Model names were checked against DeepSeek's documentation in October 2026.

There is no server: keys are entered on the phone and stored in the app's private preferences,
encrypted with an Android Keystore key. Settings shows this month's requests and token counts, plus a
DeepSeek cost ceiling at peak prices. Rate limits and server errors are retried once, and only before any
text has arrived. Errors (bad key, empty balance, quota, network) appear in the panel with what to do.

## Data

| What | Where |
|---|---|
| Books and covers | `files/books`, `files/covers`, listed in `files/library.json` |
| Settings, reading positions, progress | SharedPreferences `settings` |
| Bookmarks, highlights and notes, vocabulary, book chats, answer cache (400 entries), AI usage | SQLite `reader.db` (`ReaderDb`, schema version 1; future changes must be additive migrations) |

Removing a book deletes its file, cover, position, bookmarks, highlights and chat. Its vocabulary entries
are kept, detached from the book but still showing its title.

## Security

Book files are treated as untrusted:
- Chapters are served to the WebView with a Content-Security-Policy (header plus a meta tag placed before
  any book content). Only the reader's own scripts run, using a per-page nonce, and nothing loads from
  outside the book. Book scripts can't reach the app's JavaScript bridge.
- Links leave the app only for http(s) and mailto.
- Files are capped at 200 MB, archive entries at 64 MB, and FB2 text at 120 MB.
- Archive entries are read by name and never extracted to disk.

## Building and testing

Requires JDK 17+ and the Android SDK (platform 35).

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew testReleaseUnitTest assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

All AI tests use a local mock server; none make paid calls.

| Tests | Cover |
|---|---|
| `BookTest`, `RetrievalTest` | EPUB/FB2 parsing, text extraction, search, BM25 retrieval across chapters (including the spoiler guard), prompts |
| `GeminiTest`, `DeepSeekTest`, `LogicTest` | Request bodies, streaming, usage, error mapping, the single retry, CSP placement, typography, spaced-repetition schedule |
| `PersistenceTest` (Robolectric) | Database and settings across restarts, migration from v1 settings, library import, dedupe, removal and cleanup |
| `AiPanelTest` (Robolectric) | The AI panel end to end: context sent per action, earlier-chapter retrieval, caching, saved chat, provider errors, model renames |
| `ScreenshotTest` (Robolectric) | Renders the native screens to `app/build/screens` and checks their controls |
| `ReadingPlaceTest` (Robolectric) | Selection bar placement and buttons; keeping the reading place across jumps, going back, reading on, "Where you stopped" |
| `PreviewExport` | Writes real chapter pages to `app/build/reader-preview` for the browser check of `reader.js`: pagination, finger drags, flicks, long-press and selection guards, selection tools, the resume marker, anchors, highlights, bookmarks, search jumps, hostile book content, tablet layout |

Release builds are signed with the local debug key, so an APK built on a different machine can't install
over an existing one. Uninstall first.
