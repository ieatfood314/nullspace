package ca.heeney.uplink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DisfluencyTest {
    private val model by lazy {
        Disfluency.parse(File("src/main/assets/disfluency-filter.json").readText())
    }

    @Test fun clearRepeatIsRemoved() {
        assertEquals("i want to talk about the new plan for tomorrow",
            Disfluency.filter("i want to talk about the the new plan for tomorrow", model))
    }

    @Test fun normalProseAndShortCommandsStay() {
        val normal = "this is a normal sentence that should stay completely unchanged"
        assertEquals(normal, Disfluency.filter(normal, model))
        assertEquals("can you not notify me", Disfluency.filter("can you not notify me", model))
    }

    @Test fun pythonValidationFixturesMatch() {
        val resource = javaClass.classLoader!!.getResource("disfluency-cases.json")!!.readText()
        val cases = JSONObject(resource).getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val before = case.getString("before")
            assertEquals("case $i: $before", case.getString("after"), Disfluency.filter(before, model))
        }
    }
}
