package ca.heeney.uplink

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A task's `repeat`: an iCalendar RRULE, of which we act on FREQ, INTERVAL, BYDAY, COUNT and
 *  UNTIL. The walk is the same as occurrences() in server/nullspace.py and web/app.js; keep the
 *  three in step. The hub and the page move a task on when it's marked done; the phone only
 *  needs the times, to remind at each one. `times` are the task's times of day ("07:30"): each day
 *  the rule gives, it comes up at every one of them, and COUNT counts each. */
class Repeat private constructor(
    private val freq: String,
    private val interval: Long,
    private val byday: List<Int>,
    private val count: Int?,
    private val until: Long?,
    private val times: List<LocalTime>,
) {
    companion object {
        private val FREQS = listOf("MINUTELY", "HOURLY", "DAILY", "WEEKLY", "MONTHLY", "YEARLY")
        private val DAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
        private val CLOCK = Regex("([01]?\\d|2[0-3]):([0-5]\\d)")

        fun parse(s: String?, times: List<String>? = null): Repeat? {
            if (s.isNullOrBlank()) return null
            val parts = s.trim().uppercase().removePrefix("RRULE:").split(";").mapNotNull {
                val k = it.substringBefore("=").trim()
                if (k.isEmpty()) null else k to it.substringAfter("=", "").trim()
            }.toMap()
            val freq = parts["FREQ"]?.takeIf { it in FREQS } ?: return null
            val interval = (parts["INTERVAL"]?.toLongOrNull() ?: 1L).coerceIn(1L, 100_000L)
            val byday = (parts["BYDAY"] ?: "").split(",").map { DAYS.indexOf(it.takeLast(2)) }.filter { it >= 0 }.distinct().sorted()
            val count = parts["COUNT"]?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }?.toIntOrNull()
            val clocks = if (freq == "MINUTELY" || freq == "HOURLY") emptyList() else (times ?: emptyList()).mapNotNull {
                CLOCK.matchEntire(it.trim())?.let { m ->
                    LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                }
            }.distinct().sorted().take(12)
            return Repeat(freq, interval, byday, count, until(parts["UNTIL"]), if (clocks.size > 1) clocks else emptyList())
        }

        private fun until(v: String?): Long? = try {
            val zone = ZoneId.systemDefault()
            when {
                v == null -> null
                Regex("\\d{8}").matches(v) ->
                    LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atTime(23, 59, 59).atZone(zone).toEpochSecond()
                Regex("\\d{8}T\\d{6}Z?").matches(v) -> {
                    val t = LocalDateTime.parse(v.removeSuffix("Z"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                    if (v.endsWith("Z")) t.toEpochSecond(ZoneOffset.UTC) else t.atZone(zone).toEpochSecond()
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    /** (index, epoch seconds): the anchor is 0, then each later time, until COUNT or UNTIL. */
    fun walk(anchor: Long, after: Long? = null): Sequence<Pair<Int, Long>> =
        if (times.isEmpty()) walkRule(anchor, after, count, until) else sequence {
            val zone = ZoneId.systemDefault()
            var i = 0
            yield(0 to anchor)
            // The rule's days, from the anchor's own day on, each at every time of day.
            for ((_, day) in walkRule(anchor, null, null, null)) {
                val date = Instant.ofEpochSecond(day).atZone(zone).toLocalDate()
                for (c in times) {
                    val t = date.atTime(c).atZone(zone).toEpochSecond()
                    if (t <= anchor) continue
                    i++
                    if ((count != null && i >= count) || (until != null && t > until)) return@sequence
                    yield(i to t)
                }
            }
        }

    private fun walkRule(anchor: Long, after: Long?, count: Int?, until: Long?): Sequence<Pair<Int, Long>> = sequence {
        val zone = ZoneId.systemDefault()
        val base = Instant.ofEpochSecond(anchor).atZone(zone)
        val wall = base.toLocalDateTime()
        fun ok(i: Int, t: Long) = (count == null || i < count) && (until == null || t <= until)
        yield(0 to anchor)
        if (freq == "WEEKLY" && byday.isNotEmpty()) {
            val monday = wall.minusDays((base.dayOfWeek.value - 1).toLong())
            var i = 0
            for (w in 0 until 100_000L) for (d in byday) {
                val t = monday.plusDays(w * 7 * interval + d).atZone(zone).toEpochSecond()
                if (t <= anchor) continue
                if (!ok(++i, t)) return@sequence
                yield(i to t)
            }
            return@sequence
        }
        val step = when (freq) { "MINUTELY" -> 60L * interval; "HOURLY" -> 3600L * interval; else -> 0L }
        var k = 1L
        if (step > 0 && after != null && byday.isEmpty() && after > anchor) k = maxOf(1L, (after - anchor) / step)
        var i = (k - 1).toInt()
        val end = k + 200_000
        while (k < end) {
            val t = when (freq) {
                "MINUTELY", "HOURLY" -> anchor + k * step
                "DAILY" -> wall.plusDays(k * interval).atZone(zone).toEpochSecond()
                "WEEKLY" -> wall.plusWeeks(k * interval).atZone(zone).toEpochSecond()
                "MONTHLY" -> wall.plusMonths(k * interval).atZone(zone).toEpochSecond()
                else -> wall.plusYears(k * interval).atZone(zone).toEpochSecond()
            }
            k++
            if (byday.isNotEmpty() && freq != "MONTHLY" && freq != "YEARLY" &&
                (Instant.ofEpochSecond(t).atZone(zone).dayOfWeek.value - 1) !in byday) continue
            if (!ok(++i, t)) return@sequence
            yield(i to t)
        }
    }

    /** The time that's up: the latest one at or before `now` that hasn't fired yet.
     *  A done task has already covered its anchor. */
    fun current(anchor: Long, fired: Long, done: Boolean, now: Long): Long? {
        var hit: Long? = null
        for ((_, t) in walk(anchor, now - 1)) {
            if (t > now) break
            if (t > fired && (!done || t > anchor)) hit = t
        }
        return hit
    }

    /** When to ring next: now, for a time that's up and missed, else the next one ahead. */
    fun nextRing(anchor: Long, fired: Long, done: Boolean, now: Long): Long? {
        if (current(anchor, fired, done, now) != null) return now
        val floor = maxOf(now, fired, if (done) anchor else 0L)
        return walk(anchor, floor).firstOrNull { it.second > floor }?.second
    }
}
