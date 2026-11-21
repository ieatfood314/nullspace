package ca.heeney.uplink

import android.content.Context

/** Turns an explicit before/after edit into narrow dictionary corrections. */
object Corrections {
    data class Classification(val kind: String, val fixes: List<Pair<String, String>>)
    private data class Opcode(val tag: String, val a: IntRange, val b: IntRange)
    private val FILLERS = setOf("um", "umm", "uh", "uhm", "er", "erm", "ah", "hmm", "mm")
    private val TOKEN = Regex("""[^\W_]+(?:'[^\W_]+)*""")

    private fun bare(s: String) = s.lowercase().replace(Regex("[^\\w]"), "")
    private fun soundKey(s: String): String {
        var out = bare(s)
        for ((a, b) in listOf("ph" to "f", "ck" to "k", "c" to "k", "q" to "k", "z" to "s", "x" to "ks")) out = out.replace(a, b)
        out = out.take(1) + out.drop(1).replace(Regex("[aeiouwyh]"), "")
        return out.replace(Regex("(.)\\1+"), "$1")
    }

    private fun distance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
            else 1 + minOf(dp[i - 1][j - 1], dp[i - 1][j], dp[i][j - 1])
        }
        return dp[a.length][b.length]
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        return 1.0 - distance(a, b).toDouble() / maxOf(a.length, b.length, 1)
    }

    private fun soundsAlike(old: String, new: String): Boolean {
        val a = bare(old); val b = bare(new)
        if (a.isEmpty() || b.isEmpty()) return false
        val r = similarity(a, b)
        val sk = similarity(soundKey(old), soundKey(new))
        return r >= 0.6 || (r >= 0.4 && sk >= 0.75)
    }

    private fun opcodes(a: List<String>, b: List<String>): List<Opcode> {
        val n = a.size; val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) 1 + dp[i + 1][j + 1]
            else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val out = ArrayList<Opcode>()
        var i = 0; var j = 0
        while (i < n || j < m) {
            if (i < n && j < m && a[i] == b[j]) {
                val start = i
                while (i < n && j < m && a[i] == b[j]) { i++; j++ }
                out += Opcode("equal", start until i, start until j)
            } else {
                val ai = i; val bj = j
                while (i < n || j < m) {
                    if (i < n && j < m && a[i] == b[j]) break
                    if (i < n && j < m) {
                        if (dp[i + 1][j] >= dp[i][j + 1]) i++ else j++
                    } else if (i < n) i++ else j++
                }
                out += Opcode(if (i == ai + 1 && j == bj + 1) "replace" else if (i == ai) "insert" else "delete", ai until i, bj until j)
            }
        }
        return out
    }

    private fun stray(words: List<String>, i: Int, j: Int): Boolean {
        val gone = words.subList(i, j)
        return gone.size <= 3 && (gone.all { it in FILLERS } ||
            words.subList(maxOf(0, i - gone.size), i) == gone || words.subList(j, minOf(words.size, j + gone.size)) == gone)
    }

    /** The same broad fix/rewrite split as desktop Squelch's classify_edit. */
    fun classify(text: String, edited: String): Classification {
        if (edited.isBlank()) return Classification("deleted", emptyList())
        val tw = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val ew = edited.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val tb = tw.map(::bare); val eb = ew.map(::bare)
        if (tb == eb) return Classification(if (text.trim() == edited.trim()) "kept" else "format", emptyList())
        val fixes = ArrayList<Pair<String, String>>()
        var rewrite = false; var changed = 0
        for (op in opcodes(tb, eb)) {
            if (op.tag == "equal") continue
            changed += maxOf(op.a.count(), op.b.count())
            val old = tw.subList(op.a.first, op.a.last + 1).joinToString(" ")
            val new = ew.subList(op.b.first, op.b.last + 1).joinToString(" ")
            if (op.tag == "delete" && stray(tb, op.a.first, op.a.last + 1)) {
                fixes += old to ""
            } else if (op.tag == "replace" && maxOf(op.a.count(), op.b.count()) <= 3 && soundsAlike(old, new)) {
                fixes += old to new
            } else rewrite = true
        }
        if (rewrite || changed > maxOf(2, tw.size / 2)) return Classification("rewrite", emptyList())
        return Classification(if (fixes.isEmpty()) "format" else "fix", fixes)
    }

    /** Include one preceding word to avoid dangerous global replacements. */
    fun correctionReplacement(text: String, oldValue: String, newValue: String): Pair<String, String>? {
        val old = oldValue.trim().trim('.', ',', '!', '?', ';', ':')
        val new = newValue.trim().trim('.', ',', '!', '?', ';', ':')
        if (old.isEmpty() || new.isEmpty() || old == new) return null
        val start = text.indexOf(old)
        if (start < 0) return null
        val previous = TOKEN.findAll(text.substring(0, start)).lastOrNull()
        val heardAt = previous?.range?.first ?: start
        val heard = text.substring(heardAt, start + old.length)
        val replacement = text.substring(heardAt, start) + new
        if (maxOf(heard.split(Regex("\\s+")).size, replacement.split(Regex("\\s+")).size) > 5) return null
        return replacement to heard
    }

    /** Learn only from a clear fix, never a rewrite. Dictionary writes stay on-device. */
    fun learn(ctx: Context, text: String, edited: String) {
        val result = classify(text, edited)
        if (result.kind !in setOf("fix", "format")) return
        for ((old, new) in result.fixes) {
            val pair = correctionReplacement(text, old, new) ?: continue
            Words.add(ctx, pair.first, listOf(pair.second))
        }
    }
}
