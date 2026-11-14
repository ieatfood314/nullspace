package ca.heeney.uplink

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import org.json.JSONObject

/** Time reminders (exact alarms) and place reminders (geofences), rebuilt from the Store
 *  whenever it changes. A reminder fires once: it stamps fired_at / place_fired_at on the
 *  task, which syncs, so editing the time or place in the page arms it again. A repeating
 *  task rings at each of its times (see Repeat), done or not; fired_at holds the last one. */
object Reminders {
    private const val CHANNEL = "reminders"

    fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("captures", "Captures", NotificationManager.IMPORTANCE_LOW))
    }

    private val lock = Any()

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        try { synchronized(lock) { alarms(app); fences(app) } } catch (t: Throwable) { Log.w(TAG, "reminders: $t") }
    }

    private fun isDone(t: Store.Rec) = !t.data.isNull("done_at") && t.data.optLong("done_at") > 0L

    private fun openTasks() = Store.all("item").filter { it.data.optString("type") != "note" && !isDone(it) }

    private fun repeat(t: Store.Rec) = if (t.data.isNull("repeat")) null else Repeat.parse(t.data.optString("repeat"))

    // ---- time ----

    private fun alarmIntent(ctx: Context, uid: String) = PendingIntent.getBroadcast(
        ctx, uid.hashCode(), Intent(ctx, AlarmReceiver::class.java).setAction("ca.heeney.uplink.ALARM").putExtra("uid", uid),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @SuppressLint("ScheduleExactAlarm")
    private fun alarms(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis() / 1000
        val want = mutableMapOf<String, Long>()
        for (t in Store.all("item")) {
            if (t.data.optString("type") == "note") continue
            val at = t.data.optLong("remind_at")
            if (at <= 0) continue
            val rule = repeat(t)
            if (rule != null) {
                rule.nextRing(at, t.data.optLong("fired_at"), isDone(t), now)?.let { want[t.uid] = maxOf(it, now + 2) }
                continue
            }
            if (isDone(t) || t.data.optLong("fired_at") >= at) continue
            want[t.uid] = maxOf(at, now + 2)
        }
        val sp = Prefs.sp()
        val had = try { JSONObject(sp.getString("alarms", "{}")!!) } catch (_: Exception) { JSONObject() }
        for (uid in had.keys()) if (uid !in want) am.cancel(alarmIntent(ctx, uid))
        for ((uid, at) in want) {
            if (had.optLong(uid) == at) continue
            val pi = alarmIntent(ctx, uid)
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at * 1000, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at * 1000, pi)
        }
        sp.edit().putString("alarms", JSONObject(want as Map<*, *>).toString()).apply()
    }

    class AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            Store.init(ctx)
            val uid = intent.getStringExtra("uid") ?: return
            val t = Store.get("item", uid) ?: return
            var at = t.data.optLong("remind_at")
            val rule = repeat(t)
            if (rule != null && at > 0) {
                // Alarms may run a little early; 30 s of slack.
                at = rule.current(at, t.data.optLong("fired_at"), isDone(t), System.currentTimeMillis() / 1000 + 30) ?: 0L
            } else if (isDone(t)) return
            if (at <= 0 || t.data.optLong("fired_at") >= at) {
                Prefs.sp().edit().putString("alarms", "{}").apply()
                refresh(ctx)
                return
            }
            notify(ctx, t, null)
            Store.put("item", uid, JSONObject(t.data.toString()).put("fired_at", at))
            HubSync.soon(ctx)
            Prefs.sp().edit().putString("alarms", "{}").apply()
            refresh(ctx)
        }
    }

    // ---- places ----

    fun locationOk(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun fenceIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, FenceReceiver::class.java).setAction("ca.heeney.uplink.FENCE"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** Place reminders still waiting: "placeUid|arrive" -> the place */
    private fun wanted(): Map<String, Store.Rec> {
        val places = Store.all("place").associateBy { it.uid }
        val out = sortedMapOf<String, Store.Rec>()
        for (t in openTasks()) {
            val p = places[t.data.optString("place")] ?: continue
            if (!p.data.has("lat") || p.data.isNull("lat")) continue
            if (t.data.optLong("place_fired_at") > 0) continue
            val on = if (t.data.optString("place_on") == "leave") "leave" else "arrive"
            out["${p.uid}|$on"] = p
        }
        return out
    }

    @SuppressLint("MissingPermission")
    private fun fences(ctx: Context) {
        val want = wanted()
        val sig = want.entries.joinToString(";") { (id, p) ->
            "$id@${p.data.optDouble("lat")},${p.data.optDouble("lon")},${p.data.optInt("radius", 150)}"
        }
        val sp = Prefs.sp()
        if (sig == sp.getString("fences", null)) return
        if (!locationOk(ctx)) { sp.edit().remove("fences").apply(); return }
        val client = LocationServices.getGeofencingClient(ctx)
        val pi = fenceIntent(ctx)
        client.removeGeofences(pi).addOnCompleteListener {
            if (want.isEmpty()) { sp.edit().putString("fences", sig).apply(); return@addOnCompleteListener }
            val fences = want.map { (id, p) ->
                Geofence.Builder()
                    .setRequestId(id)
                    .setCircularRegion(p.data.optDouble("lat"), p.data.optDouble("lon"), maxOf(100, p.data.optInt("radius", 150)).toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(if (id.endsWith("|leave")) Geofence.GEOFENCE_TRANSITION_EXIT else Geofence.GEOFENCE_TRANSITION_ENTER)
                    .setNotificationResponsiveness(60_000)
                    .build()
            }
            // No initial trigger: "when I get home", said at home, waits for the next time you arrive.
            val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences.take(90)).build()
            client.addGeofences(req, pi)
                .addOnSuccessListener { sp.edit().putString("fences", sig).apply(); Log.i(TAG, "geofences: ${fences.size}") }
                .addOnFailureListener { e -> Log.w(TAG, "geofences failed: $e"); sp.edit().remove("fences").apply() }
        }
    }

    /** Geofences vanish on reboot, app update and when location is switched off. */
    fun rearm(ctx: Context) {
        Prefs.sp().edit().remove("fences").putString("alarms", "{}").apply()
        refresh(ctx)
    }

    class FenceReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            Store.init(ctx)
            val ev = GeofencingEvent.fromIntent(intent) ?: return
            if (ev.hasError()) {
                Log.w(TAG, "geofence error ${GeofenceStatusCodes.getStatusCodeString(ev.errorCode)}")
                if (ev.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) Prefs.sp().edit().remove("fences").apply()
                return
            }
            val on = if (ev.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) "leave" else "arrive"
            val ids = ev.triggeringGeofences?.map { it.requestId }?.filter { it.endsWith("|$on") } ?: return
            val now = System.currentTimeMillis() / 1000
            for (id in ids) {
                val placeUid = id.substringBefore('|')
                val place = Store.get("place", placeUid)
                for (t in openTasks()) {
                    if (t.data.optString("place") != placeUid || t.data.optLong("place_fired_at") > 0) continue
                    if ((if (t.data.optString("place_on") == "leave") "leave" else "arrive") != on) continue
                    notify(ctx, t, place)
                    Store.put("item", t.uid, JSONObject(t.data.toString()).put("place_fired_at", now))
                }
            }
            HubSync.soon(ctx)
            refresh(ctx)
        }
    }

    // ---- notifications ----

    fun notify(ctx: Context, t: Store.Rec, place: Store.Rec?) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val id = t.uid.hashCode()
        val open = PendingIntent.getActivity(
            ctx, id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val done = PendingIntent.getBroadcast(
            ctx, id, Intent(ctx, DoneReceiver::class.java).setAction("ca.heeney.uplink.DONE").putExtra("uid", t.uid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val sub = when {
            place != null -> (if (t.data.optString("place_on") == "leave") "Leaving " else "At ") + place.data.optString("name")
            else -> t.data.optString("notes").take(120).ifEmpty { null }
        }
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(t.data.optString("title"))
            .apply { if (sub != null) setContentText(sub) }
            .setContentIntent(open)
            .setAutoCancel(true)
            .setColor(0xFF3D9BFF.toInt())
            .addAction(android.app.Notification.Action.Builder(null, "Done", done).build())
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    class DoneReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            Store.init(ctx)
            val uid = intent.getStringExtra("uid") ?: return
            val t = Store.get("item", uid) ?: return
            Store.put("item", uid, JSONObject(t.data.toString()).put("done_at", System.currentTimeMillis() / 1000))
            ctx.getSystemService(NotificationManager::class.java).cancel(uid.hashCode())
            HubSync.soon(ctx)
            refresh(ctx)
        }
    }

    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            Store.init(ctx)
            rearm(ctx)
            HubSync.schedule(ctx)
        }
    }

    @Suppress("unused") private fun JSONArray.strings() = (0 until length()).map { optString(it) }
}
