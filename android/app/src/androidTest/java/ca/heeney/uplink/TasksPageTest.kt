package ca.heeney.uplink

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The Tasks tab in the real WebView: the three views, the task sheet and the back key. */
@RunWith(AndroidJUnit4::class)
class TasksPageTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Test fun viewsSheetAndBackKey() {
        seed()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        until("the switch") { js("document.querySelectorAll('#views .switch button').length") == "3" }

        // Upcoming: sections in order, each with the task that belongs there.
        until("upcoming") { js("document.querySelectorAll('#view .row').length") == "5" }
        assertEquals("Late|Today|Tomorrow|Anytime", js("[...document.querySelectorAll('#view h2.group')].map(h => h.textContent.trim()).join('|')"))
        assertEquals("Return library book", js("document.querySelector('#view section .row .t').textContent"))
        shot("1-upcoming")

        click("[data-view=categories]")
        until("categories") { js("[...document.querySelectorAll('#view h2.group')].map(h => h.textContent.trim()).join('|')") == "Work2|Chores1|Fun1|Other1" }
        shot("2-categories")

        click("[data-view=lists]")
        until("lists") { js("document.querySelector('[data-open=hw]') != null") == "true" }
        shot("3-lists")
        click("[data-open=hw]")
        until("the list page") { js("document.getElementById('title').textContent") == "Homework" }
        assertEquals("2", js("document.querySelectorAll('#view .row').length"))
        shot("4-list")

        // Back steps out of the list and stays in the app.
        back()
        until("back to Lists") { js("document.getElementById('title').textContent") == "Tasks" }
        assertEquals("true", js("document.querySelector('[data-view=lists]').classList.contains('on')"))
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
        shot("5-after-back")

        // The task sheet: quick dates and the category the hub picked.
        click("[data-item=t-today] .t")
        until("the sheet") { js("document.getElementById('sheet').open") == "true" }
        assertEquals("Today|Tomorrow|Weekend|Next week", js("[...document.querySelectorAll('#sheet [data-seg=quick] button')].map(b => b.textContent.trim()).join('|')"))
        assertEquals("Auto: Work", js("document.querySelector('#sheet select[name=category] option').textContent"))
        shot("6-sheet")

        // Back closes the sheet, then, with nothing left to close, leaves the app.
        back()
        until("the sheet to close") { js("document.getElementById('sheet').open") == "false" }
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
        click("[data-view=upcoming]")
        until("upcoming again") { js("document.querySelectorAll('#view h2.group').length") == "4" }
        back()
        until("the app to close") { scenario.state == Lifecycle.State.DESTROYED }
    }

    private fun seed() {
        val day = LocalDate.now()
        val at = System.currentTimeMillis() / 1000
        fun rec(kind: String, uid: String, data: JSONObject) =
            JSONObject().put("kind", kind).put("uid", uid).put("updated_at", at).put("data", data)
        fun task(uid: String, title: String, list: String, due: LocalDate?, category: String?) = rec("item", uid,
            JSONObject().put("type", "task").put("title", title).put("list", list).put("created_at", at).apply {
                if (due != null) put("due", due.toString())
                if (category != null) put("category", category).put("category_auto", "seeded")
            })
        val recs = JSONArray()
            .put(rec("list", "in", JSONObject().put("name", "Inbox").put("inbox", true).put("sort", 0)))
            .put(rec("list", "hw", JSONObject().put("name", "Homework").put("sort", 1)))
            .put(task("t-late", "Return library book", "in", day.minusDays(1), "chores"))
            .put(task("t-today", "Essay draft", "hw", day, "work"))
            .put(task("t-tomorrow", "Physics quiz prep", "hw", day.plusDays(1), "work"))
            .put(task("t-any", "Movie night", "in", null, "fun"))
            .put(task("t-unsorted", "Thing", "in", null, null))
        Store.pageSync(inst.targetContext, JSONObject().put("since", 0).put("records", recs).toString())
    }

    private fun web(v: View): WebView? = when (v) {
        is WebView -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { web(v.getChildAt(it)) }
        else -> null
    }

    /** The value of a JavaScript expression in the page, as text. */
    private fun js(expr: String): String {
        val done = CountDownLatch(1)
        var out = "null"
        scenario.onActivity { a ->
            web(a.findViewById(android.R.id.content))!!
                .evaluateJavascript("(function(){try{return $expr}catch(e){return 'error: '+e}})()") { out = it ?: "null"; done.countDown() }
        }
        assertTrue("the page didn't answer", done.await(10, TimeUnit.SECONDS))
        return JSONTokener(out).nextValue().toString()
    }

    private fun click(css: String) = assertEquals("clicking $css", "true",
        js("(function(){var e=document.querySelector(${JSONObject.quote(css)});if(!e)return false;e.click();return true})()"))

    private fun back() = inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

    private fun until(what: String, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) {
            if (ok()) return
            Thread.sleep(150)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /** A screenshot into Gradle's additional test output (build/outputs/connected_android_test_additional_output). */
    private fun shot(name: String) {
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        Thread.sleep(1500)  // a software-rendered emulator takes a while to put the frame up
        val bmp = inst.uiAutomation.takeScreenshot() ?: return
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
