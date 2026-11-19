package ca.heeney.uplink

import android.content.Context
import org.json.JSONObject
import java.io.File

/** The same tidying as desktop Squelch: filler words out, then your dictionary. */
object Cleanup {
    // Whole words only, and not inside hyphenated words ("uh-huh", "mm-hmm").
    // Plain "mm" is left alone: it's also millimetres.
    private val FILLER = Regex("""(?<![\w-])(u+h+|u+m+|e+r+|a+h+|e+h+|h+m+|m{3,})(?![\w-])""", RegexOption.IGNORE_CASE)

    fun defiller(text: String): String {
        var t = FILLER.replace(text, "")
        t = Regex("""([,.!?;:])(\s*\1)+""").replace(t, "$1")
        t = Regex("""\s+([,.!?;:])""").replace(t, "$1")
        t = Regex("""\s{2,}""").replace(t, " ")
        t = t.trim(' ', ',', ';', ':').trim()
        // A leading filler took the capital with it ("Um, so..." -> "so...").
        if (t.isNotEmpty() && text.firstOrNull()?.isUpperCase() == true) t = t.replaceFirstChar { it.uppercase() }
        return t
    }

    /** dictionary.json in the desktop format: {"entries": [{"term", "also_match": [...]}]} */
    fun dictFile(ctx: Context) = File(ctx.filesDir, "dictionary.json")

    private var cacheMtime = -1L
    private var subs: List<Pair<Regex, String>> = emptyList()

    @Synchronized private fun dictionary(ctx: Context): List<Pair<Regex, String>> {
        val f = dictFile(ctx)
        val mt = if (f.exists()) f.lastModified() else 0L
        if (mt == cacheMtime) return subs
        cacheMtime = mt
        subs = try {
            if (!f.exists()) emptyList() else buildList {
                val entries = JSONObject(f.readText()).optJSONArray("entries") ?: return@buildList
                for (i in 0 until entries.length()) {
                    val e = entries.optJSONObject(i) ?: continue
                    val term = e.optString("term").trim()
                    if (term.isEmpty()) continue
                    val ms = e.optJSONArray("also_match") ?: continue
                    for (j in 0 until ms.length()) {
                        val m = ms.optString(j).trim()
                        if (m.isEmpty()) continue
                        var pat = Regex.escape(m)
                        if (m.first().isLetterOrDigit()) pat = "\\b$pat"
                        if (m.last().isLetterOrDigit()) pat = "$pat\\b"
                        add(Regex(pat, RegexOption.IGNORE_CASE) to term)
                    }
                }
            }
        } catch (_: Exception) { emptyList() }
        return subs
    }

    // Parakeet's tokenizer glues a leading apostrophe onto what comes before
    // it: right for "don't", wrong for "want,'cause". After , ; : ! ? an
    // apostrophe then a letter or digit starts a clipped word ('cause, 'em,
    // 'til, '90s), so it gets its space back. After a period only when a
    // capital follows, so "U.S.'s" stays as it is.
    private val APOS_GLUE = Regex("""(?:(?<=[,;:!?])|(?<=\.)(?=['\u2019][A-Z]))(?=['\u2019][\p{L}\p{N}])""")

    fun spaceClippedWords(text: String): String = APOS_GLUE.replace(text, " ")

    fun tidy(ctx: Context, raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return ""
        t = spaceClippedWords(t)
        t = defiller(t)
        for ((rx, term) in dictionary(ctx)) t = rx.replace(t, Regex.escapeReplacement(term))
        return t
    }
}
