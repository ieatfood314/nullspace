package ca.heeney.uplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CorrectionsTest {
    @Test fun correctionKeepsContext() {
        assertEquals("after rot" to "after root",
            Corrections.correctionReplacement("Anything to know after root other than banking?", "root", "rot"))
        assertEquals("still suck" to "still stock",
            Corrections.correctionReplacement("Does it still stock?", "stock?", "suck"))
        assertEquals("fluent Grug Speak" to "fluent Grugspeak",
            Corrections.correctionReplacement("Kimi speaks fluent Grugspeak.", "Grugspeak", "Grug Speak"))
    }

    @Test fun classifyRecognizesFixesNotRewrites() {
        val fix = Corrections.classify("Anything to know after root other than banking?",
            "Anything to know after rot other than banking?")
        assertEquals("fix", fix.kind)
        assertEquals(listOf("root" to "rot"), fix.fixes)

        val rewrite = Corrections.classify("one two three four", "something completely different")
        assertEquals("rewrite", rewrite.kind)

        val kept = Corrections.classify("same text", "same text")
        assertEquals("kept", kept.kind)
    }

    @Test fun punctuationDoesNotCreateBadRules() {
        assertNull(Corrections.correctionReplacement("Wait, it's available through API.",
            "API.", "API?"))
    }
}
