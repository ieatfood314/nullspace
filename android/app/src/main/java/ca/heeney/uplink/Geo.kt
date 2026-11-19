package ca.heeney.uplink

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos

/** Address search with no hub: straight to OpenStreetMap's Nominatim, nearby places first.
 *  The hub's geocode and reverse (server/nullspace.py), ported. Nominatim allows one request a
 *  second and wants a real User-Agent. */
object Geo {
    private const val UA = "Nullspace/1.0 (Android; https://github.com/ieatfood314/nullspace)"
    private val cache = HashMap<String, Pair<Long, Any>>()
    private var last = 0L

    private fun nominatim(ctx: Context, params: Map<String, String>, path: String = "search"): Any {
        val key = path + params.toSortedMap()
        synchronized(this) {
            cache[key]?.let { (at, v) -> if (System.currentTimeMillis() - at < 3_600_000) return v }
            val wait = last + 1050 - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            val b = Uri.parse("https://nominatim.openstreetmap.org/$path").buildUpon()
                .appendQueryParameter("format", "jsonv2").appendQueryParameter("addressdetails", "1")
            if (path == "search") b.appendQueryParameter("limit", "8")
            params.forEach { (k, v) -> b.appendQueryParameter(k, v) }
            val raw = try {
                String(HubSync.post(ctx, b.build().toString(), null, Net.metered(ctx), 10_000,
                    headers = mapOf("User-Agent" to UA, "Accept-Language" to "en"), who = "Address lookup"))
            } finally { last = System.currentTimeMillis() }
            val v: Any = if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw)
            if (cache.size > 200) cache.clear()
            cache[key] = System.currentTimeMillis() to v
            return v
        }
    }

    private fun short(x: JSONObject): String {
        val a = x.optJSONObject("address") ?: JSONObject()
        val name = x.optString("name")
        val street = listOf(a.optString("house_number"), a.optString("road")).filter { it.isNotEmpty() }.joinToString(" ")
        val town = listOf("city", "town", "village", "suburb", "municipality", "county").map { a.optString(it) }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val parts = listOf(name, street, town).filter { it.isNotEmpty() }.distinct()
        return parts.joinToString(", ").ifEmpty { x.optString("display_name") }
    }

    /** where to search around: your rough location, else the last one seen, else the middle of your places */
    private fun near(lat: String?, lon: String?): DoubleArray? {
        val la = lat?.toDoubleOrNull()
        val lo = lon?.toDoubleOrNull()
        if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) {
            val pt = doubleArrayOf(Math.round(la * 100) / 100.0, Math.round(lo * 100) / 100.0)
            Store.setMeta("geo_near", "${pt[0]},${pt[1]}")
            return pt
        }
        Store.meta("geo_near").split(",").mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 2 }?.let { return it.toDoubleArray() }
        val set = Store.all("place").filter { it.data.has("lat") && !it.data.isNull("lat") }
        if (set.isEmpty()) return null
        return doubleArrayOf(set.sumOf { it.data.optDouble("lat") } / set.size, set.sumOf { it.data.optDouble("lon") } / set.size)
    }

    fun reverse(ctx: Context, lat: Double, lon: Double): JSONObject {
        val x = nominatim(ctx, mapOf("lat" to "%.5f".format(java.util.Locale.US, lat), "lon" to "%.5f".format(java.util.Locale.US, lon), "zoom" to "18"), "reverse")
        if (x !is JSONObject || x.has("error")) return JSONObject().put("name", JSONObject.NULL)
        return JSONObject().put("name", short(x).ifEmpty { null } ?: JSONObject.NULL).put("full", x.optString("display_name"))
    }

    fun geocode(ctx: Context, q0: String, lat: String?, lon: String?): JSONArray {
        val q = q0.trim().take(200)
        if (q.isEmpty()) return JSONArray()
        val pt = near(lat, lon)
        fun box(dlat: Double): String {
            val dlon = dlat / maxOf(0.2, cos(Math.toRadians(pt!![0])))
            return "${pt[1] - dlon},${pt[0] + dlat},${pt[1] + dlon},${pt[0] - dlat}"
        }
        // Nearby first (about 80 km, then about 350 km), then anywhere.
        val tries = if (pt == null) listOf(mapOf("q" to q)) else listOf(
            mapOf("q" to q, "viewbox" to box(0.7), "bounded" to "1"),
            mapOf("q" to q, "viewbox" to box(3.2), "bounded" to "1"),
            mapOf("q" to q, "viewbox" to box(3.2)),
        )
        fun first(qq: String): JSONArray {
            for (p in tries) { val r = nominatim(ctx, p + ("q" to qq)) as? JSONArray; if (r != null && r.length() > 0) return r }
            return JSONArray()
        }
        var res = first(q)
        val street = Regex("^\\d+[a-z]?(-\\d+)?\\s+", RegexOption.IGNORE_CASE).replace(q, "")
        if (res.length() == 0 && street != q) res = first(street)  // OpenStreetMap lacks many house numbers
        val out = mutableListOf<JSONObject>()
        val seen = HashSet<String>()
        for (i in 0 until res.length()) {
            val x = res.optJSONObject(i) ?: continue
            val name = short(x)
            if (!seen.add(name)) continue
            val r = JSONObject().put("name", name).put("full", x.optString("display_name"))
                .put("lat", x.optString("lat").toDouble()).put("lon", x.optString("lon").toDouble())
            if (pt != null) r.put("km", Math.round(haversine(pt[0], pt[1], r.getDouble("lat"), r.getDouble("lon")) * 10) / 10.0)
            out += r
        }
        if (pt != null) out.sortBy { it.getDouble("km") }
        return JSONArray(out.take(6))
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * Math.sin(dl / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    /** The page's hub calls, answered on the phone: address search, and Redo. */
    fun answer(ctx: Context, path: String, method: String): String = try {
        val u = Uri.parse("http://local/" + path.trimStart('/'))
        val p = u.path.orEmpty()
        when {
            p == "/api/geocode" -> geocode(ctx, u.getQueryParameter("q").orEmpty(), u.getQueryParameter("lat"), u.getQueryParameter("lon")).toString()
            p == "/api/reverse" -> reverse(ctx, u.getQueryParameter("lat")!!.toDouble(), u.getQueryParameter("lon")!!.toDouble()).toString()
            p.startsWith("/api/reprocess/") && method == "POST" -> Filer.reprocess(ctx, p.substringAfterLast('/')).toString()
            else -> JSONObject().put("error", "That needs a hub").toString()
        }
    } catch (t: Throwable) {
        JSONObject().put("error", if (!Net.online(ctx)) "You're offline" else "Address lookup failed").toString()
    }
}
