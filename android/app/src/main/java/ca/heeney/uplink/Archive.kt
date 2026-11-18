package ca.heeney.uplink

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Training data for speech models: each dictation's audio and transcript, and what you did to
 *  the text afterwards. Kept on the phone until it reaches the Squelch hub over Wi-Fi, which
 *  files it in the same archive as the desktop's. */
object Archive {
    private const val MAX_CLIPS = 300
    private const val MAX_BYTES = 60_000_000L

    private fun dir(ctx: Context) = File(ctx.filesDir, "archive")
    private fun edits(ctx: Context) = File(ctx.filesDir, "corrections.jsonl")

    fun save(ctx: Context, said: Said, pcm: FloatArray, model: String) {
        Thread({
            try {
                val d = dir(ctx).apply { mkdirs() }
                File(d, "${said.id}.wav").writeBytes(wav(pcm))
                File(d, "${said.id}.json").writeText(JSONObject()
                    .put("id", said.id).put("ts", said.ts).put("engine", model)
                    .put("audio_s", pcm.size / 16000.0).put("text", said.heard).put("cut", said.cut).toString())
                trim(d)
            } catch (t: Throwable) { Log.w(TAG, "archive: $t") }
        }, "archive").start()
    }

    @Synchronized fun edited(ctx: Context, rec: JSONObject) {
        if (!Prefs.keepRecordings) return
        try { edits(ctx).appendText(rec.toString() + "\n") } catch (t: Throwable) { Log.w(TAG, "archive: $t") }
    }

    /** Oldest clips go first once there are too many, or too much. */
    private fun trim(d: File) {
        val clips = d.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name } ?: return
        var bytes = clips.sumOf { it.length() }
        var n = clips.size
        for (f in clips) {
            if (n <= MAX_CLIPS && bytes <= MAX_BYTES) break
            bytes -= f.length(); n--
            f.delete(); File(d, f.name.removeSuffix(".wav") + ".json").delete()
        }
    }

    private fun wav(pcm: FloatArray): ByteArray {
        val b = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000)
            .putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(pcm.size * 2)
        for (x in pcm) b.putShort((x.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return b.array()
    }

    /** Send what's waiting to the hub. Called from the Words sync, which only runs on Wi-Fi. */
    fun upload(ctx: Context, url: String) {
        val dev = Sync.deviceId(ctx)
        val d = dir(ctx)
        for (meta in d.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()) {
            if (Net.metered(ctx)) return
            val wav = File(d, meta.name.removeSuffix(".json") + ".wav")
            if (!wav.exists()) { meta.delete(); continue }
            val body = JSONObject(meta.readText()).put("device", dev)
                .put("wav", Base64.encodeToString(wav.readBytes(), Base64.NO_WRAP))
            when (post("$url/v1/archive", body)) {
                200 -> { wav.delete(); meta.delete() }
                404 -> return  // the hub keeps no archive; try again another time
                else -> return
            }
        }
        val f = edits(ctx)
        val lines = synchronized(this) { if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList() }
        if (lines.isEmpty()) return
        val items = JSONArray()
        for (l in lines) try { items.put(JSONObject(l)) } catch (_: Exception) {}
        if (post("$url/v1/corrections", JSONObject().put("device", dev).put("items", items)) == 200) {
            synchronized(this) {
                // keep anything added while this was sending
                val now = if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
                val rest = now.drop(lines.size)
                if (rest.isEmpty()) f.delete() else f.writeText(rest.joinToString("\n", postfix = "\n"))
            }
        }
    }

    private fun post(url: String, body: JSONObject): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 60_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val bytes = body.toString().toByteArray()
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        val code = conn.responseCode
        try { (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() } } catch (_: Exception) {}
        return code
    }
}
