package ca.heeney.uplink

import java.util.Locale

/** A BCP-47 language tag, or "auto". Parakeet detects the language itself;
 *  regional spelling and custom words use the same choice. */
object Languages {
    private val TAG = Regex("""^[A-Za-z]{2,3}(?:-[A-Za-z]{3}){0,3}(?:-[A-Za-z]{4})?(?:-(?:[A-Za-z]{2}|\d{3}))?(?:-(?:[A-Za-z0-9]{5,8}|\d[A-Za-z0-9]{3}))*(?:-[0-9A-WY-Za-wy-z](?:-[A-Za-z0-9]{2,8})+)*(?:-[Xx](?:-[A-Za-z0-9]{1,8})+)?$""")
    private val aliases = mapOf(
        "auto" to "auto", "automatic" to "auto", "detect automatically" to "auto",
        "english" to "en", "us english" to "en-US", "american english" to "en-US",
        "canadian english" to "en-CA", "burmese" to "my", "myanmar" to "my",
        "haitian creole" to "ht", "haitian-creole" to "ht",
    )

    /** Accept a tag (fr-CA), a Whisper language code (yue), or an English language name (French). */
    fun normalize(input: String): String? {
        val raw = input.trim().replace('_', '-')
        if (raw.isEmpty()) return null
        aliases[raw.lowercase()]?.let { return it }
        val named = if ('-' !in raw && raw.length > 3) {
            Locale.getAvailableLocales().firstOrNull {
                it.country.isEmpty() && it.getDisplayLanguage(Locale.ENGLISH).equals(raw, ignoreCase = true)
            }?.language
        } else null
        val tag = named ?: raw
        if (!TAG.matches(tag)) return null
        val locale = Locale.forLanguageTag(tag)
        if (locale.language.length !in 2..3 || locale.language == "und") return null
        return locale.toLanguageTag()
    }

    fun name(tag: String): String = when (normalize(tag)) {
        "auto" -> "Automatic"
        "en-US" -> "US English"
        "en-CA" -> "Canadian English"
        else -> Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH).replaceFirstChar { it.uppercase() }
    }
}
