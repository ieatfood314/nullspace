package ca.heeney.uplink

import android.content.Context
import org.json.JSONObject
import kotlin.math.ln

/** Local false-start filter trained on Apache-2.0 DisfluencySpeech. No network calls. */
object Disfluency {
    internal data class Model(val intercept: Double, val cutoff: Double, val weights: Map<String, Double>)

    private var cached: Model? = null
    private var attempted = false

    @Synchronized private fun model(ctx: Context): Model? {
        if (!attempted) {
            attempted = true
            cached = try {
                parse(ctx.assets.open("disfluency-filter.json").bufferedReader().use { it.readText() })
            } catch (_: Exception) { null }
        }
        return cached
    }

    internal fun parse(json: String): Model {
        val root = JSONObject(json)
        val threshold = root.getDouble("threshold")
        require(threshold > 0 && threshold < 1)
        val objectWeights = root.getJSONObject("weights")
        val weights = HashMap<String, Double>(objectWeights.length() * 2)
        for (key in objectWeights.keys()) weights[key] = objectWeights.getDouble(key)
        return Model(root.getDouble("intercept"), ln(threshold / (1 - threshold)), weights)
    }

    private fun bare(word: String) = word.lowercase().trim('.', ',', '!', '?', ';', ':')
    private fun bool(value: Boolean) = if (value) "True" else "False"
    private val FUNCTION_WORDS = setOf(
        "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can", "could", "did", "do", "does",
        "for", "from", "had", "has", "have", "he", "her", "hers", "him", "his", "i", "if", "in", "into", "is",
        "it", "its", "me", "my", "of", "on", "or", "our", "she", "should", "so", "that", "the", "their", "them",
        "there", "these", "they", "this", "those", "to", "was", "we", "were", "what", "when", "which", "who",
        "will", "with", "would", "you", "your",
    )

    /** Only an exact stutter or an earlier content-bearing repeated phrase. */
    private fun supported(words: List<String>, index: Int): Boolean {
        val low = bare(words[index])
        if (index + 1 < words.size) {
            val followingToken = words[index + 1]
            val following = bare(followingToken)
            val exactSurfaceRepeat = words[index].lowercase() == followingToken.lowercase()
            val barePrefixRepeat = words[index].lowercase() == low && followingToken.lowercase() == following &&
                low.length >= 3 && following.length >= 3 && (low.startsWith(following) || following.startsWith(low))
            if (exactSurfaceRepeat || barePrefixRepeat) return true
        }
        val values = words.map(::bare)
        for (size in 2..4) {
            for (start in maxOf(0, index - size + 1) until minOf(index + 1, words.size - size + 1)) {
                val phrase = values.subList(start, start + size)
                if (phrase.all { it in FUNCTION_WORDS }) continue
                for (later in start + size..words.size - size) {
                    if (values.subList(later, later + size) == phrase) return true
                }
            }
        }
        return false
    }

    /** Keep these features identical to the training harness and desktop squelch-ptt. */
    private fun features(words: List<String>, index: Int): Array<String> {
        val token = words[index]
        val low = bare(token)
        val prev1 = if (index >= 1) bare(words[index - 1]) else "<start>"
        val prev2 = if (index >= 2) bare(words[index - 2]) else "<start2>"
        val next1 = if (index + 1 < words.size) bare(words[index + 1]) else "<end>"
        val next2 = if (index + 2 < words.size) bare(words[index + 2]) else "<end2>"
        val repeated = prev1 == low
        val partial = low.length >= 3 && prev1.length >= 3 && (low.startsWith(prev1) || prev1.startsWith(low))
        return arrayOf(
            "token=$low", "prev=$prev1", "prev2=$prev2", "next=$next1", "next2=$next2",
            "bigram=$prev1 $low", "bigram=$low $next1", "trigram=$prev1 $low $next1",
            "repeat=${bool(repeated)}", "partial=${bool(partial)}",
            "prefix=${low.take(3)}", "suffix=${low.takeLast(3)}",
            "len=${minOf(low.length, 20)}", "pos=${minOf(index, 30)}",
            "last=${bool(index + 1 == words.size)}", "first=${bool(index == 0)}",
            "comma_before=${bool(token.first() in ".,!?;:")}",
            "comma_after=${bool(token.last() in ".,!?;:")}",
            "upper=${bool(token.firstOrNull()?.isUpperCase() == true)}",
            "digit=${bool(token.any { it.isDigit() })}",
            "next_repeat=${bool(low == next1)}",
        )
    }

    internal fun filter(text: String, model: Model): String {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < Brain.TIDY_MIN_WORDS) return text
        val kept = ArrayList<String>(words.size)
        for (index in words.indices) {
            var score = model.intercept
            for (key in features(words, index)) score += model.weights[key] ?: 0.0
            var delete = score >= model.cutoff && supported(words, index)
            if (delete) {
                val terminal = words[index].last() in ".!?"
                val immediateRepeat = index + 1 < words.size && bare(words[index]) == bare(words[index + 1])
                val sentenceStart = index == 0 || words[index - 1].last() in ".!?"
                if (terminal || (sentenceStart && !immediateRepeat)) delete = false
            }
            if (!delete) kept += words[index]
        }
        return kept.joinToString(" ")
    }

    fun tidy(ctx: Context, text: String): String = model(ctx)?.let { filter(text, it) } ?: text
}
