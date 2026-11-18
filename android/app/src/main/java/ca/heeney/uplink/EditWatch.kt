package ca.heeney.uplink

import org.json.JSONObject

/** Watches the text a dictation went into until you're done with it, so the training archive
 *  gets the transcript next to the version you left it as (the hub decides whether that was a
 *  fix to what was heard or you changing your mind). Desktop Squelch does the same; this is
 *  its edited_region, ported. t0 is the text it went into, t0[a, b) the dictation. */
class EditWatch(val said: Said, val pkg: String, private val t0: String, private val a: Int, private val b: Int) {
    var region: String = t0.substring(a, b)
        private set
    private var cur = t0
    val started = System.currentTimeMillis()
    var changed = started
        private set

    /** A new look at the text. Returns why the watch is over, if it is. */
    fun update(t: String): String? {
        if (t == cur) return null
        val r = editedRegion(t0, a, b, t) ?: return "lost"
        cur = t
        changed = System.currentTimeMillis()
        val now = t.substring(r.first, r.second)
        if (now.isNotBlank()) { region = now; return null }
        if (t.isBlank()) return "sent"  // the box emptied at once: keep the last look
        region = ""
        return "deleted"
    }

    fun record(why: String): JSONObject {
        val o = JSONObject()
            .put("id", said.id).put("ts", said.ts).put("app", pkg).put("how", "a11y").put("end", why)
            .put("text", t0.substring(a, b).trim()).put("edited", region.trim())
            .put("heard", said.heard).put("cut", said.cut)
            .put("before", t0.substring(maxOf(0, a - 300), a))
        if (said.text != said.heard) o.put("guess", said.text)
        return o
    }

    companion object {
        /** (a, b) of `inserted` in t: the copy that ends at the cursor, or the only one. */
        fun locate(t: String, inserted: String, caret: Int): Pair<Int, Int>? {
            val needle = inserted.trim()
            if (needle.isEmpty()) return null
            val hits = mutableListOf<Int>()
            var i = t.indexOf(needle)
            while (i >= 0) { hits += i; i = t.indexOf(needle, i + 1) }
            if (hits.isEmpty()) return null
            val at = if (caret >= 0) {
                val best = hits.minBy { kotlin.math.abs(it + needle.length - caret) }
                if (kotlin.math.abs(best + needle.length - caret) > 2) return null
                best
            } else if (hits.size == 1) hits[0] else return null
            var end = at + needle.length
            if (inserted.endsWith(" ") && end < t.length && t[end] == ' ') end++
            return at to end
        }

        /** Where t0[a, b) is in t, by a character diff. Text typed right before or after it
         *  stays outside; edits that reach into it are in. Null if it can't be worked out. */
        fun editedRegion(t0: String, a: Int, b: Int, t: String): Pair<Int, Int>? {
            val n0 = t0.length; val n1 = t.length
            val lim = minOf(n0, n1)
            var p = 0
            while (p < lim && t0[p] == t[p]) p++
            var s = 0
            while (s < lim - p && t0[n0 - 1 - s] == t[n1 - 1 - s]) s++
            // ops: (equal?, i1, i2, j1, j2); runs of changes are merged into one replace
            val ops = ArrayList<IntArray>()
            fun add(eq: Boolean, i1: Int, i2: Int, j1: Int, j2: Int) {
                if (i1 == i2 && j1 == j2) return
                val last = ops.lastOrNull()
                if (last != null && last[0] == (if (eq) 1 else 0)) { last[2] = i2; last[4] = j2; return }
                ops += intArrayOf(if (eq) 1 else 0, i1, i2, j1, j2)
            }
            add(true, 0, p, 0, p)
            val m0 = n0 - s - p; val m1 = n1 - s - p
            if (m0 > 0 && m1 > 0 && m0.toLong() * m1 <= 2_000_000L) {
                // LCS table over the middle, then walk it front to back
                val w = m1 + 1
                val L = IntArray((m0 + 1) * w)
                for (i in m0 - 1 downTo 0) for (j in m1 - 1 downTo 0) {
                    L[i * w + j] = if (t0[p + i] == t[p + j]) L[(i + 1) * w + j + 1] + 1
                    else maxOf(L[(i + 1) * w + j], L[i * w + j + 1])
                }
                var i = 0; var j = 0
                while (i < m0 || j < m1) {
                    when {
                        i < m0 && j < m1 && t0[p + i] == t[p + j] -> { add(true, p + i, p + i + 1, p + j, p + j + 1); i++; j++ }
                        j < m1 && (i == m0 || L[i * w + j + 1] >= L[(i + 1) * w + j]) -> { add(false, p + i, p + i, p + j, p + j + 1); j++ }
                        else -> { add(false, p + i, p + i + 1, p + j, p + j); i++ }
                    }
                }
            } else {
                add(false, p, n0 - s, p, n1 - s)
            }
            add(true, n0 - s, n0, n1 - s, n1)
            var a2 = -1; var b2 = -1
            for (op in ops) {
                val (i1, i2, j1, j2) = listOf(op[1], op[2], op[3], op[4])
                if (op[0] == 1) {
                    if (a2 < 0 && a in i1 until i2) a2 = j1 + a - i1
                    if (b in (i1 + 1)..i2) b2 = j1 + b - i1
                    continue
                }
                val inside = if (i1 < i2) i1 < b && i2 > a else a < i1 && i1 < b
                if (inside) {
                    if (a2 < 0) a2 = j1
                    b2 = j2
                }
            }
            if (a2 < 0 || b2 < 0 || b2 < a2) return null
            return a2 to b2
        }
    }
}
