package ca.heeney.uplink

import org.json.JSONArray
import org.json.JSONObject

/** org.json to plain Kotlin and back. The filer works on maps and lists like the hub's dicts,
 *  and org.json's own getters turn a JSON null into the string "null". */
object Js {
    fun kt(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> map(v)
        is JSONArray -> MutableList(v.length()) { kt(v.opt(it)) }
        else -> v
    }

    fun map(o: JSONObject?): MutableMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        if (o != null) for (k in o.keys()) out[k] = kt(o.opt(k))
        return out
    }

    fun json(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> for ((k, x) in v) o.put(k.toString(), json(x)) }
        is Collection<*> -> JSONArray().also { a -> v.forEach { a.put(json(it)) } }
        else -> v
    }

    fun obj(m: Map<String, Any?>): JSONObject = json(m) as JSONObject
}
