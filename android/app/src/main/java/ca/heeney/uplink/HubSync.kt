package ca.heeney.uplink

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Process
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.YearMonth
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object Net {
    /** true on mobile data (or any network Android calls metered) */
    fun metered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        return cm.isActiveNetworkMetered
    }

    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/** What Uplink has used on mobile data this month, and the line it won't cross.
 *  Counted from the app's own traffic counters around each request, plus an allowance for
 *  Tailscale's per-packet overhead, so it errs on the high side. */
object Budget {
    const val LIMIT = 10_000_000L
    /** past this, only send things you made (no polling) */
    const val SOFT = 8_000_000L
    /** past this, nothing at all on mobile data until Wi-Fi or next month */
    const val HARD = 9_500_000L

    private fun month() = YearMonth.now().toString()

    @Synchronized fun used(): Long {
        val sp = Prefs.sp()
        return if (sp.getString("budget_month", "") == month()) sp.getLong("budget_bytes", 0) else 0
    }

    @Synchronized fun add(bytes: Long) {
        val sp = Prefs.sp()
        val base = if (sp.getString("budget_month", "") == month()) sp.getLong("budget_bytes", 0) else 0
        sp.edit().putString("budget_month", month()).putLong("budget_bytes", base + bytes).apply()
    }

    fun text(): String = "%.1f MB of %d MB".format(used() / 1e6, LIMIT / 1_000_000)
}

/** Keeps the phone's Store in step with the hub (POST /api/sync, gzip both ways).
 *  Runs after every change, when the app opens, and from a periodic job: every 15 minutes
 *  on Wi-Fi, at most hourly on mobile data. Screenshots only upload on Wi-Fi. */
object HubSync {
    enum class Status { Off, Syncing, Synced, Failed }
    val status = Observable(Status.Off)
    @Volatile var lastOk = 0L
    @Volatile var lastError = ""

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "hub-sync") }
    @Volatile private var queued = false
    @Volatile private var queuedWait = 0

    fun url(): String? {
        val u = Prefs.hubUrl.trim().trimEnd('/')
        if (u.isEmpty()) return null
        return if ("://" in u) u else "http://$u"
    }

    fun soon(ctx: Context, wait: Int = 0, poll: Boolean = false) {
        if (url() == null) { status.value = Status.Off; return }
        val app = ctx.applicationContext
        queuedWait = maxOf(queuedWait, wait)
        if (queued) return
        queued = true
        exec.execute {
            queued = false
            val w = queuedWait
            queuedWait = 0
            try { run(app, w, poll) } catch (t: Throwable) {
                Log.w(TAG, "hub sync failed: $t")
                lastError = t.message ?: t.toString()
                status.value = Status.Failed
            }
        }
    }

    /** Blocking: one sync now. Returns false when it was skipped or failed. */
    fun run(ctx: Context, wait: Int = 0, poll: Boolean = false): Boolean {
        val url = url() ?: return false
        if (!Net.online(ctx)) return false
        val metered = Net.metered(ctx)
        if (metered) {
            val used = Budget.used()
            if (used >= Budget.HARD) { lastError = "Mobile data limit reached"; status.value = Status.Failed; return false }
            if (used >= Budget.SOFT && !Store.hasDirty()) return false
            if (poll && System.currentTimeMillis() - Prefs.sp().getLong("last_metered_poll", 0) < 55 * 60_000 && !Store.hasDirty()) return false
        }
        status.value = Status.Syncing
        val sent = Store.dirty()
        val body = JSONObject()
            .put("since", Store.meta("hub_rev", "0").toLong())
            .put("device", Sync.deviceName(ctx))
            .put("records", JSONArray().apply { sent.forEach { put(it.json()) } })
            .put("usage", JSONObject().put("mobile_bytes", Budget.used()).put("month", YearMonth.now().toString()))
        if (wait > 0 && sent.any { it.kind == "capture" }) body.put("wait", wait)
        val res = JSONObject(String(post(ctx, "$url/api/sync", body.toString().toByteArray(), metered, (wait + 20) * 1000)))
        for (r in sent) Store.clean(r.kind, r.uid, r.updatedAt)
        val recs = res.optJSONArray("records") ?: JSONArray()
        for (i in 0 until recs.length()) recs.optJSONObject(i)?.let { Store.merge(it, true) }
        Store.setMeta("hub_rev", res.optLong("rev").toString())
        if (metered) Prefs.sp().edit().putLong("last_metered_poll", System.currentTimeMillis()).apply()
        lastOk = System.currentTimeMillis()
        lastError = ""
        status.value = Status.Synced
        Reminders.refresh(ctx)
        Capture.announce(ctx)
        if (!metered) uploadShots(ctx, url)
        return true
    }

    /** POST (or GET with body null), counting what it costs on mobile data. */
    fun post(ctx: Context, url: String, body: ByteArray?, metered: Boolean, timeoutMs: Int = 30_000, ctype: String = "application/json"): ByteArray {
        val uid = Process.myUid()
        val before = TrafficStats.getUidRxBytes(uid) + TrafficStats.getUidTxBytes(uid)
        var wire = 0L
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept-Encoding", "gzip")
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", ctype)
                val payload = if (ctype == "application/json") gzip(body).also { conn.setRequestProperty("Content-Encoding", "gzip") } else body
                conn.setFixedLengthStreamingMode(payload.size)
                conn.outputStream.use { it.write(payload) }
                wire += payload.size
            }
            val code = conn.responseCode
            val raw = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            wire += raw.size + 600  // headers both ways
            val out = if (conn.getHeaderField("Content-Encoding") == "gzip") GZIPInputStream(raw.inputStream()).readBytes() else raw
            if (code != 200) {
                val msg = try { JSONObject(String(out)).optString("error") } catch (_: Exception) { "" }
                error(if (msg.isNotEmpty()) msg else "Hub said $code")
            }
            return out
        } finally {
            if (metered) {
                val after = TrafficStats.getUidRxBytes(uid) + TrafficStats.getUidTxBytes(uid)
                val counted = if (before >= 0 && after >= before) after - before else 0
                // TCP handshake and teardown plus Tailscale's ~60 bytes a packet on top.
                Budget.add((maxOf(counted, wire) * 1.15).toLong() + 800)
            }
        }
    }

    private fun gzip(b: ByteArray): ByteArray {
        val bo = ByteArrayOutputStream()
        GZIPOutputStream(bo).use { it.write(b) }
        return bo.toByteArray()
    }

    /** Screenshots the hub doesn't have yet, Wi-Fi only. */
    private fun uploadShots(ctx: Context, url: String) {
        for (c in Store.all("capture")) {
            val d = c.data
            if (d.optString("type") != "screenshot" || d.optBoolean("image_on_hub") || !d.optBoolean("image")) continue
            val f = Capture.shotFile(ctx, c.uid)
            if (!f.exists() || f.length() == 0L) continue
            if (Net.metered(ctx)) return
            try {
                post(ctx, "$url/api/shot/${c.uid}", f.readBytes(), false, 60_000, "image/webp")
            } catch (t: Throwable) { Log.w(TAG, "shot upload: $t"); return }
        }
        // The hub marks image_on_hub on the capture; pick that up next time round.
    }

    /** Fetch a file from the hub into dest, Wi-Fi only (fonts, screenshots taken elsewhere). */
    fun fetchTo(ctx: Context, path: String, dest: File): Boolean {
        val url = url() ?: return false
        if (Net.metered(ctx) || !Net.online(ctx)) return false
        return try {
            val b = post(ctx, "$url/$path", null, false, 20_000)
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".tmp")
            tmp.writeBytes(b)
            tmp.renameTo(dest)
        } catch (_: Throwable) { false }
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("hub-poll", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    class PollWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
        override fun doWork(): Result {
            Store.init(applicationContext)
            try { run(applicationContext, poll = true) } catch (t: Throwable) { Log.w(TAG, "poll: $t") }
            Reminders.refresh(applicationContext)
            return Result.success()
        }
    }
}
