package com.aireader.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The selection bar and keeping the reader's place, driven through the same bridge calls reader.js makes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h832dp-xxhdpi")
class ReadingPlaceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var controller: ActivityController<ReaderActivity>
    private lateinit var activity: ReaderActivity
    private lateinit var web: ReaderWebView
    private lateinit var bridge: Any
    private lateinit var bookId: String
    private val place = ReadingPosition(1, 0.5f, "He had become a clockmaker", 0)

    @Before
    fun setUp() {
        ReaderDb.reset()
        val file = File(context.cacheDir, "retrieval.epub")
        BookTest.fixture("retrieval.epub").copyTo(file, overwrite = true)
        bookId = Library(context).import(Uri.fromFile(file)).id
        Prefs(context).setPosition(bookId, place, 0.4f)
        controller = Robolectric.buildActivity(ReaderActivity::class.java, Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)).setup()
        activity = controller.get()
        settle()
        web = activity.findViewById(R.id.web)
        bridge = shadowOf(web).getJavascriptInterface("Android")
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
        ReaderDb.reset()
    }

    private fun settle() = repeat(5) {
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun call(name: String, vararg args: Any) {
        val method = bridge.javaClass.declaredMethods.first { it.name == name }
        method.isAccessible = true
        method.invoke(bridge, *args)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun pendingInit() = JSONObject(activity.javaClass.getDeclaredField("pendingInit").apply { isAccessible = true }.get(activity) as String)

    private fun capture(a: Activity, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val view = a.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/screens").mkdirs()
        File("build/screens/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun page(chapter: Int, p: Int) = call("onPage", p, 5, JSONObject().put("text", "page $p of chapter $chapter").put("occurrence", 0).toString(), 0L)

    @Test
    fun reopeningMarksWhereYouStopped() {
        val init = pendingInit()
        assertEquals("You stopped here", init.getJSONObject("resume").getString("label"))
        assertEquals(place.anchorText, init.getJSONObject("position").getString("text"))
        assertTrue(shadowOf(web).lastLoadedUrl.endsWith("/OPS/c1.xhtml"))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.backToPlace).visibility)
    }

    @Test
    fun selectionBarAvoidsTheSelectionAndDrivesIt() {
        val bar = activity.findViewById<View>(R.id.selectionBar)
        call("onSelectionChanged", true, 600, 640)
        assertEquals(View.VISIBLE, bar.visibility)
        assertEquals("a selection low on the page puts the bar at the top", Gravity.TOP, (bar.layoutParams as FrameLayout.LayoutParams).gravity)
        capture(activity, "reader-selection-bar")
        call("onSelectionChanged", true, 120, 160)
        assertEquals(Gravity.BOTTOM, (bar.layoutParams as FrameLayout.LayoutParams).gravity)

        activity.findViewById<View>(R.id.selSentence).performClick()
        assertEquals("reader.expandSelection('sentence')", shadowOf(web).lastEvaluatedJavascript)
        activity.findViewById<View>(R.id.selParagraph).performClick()
        assertEquals("reader.expandSelection('paragraph')", shadowOf(web).lastEvaluatedJavascript)
        activity.findViewById<View>(R.id.selRight).performClick()
        assertEquals("reader.nudgeSelection('end', 1)", shadowOf(web).lastEvaluatedJavascript)
        activity.findViewById<View>(R.id.selRight).performLongClick()
        assertEquals("reader.nudgeSelection('end', -1)", shadowOf(web).lastEvaluatedJavascript)
        activity.findViewById<View>(R.id.selLeft).performClick()
        assertEquals("reader.nudgeSelection('start', -1)", shadowOf(web).lastEvaluatedJavascript)
        activity.findViewById<View>(R.id.selExplain).performClick()
        assertEquals("actions read the selection", "reader.selectionInfo()", shadowOf(web).lastEvaluatedJavascript)

        call("onSelectionChanged", false, 0, 0)
        assertEquals(View.GONE, bar.visibility)
    }

    private fun jumpFromNavigator(chapter: Int, target: JSONObject?) {
        activity.findViewById<View>(R.id.toc).performClick()
        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals(NavigatorActivity::class.java.name, started.intent.component!!.className)
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, Intent()
            .putExtra(ReaderActivity.EXTRA_CHAPTER, chapter)
            .putExtra(ReaderActivity.EXTRA_TARGET, target?.toString()))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun jumpingAwayKeepsTheReadingPlace() {
        page(1, 2) // reading normally saves the place
        val reading = Prefs(context).position(bookId)
        assertEquals("page 2 of chapter 1", reading.anchorText)

        jumpFromNavigator(3, JSONObject().put("text", "letters for Jonas").put("occurrence", 0).put("flash", true))
        val back = activity.findViewById<View>(R.id.backToPlace)
        assertEquals(View.VISIBLE, back.visibility)
        capture(activity, "reader-back-to-place")

        // Looking around after the jump doesn't move the saved place…
        page(3, 0)
        page(3, 1)
        assertEquals(reading, Prefs(context).position(bookId))
        // …and the button goes back to it, marked.
        back.performClick()
        assertEquals(View.GONE, back.visibility)
        assertTrue(shadowOf(web).lastLoadedUrl.endsWith("/OPS/c1.xhtml"))
        assertEquals("page 2 of chapter 1", pendingInit().getJSONObject("position").getString("text"))
        assertTrue(pendingInit().has("resume"))
    }

    @Test
    fun readingOnAfterAJumpMakesItTheNewPlace() {
        page(1, 2)
        jumpFromNavigator(2, null)
        page(2, 0) // landing page
        page(2, 1)
        page(2, 2)
        assertEquals("still the old place after two turns", 1, Prefs(context).position(bookId).chapter)
        page(2, 3) // third turn: the reader has moved on
        assertEquals(2, Prefs(context).position(bookId).chapter)
        assertEquals("page 3 of chapter 2", Prefs(context).position(bookId).anchorText)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.backToPlace).visibility)
    }

    @Test
    fun closingTheButtonStaysHere() {
        page(1, 2)
        jumpFromNavigator(3, null)
        page(3, 0)
        activity.findViewById<View>(R.id.backToPlaceClose).performClick()
        assertEquals(View.GONE, activity.findViewById<View>(R.id.backToPlace).visibility)
        assertEquals(3, Prefs(context).position(bookId).chapter)
    }

    @Test
    fun navigatorListsWhereYouStoppedFirst() {
        val session = BookSession.get(bookId)!!
        val intent = Intent(context, NavigatorActivity::class.java).putExtra(NavigatorActivity.EXTRA_BOOK_ID, session.bookId)
        val nav = Robolectric.buildActivity(NavigatorActivity::class.java, intent).setup().get()
        nav.findViewById<View>(R.id.tabBookmarks).performClick()
        val list = nav.findViewById<ListView>(R.id.list)
        val first = list.adapter.getView(0, null, list)
        assertEquals("Where you stopped", first.findViewById<TextView>(R.id.itemTitle).text.toString())
        assertTrue(first.findViewById<TextView>(R.id.itemSubtitle).text.toString().contains("Letters from Hamburg"))
        capture(nav, "navigator-bookmarks")
        shadowOf(list).performItemClick(0)
        assertTrue(shadowOf(nav).resultIntent.getBooleanExtra(ReaderActivity.EXTRA_RESUME, false))
        assertFalse(shadowOf(nav).resultIntent.hasExtra(ReaderActivity.EXTRA_TARGET) && shadowOf(nav).resultIntent.getStringExtra(ReaderActivity.EXTRA_TARGET) != null)
    }
}
