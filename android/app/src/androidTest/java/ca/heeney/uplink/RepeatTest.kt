package ca.heeney.uplink

import android.Manifest
import android.app.NotificationManager
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Repeating tasks: the times a rule gives, the phone ringing at each one without the task
 *  being ticked off, and ticking it off in the page moving it to its next time. */
@RunWith(AndroidJUnit4::class)
class RepeatTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext

    /** The tests share the app's data with TasksPageTest, so leave none of it behind. */
    @After fun clean() {
        for (uid in listOf("rep-water", "rep-vit")) Store.get("item", uid)?.let { Store.put("item", uid, it.data, deleted = true) }
        ctx.getSystemService(NotificationManager::class.java).cancelAll()
        Reminders.refresh(ctx)
    }

    @Test fun ruleTimes() {
        val zone = ZoneId.systemDefault()
        val tue = LocalDate.of(2026, 11, 17).atTime(16, 0).atZone(zone).toEpochSecond()
        val r = Repeat.parse("FREQ=WEEKLY;BYDAY=TU,TH")!!
        // Tue 16:00, Thu 16:00, Tue 16:00 ...
        assertEquals(listOf(tue, tue + 2 * 86400, tue + 7 * 86400), r.walk(tue).take(3).map { it.second }.toList())
        // Before the first: ring at it. Once it's up: ring now. After it rang: the next one.
        assertEquals(tue, r.nextRing(tue, 0, false, tue - 3600))
        assertEquals(tue + 10, r.nextRing(tue, 0, false, tue + 10))
        assertEquals(tue + 2 * 86400, r.nextRing(tue, tue, false, tue + 10))
        // Ticked off early: that one is covered, the next one rings.
        assertEquals(tue + 2 * 86400, r.nextRing(tue, 0, true, tue - 3600))
        // Every 11.46 hours, and an end.
        assertEquals(tue + 688 * 60, Repeat.parse("FREQ=MINUTELY;INTERVAL=688")!!.walk(tue).drop(1).first().second)
        assertEquals(3, Repeat.parse("FREQ=DAILY;COUNT=3")!!.walk(tue).count())
    }

    @Test fun ringsAtEachTime() {
        inst.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        Store.init(ctx)
        val first = System.currentTimeMillis() / 1000 + 5
        seed(task("rep-water", "Drink water", JSONObject().put("remind_at", first).put("repeat", "FREQ=MINUTELY")))
        Reminders.refresh(ctx)
        until("the first ring", 30) { fired("rep-water") == first }
        assertTrue("a notification", notified("Drink water"))
        ctx.getSystemService(NotificationManager::class.java).cancelAll()
        // Not ticked off, and it rings again a minute later.
        until("the second ring", 90) { fired("rep-water") == first + 60 }
        assertTrue("a second notification", notified("Drink water"))
        assertTrue("still open", Store.get("item", "rep-water")!!.data.isNull("done_at"))
    }

    @Test fun tickingMovesItOn() {
        val zone = ZoneId.systemDefault()
        val at = LocalDate.now().plusDays(1).atTime(8, 0).atZone(zone).toEpochSecond()
        seed(task("rep-vit", "Take vitamins", JSONObject().put("remind_at", at).put("repeat", "FREQ=DAILY;COUNT=7")))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        until("the task", 20) { js(scenario, "document.querySelector('[data-check=rep-vit]') != null") == "true" }
        assertEquals("Every day, 7 left", js(scenario, "[...document.querySelectorAll('[data-item=rep-vit] .meta span')].pop().textContent"))
        js(scenario, "document.querySelector('[data-check=rep-vit]').click()")
        until("it to move on", 10) { Store.get("item", "rep-vit")!!.data.optLong("remind_at") == at + 86400 }
        val d = Store.get("item", "rep-vit")!!.data
        assertTrue("still open", d.isNull("done_at"))
        assertEquals("FREQ=DAILY;COUNT=6", d.optString("repeat"))
        scenario.close()
    }

    private fun task(uid: String, title: String, extra: JSONObject) = JSONObject().put("kind", "item").put("uid", uid)
        .put("updated_at", System.currentTimeMillis())
        .put("data", JSONObject(extra.toString()).put("type", "task").put("title", title).put("list", "in"))

    private fun seed(vararg recs: JSONObject) {
        val all = JSONArray().put(JSONObject().put("kind", "list").put("uid", "in").put("updated_at", System.currentTimeMillis())
            .put("data", JSONObject().put("name", "Inbox").put("inbox", true)))
        recs.forEach { all.put(it) }
        Store.pageSync(ctx, JSONObject().put("since", 0).put("records", all).toString())
    }

    private fun fired(uid: String) = Store.get("item", uid)?.data?.optLong("fired_at") ?: 0L

    private fun notified(title: String) = ctx.getSystemService(NotificationManager::class.java).activeNotifications
        .any { it.notification.extras.getCharSequence("android.title")?.toString() == title }

    private fun web(v: View): WebView? = when (v) {
        is WebView -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { web(v.getChildAt(it)) }
        else -> null
    }

    private fun js(scenario: ActivityScenario<MainActivity>, expr: String): String {
        val done = CountDownLatch(1)
        var out = "null"
        scenario.onActivity { a ->
            web(a.findViewById(android.R.id.content))!!
                .evaluateJavascript("(function(){try{return $expr}catch(e){return 'error: '+e}})()") { out = it ?: "null"; done.countDown() }
        }
        assertTrue("the page didn't answer", done.await(10, TimeUnit.SECONDS))
        return org.json.JSONTokener(out).nextValue().toString()
    }

    private fun until(what: String, seconds: Int, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            if (ok()) return
            Thread.sleep(250)
        }
        throw AssertionError("timed out waiting for $what")
    }
}
