package ca.heeney.uplink

import org.junit.Assert.assertEquals
import org.junit.Test

class CleanupTest {
    @Test fun clippedWordGetsItsSpace() {
        assertEquals("if you want, 'cause I'm asking", Cleanup.spaceClippedWords("if you want,'cause I'm asking"))
        assertEquals("really? 'cause", Cleanup.spaceClippedWords("really?'cause"))
        assertEquals("the, '90s", Cleanup.spaceClippedWords("the,'90s"))
        assertEquals("want, ’cause", Cleanup.spaceClippedWords("want,’cause"))
        assertEquals("Done. 'Cause it works", Cleanup.spaceClippedWords("Done.'Cause it works"))
    }

    @Test fun realApostrophesStay() {
        for (t in listOf("don't stop", "the U.S.'s rules", "he said 'hi,' then left", "it's"))
            assertEquals(t, Cleanup.spaceClippedWords(t))
    }

    @Test fun clearDisfluencyIsLocal() {
        assertEquals("the equilibrium constant", Cleanup.tidyDisfluency("the equ equilibrium constant"))
        assertEquals("never ever crazy crazy", Cleanup.tidyDisfluency("never ever crazy crazy"))
        assertEquals("the thesis", Cleanup.tidyDisfluency("the thesis"))
        assertEquals("can you not notify me", Cleanup.tidyDisfluency("can you not notify me"))
        assertEquals("on Wednesday", Cleanup.tidyDisfluency("on Tuesday, no, Wednesday"))
        assertEquals("iron three", Cleanup.tidyDisfluency("copper, sorry, iron three"))
    }
}
