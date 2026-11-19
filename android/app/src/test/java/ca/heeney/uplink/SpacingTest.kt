package ca.heeney.uplink

import org.junit.Assert.assertEquals
import org.junit.Test

/** Cursor offsets and text as Obsidian's editor reported them on the phone (Android 16,
 *  WebView 157): the text has a break at each line's edge that the cursor doesn't count. */
class SpacingTest {
    // A note with an empty line between BBB and CCC, which this text leaves out (the cursor
    // counts it: the end of the note reads 24).
    private val note = "AAA aaa x\nBBB bbb\nCCC ccc"

    @Test fun plainFieldsAreExact() {
        assertEquals(" two", spaced("two", "one", 3, 3, true))
        assertEquals("two ", spaced("two", "three", 0, 0, true))
        assertEquals(" two", spaced("two", "one.", 4, 4, true))
        assertEquals("two", spaced("two", "one \nthree", 4, 4, true))
    }

    @Test fun endOfALineInObsidian() {
        // The cursor at the end of "BBB bbb" reads 16: with the break before BBB counted that
        // lands inside the word, which is what put dictations in the middle of a sentence.
        assertEquals(listOf(16, 17), cursorSpots(note, 16, false))
        assertEquals(" two ", spaced("two", note, 16, 16, false))
    }

    @Test fun endOfTheNoteInObsidian() {
        // 24 is the end of the note, one short of the text's length. It could also be just before
        // the last c, so it gets a space after too, which can't be seen at the end of a line.
        assertEquals(" four ", spaced("four", note, 24, 24, false))
    }

    @Test fun noBreaksBeforeTheCursorIsExact() {
        assertEquals(listOf(9), cursorSpots(note, 9, false))
        assertEquals(" one", spaced("one", note, 9, 9, false))
    }
}
