package ca.heeney.uplink

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** The phone's copy of every record, the same shape as the hub's: (kind, uid, data,
 *  updated_at, deleted). The page reads and writes it through the bridge; HubSync sends the
 *  dirty rows to the hub and merges what comes back. The newer updated_at wins. */
object Store {
    private lateinit var db: SQLiteDatabase
    /** bumped on every change, so the page and reminders can refresh */
    val changed = Observable(0L)

    class Rec(val kind: String, val uid: String, val data: JSONObject, val updatedAt: Long, val deleted: Boolean) {
        fun json(): JSONObject = JSONObject().put("kind", kind).put("uid", uid).put("data", data)
            .put("updated_at", updatedAt).put("deleted", deleted)
    }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "uplink.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE records (kind TEXT NOT NULL, uid TEXT NOT NULL, data TEXT NOT NULL, " +
                    "updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, rev INTEGER NOT NULL, " +
                    "dirty INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (kind, uid))",
            )
            db.execSQL("CREATE INDEX records_rev ON records(rev)")
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
    }

    fun init(ctx: Context) {
        if (::db.isInitialized) return
        db = Helper(ctx.applicationContext).writableDatabase
    }

    @Synchronized fun meta(key: String, def: String = ""): String =
        db.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else def }

    @Synchronized fun setMeta(key: String, value: String) {
        db.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun rev(): Long = meta("rev", "0").toLong()

    private fun nextRev(): Long { val r = rev() + 1; setMeta("rev", r.toString()); return r }

    /** Last writer wins. fromHub rows aren't dirty; local ones are until the hub has them. */
    @Synchronized fun merge(rec: JSONObject, fromHub: Boolean): Boolean {
        val kind = rec.optString("kind")
        val uid = rec.optString("uid")
        if (kind !in KINDS || uid.isEmpty() || uid.length > 64) return false
        val at = rec.optLong("updated_at")
        val cur = db.rawQuery("SELECT updated_at, dirty FROM records WHERE kind=? AND uid=?", arrayOf(kind, uid))
            .use { if (it.moveToFirst()) it.getLong(0) to (it.getInt(1) == 1) else null }
        if (cur != null) {
            if (cur.first > at) return false
            if (cur.first == at) {
                // The hub echoing our own write: it's safe there now.
                if (fromHub && cur.second) db.execSQL("UPDATE records SET dirty=0 WHERE kind=? AND uid=?", arrayOf(kind, uid))
                return false
            }
        }
        val data = rec.optJSONObject("data") ?: JSONObject()
        db.insertWithOnConflict("records", null, ContentValues().apply {
            put("kind", kind); put("uid", uid); put("data", data.toString()); put("updated_at", at)
            put("deleted", if (rec.optBoolean("deleted")) 1 else 0); put("rev", nextRev()); put("dirty", if (fromHub) 0 else 1)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        changed.value = changed.value + 1
        return true
    }

    /** A write made on the phone itself (a capture, a reminder that fired). */
    @Synchronized fun put(kind: String, uid: String, data: JSONObject, deleted: Boolean = false): Rec {
        val cur = get(kind, uid, true)
        val at = maxOf(System.currentTimeMillis(), (cur?.updatedAt ?: 0) + 1)
        merge(JSONObject().put("kind", kind).put("uid", uid).put("data", data).put("updated_at", at).put("deleted", deleted), false)
        return Rec(kind, uid, data, at, deleted)
    }

    @Synchronized fun get(kind: String, uid: String, withDeleted: Boolean = false): Rec? =
        db.rawQuery("SELECT * FROM records WHERE kind=? AND uid=?" + if (withDeleted) "" else " AND deleted=0", arrayOf(kind, uid))
            .use { if (it.moveToFirst()) row(it) else null }

    @Synchronized fun all(kind: String): List<Rec> =
        db.rawQuery("SELECT * FROM records WHERE kind=? AND deleted=0", arrayOf(kind)).use { c ->
            buildList { while (c.moveToNext()) add(row(c)) }
        }

    @Synchronized fun since(rev: Long): JSONArray {
        val out = JSONArray()
        db.rawQuery("SELECT * FROM records WHERE rev > ? ORDER BY rev", arrayOf(rev.toString())).use { c ->
            while (c.moveToNext()) out.put(row(c).json())
        }
        return out
    }

    @Synchronized fun dirty(): List<Rec> =
        db.rawQuery("SELECT * FROM records WHERE dirty=1", null).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

    @Synchronized fun hasDirty(): Boolean =
        db.rawQuery("SELECT 1 FROM records WHERE dirty=1 LIMIT 1", null).use { it.moveToFirst() }

    @Synchronized fun clean(kind: String, uid: String, at: Long) {
        db.execSQL("UPDATE records SET dirty=0 WHERE kind=? AND uid=? AND updated_at=?", arrayOf(kind, uid, at))
    }

    private fun row(c: android.database.Cursor) = Rec(
        c.getString(c.getColumnIndexOrThrow("kind")), c.getString(c.getColumnIndexOrThrow("uid")),
        try { JSONObject(c.getString(c.getColumnIndexOrThrow("data"))) } catch (_: Exception) { JSONObject() },
        c.getLong(c.getColumnIndexOrThrow("updated_at")), c.getInt(c.getColumnIndexOrThrow("deleted")) == 1,
    )

    /** The page's sync call, answered locally: take its writes, return everything newer than its rev. */
    fun pageSync(ctx: Context, body: String): String {
        val req = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        val recs = req.optJSONArray("records") ?: JSONArray()
        var took = 0
        var capture = false
        for (i in 0 until recs.length()) {
            val r = recs.optJSONObject(i) ?: continue
            if (merge(r, false)) { took++; if (r.optString("kind") == "capture") capture = true }
        }
        if (took > 0) {
            HubSync.soon(ctx, wait = if (capture) 20 else 0)
            Reminders.refresh(ctx)
        }
        val since = req.optLong("since")
        return JSONObject().put("rev", rev()).put("records", since(since)).toString()
    }

    fun newUid(): String {
        val b = ByteArray(8)
        java.security.SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private val KINDS = setOf("list", "item", "place", "capture")
}
