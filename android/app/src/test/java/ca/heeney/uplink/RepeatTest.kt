package ca.heeney.uplink

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone

class RepeatTest {
    private val zone = ZoneId.of("America/Edmonton")
    private val fmt = DateTimeFormatter.ofPattern("EEE dd HH:mm", java.util.Locale.US)

    private val was = TimeZone.getDefault()

    @Before fun tz() = TimeZone.setDefault(TimeZone.getTimeZone(zone))

    @After fun restore() = TimeZone.setDefault(was)

    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toEpochSecond()
    private fun show(t: Long) = java.time.Instant.ofEpochSecond(t).atZone(zone).format(fmt)

    @Test fun timesOfDay() {
        val r = Repeat.parse("FREQ=DAILY;INTERVAL=2", listOf("18:00", "7:30"))!!
        val got = r.walk(at("2026-11-18T18:00")).take(5).map { show(it.second) }.toList()
        assertEquals(listOf("Wed 18 18:00", "Fri 20 07:30", "Fri 20 18:00", "Sun 22 07:30", "Sun 22 18:00"), got)
    }

    @Test fun countCountsEachTime() {
        val r = Repeat.parse("FREQ=WEEKLY;BYDAY=MO,WE;COUNT=4", listOf("08:00", "20:00"))!!
        val got = r.walk(at("2026-11-18T08:00")).map { show(it.second) }.toList()
        assertEquals(listOf("Wed 18 08:00", "Wed 18 20:00", "Mon 23 08:00", "Mon 23 20:00"), got)
    }

    @Test fun ringsAtEachTimeDoneOrNot() {
        val r = Repeat.parse("FREQ=DAILY;INTERVAL=2", listOf("07:30", "18:00"))!!
        val anchor = at("2026-11-18T18:00")
        // fired at 18:00, not ticked off: the next ring is Friday morning
        assertEquals("Fri 20 07:30", show(r.nextRing(anchor, anchor, false, at("2026-11-18T19:00"))!!))
        assertEquals(at("2026-11-20T07:30"), r.current(anchor, anchor, false, at("2026-11-20T07:31")))
    }

    @Test fun oneTimeIsThePlainRule() {
        val r = Repeat.parse("FREQ=DAILY", listOf("07:30"))!!
        val got = r.walk(at("2026-11-18T18:00")).take(2).map { show(it.second) }.toList()
        assertEquals(listOf("Wed 18 18:00", "Thu 19 18:00"), got)
    }
}
