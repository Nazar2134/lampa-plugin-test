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
  Ask AI, ChatGPT, Listen and Copy.
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

## Learning mode (English, German and other languages)

Turn it on under **Aa → Learning → Tap words**.

- **Tap a word** for a card with its meaning (other taps work as before):
  - the dictionary form; German nouns get their article in colour (der blue, die red, das green);
  - part of speech, plural, and the principal parts of verbs ("geht, ging, ist gegangen");
  - a translation into your answer language, and what it means in this sentence;
  - the whole unit the word belongs to, such as a phrasal verb, an idiom, or a German separable verb
    ("fing … an" → anfangen);
  - a short note when useful (a false friend, the case a verb takes).
- **Look-ups are cached per word form.** A word looked up before shows instantly with no request. If it was
  looked up in a different sentence, the card says so and offers to check this one.
- **Card buttons:**
  - 🔊 says the word (long-press: the whole sentence);
  - **☆ Learning** marks the word and saves it to your vocabulary with its translation;
  - **✓ Known** marks it known;
  - **More…** opens the full Vocabulary answer in the AI panel (and asks for a key there if none is saved).
- **Word statuses** are new, learning or known, kept per language:
  - words you're learning are tinted in the text;
  - **Mark new** also underlines words you haven't looked at;
  - forms are grouped under their dictionary form once looked up, so marking "Haus" also covers "Häusern".
- **The status line** shows the page's new words and how much of it is familiar, e.g.
  "6 new words · 94% familiar". Tap it to tick off the new words you already know and mark them known in one go.
- A word reviewed to "known" in the vocabulary becomes known in your books too.
- The book's language comes from its metadata, or is guessed from its text (English, German, Ukrainian).

**Listening** uses the phone's text-to-speech voices (for German, install the German voice under
Settings → Text-to-speech if it's missing; the app offers to open that).
- The **headphones** icon in the toolbar reads aloud from the top of the page. The sentence being read is
  highlighted, pages turn with it, and it carries on into the next chapter. **■ Stop reading aloud** stops it.
- **Listen** in the selection bar says the selected text.
- **Aa → Voice** sets the speed (0.5–2.0×).

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
| Bookmarks, highlights and notes, vocabulary, book chats, answer cache (400 entries), AI usage | SQLite `reader.db` (`ReaderDb`) |
| Word statuses (`words`) and word look-ups (`lexicon`), per language | SQLite `reader.db`, added in schema version 2 |

Schema changes are additive migrations: upgrading from version 1 only adds the two learning tables and keeps
everything else.

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
| `LearningTest` (Robolectric) | Word look-ups against a mock DeepSeek (prompt, card, cache, "another sentence", missing or rejected key), statuses and vocabulary, the status line, statuses sent with each chapter, pronunciation and reading aloud, a missing voice, the upgrade from database version 1 |
| `ReadingPlaceTest` (Robolectric) | Selection bar placement and buttons; keeping the reading place across jumps, going back, reading on, "Where you stopped" |
| `PreviewExport` | Writes real chapter pages to `app/build/reader-preview` for the browser check of `reader.js`: pagination, finger drags, flicks, long-press and selection guards, selection tools, the resume marker, anchors, highlights, bookmarks, search jumps, hostile book content, tablet layout, and learning mode (word taps that don't turn the page, margin taps that still do, status tints, per-page counts, sentences for reading aloud) |

Release builds are signed with the local debug key, so an APK built on a different machine can't install
over an existing one. Uninstall first.
