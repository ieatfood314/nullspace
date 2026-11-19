package ca.heeney.uplink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Plain.kt against what server/nullspace.py gives for the same words at the same times
 *  (written by server/plain_cases.py). A failure means the port and the hub drifted apart. */
class PlainTest {
    private val cases = JSONObject(javaClass.classLoader!!.getResource("plain-cases.json")!!.readText())

    private fun norm(v: Any?): Any? = when (v) {
        is Map<*, *> -> v.entries.associate { it.key.toString() to norm(it.value) }
        is List<*> -> v.map { norm(it) }
        is Number -> if (v.toDouble() == Math.floor(v.toDouble())) v.toLong() else v.toDouble()
        else -> v
    }

    private fun eachRun(block: (JSONObject) -> Unit) {
        Plain.zoneOverride = ZoneId.of(cases.getString("zone"))
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(cases.getString("zone")))  // Repeat walks in it
        val runs = cases.getJSONArray("runs")
        for (i in 0 until runs.length()) {
            val run = runs.getJSONObject(i)
            val now = LocalDateTime.parse(run.getString("now"))
            Plain.clock = { now }
            block(run)
        }
    }

    private fun tasks(): List<Plain.Task> {
        val a = cases.getJSONArray("tasks")
        return (0 until a.length()).map {
            val t = a.getJSONObject(it)
            Plain.Task(t.getString("id"), t.getString("uid"), Js.map(t.getJSONObject("data")))
        }
    }

    private fun places(): List<String> = (Js.kt(cases.getJSONArray("places")) as List<*>).map { it.toString() }

    @Test fun basicParse() = eachRun { run ->
        val a = run.getJSONArray("parse")
        for (i in 0 until a.length()) {
            val c = a.getJSONArray(i)
            val text = c.getString(0)
            val capture = if (text.isEmpty()) mapOf("type" to "screenshot", "app" to "com.android.chrome", "screen_text" to "Hello")
            else mapOf("type" to "voice", "text" to text)
            val got = Plain.basicParse(capture, places(), tasks())
            assertEquals("${run.getString("now")}: $text", norm(Js.kt(c.get(1))), norm(got))
        }
    }

    @Test fun settle() = eachRun { run ->
        val a = run.getJSONArray("settle")
        val now = Plain.now()
        for (i in 0 until a.length()) {
            val c = a.getJSONArray(i)
            @Suppress("UNCHECKED_CAST")
            val it = Js.kt(c.get(0)) as MutableMap<String, Any?>
            val got = Plain.settle(it, c.getString(1), now)
            assertEquals("${run.getString("now")}: ${c.getString(1)}", norm(Js.kt(c.get(2))), norm(got))
        }
    }

    @Test fun saidTimeSpanRepeat() = eachRun { run ->
        val st = run.getJSONArray("said_time")
        val sp = run.getJSONArray("said_span")
        val rp = run.getJSONArray("repeat")
        for (i in 0 until st.length()) {
            val text = st.getJSONArray(i).getString(0)
            val label = "${run.getString("now")}: $text"
            assertEquals("time $label", Js.kt(st.getJSONArray(i).get(1)), Plain.saidTime(text))
            assertEquals("span $label", norm(Js.kt(sp.getJSONArray(i).get(1))), Plain.saidSpan(text)?.seconds)
            val (rule, rest) = Plain.pullRepeat(text)
            assertEquals("repeat $label", Js.kt(rp.getJSONArray(i).get(1)), listOf(rule, rest))
        }
    }

    @Test fun tidyRepeats() = eachRun { run ->
        val a = run.getJSONArray("tidy")
        for (i in 0 until a.length()) {
            val c = a.getJSONArray(i)
            @Suppress("UNCHECKED_CAST")
            val items = (Js.kt(c.get(0)) as List<MutableMap<String, Any?>>)
            val got = Plain.tidyRepeats(items, Plain.now())
            assertEquals("${run.getString("now")}: tidy $i", norm(Js.kt(c.get(1))), norm(got))
        }
    }

    @Test fun roll() = eachRun { run ->
        val a = run.getJSONArray("roll")
        for (i in 0 until a.length()) {
            val c = a.getJSONArray(i)
            @Suppress("UNCHECKED_CAST")
            val d = Js.kt(c.get(0)) as MutableMap<String, Any?>
            assertEquals("${run.getString("now")}: roll $i", norm(Js.kt(c.get(1))), norm(Plain.roll(d, Plain.now())))
        }
    }
}
