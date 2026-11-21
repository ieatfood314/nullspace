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

    @Test fun canadianSpelling() {
        assertEquals("My favourite colours, with honour.",
            Cleanup.applyLanguage("My favorite colors, with honor.", "en-CA"))
        assertEquals("COLOUR Favour favour", Cleanup.applyLanguage("COLOR Favor favor", "en-CA"))
        assertEquals("The neighbour's grey travelled-centre theatre.",
            Cleanup.applyLanguage("The neighbor's gray traveled-center theater.", "en-CA"))
        assertEquals("Labourers were cancelling and modelling.",
            Cleanup.applyLanguage("Laborers were canceling and modeling.", "en-CA"))
    }

    @Test fun customTermsAreProtectedFromCanadianConversion() {
        assertEquals("color favour", Cleanup.applyLanguage("color favor", "en-CA", listOf("color")))
        assertEquals("color and colour", Cleanup.applyLanguage("color and colour", "en-CA", listOf("color")))
        assertEquals("favourite color", Cleanup.applyLanguage("favorite color", "en-CA", listOf("color")))
    }

    @Test fun spellingOnlyChangesSelectedWords() {
        val text = "Organize and analyze the colorful discolor; favorites stay favorites in US English."
        assertEquals(text, Cleanup.applyLanguage(text, "en-US"))
        assertEquals("Organize and analyze the colourful discolour; favourites stay favourites in US English.",
            Cleanup.applyLanguage(text, "en-CA"))
        assertEquals(text, Cleanup.applyLanguage(text, "fr-CA"))
    }
}
