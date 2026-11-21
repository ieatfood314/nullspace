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
    private var terms: List<String> = emptyList()
    private var subs: List<Pair<Regex, String>> = emptyList()

    private fun matchPattern(text: String): String {
        var pat = Regex.escape(text)
        if (text.first().isLetterOrDigit()) pat = "\\b$pat"
        if (text.last().isLetterOrDigit()) pat = "$pat\\b"
        return pat
    }

    @Synchronized private fun dictionary(ctx: Context): List<Pair<Regex, String>> {
        val f = dictFile(ctx)
        val mt = if (f.exists()) f.lastModified() else 0L
        if (mt == cacheMtime) return subs
        cacheMtime = mt
        val foundTerms = mutableListOf<String>()
        subs = try {
            if (!f.exists()) emptyList() else buildList {
                val entries = JSONObject(f.readText()).optJSONArray("entries") ?: return@buildList
                for (i in 0 until entries.length()) {
                    val e = entries.optJSONObject(i) ?: continue
                    val term = e.optString("term").trim()
                    if (term.isEmpty()) continue
                    foundTerms += term
                    val ms = e.optJSONArray("also_match") ?: continue
                    for (j in 0 until ms.length()) {
                        val m = ms.optString(j).trim()
                        if (m.isEmpty()) continue
                        add(Regex(matchPattern(m), RegexOption.IGNORE_CASE) to term)
                    }
                }
            }
        } catch (_: Exception) { foundTerms.clear(); emptyList() }
        terms = foundTerms
        return subs
    }

    // Parakeet's tokenizer glues a leading apostrophe onto what comes before
    // it: right for "don't", wrong for "want,'cause". After , ; : ! ? an
    // apostrophe then a letter or digit starts a clipped word ('cause, 'em,
    // 'til, '90s), so it gets its space back. After a period only when a
    // capital follows, so "U.S.'s" stays as it is.
    private val APOS_GLUE = Regex("""(?:(?<=[,;:!?])|(?<=\.)(?=['\u2019][A-Z]))(?=['\u2019][\p{L}\p{N}])""")

    fun spaceClippedWords(text: String): String = APOS_GLUE.replace(text, " ")

    // Unambiguous US forms and their Canadian equivalents. Canadian English keeps
    // -ize/-yze spellings (organize, analyze), so those are deliberately untouched.
    private val CANADIAN_SPELLING = """
color:colour colors:colours colored:coloured coloring:colouring
colorful:colourful colorfully:colourfully colorfulness:colourfulness
colorless:colourless discolor:discolour discolors:discolours
discolored:discoloured discoloring:discolouring discoloration:discolouration
favor:favour favors:favours favored:favoured favoring:favouring
favorable:favourable favorably:favourably favorite:favourite favorites:favourites
favoritism:favouritism honor:honour honors:honours honored:honoured
honoring:honouring honorable:honourable honorably:honourably
dishonor:dishonour dishonors:dishonours dishonored:dishonoured
dishonoring:dishonouring dishonorable:dishonourable
labor:labour labors:labours labored:laboured laboring:labouring
laborer:labourer laborers:labourers neighbor:neighbour neighbors:neighbours
neighbored:neighboured neighboring:neighbouring neighborhood:neighbourhood
neighborhoods:neighbourhoods harbor:harbour harbors:harbours harbored:harboured
harboring:harbouring humor:humour humors:humours humored:humoured
humoring:humouring rumor:rumour rumors:rumours rumored:rumoured
rumoring:rumouring behavior:behaviour behaviors:behaviours
behavioral:behavioural misbehavior:misbehaviour flavor:flavour flavors:flavours
flavored:flavoured flavoring:flavouring flavorful:flavourful flavorless:flavourless
armor:armour armors:armours armored:armoured armoring:armouring armory:armoury
odor:odour odors:odours odorless:odourless vapor:vapour vapors:vapours
valor:valour vigor:vigour ardor:ardour clamor:clamour clamors:clamours
clamored:clamoured clamoring:clamouring candor:candour demeanor:demeanour
demeanors:demeanours endeavor:endeavour endeavors:endeavours
endeavored:endeavoured endeavoring:endeavouring fervor:fervour parlor:parlour
parlors:parlours rancor:rancour savior:saviour saviors:saviours savor:savour
savors:savours savored:savoured savoring:savouring savory:savoury
smolder:smoulder smolders:smoulders smoldered:smouldered smoldering:smouldering
splendor:splendour tumor:tumour tumors:tumours succor:succour succors:succours
succored:succoured succoring:succouring center:centre centers:centres
centered:centred centering:centring centerpiece:centrepiece
centerpieces:centrepieces theater:theatre theaters:theatres
theatergoer:theatregoer theatergoers:theatregoers fiber:fibre fibers:fibres
liter:litre liters:litres caliber:calibre calibers:calibres saber:sabre sabers:sabres
somber:sombre specter:spectre specters:spectres meager:meagre
defense:defence defenses:defences defenseless:defenceless offense:offence
offenses:offences pretense:pretence pretenses:pretences gray:grey grays:greys
grayed:greyed graying:greying grayish:greyish traveling:travelling
traveled:travelled traveler:traveller travelers:travellers canceling:cancelling
canceled:cancelled labeling:labelling labeled:labelled modeling:modelling
modeled:modelled modeler:modeller modelers:modellers marveling:marvelling
marveled:marvelled fueling:fuelling fueled:fuelled crueler:crueller
cruelest:cruellest jeweler:jeweller jewelers:jewellers jewelry:jewellery
aluminum:aluminium enroll:enrol enrolls:enrols enrollment:enrolment
enrollments:enrolments fulfill:fulfil fulfills:fulfils fulfillment:fulfilment
instill:instil instills:instils distill:distil distills:distils
installment:instalment installments:instalments skillful:skilful willful:wilful
catalog:catalogue catalogs:catalogues cataloged:catalogued cataloging:cataloguing
dialog:dialogue dialogs:dialogues monolog:monologue monologs:monologues
analog:analogue analogs:analogues molt:moult molts:moults molted:moulted
molting:moulting plow:plough plows:ploughs plowed:ploughed plowing:ploughing
skeptic:sceptic skeptics:sceptics skeptical:sceptical skepticism:scepticism
mustache:moustache mustaches:moustaches checkered:chequered
maneuver:manoeuvre maneuvers:manoeuvres maneuvered:manoeuvred
maneuvering:manoeuvring
""".trim().split(Regex("\\s+")).associate { pair ->
        val (us, ca) = pair.split(":")
        us to ca
    }
    private val WORD = Regex("[A-Za-z]+")

    fun applyLanguage(text: String, language: String, protect: List<String> = emptyList()): String {
        if (language != "en-CA") return text
        fun convert(fragment: String): String = WORD.replace(fragment) { m ->
            val word = m.value
            val fixed = CANADIAN_SPELLING[word.lowercase()] ?: return@replace word
            when {
                word.all { it.isUpperCase() } -> fixed.uppercase()
                word.first().isUpperCase() -> fixed.replaceFirstChar { it.uppercase() }
                else -> fixed
            }
        }
        val custom = protect.filter { it.isNotEmpty() }.sortedByDescending { it.length }
        if (custom.isEmpty()) return convert(text)
        val rx = Regex(custom.joinToString("|") { matchPattern(it) }, RegexOption.IGNORE_CASE)
        val out = StringBuilder()
        var at = 0
        for (m in rx.findAll(text)) {
            out.append(convert(text.substring(at, m.range.first)))
            out.append(m.value)
            at = m.range.last + 1
        }
        out.append(convert(text.substring(at)))
        return out.toString()
    }

    /** The selected regional spelling, then custom words so those win. */
    fun spelling(ctx: Context, text: String): String {
        val rules = dictionary(ctx)
        var t = applyLanguage(text, Prefs.language, terms)
        for ((rx, term) in rules) t = rx.replace(t, Regex.escapeReplacement(term))
        return t
    }

    fun tidy(ctx: Context, raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return ""
        t = spaceClippedWords(t)
        t = defiller(t)
        return spelling(ctx, t)
    }
}
