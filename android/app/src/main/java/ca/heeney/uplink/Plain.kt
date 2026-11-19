package ca.heeney.uplink

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/** The plain filer: what can be read from a capture without an LLM (tasks or a note, with
 *  time, place and repeat phrases pulled out, "mark X done", "move X to Friday"), and the checks
 *  that fix an LLM's dates against the words that were said.
 *
 *  A line-for-line port of the same functions in server/nullspace.py, so the phone files a
 *  capture the way the hub would. Keep the two in step: PlainTest replays cases the Python
 *  wrote (server/plain_cases.py) and fails when they drift.
 *
 *  Items and changes are plain maps (field to String, Number, Boolean, List or null), like the
 *  hub's dicts. Times are local wall-clock times, as the hub's are. */
object Plain {
    /** local wall-clock time now; tests pin it */
    @Volatile var clock: () -> LocalDateTime = { LocalDateTime.now() }
    @Volatile var zoneOverride: ZoneId? = null
    fun zone(): ZoneId = zoneOverride ?: ZoneId.systemDefault()
    fun now(): LocalDateTime = clock()

    class Info(var deadline: Boolean = false, var timed: Boolean = false)

    // ---------------------------------------------------------------- regex helpers

    private val I = RegexOption.IGNORE_CASE
    private fun rx(p: String) = Regex(p, I)
    /** Python's re.match: anchored at the start */
    private fun match(p: String, s: String) = Regex("^(?:$p)", I).find(s)
    private fun search(p: String, s: String) = Regex(p, I).find(s)
    private fun MatchResult.g(i: Int): String? = groups[i]?.value
    private val MatchResult.s get() = range.first
    private val MatchResult.e get() = range.last + 1
    private fun String.stripChars(chars: String) = trim { it in chars }
    /** Python's str.capitalize(): first letter up, the rest down */
    private fun String.capitalizePy() = if (isEmpty()) this else substring(0, 1).uppercase() + substring(1).lowercase()
    private fun String.upFirst() = if (isEmpty()) this else substring(0, 1).uppercase() + substring(1)
    private fun weekday(d: LocalDate) = d.dayOfWeek.value - 1  // Monday 0, like Python

    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    fun fmt(t: LocalDateTime): String = t.format(FMT)
    fun epoch(t: LocalDateTime): Long = t.atZone(zone()).toEpochSecond()
    fun local(epoch: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(epoch), zone())

    // ---------------------------------------------------------------- times

    /** 'YYYY-MM-DD' or 'YYYY-MM-DDTHH:MM' -> local time (dates at 09:00) */
    fun parseLocal(s: String?): LocalDateTime? {
        if (s.isNullOrEmpty()) return null
        return try {
            if (s.length == 10) LocalDate.parse(s).atTime(9, 0)
            else LocalDateTime.parse(s.take(16))
        } catch (_: Exception) { null }
    }

    fun cleanDue(v: Any?): String? {
        if (v !is String || v.isEmpty()) return null
        val s = v.trim().take(16)
        if (Regex("\\d{4}-\\d\\d-\\d\\d").matches(s) || Regex("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d").matches(s)) {
            return if (parseLocal(s) != null) s else null
        }
        return null
    }

    // ---------------------------------------------------------------- repeats

    val FREQS = listOf("MINUTELY", "HOURLY", "DAILY", "WEEKLY", "MONTHLY", "YEARLY")
    val WEEKDAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    class Rule(val freq: String, val interval: Long, val byday: List<Int>, val count: Int?, val until: Boolean,
               val parts: LinkedHashMap<String, String>)

    fun parseRule(s: Any?): Rule? {
        if (s !is String || s.isBlank()) return null
        val parts = LinkedHashMap<String, String>()
        for (p in s.trim().uppercase().removePrefix("RRULE:").split(";")) {
            val k = p.substringBefore("=").trim()
            if (k.isNotEmpty()) parts[k] = p.substringAfter("=", "").trim()
        }
        val freq = parts["FREQ"]?.takeIf { it in FREQS } ?: return null
        val interval = (parts["INTERVAL"]?.ifEmpty { "1" } ?: "1").toLongOrNull()?.coerceIn(1, 100_000) ?: 1
        val byday = (parts["BYDAY"] ?: "").split(",").map { it.takeLast(2) }.filter { it in WEEKDAYS }
            .map { WEEKDAYS.indexOf(it) }.distinct().sorted()
        val count = parts["COUNT"]?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
        return Rule(freq, interval, byday, count, untilOk(parts["UNTIL"]), parts)
    }

    private fun untilOk(v: String?): Boolean {
        if (v.isNullOrEmpty()) return false
        val s = v.replace("-", "").replace(":", "")
        return try {
            if (Regex("\\d{8}").matches(s)) { LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE); true }
            else { LocalDateTime.parse(s.removeSuffix("Z"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")); true }
        } catch (_: Exception) { false }
    }

    /** A rule from the LLM or a client, checked and tidied, or null. */
    fun cleanRule(s: Any?): String? {
        val r = parseRule(s) ?: return null
        val keep = LinkedHashMap<String, String>()
        keep["FREQ"] = r.freq
        if (r.interval > 1) keep["INTERVAL"] = r.interval.toString()
        if (r.byday.isNotEmpty()) keep["BYDAY"] = r.byday.joinToString(",") { WEEKDAYS[it] }
        if (r.count != null && r.count > 0) keep["COUNT"] = r.count.toString()
        if (r.until) keep["UNTIL"] = r.parts["UNTIL"]!!.replace("-", "").replace(":", "")
        return keep.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    fun formatRule(r: Rule, count: Int): String {
        val parts = LinkedHashMap(r.parts)
        parts["COUNT"] = count.toString()
        val order = listOf("FREQ", "INTERVAL", "BYDAY", "COUNT", "UNTIL")
        val keys = order.filter { it in parts } + parts.keys.filter { it !in order }
        return keys.joinToString(";") { "$it=${parts[it]}" }
    }

    private fun addMonths(t: LocalDateTime, n: Long) = t.plusMonths(n)  // clamps the day, like the hub

    // ---- several times a day: the hub's clean_times, fit_times and tidy_repeats ----

    const val MAX_TIMES = 12
    private val HHMM = Regex("([01]?\\d|2[0-3]):([0-5]\\d)")
    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val SUBDAY = setOf("MINUTELY", "HOURLY")

    /** A task's times of day, checked and sorted ("7:30" -> "07:30"), or null for fewer than two. */
    fun cleanTimes(v: Any?): List<String>? {
        val l = v as? List<*> ?: return null
        val out = sortedSetOf<String>()
        for (x in l.take(50)) HHMM.matchEntire(x.toString().trim())?.let { m ->
            out += "%02d:%s".format(m.groupValues[1].toInt(), m.groupValues[2])
        }
        val r = out.toList().take(MAX_TIMES)
        return if (r.size > 1) r else null
    }

    private fun timesArg(v: Any?): List<String>? = (v as? List<*>)?.map { it.toString() }

    private fun withoutCount(repeat: String): String =
        repeat.split(";").filter { !it.trim().uppercase().startsWith("COUNT=") }.joinToString(";")

    /** (index, time) of the first time the rule gives after t. */
    fun nextAfter(anchor: LocalDateTime, repeat: String, t: LocalDateTime, times: List<String>?): Pair<Int, LocalDateTime>? {
        val w = Repeat.parse(repeat, times) ?: return null
        val after = epoch(t)
        return w.walk(epoch(anchor), after).firstOrNull { it.first > 0 && it.second > after }?.let { it.first to local(it.second) }
    }

    /** A repeating task's reminder moved onto one of its times of day: the first of the task's
     *  times at or after it, on a day the rule gives. */
    fun fitTimes(anchor: LocalDateTime, repeat: String?, times: List<String>?): LocalDateTime {
        val rule = parseRule(repeat) ?: return anchor
        if (times.isNullOrEmpty() || rule.freq in SUBDAY || anchor.format(HM) in times) return anchor
        val (h, m) = times[0].split(":").map { it.toInt() }
        val start = anchor.withHour(h).withMinute(m).withSecond(0).withNano(0)
        val w = Repeat.parse(repeat as String, times) ?: return anchor
        val a = epoch(anchor)
        return w.walk(epoch(start)).firstOrNull { it.second >= a }?.let { local(it.second) } ?: anchor
    }

    /** Items for one capture, after settle(). A repeating task the filer split by time of day
     *  ("at 7:30 and at 6" as two tasks) becomes one task with times, and a repeating reminder
     *  starts at its next time still ahead, not at one that had gone by when it was said. */
    fun tidyRepeats(items: List<MutableMap<String, Any?>>, saidAt: LocalDateTime): List<MutableMap<String, Any?>> {
        val out = mutableListOf<MutableMap<String, Any?>>()
        val keys = LinkedHashMap<List<Any?>, MutableMap<String, Any?>>()
        for (it in items) {
            val rule = if (it["type"] == "task") parseRule(it["repeat"]) else null
            val rem = cleanDue(it["remind_at"])
            if (rule == null || rule.freq in SUBDAY || rem == null || rem.length <= 10) { out += it; continue }
            // Halves of one task share a day ("every Monday at 7 and Thursday at 7" is two tasks),
            // except a plain daily repeat, where the filer may have put one half on tomorrow.
            val daily = rule.freq == "DAILY" && rule.interval == 1L && rule.byday.isEmpty()
            val key = listOf(norm(it["title"]?.toString()), cleanRule(it["repeat"]), norm(it["place"]?.toString() ?: ""),
                norm(it["list"]?.toString() ?: ""), cleanDue(it["due"]), if (daily) "" else rem.take(10))
            it["remind_at"] = rem
            it["times"] = (cleanTimes(it["times"]) ?: listOf(rem.substring(11))).toMutableList()
            val twin = keys[key]
            if (twin == null) { keys[key] = it; out += it; continue }
            @Suppress("UNCHECKED_CAST")
            (twin["times"] as MutableList<String>) += it["times"] as List<String>
            twin["remind_at"] = minOf(twin["remind_at"] as String, rem)
            twin["notes"] = listOf(twin["notes"], it["notes"]).map { n -> (n?.toString() ?: "").trim() }
                .filter { n -> n.isNotEmpty() }.distinct().joinToString("\n")
        }
        for (it in keys.values) {
            val rep = cleanRule(it["repeat"])!!
            val times = cleanTimes(it["times"])
            it["times"] = times
            var anchor = fitTimes(parseLocal(it["remind_at"] as String)!!, rep, times)
            val floor = saidAt.minusMinutes(15)
            if (anchor.isBefore(floor) && cleanDue(it["due"]) == null) {
                // COUNT stays: "for 7 days" still means 7 from the first one ahead.
                nextAfter(anchor, withoutCount(rep), floor, times)?.let { n -> anchor = n.second }
            }
            it["remind_at"] = fmt(anchor)
        }
        return out
    }

    /** A repeating task that was marked done, moved to its next time and opened again. Null when
     *  it doesn't repeat or its repeats are used up (it stays done). */
    fun roll(d: MutableMap<String, Any?>, now: LocalDateTime = now()): MutableMap<String, Any?>? {
        val rule = parseRule(d["repeat"]) ?: return null
        if (!truthy(d["done_at"]) || d["type"] == "note") return null
        val nd = LinkedHashMap(d)
        nd["done_at"] = null
        nd.remove("place_fired_at")
        val anchor = anchorOf(d) ?: return nd
        val walk = Repeat.parse(d["repeat"] as String, timesArg(d["times"])) ?: return nd
        val after = epoch(if (now > anchor) now else anchor)
        val (i, whenEpoch) = walk.walk(epoch(anchor), after).firstOrNull { it.first > 0 && it.second > after } ?: return null
        val wt = local(whenEpoch)
        if (rule.count != null) nd["repeat"] = formatRule(rule, rule.count - i)
        if (truthy(d["remind_at"])) nd["remind_at"] = whenEpoch
        val due = d["due"] as? String
        if (!due.isNullOrEmpty()) {
            if (due.length == 10) {
                nd["due"] = LocalDate.parse(due).plusDays(java.time.temporal.ChronoUnit.DAYS.between(anchor.toLocalDate(), wt.toLocalDate())).toString()
            } else {
                val old = parseLocal(due)!!
                nd["due"] = fmt(old.plus(Duration.between(anchor, wt)))
            }
        }
        return nd
    }

    private fun anchorOf(d: Map<String, Any?>): LocalDateTime? {
        val r = (d["remind_at"] as? Number)?.toLong()
        if (r != null && r != 0L) return local(r)
        return parseLocal(d["due"] as? String)
    }

    fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        else -> true
    }

    // ---------------------------------------------------------------- words

    private val WORDNUM = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "an" to 1, "a" to 1)
    val DAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val DAYS_RX = DAYS.joinToString("|")

    const val VERBS = "buy|call|email|e-mail|text|message|get|grab|pick up|book|pay|send|finish|fix|clean|tidy|study|write|" +
        "read|order|return|check|ask|tell|make|take|bring|start|hand in|water|wash|do|go|cook|feed|renew|" +
        "submit|charge|follow up|print|meet|visit|cancel|schedule|reply|post|drop off|put|walk|practi[cs]e|" +
        "review|sign|fill|empty|vacuum|mow|sweep|load|unload|change|install|update|backup|back up|look up|" +
        "find|try|plan|prep|prepare|pack|move|sell|ship|mail|phone|research"
    private val COMMON_PLACES = listOf("home", "work", "school", "gym", "office", "store", "grocery store", "supermarket", "library",
        "mall", "church", "bank", "pharmacy", "post office", "doctor's", "dentist", "station", "airport",
        "park", "class", "practice", "the cottage", "my mom's", "my dad's", "grandma's")
    const val LEADS = "^(?:please\\s+)?(?:(?:can you\\s+)?remind me\\s+(?:to\\s+|that\\s+|about\\s+)?|don'?t\\s+(?:let me\\s+)?forget\\s+(?:to\\s+)?" +
        "|remember\\s+to\\s+|todo:?\\s*|to do:?\\s*|i\\s+(?:need|have|want|ought)\\s+to\\s+|i(?:'ve| have)?\\s+got\\s+to\\s+" +
        "|i\\s+gotta\\s+|i\\s+should\\s+|i\\s+must\\s+)"
    val EDITS = rx("\\b(?:move|moved|push|pushed|change|changed|rename|cancel|delete|remove|mark|done|" +
        "finished|completed|did|reschedule|postpone|instead|already|tick|check off|cross off|" +
        "no longer|don'?t need|undo|update)\\b")

    private const val MONTHS = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|" +
        "oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
    private const val NUMWORD = "\\d+|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"
    val REMIND = rx("\\bremind\\b|\\bdon'?t (?:let me )?forget\\b|\\bremember to\\b")

    private const val ORDINAL = "\\d{1,2}(?:st|nd|rd|th)?|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|" +
        "eleventh|twelfth|fifteenth|twentieth|last"
    private val ORDWORDS = mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6,
        "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10, "eleventh" to 11, "twelfth" to 12, "fifteenth" to 15,
        "twentieth" to 20, "last" to -1)
    private val MONTH_NAMES = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private fun ordinal(s0: String): Int {
        val s = s0.lowercase()
        return if (s[0].isDigit()) s.filter(Char::isDigit).toInt() else ORDWORDS[s] ?: 1
    }

    private fun amount(k0: String): Double {
        val k = k0.lowercase()
        return if (k[0].isDigit()) k.toDouble() else (WORDNUM[k] ?: 1).toDouble()
    }

    private val SPOKEN_CLOCK = rx("\\b(at|by|around)\\s+(\\d{1,2})(?:\\.|\\s)?(\\d\\d)(?!\\d)(?=\\s*(?:$|[.,!?;)]|(?:am|pm|a\\.m\\.|p\\.m\\.|" +
        "o'?clock|on|today|tomorrow|tonight|this|next|and|$DAYS_RX)(?!\\w)))")

    /** Speech-to-text drops the colon: "at 140" is 1:40 and "at 2.45" is 2:45. */
    fun spokenClock(text: String?): String = SPOKEN_CLOCK.replace(text ?: "") { m ->
        val h = m.g(2)!!.toInt()
        val mi = m.g(3)!!.toInt()
        if (h < 24 && mi < 60) "${m.g(1)} $h:${"%02d".format(mi)}" else m.value
    }

    fun norm(s: String?): String =
        Regex("\\s+").replace(Regex("[^\\p{L}\\p{N}_\\s]").replace((s ?: "").lowercase(), ""), " ").trim()

    // ---------------------------------------------------------------- pull_time

    /** Pulls the time phrases out of t: (when 'YYYY-MM-DDTHH:MM' or null, the rest, info). */
    fun pullTime(t0: String): Triple<String?, String, Info> {
        val t = spokenClock(t0)
        val n = now()
        val today = n.toLocalDate()
        var day: LocalDate? = null
        var hour: Pair<Int, Int>? = null
        var evening: Pair<Int, Int>? = null
        val info = Info()
        var rest = t

        fun cut(m: MatchResult) { rest = rest.substring(0, m.s) + " " + rest.substring(m.e) }
        fun deadlineBefore(m: MatchResult): MatchResult? {
            val d = search("\\b(?:(?:is|are)\\s+)?(?:due|by)(?:\\s+on)?\\s*$", rest.substring(0, m.s))
            if (d != null) info.deadline = true
            return d
        }

        var m = search("\\bin\\s+(?:($NUMWORD|\\d+\\.\\d+)\\s+(minutes?|mins?|hours?)(\\s+and\\s+a\\s+half)?|(half\\s+an?\\s+hour)" +
            "|(an?\\s+hour\\s+and\\s+a\\s+half))\\b", rest)
        if (m != null) {
            val micros: Long = when {
                m.g(4) != null -> 30L * 60_000_000
                m.g(5) != null -> 90L * 60_000_000
                else -> {
                    val k = amount(m.g(1)!!) + if (m.g(3) != null) 0.5 else 0.0
                    (k * (if (m.g(2)!!.lowercase().startsWith("h")) 3_600_000_000.0 else 60_000_000.0)).roundToLong()
                }
            }
            cut(m)
            info.timed = true
            return Triple(fmt(n.plusNanos(micros * 1000)), rest, info)
        }
        val pats: List<Pair<String, (MatchResult) -> LocalDate?>> = listOf(
            "\\bin\\s+($NUMWORD)\\s+(days?|weeks?)\\b" to { x ->
                today.plusDays(amount(x.g(1)!!).toLong() * (if (x.g(2)!!.lowercase()[0] == 'w') 7 else 1))
            },
            "\\b(?:the\\s+)?day\\s+after\\s+tomorrow\\b" to { _ -> today.plusDays(2) },
            "\\btomorrow\\b" to { _ -> today.plusDays(1) },
            "\\b(?:today|tonight)\\b" to { _ -> today },
            "\\b(?:on\\s+)?(?:the\\s+)?($ORDINAL)\\s+of\\s+($MONTHS)\\b|\\b(?:on\\s+)?($MONTHS)\\.?\\s+(?:the\\s+)?($ORDINAL)\\b" to { x ->
                monthDay(today, x.g(2) ?: x.g(3), x.g(1) ?: x.g(4)!!)
            },
            "\\b(?:on\\s+)?the\\s+(\\d{1,2}(?:st|nd|rd|th)|first|second|third|fifteenth|twentieth|last)\\b(?!\\s+(?:time|one|thing|step|day|week))" to { x ->
                monthDay(today, null, x.g(1)!!)
            },
            "\\b(?:on\\s+|next\\s+|this\\s+|coming\\s+)?($DAYS_RX)\\b" to { x ->
                val ahead = Math.floorMod(DAYS.indexOf(x.g(1)!!.lowercase()) - weekday(today), 7)
                today.plusDays((if (ahead == 0) 7 else ahead).toLong())
            },
            "\\b(?:on\\s+|this\\s+|next\\s+)?the\\s+weekend\\b|\\b(?:this|next)\\s+weekend\\b" to { _ ->
                today.plusDays(Math.floorMod(5 - weekday(today), 7).toLong())
            },
        )
        for ((pat, fn) in pats) {
            val x = search(pat, rest) ?: continue
            day = fn(x) ?: continue
            val dl = deadlineBefore(x)
            if (match("tonight", x.value) != null) evening = 20 to 0
            cut(x)
            if (dl != null) rest = rest.substring(0, dl.s) + " " + rest.substring(dl.e)
            break
        }
        m = search("\\b(?:at\\s+|by\\s+)?(noon|midday|midnight)\\b", rest)
        if (m != null) {
            if (m.g(1)!!.lowercase() == "midnight") {
                hour = 0 to 0
                day = (day ?: today).plusDays(1)  // "Friday at midnight" is the night of Friday
            } else hour = 12 to 0
            cut(m)
            info.timed = true
        }
        m = if (hour != null) null else (search("\\b(?:at\\s+|by\\s+)?(\\d{1,2})(?::(\\d\\d))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)(?!\\w)", rest)
            ?: search("\\bat\\s+(\\d{1,2})(?::(\\d\\d))?()\\b(?!\\s*(?:minutes?|mins?|hours?|days?|%))", rest))
        if (m != null) {
            var h = m.g(1)!!.toInt()
            val mi = (m.g(2) ?: "0").toInt()
            val ap = (m.g(3) ?: "").lowercase().replace(".", "")
            if (ap == "pm" && h < 12) h += 12
            else if (ap == "am" && h == 12) h = 0
            else if (ap.isEmpty() && (h < 7 || evening != null && h < 12)) h += 12  // "at 5" means 5 pm
            if (h < 24 && mi < 60) {
                hour = h to mi
                info.timed = true
                if (match("by\\b", m.value) != null) info.deadline = true
                cut(m)
            }
            val m2 = search("\\b(?:in\\s+the\\s+|this\\s+)?(afternoon|evening|night)\\b", rest)
            val hh = hour
            if (hh != null && m2 != null && ap.isEmpty() && hh.first < 12) hour = (hh.first + 12) to hh.second
        }
        for ((word, hh) in listOf("morning" to 9, "afternoon" to 14, "evening" to 19, "night" to 20,
            "(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?(?:work\\s+)?day" to 17)) {
            val mm = search("\\b(?:in\\s+the\\s+|this\\s+|at\\s+|by\\s+)?$word\\b", rest) ?: continue
            if (hour == null) { hour = hh to 0; info.timed = true }
            cut(mm)
        }
        if (evening != null && hour == null) { hour = evening; info.timed = true }
        if (day == null && hour == null) return Triple(null, t, info)
        val d = day ?: today
        val hm = hour ?: (9 to 0)
        var w = d.atTime(hm.first, hm.second)
        if (w < n && d == today) w = w.plusDays(1)
        return Triple(fmt(w), rest, info)
    }

    /** 'the 20th' (this month, or next once it's gone), 'December 3rd' (this year, or next). */
    fun monthDay(today: LocalDate, month: String?, day: String): LocalDate? {
        val d = ordinal(day)
        if (month != null) {
            val mo = MONTH_NAMES.indexOf(month.lowercase().take(3)) + 1
            for (y in listOf(today.year, today.year + 1)) {
                var c = try { LocalDate.of(y, mo, if (d > 0) d else 1) } catch (_: Exception) { return null }
                if (d < 0) c = c.withDayOfMonth(c.lengthOfMonth())
                if (c >= today) return c
            }
            return null
        }
        for (k in 0L..12L) {
            val first = today.withDayOfMonth(1).plusMonths(k)
            val c = if (d < 0) first.withDayOfMonth(first.lengthOfMonth()) else if (d <= first.lengthOfMonth()) first.withDayOfMonth(d) else null
            if (c != null && c >= today) return c
        }
        return null
    }

    // ---------------------------------------------------------------- pull_repeat

    private val UNITS = mapOf("minute" to "MINUTELY", "min" to "MINUTELY", "hour" to "HOURLY", "day" to "DAILY",
        "week" to "WEEKLY", "month" to "MONTHLY", "year" to "YEARLY")
    private val UNIT_MIN = mapOf("MINUTELY" to 1.0, "HOURLY" to 60.0, "DAILY" to 1440.0, "WEEKLY" to 10080.0)
    private val SMALLER = mapOf("HOURLY" to "MINUTELY", "DAILY" to "HOURLY", "WEEKLY" to "DAILY")
    private val ADVERBS = linkedMapOf("hourly" to "FREQ=HOURLY", "daily" to "FREQ=DAILY", "nightly" to "FREQ=DAILY",
        "weekly" to "FREQ=WEEKLY", "monthly" to "FREQ=MONTHLY", "yearly" to "FREQ=YEARLY", "annually" to "FREQ=YEARLY")

    /** 'every 2 weeks', 'every Tuesday and Thursday', 'daily for the next week' -> (RRULE, rest). */
    fun pullRepeat(t0: String): Pair<String?, String> {
        var t = t0
        val num = "(\\d+(?:\\.\\d+)?|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|other|second)"
        val unit = "(minutes?|mins?|hours?|days?|weeks?|months?|years?)"
        val day = "(?:mon|tues|wednes|thurs|fri|satur|sun)days?"
        var rule: String? = null
        var m = search("\\b(?:on\\s+)?the\\s+($ORDINAL)\\s+(?:day\\s+)?of\\s+(?:every|each|the)\\s+month\\b" +
            "|\\bevery\\s+month\\s+on\\s+the\\s+($ORDINAL)\\b", t)
        if (m != null) rule = "FREQ=MONTHLY;BYMONTHDAY=${ordinal(m.g(1) ?: m.g(2)!!)}"
        else m = search("\\bevery\\s+(?:$num\\s+)?$unit\\b", t)
        if (m != null && rule == null) {
            val k = (m.g(1) ?: "1").lowercase()
            var n = if (k == "other" || k == "second") 2.0 else if (k[0].isDigit()) k.toDouble() else (WORDNUM[k] ?: 1).toDouble()
            var freq = UNITS[m.g(2)!!.lowercase().removeSuffix("s")]!!
            while (n != Math.floor(n) && freq in SMALLER) {  // 11.46 hours -> 688 minutes
                n = n * UNIT_MIN[freq]!! / UNIT_MIN[SMALLER[freq]!!]!!
                freq = SMALLER[freq]!!
            }
            val ni = maxOf(1L, Math.rint(n).toLong())
            rule = "FREQ=$freq" + if (ni > 1) ";INTERVAL=$ni" else ""
        }
        if (m == null) {
            m = search("\\bevery\\s+weekday\\b", t)
            if (m != null) rule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
        }
        if (m == null) {
            m = search("\\b(every|on)\\s+(?:(other|second)\\s+)?($day(?:\\s*(?:,\\s*and|,|and|&)\\s*$day)*)", t)
            if (m != null && (m.g(1)!!.lowercase() == "every" || m.g(3)!!.lowercase().endsWith("s"))) {
                val found = rx(day).findAll(m.g(3)!!).map { it.value }.toList()
                val byday = found.map { DAYS.indexOf(it.lowercase().removeSuffix("s")) }.toSortedSet()
                rule = "FREQ=WEEKLY;" + (if (m.g(2) != null) "INTERVAL=2;" else "") + "BYDAY=" + byday.joinToString(",") { WEEKDAYS[it] }
            } else m = null
        }
        if (m == null) {
            val x = search("\\bevery\\s+(morning|afternoon|evening|night)\\b", t)
            if (x != null) {
                rule = "FREQ=DAILY"
                t = t.substring(0, x.s) + (if (x.g(1)!!.lowercase() == "night") "tonight" else x.g(1)) + t.substring(x.e)
                // m stays null: the time-of-day word is kept for pull_time
            }
        }
        if (m == null && rule == null) {
            // "pay rent monthly", "take pills daily at 8", but not "read the weekly report"
            m = search("\\b(" + ADVERBS.keys.joinToString("|") + ")\\b(?=\\s*(?:$|[,.!]|at\\b|for\\b|until\\b|on\\b|in the\\b))", t)
            if (m != null) rule = ADVERBS[m.g(1)!!.lowercase()]
        }
        if (rule == null) return null to t
        if (m != null) t = (t.substring(0, m.s) + t.substring(m.e)).stripChars(" ,")
        val e = search("\\bfor (?:the )?(?:next )?(?:$num\\s+)?$unit\\b", t)
        if (e != null) {
            val k = (e.g(1) ?: "1").lowercase()
            val n = if (k[0].isDigit()) k.toDouble() else (WORDNUM[k] ?: 1).toDouble()
            val u = UNITS[e.g(2)!!.lowercase().removeSuffix("s")]!!
            val spanMicros: Long = when (u) {
                "MONTHLY" -> (30.5 * n * 86_400_000_000.0).roundToLong()
                "YEARLY" -> (365 * n * 86_400_000_000.0).roundToLong()
                else -> (UNIT_MIN[u]!! * n * 60_000_000.0).roundToLong()
            }
            val last = now().plusNanos(spanMicros * 1000)
            rule += ";UNTIL=" + if (spanMicros >= 86_400_000_000L) last.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            else last.atZone(zone()).withZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
            t = (t.substring(0, e.s) + t.substring(e.e)).stripChars(" ,")
        }
        return rule to Regex("\\s{2,}").replace(t, " ")
    }

    /** The first reminder of a repeating task: the time said, else 09:00, on a day the rule allows. */
    fun firstTime(whenS: String?, rule: Rule): String {
        val n = now()
        var t: LocalDateTime
        if (whenS != null) t = parseLocal(whenS)!!
        else if (rule.freq == "MINUTELY" || rule.freq == "HOURLY") {
            t = n.withSecond(0).withNano(0)
            return fmt(if (rule.freq == "MINUTELY") t.plusMinutes(rule.interval) else t.plusHours(rule.interval))
        } else {
            t = n.withHour(9).withMinute(0).withSecond(0).withNano(0)
            if (t < n) t = t.plusDays(1)
        }
        val md = rule.parts["BYMONTHDAY"] ?: ""
        for (i in 0 until 366) {
            if (md.isNotEmpty() && md.all(Char::isDigit) && t.dayOfMonth != md.toInt()) { t = t.plusDays(1); continue }
            if (rule.byday.isEmpty() || weekday(t.toLocalDate()) in rule.byday) break
            t = t.plusDays(1)
        }
        return fmt(t)
    }

    // ---------------------------------------------------------------- the rest of basic_parse

    /** The words left once the phrases are out, as a title. */
    fun tidy(t0: String): String {
        var t = Regex("\\s+").replace(t0, " ").stripChars(" ,;:-")
        repeat(3) {
            t = rx("^(?:to|that|about|and|then|also|so|please)\\s+").replace(t, "")
            t = rx("\\s+(?:by|due|on|at|next|the|is|are|and|to|for|in|from)$").replace(t, "").stripChars(" ,;:-")
        }
        return t
    }

    /** 'when I get home', 'once I leave work' -> (place, arrive|leave, rest). */
    fun pullPlace(t: String, places: List<String>): Triple<String?, String?, String> {
        val m = search("\\b(?:when|once|as soon as|whenever|after)\\s+i(?:'m|\\s+am)?\\s*(get back to|get back|get to|get|" +
            "arrive at|arrive|reach|go to|leave|head out of|walk out of|am at|at|)\\s+(?:to\\s+|at\\s+|from\\s+)?", t)
            ?: return Triple(null, null, t)
        val after = t.substring(m.e)
        val known = (places.filter { it.isNotEmpty() }.map { it.lowercase() } + COMMON_PLACES).toSet().sortedByDescending { it.length }
        var pm: MatchResult? = null
        for (name in known) {
            pm = match("(?:the\\s+|my\\s+)?${Regex.escape(name)}\\b", after)
            if (pm != null) break
        }
        if (pm == null) {  // a place we don't know: up to a comma, or the next few words
            pm = match("(?:the\\s+|my\\s+)?[\\w']+(?:\\s+(?!to\\b|and\\b|i\\b|remind\\b)[\\w']+){0,2}(?=\\s*,|\\s*$)", after)
                ?: match("(?:the\\s+|my\\s+)?[\\w']+", after)
        }
        if (pm == null) return Triple(null, null, t)
        val name = rx("^(?:the|my)\\s+").replace(pm.value.trim(), "")
        val verb = (m.g(1) ?: "").lowercase()
        val on = if (verb in listOf("leave", "head out of", "walk out of")) "leave" else "arrive"
        return Triple(name, on, t.substring(0, m.s) + " " + after.substring(pm.e))
    }

    /** An open task a capture may name: its id in the prompt ("t3"), its uid and its data. */
    class Task(val id: String, val uid: String, val data: Map<String, Any?>)

    /** The one open task called name (or whose title holds it), or null. */
    fun findTask(name: String, tasks: List<Task>): Task? {
        val want = norm(rx("^(?:the|my)\\s+").replace(name, ""))
        var hits = tasks.filter { norm(it.data["title"] as? String) == want }
        if (hits.isEmpty()) hits = tasks.filter { want.isNotEmpty() && want in norm(it.data["title"] as? String) }
        return if (hits.size == 1) hits[0] else null
    }

    private fun basicMove(t: String, tasks: List<Task>): MutableMap<String, Any?>? {
        val m = match("^(?:please\\s+)?(?:move|push|push back|reschedule|change|put off|postpone)\\s+(.+?)" +
            "(?:\\s+(?:task|reminder))?\\s+(?:to|till|until|for)\\s+(.+)$", t) ?: return null
        val hit = findTask(m.g(1)!!, tasks) ?: return null
        var (w, _, info) = pullTime(m.g(2)!!)
        if (w == null) return null
        val d = hit.data
        val ch = linkedMapOf<String, Any?>("task" to hit.id)
        val due = d["due"] as? String
        if (!due.isNullOrEmpty()) {
            ch["due"] = if (info.timed) w else w.take(10) + due.drop(10)  // keeps a timed due's time
        } else {
            val r = (d["remind_at"] as? Number)?.toLong()
            if (!info.timed && r != null && r != 0L) w = w.take(11) + local(r).format(DateTimeFormatter.ofPattern("HH:mm"))
            ch["remind_at"] = w
        }
        return linkedMapOf("summary" to "Moved ${d["title"] ?: ""}", "items" to mutableListOf<Any?>(), "changes" to mutableListOf<Any?>(ch))
    }

    private fun basicDone(t: String, tasks: List<Task>): Task? {
        val m = match("^(?:please\\s+)?(?:mark|tick off|check off|cross off|complete)\\s+(?:the\\s+)?(.+?)" +
            "(?:\\s+task)?(?:\\s+(?:as\\s+)?(?:done|complete|completed|finished))?$", t)
            ?: match("^i(?:'ve|\\s+have)?\\s+(?:just\\s+)?(?:finished|completed|done)\\s+(?:the\\s+)?(.+?)(?:\\s+task)?$", t)
        if (m == null || tasks.isEmpty()) return null
        return findTask(m.g(1)!!, tasks)
    }

    /** What we can do without the LLM. capture: the capture's data (type, text, screen_text, app). */
    fun basicParse(capture: Map<String, Any?>, places: List<String> = emptyList(), tasks: List<Task> = emptyList()): MutableMap<String, Any?> {
        val text = ((capture["text"] as? String) ?: "").trim()
        if (capture["type"] == "screenshot" && text.isEmpty()) {
            val screen = ((capture["screen_text"] as? String) ?: "").trim()
            val title = ((capture["app"] as? String)?.ifEmpty { null } ?: "Screenshot").split(".").last().capitalizePy()
            return linkedMapOf("summary" to "Screenshot of $title",
                "items" to mutableListOf<Any?>(linkedMapOf("type" to "note", "title" to "Screenshot: $title", "notes" to screen.take(2000))))
        }
        if (text.isEmpty()) return linkedMapOf("summary" to "Empty", "items" to mutableListOf<Any?>())
        var t = Regex("\\s+").replace(text, " ").trim().trimEnd('.', '!', '?')
        basicDone(t, tasks)?.let { done ->
            return linkedMapOf("summary" to "Done: ${done.data["title"] ?: ""}", "items" to mutableListOf<Any?>(),
                "changes" to mutableListOf<Any?>(linkedMapOf("task" to done.id, "done" to true)))
        }
        basicMove(t, tasks)?.let { return it }
        var task = false
        val (rep, t1) = pullRepeat(t)
        t = t1
        var lst: String? = null
        var m = match("^(?:please\\s+)?(?:put|add)\\s+(.+?)\\s+(?:on|to|in|into)\\s+(?:my|the)\\s+(.+?)\\s+list$", t)
        if (m != null) { lst = m.g(2); t = m.g(1)!!; task = true }
        m = match(LEADS, t)
        if (m != null) { t = t.substring(m.e); task = true }
        val (place, placeOn, t2) = pullPlace(t, places)
        t = t2
        var (w, t3, info) = pullTime(t)
        t = t3
        val remind = REMIND.containsMatchIn(text)
        var due2: String? = null
        if (w != null && remind && !info.deadline) {
            // "remind me Wednesday that the homework is due Friday": the second day is the deadline
            val (w2, tt2, i2) = pullTime(t)
            if (w2 != null && i2.deadline) { due2 = if (i2.timed) w2 else w2.take(10); t = tt2 }
        }
        var notes = ""
        val b = search("\\b($NUMWORD)\\s+(minutes?|mins?|hours?)\\s+before\\b", t)
        if (b != null && w != null && rep == null) {
            // "10 minutes before my 3 pm meeting": ring that much earlier; the meeting goes in notes
            val k = amount(b.g(1)!!)
            val micros = (k * (if (b.g(2)!!.lowercase()[0] == 'h') 3_600_000_000.0 else 60_000_000.0)).roundToLong()
            w = fmt(parseLocal(w)!!.minusNanos(micros * 1000))
            t = t.substring(0, b.s) + " before " + t.substring(b.e)
            val v = search("\\bto\\s+(?=(?:$VERBS)\\b)", t)
            if (v != null) { notes = tidy(t.substring(0, v.s)).capitalizePy(); t = t.substring(v.e) }
        }
        if (rep != null) w = firstTime(w, parseRule(rep)!!)
        t = tidy(t)
        if (rep != null || place != null || w != null || match("(?:$VERBS)\\b", t) != null) task = true
        if (!task) {
            var title = if (t.isNotEmpty()) t.upFirst() else text
            val item = linkedMapOf<String, Any?>("type" to "note", "title" to title, "notes" to "")
            if (title.length > 80) {
                item["notes"] = title
                val cut = title.take(60)
                title = (if (' ' in cut) cut.substringBeforeLast(' ') else cut) + "…"
                item["title"] = title
            }
            return linkedMapOf("summary" to item["title"], "items" to mutableListOf<Any?>(item))
        }
        // "buy eggs, call the bank and email Mr. Smith": a task each, when every part starts with a verb
        var parts = rx(",\\s*(?:and\\s+|then\\s+)?|\\s+(?:and\\s+)?then\\s+|\\s+and\\s+(?=(?:$VERBS)\\b)").split(t).filter { it.isNotBlank() }
        if (parts.size < 2 || !parts.all { match("(?:$VERBS)\\b", it.trim()) != null }) parts = listOf(t)
        val items = mutableListOf<Any?>()
        for (p in parts) {
            val part = tidy(p).ifEmpty { text }
            val item = linkedMapOf<String, Any?>("type" to "task", "title" to part.upFirst(), "notes" to notes)
            if (due2 != null) item["due"] = due2
            if (lst != null) item["list"] = lst
            if (place != null) { item["place"] = place; item["place_on"] = placeOn }
            if (rep != null) item["repeat"] = rep
            if (w != null) {
                if (info.deadline && rep == null) {
                    item["due"] = if (info.timed) w else w.take(10)
                    if (remind) item["remind_at"] = w
                } else item["remind_at"] = w
            }
            items.add(item)
        }
        return linkedMapOf("summary" to items.joinToString(", ") { (it as Map<*, *>)["title"].toString() }, "items" to items)
    }

    // ---------------------------------------------------------------- checking the filer's dates

    class Found(val ok: Set<LocalDate>, val use: LocalDate, val dl: Boolean)

    /** The days the capture's words name, empty when they name none. Null when they say something
     *  not to second-guess: a calendar date, a repeat, or a span of minutes or hours. */
    fun saidDays(text: String?, today: LocalDate): List<Found>? {
        var t = " " + Regex("\\s+").replace((text ?: "").lowercase(), " ") + " "
        if (Regex("\\b(?:every|each|daily|weekly|nightly|yesterday|last|ago|next (?:week|month|year))\\b" +
                "|\\b(?:$MONTHS)\\.? ?\\d|\\d(?:st|nd|rd|th)? (?:of )?(?:$MONTHS)\\b|\\bthe \\d{1,2}(?:st|nd|rd|th)\\b" +
                "|\\d{1,2}/\\d{1,2}|\\d{4}-\\d\\d|\\bin (?:$NUMWORD|half an?) (?:minutes?|mins?|hours?)\\b" +
                "|\\b(?:$DAYS_RX)s\\b").containsMatchIn(t)) return null
        val found = mutableListOf<Found>()
        fun add(m: MatchResult, ok: Set<LocalDate>, use: LocalDate) {
            val dl = Regex("\\b(?:due|by|before|deadline)(?: on)?\\s*$").containsMatchIn(t.substring(0, m.s))
            found.add(Found(ok, use, dl))
        }
        Regex("\\bday after tomorrow\\b").find(t)?.let { m ->
            add(m, setOf(today.plusDays(2)), today.plusDays(2))
            t = t.replace("day after tomorrow", " ".repeat("day after tomorrow".length))
        }
        for (m in Regex("\\btomorrow\\b").findAll(t)) add(m, setOf(today.plusDays(1)), today.plusDays(1))
        for (m in Regex("\\b(?:today|this (?:morning|afternoon|evening))\\b").findAll(t)) add(m, setOf(today), today)
        for (m in Regex("\\btonight\\b").findAll(t)) add(m, setOf(today, today.plusDays(1)), today)
        for (m in Regex("\\bin ($NUMWORD) (days?|weeks?)\\b").findAll(t)) {
            val k = m.g(1)!!
            val n = if (k.all(Char::isDigit)) k.toLong() else (WORDNUM[k] ?: 1).toLong()
            val d = today.plusDays(n * if (m.g(2)!!.startsWith("w")) 7 else 1)
            add(m, setOf(d), d)
        }
        for (m in Regex("\\b(next |this |coming )?($DAYS_RX)\\b").findAll(t)) {
            val ahead = Math.floorMod(DAYS.indexOf(m.g(2)!!) - weekday(today), 7)
            val coming = today.plusDays((if (ahead == 0) 7 else ahead).toLong())
            if (m.g(1) == "next ") add(m, setOf(coming, coming.plusDays(7)), coming)
            else add(m, setOf(today.plusDays(ahead.toLong()), coming), coming)
        }
        Regex("\\b(next |this |the |on the )?weekend\\b").find(t)?.let { m ->
            var sat = today.plusDays(Math.floorMod(5 - weekday(today), 7).toLong())
            if (weekday(today) == 6) sat = today.minusDays(1)
            val ok = listOf(sat, sat.plusDays(1)).filter { it >= today }.toMutableSet()
            if (m.g(1) == "next ") ok += listOf(sat.plusDays(7), sat.plusDays(8))
            add(m, ok, ok.minByOrNull { it.toEpochDay() }!!)
        }
        return found
    }

    private fun dayFor(found: List<Found>?, field: String): Found? {
        if (found.isNullOrEmpty()) return null
        val pick = found.filter { it.dl == (field == "due") }.ifEmpty { found }
        return if (pick.map { it.ok }.toSet().size == 1) pick[0] else null
    }

    private val TIME_WORDS = rx("\\b(?:morning|afternoon|evening|night|tonight|noon|midday|midnight|lunch|dinner|" +
        "breakfast|supper|bed|bedtime|end of (?:the )?(?:work ?)?day|after|before|later|soon|" +
        "earlier|now|minutes?|mins?|hours?|o'?clock)\\b|\\d")
    private val CLOCK = rx("\\b\\d{1,2}(?::\\d\\d)?\\s*(?:am|pm|a\\.m\\.|p\\.m\\.)(?!\\w)|\\bat \\d{1,2}(?::\\d\\d)?\\b|\\b(?:noon|midday|midnight)\\b")

    /** "HH:MM" when the words give exactly one clock time and nothing shifts it, "" when they give
     *  no time of day at all, null when it isn't that simple. */
    fun saidTime(text0: String?): String? {
        val text = spokenClock(text0)
        val t = text.lowercase()
        if (Regex("\\b(?:before|after|earlier|later|ago|in (?:\\d+|an?|half)[^.,]{0,12}(?:minutes?|mins?|hours?))\\b").containsMatchIn(t)) return null
        val clocks = CLOCK.findAll(t).count()
        if (clocks == 1) {
            val (_, rest) = pullRepeat(text)
            val (w, _, info) = pullTime(rest)
            return if (w != null && info.timed) w.substring(11, 16) else null
        }
        if (clocks == 0 && !TIME_WORDS.containsMatchIn(Regex("\\b(?:$NUMWORD) (?:days?|weeks?)\\b|\\b\\d{1,2}(?:st|nd|rd|th)\\b").replace(t, ""))) return ""
        return null
    }

    /** 'in 20 minutes', 'in an hour and a half': the one span the words give, or null. */
    fun saidSpan(text: String?): Duration? {
        val t = (text ?: "").lowercase()
        val found = Regex("\\bin\\s+(?:(?:$NUMWORD|\\d+\\.\\d+)\\s+(?:minutes?|mins?|hours?)(?:\\s+and\\s+a\\s+half)?" +
            "|half\\s+an?\\s+hour|an?\\s+hour\\s+and\\s+a\\s+half)\\b").findAll(t).map { it.value }.toList()
        if (found.size != 1 || CLOCK.containsMatchIn(t) ||
            Regex("\\b(?:tomorrow|tonight|today|every|before|after)\\b|\\bin\\s+(?:$NUMWORD)\\s+(?:days?|weeks?)\\b").containsMatchIn(t)) return null
        val (w, _, _) = pullTime(found[0])
        return if (w != null) Duration.between(now().withSecond(0).withNano(0), parseLocal(w)!!) else null
    }

    private val LATER = rx("\\b(?:$MONTHS|months?|years?|weeks?|couple|few|after|until|later|some ?time|someday|" +
        "holidays?|christmas|spring|summer|fall|autumn|winter|semester|break)\\b")

    /** Checks the due and remind_at strings an item or change came with (in place, and returned).
     *  fresh: a new item. saidAt: when the capture was made, which "in 20 minutes" counts from. */
    fun settle(it: MutableMap<String, Any?>, text0: String?, now: LocalDateTime, fresh: Boolean = true, saidAt: LocalDateTime? = null): MutableMap<String, Any?> {
        val text = text0 ?: ""
        val span = saidSpan(text)
        if (span != null && !span.isZero && fresh && !truthy(it["repeat"])) it["remind_at"] = fmt((saidAt ?: now).plus(span))
        val rule = if (truthy(it["repeat"])) parseRule(it["repeat"]) else null
        val found = if (rule != null) null else saidDays(text, now.toLocalDate())
        for (f in listOf("due", "remind_at")) {
            val v = cleanDue(it[f])
            val said = dayFor(found, f)
            val night = if (v != null && v.drop(10) == "T00:00" && rx("\\bmidnight\\b").containsMatchIn(text)) 1L else 0L
            if (v != null && said != null && LocalDate.parse(v.take(10)).minusDays(night) !in said.ok) {
                // "Friday at midnight" is the night of Friday, as pull_time reads it
                it[f] = said.use.plusDays(night).toString() + v.drop(10)
            }
        }
        var v = cleanDue(it["remind_at"])
        if (found != null && found.isEmpty() && v != null && parseLocal(v)!! > now.plusDays(1) && !LATER.containsMatchIn(text)) {
            // No day said ("at the end of the day"): the next time that time of day comes round.
            val t = now.toLocalDate().atTime(parseLocal(v)!!.toLocalTime())
            it["remind_at"] = fmt(if (t >= now.minusMinutes(15)) t else t.plusDays(1))
        }
        if (fresh && REMIND.containsMatchIn(text) && listOf("remind_at", "due").none { f -> cleanDue(it[f]) != null } &&
            !truthy(it["place"]) && rule == null) {
            // Asked to be reminded but given no time: take the plain parser's reading of the words.
            val (rep, rest) = pullRepeat(text)
            val w = if (rep == null) pullTime(rest).first else null
            if (w != null) it["remind_at"] = w
        }
        if (rule == null || rule.freq !in listOf("MINUTELY", "HOURLY")) {
            val clock = saidTime(text)
            for (f in listOf("remind_at", "due")) {
                v = cleanDue(it[f])
                if (v != null && v.length > 10 && clock != null && (clock.isNotEmpty() || fresh && f == "remind_at")) {
                    it[f] = v.take(11) + clock.ifEmpty { "09:00" }  // a new reminder with no time said is at 09:00
                }
            }
        }
        val due = cleanDue(it["due"])
        if (fresh && due != null && cleanDue(it["remind_at"]) == null && !truthy(it["place"]) && REMIND.containsMatchIn(text)) {
            it["remind_at"] = if (due.length > 10) due else "${due}T09:00"
        }
        if (rule != null) return startOnRule(it, rule, text, now)
        for (f in listOf("remind_at", "due")) {
            v = cleanDue(it[f])
            var t = if (v != null && v.length > 10) parseLocal(v) else null
            if (t == null || t >= now.minusMinutes(15) ||
                (f == "due" && (t.toLocalDate() != now.toLocalDate() || rx("\\btoday\\b").containsMatchIn(text)))) continue
            val named = Regex("\\b(?:$DAYS_RX)\\b").containsMatchIn(text.lowercase())
            val month = Regex("\\b(?:$MONTHS)\\b").containsMatchIn(text.lowercase())
            while (t!! < now) {
                t = if (Duration.between(t, now) > Duration.ofDays(1) && month && !named) addMonths(t, 12)
                else if (Duration.between(t, now) > Duration.ofDays(1) && named) t.plusDays(7)
                else t.plusDays(1)
            }
            it[f] = fmt(t)
        }
        return it
    }

    /** A repeating task's first time on a day its rule allows. */
    private fun startOnRule(it: MutableMap<String, Any?>, rule: Rule, text: String, now: LocalDateTime): MutableMap<String, Any?> {
        for (f in listOf("remind_at", "due")) {
            val v = cleanDue(it[f]) ?: continue
            var t = parseLocal(v)!!
            if ((rule.freq == "DAILY" || rule.freq == "WEEKLY") && rule.byday.isNotEmpty() && weekday(t.toLocalDate()) !in rule.byday) {
                t = now.toLocalDate().atTime(t.toLocalTime())
                for (i in 0 until 8) {
                    if (weekday(t.toLocalDate()) in rule.byday && t >= now.minusMinutes(15)) break
                    t = t.plusDays(1)
                }
            }
            val m = if (rule.freq == "MONTHLY") search("\\bthe\\s+($ORDINAL)\\b", text) else null
            val d = if (m != null) monthDay(now.toLocalDate(), null, m.g(1)!!) else null
            if (d != null) {
                var c = d.atTime(t.toLocalTime())
                if (c < now) c = (monthDay(d.plusDays(1), null, m!!.g(1)!!) ?: d).atTime(t.toLocalTime())
                t = c
            }
            it[f] = if (v.length > 10) fmt(t) else t.toLocalDate().toString()
        }
        return it
    }

}
