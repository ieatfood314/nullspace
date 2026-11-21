package ca.heeney.uplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguagesTest {
    @Test fun automaticAndEnglishVariants() {
        assertEquals("auto", Languages.normalize("Automatic"))
        assertEquals("en-US", Languages.normalize("US English"))
        assertEquals("en-CA", Languages.normalize("Canadian English"))
        assertEquals("Automatic", Languages.name("auto"))
        assertEquals("Canadian English", Languages.name("en-CA"))
    }

    @Test fun otherNamesAndTags() {
        assertEquals("fr", Languages.normalize("French"))
        assertEquals("fr-CA", Languages.normalize("fr-ca"))
        assertEquals("zh-Hant", Languages.normalize("zh-hant"))
        assertEquals("yue", Languages.normalize("yue"))
        assertEquals("ca-ES-valencia", Languages.normalize("ca-es-valencia"))
        assertEquals("de-CH-1901", Languages.normalize("de-ch-1901"))
        assertEquals("en-u-ca-buddhist", Languages.normalize("en-u-ca-buddhist"))
        assertEquals("my", Languages.normalize("Burmese"))
        assertEquals("ht", Languages.normalize("Haitian Creole"))
        assertEquals("French (Canada)", Languages.name("fr-CA"))
    }

    @Test fun invalidTagsDoNotBecomeLanguages() {
        assertNull(Languages.normalize(""))
        assertNull(Languages.normalize("not-a"))
        assertNull(Languages.normalize("english; rm"))
    }
}
