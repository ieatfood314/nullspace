package ca.heeney.uplink

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** With no hub, gives open tasks a category through your own LLM: every waiting task in one
 *  request, at most every 5 minutes. The hub's Categorizer.judge_llm, ported; keep them in step.
 *  `category_auto` is the signature of what was judged; a category without it is your own pick
 *  and is never touched. Without an LLM, tasks stay under Other. */
object Categories {
    val ALL = listOf("work", "chores", "projects", "fun", "other")
    private val CRITERIA = linkedMapOf(
        "work" to "School or a job. Studying, homework, assignments, exams, applications, clubs, anything owed to a teacher or a boss.",
        "chores" to "Everyday upkeep and errands. Cleaning, shopping, appointments, phone calls, money, paperwork, fetching or returning things.",
        "projects" to "Something the person is making, building or setting up by choice. Software, hardware, 3D prints, servers, things to build or fix up. Something assigned by a school or job is work, not this.",
        "fun" to "Leisure. Games, shows, books, outings, things done purely for enjoyment.",
    )
    private const val SETTLE_MS = 15_000L
    private const val GAP_MS = 5 * 60_000L
    private const val BACKOFF_MS = 60 * 60_000L
    private const val BATCH = 30

    private val thread = HandlerThread("categories").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var notBefore = 0L
    private val skip = HashMap<String, String>()
    private var app: Context? = null
    private val tick = Runnable { app?.let { run(it) } }

    fun soon(ctx: Context) {
        if (!Filer.on() || !Brain.on()) return
        app = ctx.applicationContext
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, maxOf(SETTLE_MS, notBefore - System.currentTimeMillis()))
    }

    private fun state(d: JSONObject): Map<String, Any?> {
        val st = linkedMapOf<String, Any?>("title" to d.optString("title"))
        val notes = Js.kt(d.opt("notes"))?.toString()?.trim()?.take(500).orEmpty()
        if (notes.isNotEmpty()) st["notes"] = notes
        val l = (Js.kt(d.opt("list")) as? String)?.let { Store.get("list", it) }
        if (l != null && !l.data.optBoolean("inbox") && l.data.optString("name").isNotEmpty()) st["list"] = l.data.optString("name")
        return st
    }

    private fun sig(st: Map<String, Any?>) = Filer.sha1(Filer.canon(st)).take(12)

    private fun needs(r: Store.Rec): Boolean {
        val d = r.data
        if (d.optString("type") != "task" || Plain.truthy(Js.kt(d.opt("done_at")))) return false
        if (Js.kt(d.opt("category")) !in ALL) return true
        val auto = Js.kt(d.opt("category_auto")) as? String
        return !auto.isNullOrEmpty() && auto != sig(state(d))
    }

    private fun run(ctx: Context) {
        if (!Filer.on() || !Brain.on() || System.currentTimeMillis() < notBefore) return
        if (!Brain.canCall(ctx, made = false)) { notBefore = System.currentTimeMillis() + GAP_MS; return }
        val batch = Store.all("item").filter { needs(it) }.sortedByDescending { it.updatedAt }
            .map { Triple(it.uid, state(it.data), sig(state(it.data))) }
            .filter { skip[it.first] != it.third }.take(BATCH)
        if (batch.isEmpty()) return
        val prompt = "You sort one person's to-do items into categories. Each item has an id, a title, and maybe " +
            "notes and the list it is in.\nCategories:\n" + CRITERIA.entries.joinToString("") { "- ${it.key}: ${it.value}\n" } +
            "- other: too vague to place, or about something none of those cover.\n" +
            "Reply with one JSON object only, no prose, mapping every id to its category, like {\"1\": \"work\", \"2\": \"fun\"}."
        val tasks = JSONArray()
        batch.forEachIndexed { i, (_, st, _) -> tasks.put(Js.obj(st + ("id" to "${i + 1}"))) }
        val msg = JSONArray().put(JSONObject().put("role", "system").put("content", prompt))
            .put(JSONObject().put("role", "user").put("content", tasks.toString()))
        val res = Brain.chat(ctx, msg, { r -> (1..batch.size).any { r.optString("$it") in ALL } }, maxTokens = 800)
        if (res == null) { notBefore = System.currentTimeMillis() + BACKOFF_MS; Log.w(TAG, "categories: ${Brain.lastError}"); return }
        batch.forEachIndexed { i, (uid, _, sent) ->
            val cat = res.optString("${i + 1}").trim().lowercase()
            val cur = Store.get("item", uid)
            if (cat !in ALL) { skip[uid] = sent; return@forEachIndexed }
            if (cur == null || !needs(cur) || sig(state(cur.data)) != sent) return@forEachIndexed  // changed meanwhile
            Store.put("item", uid, JSONObject(cur.data.toString()).put("category", cat).put("category_auto", sent))
        }
        notBefore = System.currentTimeMillis() + GAP_MS
    }
}
