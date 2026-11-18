package ca.heeney.uplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The same cases as desktop Squelch's edited_region. */
class EditWatchTest {
    private fun region(t0: String, a: Int, b: Int, t: String): String? =
        EditWatch.editedRegion(t0, a, b, t)?.let { t.substring(it.first, it.second) }

    @Test fun regionFollowsEdits() {
        val p = "Before. "; val r = "hello world "; val s = "after"
        val t0 = p + r + s; val a = p.length; val b = a + r.length
        assertEquals(r, region(t0, a, b, t0))
        assertEquals("hello word ", region(t0, a, b, p + "hello word " + s))
        assertEquals(r, region(t0, a, b, p + r + "more stuff " + s))
        assertEquals(r, region(t0, a, b, "Typed first. " + p + r + s))
        assertEquals("hello worlds ", region(t0, a, b, p + "hello worlds " + s))
        assertEquals("hello there ", region(t0, a, b, p + "hello there " + s))
        assertEquals("", region(t0, a, b, p + s))
        assertEquals("Hello world, ", region(t0, a, b, p + "Hello world, " + s))
    }

    @Test fun regionAtTheEnd() {
        val t0 = "hello world "
        assertEquals("hello word ", region(t0, 0, t0.length, "hello word more stuff"))
        assertEquals("hello word.", region(t0, 0, t0.length, "hello word."))
        assertEquals("hello world ", region(t0, 0, t0.length, "hello world and more"))
        assertEquals("hello there", region("x hello world", 2, 13, "x hello there"))
    }

    @Test fun locateFindsTheDictation() {
        assertEquals(2 to 14, EditWatch.locate("x hello world y", "hello world ", 13))
        assertEquals(2 to 13, EditWatch.locate("x hello world", "hello world ", 13))
        assertEquals(12 to 24, EditWatch.locate("hello world hello world ", "hello world ", 24))
        assertNull(EditWatch.locate("abc", "hello ", 3))
        assertNull(EditWatch.locate("hi hi", "hi", -1))
    }
}
