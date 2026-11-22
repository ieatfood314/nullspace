package ca.heeney.uplink

import org.junit.Assert.assertEquals
import org.junit.Test

class WordsTest {
    @Test fun legacyExperimentEntriesAreRemovedWithoutTouchingOriginalWords() {
        val existing = listOf(
            Words.Entry("Nullspace", listOf("Nell Space")),
            Words.Entry("after rot", listOf("after it")),
            Words.Entry("with omarchy", listOf("with Hallmarchie")),
        )
        val cleaned = Words.withoutLegacyExperimentEntries(existing)
        assertEquals(1, cleaned.size)
        assertEquals(Words.Entry("Nullspace", listOf("Nell Space")), cleaned[0])
        assertEquals(21, Words.LEGACY_EXPERIMENT_TERMS.size)
    }
}
