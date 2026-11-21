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
 *  the text afterwards. Two queues, both kept on the phone until they go out over Wi-Fi:
 *  - mine: to your own Squelch hub, which files it with the desktop's (Prefs.keepRecordings)
 *  - shared: to the project, only if you turned on "Share training data" (Prefs.shareTraining,
 *    off by default). Same format, so one receiver can take either. */
object Archive {
    private const val MAX_CLIPS = 300
    private const val MAX_BYTES = 60_000_000L

    class Queue(val dirName: String, val editsName: String) {
        fun dir(ctx: Context) = File(ctx.filesDir, dirName)
        fun edits(ctx: Context) = File(ctx.filesDir, editsName)
    }
    private val MINE = Queue("archive", "corrections.jsonl")
    private val SHARED = Queue("shared", "shared-corrections.jsonl")

    private fun queues(): List<Queue> = listOfNotNull(MINE.takeIf { Prefs.keepRecordings }, SHARED.takeIf { Prefs.shareTraining })

    fun save(ctx: Context, said: Said, pcm: FloatArray, model: String) {
        val qs = queues()
        if (qs.isEmpty()) return
        Thread({
            try {
                val wav = wav(pcm)
                val meta = JSONObject()
                    .put("id", said.id).put("ts", said.ts).put("engine", model)
                    .put("audio_s", pcm.size / 16000.0).put("text", said.heard).put("cut", said.cut).toString()
                for (q in qs) {
                    val d = q.dir(ctx).apply { mkdirs() }
                    File(d, "${said.id}.wav").writeBytes(wav)
                    File(d, "${said.id}.json").writeText(meta)
                    trim(d)
                }
                if (SHARED in qs) shareSoon(ctx)
            } catch (t: Throwable) { Log.w(TAG, "archive: $t") }
        }, "archive").start()
    }

    @Synchronized fun edited(ctx: Context, rec: JSONObject) {
        for (q in queues()) try {
            // shared: never the text that was already in the box, which may be someone else's
            val r = if (q === SHARED) JSONObject(rec.toString()).apply { remove("before") } else rec
            q.edits(ctx).appendText(r.toString() + "\n")
        } catch (t: Throwable) { Log.w(TAG, "archive: $t") }
        if (!rec.optBoolean("unsure", false)) try {
            Corrections.learn(ctx, rec.optString("text"), rec.optString("edited"))
        } catch (t: Throwable) { Log.w(TAG, "correction learning: $t") }
    }

    /** Sharing turned off: what was waiting to go is deleted, not sent. */
    @Synchronized fun dropShared(ctx: Context) {
        SHARED.dir(ctx).deleteRecursively()
        SHARED.edits(ctx).delete()
    }

    private val sharing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Send what's waiting to be shared, on Wi-Fi only, in the background. */
    fun shareSoon(ctx: Context) {
        if (!Prefs.shareTraining || Net.metered(ctx) || !Net.online(ctx) || !sharing.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread({
            try { send(app, SHARED, BuildConfig.DONATE_URL.trimEnd('/')) } catch (t: Throwable) { Log.w(TAG, "share: $t") }
            finally { sharing.set(false) }
        }, "share").start()
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

    /** Send what's waiting to your Squelch hub. Called from the Words sync, which only runs on Wi-Fi. */
    fun upload(ctx: Context, url: String) {
        send(ctx, MINE, url)
        shareSoon(ctx)
    }

    private fun send(ctx: Context, q: Queue, url: String) {
        if (url.isEmpty()) return
        val dev = Sync.deviceId(ctx)
        val d = q.dir(ctx)
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
        val f = q.edits(ctx)
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
