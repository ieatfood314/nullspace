package ca.heeney.uplink

import org.junit.Assert.assertEquals
import org.junit.Test

class WordsTest {
    @Test fun experimentDictionaryMergesWithoutReplacingUserEntries() {
        val existing = listOf(Words.Entry("Nullspace", listOf("Nell Space")), Words.Entry("Omarchy", listOf("Oh Marchy")))
        val incoming = listOf(Words.Entry("nullspace", listOf("Now Space", "nell space")), Words.Entry("after rot", listOf("after it")))
        val merged = Words.mergeEntries(existing, incoming)
        assertEquals(3, merged.size)
        assertEquals(Words.Entry("Nullspace", listOf("Nell Space", "Now Space")), merged[0])
        assertEquals(Words.Entry("Omarchy", listOf("Oh Marchy")), merged[1])
        assertEquals(Words.Entry("after rot", listOf("after it")), merged[2])
    }
}
