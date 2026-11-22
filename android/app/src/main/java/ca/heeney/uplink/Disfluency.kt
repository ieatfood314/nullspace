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
    private val FUNCTION_WORDS = """a an and are as at be been but by can could did do does for from had has have he her
        hers him his i if in into is it its me my of on or our she should so that the their them
        there these they this those to was we were what when which who will with would you your
        about after again against ago all almost also always among amount another anyone around away
        because before behind being below between both down during each every first found further going
        gonna gotta great however just little look make many more most much need never only other out off
        over really right said same should since small still such take than then there these thing think
        those through too under until up want well went were what when where which while within would yeah
        yes gonna wanna stuff things okay ok word words say says tell told talk talking speak speaking""".split(Regex("\\s+")).toSet()
    private val NP_START = setOf("a", "an", "the", "this", "that", "these", "those", "my", "your", "our", "his", "her", "their", "its")
    private val VERBS = """am are be been being can could did do does doing go going went have has had having is was were
        hold held make makes made making take takes took taking get gets got getting use uses used using
        say says said saying tell tells told telling think thinks thought thinking know knows knew knowing
        want wants wanted wanting need needs needed needing like likes liked liking look looks looked looking
        work works worked working try tries tried trying see sees saw seeing show shows showed showing
        release releases released releasing update updates updated updating change changes changed changing
        open opens opened opening close closes closed closing run runs ran running give gives gave giving
        put puts putting set sets setting keep keeps kept keeping let lets letting start starts started starting
        stop stops stopped stopping play plays played playing speak speaks spoke speaking talk talks talked talking
        drive drives drove driving walk walks walked walking eat eats ate eating sit sits sat sitting
        stand stands stood standing read reads reading write writes wrote writing learn learns learned learning
        mean means meant meaning call calls called calling move moves moved moving happen happens happened happening
        seem seems seemed seeming feel feels felt feeling become becomes became becoming""".split(Regex("\\s+")).toSet()
    private val INCOMPLETE_END = setOf("am", "are", "be", "been", "being", "can", "could", "should", "would", "will", "do", "does", "did", "have", "has", "had", "is", "was", "were")
    private val CUE = Regex("""^(?:(?:well|actually|sorry|wait|no|i mean)\b[\s,]*)+""", RegexOption.IGNORE_CASE)
    private val WORD = Regex("""[^\W_]+(?:'[^\W_]+)*""")

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

    private fun bareWords(text: String) = WORD.findAll(text).map { it.value.lowercase() }.toList()

    private fun repeated(left: List<String>, right: List<String>): Boolean {
        if (left.isNotEmpty() && left.size <= 6 && right.take(left.size) == left) return true
        for (size in 4 downTo 2) if (left.size >= size && left.takeLast(size) == right.take(size)) return true
        return false
    }

    private fun shouldCut(leftText: String, rightText: String): Boolean {
        val cleanedRight = CUE.replace(rightText.trim(), "")
        val left = bareWords(leftText)
        val right = bareWords(cleanedRight)
        if (left.isEmpty() || right.isEmpty() || left.any { it in setOf("no", "not", "never") }) return false
        if (leftText.any { it.isDigit() }) return false
        val leftContent = left.filter { it !in FUNCTION_WORDS }.toSet()
        val rightContent = right.filter { it !in FUNCTION_WORDS }.toSet()
        if ((rightContent - leftContent).isEmpty()) return false
        val incomplete = left.last() in INCOMPLETE_END || (left.first() in NP_START && left.none { it in VERBS })
        if (incomplete && repeated(left, right)) return true
        if (left.first() !in NP_START || left.any { it in VERBS }) return false
        return (leftContent intersect rightContent).isNotEmpty()
    }

    /** Cut abandoned comma-delimited fragments with conservative overlap checks. */
    fun tidyFalseStarts(text: String): String {
        var t = text
        repeat(4) {
            var changed = false
            for (comma in t.indices.filter { t[it] == ',' }.reversed()) {
                val previous = listOf('.', '!', '?', ',').maxOf { symbol -> t.lastIndexOf(symbol, comma - 1) }
                val start = previous + 1
                val end = listOf('.', '!', '?').map { t.indexOf(it, comma + 1) }.filter { it >= 0 }.minOrNull() ?: t.length
                val left = t.substring(start, comma)
                val right = t.substring(comma + 1, end)
                if (shouldCut(left, right)) {
                    var cleanedRight = CUE.replace(right.trim(), "")
                    if ((previous < 0 || t[previous] in ".!?") && cleanedRight.isNotEmpty()) {
                        cleanedRight = cleanedRight.replaceFirstChar { it.uppercase() }
                    }
                    t = (t.substring(0, start) + " " + cleanedRight + t.substring(end)).trim()
                    changed = true
                    break
                }
            }
            if (!changed) return@repeat
        }
        return Regex("\\s{2,}").replace(t, " ").trim()
    }

    fun tidy(ctx: Context, text: String): String = model(ctx)?.let { filter(text, it) } ?: text
}
