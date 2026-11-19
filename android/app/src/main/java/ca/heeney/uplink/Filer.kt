package ca.heeney.uplink

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Files captures on the phone when there's no hub: your own LLM if you've set one up, else the
 *  plain parser. The hub's Processor and Model (server/nullspace.py), ported; keep them in step.
 *  With a hub, the hub files and this stays idle. */
object Filer {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "filer") }
    @Volatile private var queued = false

    /** on when there's no hub to do it */
    fun on(): Boolean = HubSync.url() == null

    fun soon(ctx: Context) {
        if (!on() || queued) return
        queued = true
        val app = ctx.applicationContext
        exec.execute {
            queued = false
            try { runPending(app) } catch (t: Throwable) { Log.w(TAG, "filer: $t") }
        }
    }

    /** Repeating tasks ticked off move on to their next time, as the hub's roll_done does. */
    fun rollDone(): Int {
        var n = 0
        for (r in Store.all("item")) {
            if (!r.data.has("repeat") || r.data.isNull("repeat") || !Plain.truthy(Js.kt(r.data.opt("done_at")))) continue
            val nd = try { Plain.roll(Js.map(r.data)) } catch (t: Throwable) { Log.w(TAG, "repeat ${r.uid}: $t"); null } ?: continue
            Store.put("item", r.uid, Js.obj(nd))
            n++
        }
        return n
    }

    private fun runPending(ctx: Context) {
        var any = rollDone() > 0
        for (c in Store.all("capture")) {
            if (c.data.optString("status", "new").ifEmpty { "new" } != "new") continue
            try { process(ctx, c, false); any = true } catch (t: Throwable) { Log.w(TAG, "file ${c.uid}: $t") }
        }
        if (any) {
            Capture.announce(ctx)
            Reminders.refresh(ctx)
        }
        Categories.soon(ctx)
    }

    /** Redo: file it again, replacing what it made and undoing what it changed. Blocking. */
    fun reprocess(ctx: Context, uid: String): JSONObject {
        val app = ctx.applicationContext
        val f = exec.submit<JSONObject> {
            val c = Store.get("capture", uid) ?: return@submit JSONObject().put("error", "No such capture")
            process(app, c, true)
            Reminders.refresh(app)
            JSONObject().put("ok", true)
        }
        return try { f.get(90, TimeUnit.SECONDS) } catch (t: Throwable) { JSONObject().put("error", "Couldn't file it again") }
    }

    // ---------------------------------------------------------------- lists, places, tasks

    fun listByName(name: Any?): Store.Rec? {
        if (name !is String || name.isEmpty()) return null
        val key = Plain.norm(name)
        val lists = Store.all("list")
        lists.firstOrNull { Plain.norm(it.data.optString("name")) == key }?.let { return it }
        if (key.length >= 4) lists.firstOrNull { Plain.norm(it.data.optString("name")).startsWith(key) }?.let { return it }
        return null
    }

    /** the list a capture was typed in, if it still exists */
    fun hintList(cap: Map<String, Any?>): Store.Rec? {
        val uid = cap["list"]?.toString()?.takeIf { it.isNotEmpty() } ?: return null
        return Store.get("list", uid)
    }

    fun inbox(): String {
        Store.all("list").firstOrNull { it.data.optBoolean("inbox") }?.let { return it.uid }
        Store.put("list", "inbox", JSONObject().put("name", "Inbox").put("inbox", true).put("sort", -1))
        return "inbox"
    }

    fun placeByName(name: String, create: Boolean = true): String? {
        if (name.isBlank()) return null
        val key = Plain.norm(Regex("^(the|my)\\s+", RegexOption.IGNORE_CASE).replace(name.trim(), ""))
        Store.all("place").firstOrNull { Plain.norm(it.data.optString("name")) == key }?.let { return it.uid }
        if (!create) return null
        val uid = Store.newUid()
        Store.put("place", uid, JSONObject().put("name", key.replaceFirstChar { it.uppercase() }).put("radius", 150))
        return uid
    }

    private val EN = Locale.ENGLISH
    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd", EN)
    private val DAYTIME = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd'T'HH:mm", EN)

    /** One line about a task for the LLM: its title and when or where it comes up. */
    fun describe(d: Map<String, Any?>): String {
        val bits = mutableListOf<String>()
        (d["list"] as? String)?.let { Store.get("list", it) }?.takeIf { !it.data.optBoolean("inbox") }
            ?.let { bits += "list ${it.data.optString("name")}" }
        (d["due"] as? String)?.takeIf { it.isNotEmpty() }?.let { due ->
            Plain.parseLocal(due)?.let { bits += "due " + it.format(if (due.length == 10) DAY else DAYTIME) }
        }
        (d["remind_at"] as? Number)?.toLong()?.takeIf { it != 0L }?.let { bits += "remind " + Plain.local(it).format(DAYTIME) }
        (d["place"] as? String)?.takeIf { it.isNotEmpty() }?.let { p ->
            val name = Store.get("place", p)?.data?.optString("name") ?: "a place"
            bits += "${(d["place_on"] as? String)?.ifEmpty { null } ?: "arrive"} $name"
        }
        (d["repeat"] as? String)?.takeIf { it.isNotEmpty() }?.let {
            bits += "repeat $it"
            Plain.cleanTimes(d["times"])?.let { ts -> bits += "times " + ts.joinToString(", ") }
        }
        val title = Regex("\\s+").replace((d["title"] as? String) ?: "", " ").take(120)
        return title + if (bits.isNotEmpty()) " (${bits.joinToString(", ")})" else ""
    }

    /** The open tasks a capture might change, most recently touched first. */
    fun openTasks(skip: Set<String>, limit: Int = 60): List<Plain.Task> =
        Store.all("item").filter {
            it.data.optString("type") == "task" && !Plain.truthy(Js.kt(it.data.opt("done_at"))) && it.uid !in skip
        }.sortedByDescending { it.updatedAt }.take(limit)
            .mapIndexed { i, r -> Plain.Task("t${i + 1}", r.uid, Js.map(r.data)) }

    // ---------------------------------------------------------------- filing

    private fun process(ctx: Context, cap: Store.Rec, force: Boolean) {
        val d = Js.map(cap.data)
        @Suppress("UNCHECKED_CAST")
        val mine = ((d["items"] as? List<Any?>) ?: emptyList()).map { it.toString() }.toSet()
        if (force) undo(d)
        val text = ((d["text"] as? String) ?: "").trim()
        // What was said can change tasks already there ("mark X done"), so the filer sees them.
        val tasks = if (text.isNotEmpty() && d["type"] in listOf("voice", "text", "share")) openTasks(mine) else emptyList()
        val places = Store.all("place").map { it.data.optString("name") }
        var result = Brain.file(ctx, d, tasks)
        var basic = result == null
        if (result == null) result = Plain.basicParse(d, places, tasks)
        val now = Plain.now()
        var saidAt = (d["created_at"] as? Number)?.toLong()?.let { Plain.local(it) } ?: now
        if (saidAt > now || saidAt < now.minusDays(2)) saidAt = now
        @Suppress("UNCHECKED_CAST")
        val items = ((result["items"] as? List<Any?>) ?: emptyList()).take(12).filterIsInstance<Map<String, Any?>>()
            .map { Plain.settle(LinkedHashMap(it), text, now, saidAt = saidAt) }
        val made = Plain.tidyRepeats(items, saidAt).map { makeItem(it, cap.uid, d) }.toMutableList()
        val ids = tasks.associateBy { it.id }
        val leads = Regex(Plain.LEADS, RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (!basic && leads && !Plain.EDITS.containsMatchIn(text) && Plain.truthy(result["changes"])) {
            // "Remind me to X" is a new task even when the LLM would rather fold it into an old one.
            result["changes"] = mutableListOf<Any?>()
            if (made.isEmpty()) made += plainItems(d, places, text, now, saidAt, cap.uid)
        }
        val changed = mutableListOf<MutableMap<String, Any?>>()
        for (ch in ((result["changes"] as? List<*>) ?: emptyList<Any?>()).take(12)) {
            @Suppress("UNCHECKED_CAST")
            val c = (ch as? Map<String, Any?>)?.let { changeItem(ids, it, text, now) } ?: continue
            val same = changed.firstOrNull { it["uid"] == c["uid"] }
            if (same != null) same["sig"] = c["sig"]  // a second change to it: Redo still goes back to the first before
            else changed += c
        }
        if (made.isEmpty() && changed.isEmpty() && !basic && (Plain.REMIND.containsMatchIn(text) || leads)) {
            // A new to-do and the LLM made nothing (it thought a task already covers it): file it plainly.
            basic = true
            made += plainItems(d, places, text, now, saidAt, cap.uid)
        }
        d["status"] = "done"
        d["summary"] = (result["summary"]?.toString() ?: "").take(200)
        d["items"] = made
        d["processed_at"] = System.currentTimeMillis() / 1000
        d["basic"] = basic
        d["changed"] = changed
        if (force) {
            for (old in mine) Store.get("item", old)?.let { Store.put("item", old, it.data, deleted = true) }
        }
        Store.put("capture", cap.uid, Js.obj(d))
        Log.i(TAG, "capture ${cap.uid} (${d["type"]}): ${made.size} item(s), ${changed.size} changed${if (basic) " (basic)" else ""}")
    }

    private fun plainItems(d: Map<String, Any?>, places: List<String>, text: String, now: LocalDateTime, saidAt: LocalDateTime,
                           capUid: String): List<String> {
        @Suppress("UNCHECKED_CAST")
        val items = (Plain.basicParse(d, places)["items"] as? List<Any?>).orEmpty().filterIsInstance<Map<String, Any?>>()
            .map { Plain.settle(LinkedHashMap(it), text, now) }
        return Plain.tidyRepeats(items, saidAt).map { makeItem(it, capUid, d) }
    }

    /** What a change wrote, minus what's filled in later (the category). */
    private fun editSig(r: Store.Rec): String {
        val d = Js.map(r.data).filterKeys { it != "category" && it != "category_auto" }
        return sha1(canon(listOf(r.deleted, d))).take(16)
    }

    /** Puts back the tasks a capture changed, unless they've been changed again since. */
    private fun undo(capd: Map<String, Any?>) {
        for (c in (capd["changed"] as? List<*>).orEmpty()) {
            val m = c as? Map<*, *> ?: continue
            val cur = Store.get("item", m["uid"].toString(), withDeleted = true) ?: continue
            val before = m["before"] as? Map<*, *> ?: continue
            if (editSig(cur) == m["sig"]) Store.put("item", cur.uid, Js.json(before) as JSONObject)
        }
    }

    private fun changeItem(ids: Map<String, Plain.Task>, ch: Map<String, Any?>, text: String, now: LocalDateTime): MutableMap<String, Any?>? {
        val task = ids[ch["task"]?.toString()?.trim().orEmpty()] ?: return null
        val cur = Store.get("item", task.uid) ?: return null
        val uid = cur.uid
        val before = Js.map(cur.data)
        val nd = LinkedHashMap(before)
        if (ch["delete"] == true) {
            Store.put("item", uid, cur.data, deleted = true)
        } else {
            if (ch["done"] == true) nd["done_at"] = System.currentTimeMillis() / 1000
            else if (ch["done"] == false) nd["done_at"] = null
            val title = (ch["title"]?.toString() ?: "").trim().trimEnd('.').take(300)
            if (title.isNotEmpty()) nd["title"] = title.replaceFirstChar { it.uppercase() }
            if ("notes" in ch) nd["notes"] = (ch["notes"]?.toString() ?: "").take(20000)
            listByName(ch["list"])?.let { nd["list"] = it.uid }
            if ("repeat" in ch) nd["repeat"] = Plain.cleanRule(ch["repeat"])
            if ("times" in ch) nd["times"] = Plain.cleanTimes(ch["times"])
            val t = Plain.settle(LinkedHashMap<String, Any?>().apply {
                for (k in listOf("due", "remind_at")) if (k in ch) put(k, ch[k])
                put("repeat", nd["repeat"])
            }, text, now, fresh = false)
            val saidNoTime = Plain.saidTime(text) == ""
            if ("due" in ch && (ch["due"] == null || Plain.cleanDue(t["due"]) != null)) {
                var due = Plain.cleanDue(t["due"])
                val old = Plain.cleanDue(before["due"])
                if (due != null && due.length == 10 && old != null && old.length > 10 && saidNoTime) due += old.drop(10)
                val oldRemind = (before["remind_at"] as? Number)?.toLong()
                if (due != null && old != null && oldRemind != null && oldRemind != 0L && "remind_at" !in ch) {
                    // Moving a task's day moves its reminder with it.
                    val shift = java.time.temporal.ChronoUnit.DAYS.between(Plain.parseLocal(old)!!.toLocalDate(), Plain.parseLocal(due)!!.toLocalDate())
                    nd["remind_at"] = Plain.epoch(Plain.local(oldRemind).plusDays(shift))
                }
                nd["due"] = due
            }
            if ("remind_at" in ch && (ch["remind_at"] == null || Plain.cleanDue(t["remind_at"]) != null)) {
                var r = Plain.parseLocal(Plain.cleanDue(t["remind_at"]))
                val oldRemind = (before["remind_at"] as? Number)?.toLong()
                if (r != null && oldRemind != null && oldRemind != 0L && saidNoTime) {
                    r = r.toLocalDate().atTime(Plain.local(oldRemind).toLocalTime())  // a new day, the same time of day
                }
                nd["remind_at"] = r?.let { Plain.epoch(it) }
            }
            var times = if (Plain.parseRule(nd["repeat"]) != null) Plain.cleanTimes(nd["times"]) else null
            val was = num(before["remind_at"])?.takeIf { it != 0L }
            val now2 = num(nd["remind_at"])?.takeIf { it != 0L }
            if (times != null && "times" !in ch && was != null && now2 != null && now2 != was) {
                // "move it to 8": the time that moved changes in the list too
                val old = Plain.local(was).format(HM)
                val new = Plain.local(now2).format(HM)
                times = Plain.cleanTimes(times.map { x -> if (x == old) new else x })
            }
            if (times != null && now2 != null) {
                nd["remind_at"] = Plain.epoch(Plain.fitTimes(Plain.local(now2), nd["repeat"] as String, times))
            }
            if (times != null) nd["times"] = times else nd.remove("times")
            if (num(nd["remind_at"]) != num(before["remind_at"])) nd.remove("fired_at")
            if ("place" in ch) {
                val p = ch["place"]?.toString()
                val ok = !p.isNullOrBlank() && p.trim().split(Regex("\\s+")).size <= 4
                nd["place"] = if (ok) placeByName(p!!.take(40)) else null
                nd["place_on"] = if (nd["place"] != null) (if (ch["place_on"] == "leave") "leave" else "arrive") else null
            } else if (ch["place_on"] in listOf("arrive", "leave") && Plain.truthy(nd["place"])) nd["place_on"] = ch["place_on"]
            if (nd["place"] != before["place"] || nd["place_on"] != before["place_on"]) nd.remove("place_fired_at")
            if (canon(nd) == canon(before)) return null
            val rolled = if (Plain.truthy(nd["done_at"]) && Plain.truthy(nd["repeat"])) Plain.roll(nd) else null
            Store.put("item", uid, Js.obj(rolled ?: nd))
        }
        val after = Store.get("item", uid, withDeleted = true) ?: return null
        return linkedMapOf("uid" to uid, "title" to (before["title"] ?: ""), "before" to before, "sig" to editSig(after))
    }

    private fun num(v: Any?): Long? = (v as? Number)?.toLong()

    private fun makeItem(it: Map<String, Any?>, capUid: String, cap: Map<String, Any?>): String {
        val kind = if (it["type"] == "task") "task" else "note"
        var title = (it["title"]?.toString() ?: "").trim().trimEnd('.').take(300).ifEmpty { "Untitled" }
        title = title.replaceFirstChar { c -> c.uppercase() }
        val data = linkedMapOf<String, Any?>(
            "type" to kind, "title" to title, "notes" to (it["notes"]?.toString() ?: "").take(20000),
            "created_at" to System.currentTimeMillis() / 1000, "source" to (cap["type"] ?: "text"), "capture" to capUid,
        )
        // A list the filer names wins, then the list the capture was typed in, then the Inbox.
        val hint = hintList(cap)
        val fallback = { hint?.uid ?: inbox() }
        var lst = if (kind == "task") listByName(it["list"]) else null
        if (lst != null && hint != null && lst.data.optBoolean("inbox")) lst = null  // "Inbox" from the filer means unsorted
        data["list"] = lst?.uid ?: if (kind == "task") fallback() else null
        Plain.cleanDue(it["due"])?.let { due -> data["due"] = due }
        Plain.parseLocal(Plain.cleanDue(it["remind_at"]))?.let { r -> data["remind_at"] = Plain.epoch(r) }
        val rule = if (kind == "task") Plain.parseRule(it["repeat"]) else null
        if (rule != null) {
            data["repeat"] = Plain.cleanRule(it["repeat"])
            if (rule.freq != "MINUTELY" && rule.freq != "HOURLY") Plain.cleanTimes(it["times"])?.let { ts -> data["times"] = ts }
            if (data["remind_at"] == null && data["due"] == null) {
                data["remind_at"] = Plain.epoch(Plain.parseLocal(Plain.firstTime(null, rule))!!)  // a repeat needs a first time
            }
        }
        val place = it["place"]?.toString()
        if (!place.isNullOrBlank() && place.trim().split(Regex("\\s+")).size <= 4) {  // longer is a misheard phrase
            data["place"] = placeByName(place.take(40))
            data["place_on"] = if (it["place_on"] == "leave") "leave" else "arrive"
            data["type"] = "task"
            if (data["list"] == null) data["list"] = fallback()
        }
        val uid = Store.newUid()
        Store.put("item", uid, Js.obj(data))
        return uid
    }

    // ---------------------------------------------------------------- canonical JSON, for signatures

    fun canon(v: Any?): String = when (v) {
        null -> "null"
        is Map<*, *> -> v.entries.sortedBy { it.key.toString() }.joinToString(",", "{", "}") { JSONObject.quote(it.key.toString()) + ":" + canon(it.value) }
        is Collection<*> -> v.joinToString(",", "[", "]") { canon(it) }
        is String -> JSONObject.quote(v)
        is Number -> if (v.toDouble() == Math.floor(v.toDouble()) && !v.toDouble().isInfinite()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
