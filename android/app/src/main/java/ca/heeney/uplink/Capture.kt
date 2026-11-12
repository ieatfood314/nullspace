package ca.heeney.uplink

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Turning a key press into a capture record. The hub files it (an LLM reads the text, never
 *  the audio or the picture); the phone only keeps the screenshot and sends it on Wi-Fi. */
object Capture {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "capture") }

    fun shotFile(ctx: Context, uid: String) = File(File(ctx.filesDir, "shots"), "$uid.webp")

    /** A screenshot waiting to see whether the press becomes a double press. */
    class Pending(val uid: String, val app: String, val text: String) {
        @Volatile var bitmap: Bitmap? = null
        @Volatile var dropped = false
    }

    /** Grab the screen and its text now, at the moment of the press. */
    fun grab(svc: AccessibilityService): Pending {
        val text = screenText(svc)
        val app = svc.rootInActiveWindow?.packageName?.toString().orEmpty()
        val p = Pending(Store.newUid(), app, text)
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(s: AccessibilityService.ScreenshotResult) {
                    val hb = s.hardwareBuffer
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(hb, s.colorSpace)
                        p.bitmap = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally { hb.close() }
                    if (p.dropped) p.bitmap = null
                }
                override fun onFailure(code: Int) { Log.w(TAG, "screenshot failed: $code") }
            })
        } catch (t: Throwable) { Log.w(TAG, "screenshot: $t") }
        return p
    }

    /** It was a single press: keep it. */
    fun commit(ctx: Context, p: Pending) {
        val app = ctx.applicationContext
        io.execute {
            // The screenshot callback can land a moment after the press is decided.
            var waited = 0
            while (p.bitmap == null && waited < 1500) { Thread.sleep(50); waited += 50 }
            val bmp = p.bitmap
            var saved = false
            if (bmp != null) {
                try {
                    val f = shotFile(app, p.uid)
                    f.parentFile?.mkdirs()
                    val w = 720
                    val scaled = if (bmp.width > w) Bitmap.createScaledBitmap(bmp, w, bmp.height * w / bmp.width, true) else bmp
                    f.outputStream().use { scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 72, it) }
                    saved = true
                } catch (t: Throwable) { Log.w(TAG, "save shot: $t") }
            }
            val d = JSONObject()
                .put("type", "screenshot").put("status", "new").put("created_at", System.currentTimeMillis() / 1000)
                .put("app", p.app).put("screen_text", p.text.take(12000)).put("image", saved).put("device", "phone")
            Store.put("capture", p.uid, d)
            HubSync.soon(app)
            Reminders.refresh(app)
        }
    }

    fun voiceNote(ctx: Context, text: String) {
        val app = ctx.applicationContext
        val uid = Store.newUid()
        Store.put("capture", uid, JSONObject()
            .put("type", "voice").put("status", "new").put("created_at", System.currentTimeMillis() / 1000)
            .put("text", text).put("device", "phone").put("announce", true))
        Notice.show(app, if (Net.online(app)) "Sending" else "Saved, sends when online")
        HubSync.soon(app, wait = 20)
    }

    fun share(ctx: Context, text: String, image: android.net.Uri?) {
        val app = ctx.applicationContext
        val uid = Store.newUid()
        var saved = false
        if (image != null) {
            try {
                val bmp = app.contentResolver.openInputStream(image)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    val f = shotFile(app, uid); f.parentFile?.mkdirs()
                    val w = 1080
                    val scaled = if (bmp.width > w) Bitmap.createScaledBitmap(bmp, w, bmp.height * w / bmp.width, true) else bmp
                    f.outputStream().use { scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, it) }
                    saved = true
                }
            } catch (t: Throwable) { Log.w(TAG, "share image: $t") }
        }
        Store.put("capture", uid, JSONObject()
            .put("type", if (saved) "screenshot" else "share").put("status", "new")
            .put("created_at", System.currentTimeMillis() / 1000).put("text", text.take(20000))
            .put("image", saved).put("device", "phone").put("announce", true))
        HubSync.soon(app, wait = 20)
    }

    /** Say what a voice note became once the hub has filed it. */
    fun announce(ctx: Context) {
        for (c in Store.all("capture")) {
            if (!c.data.optBoolean("announce") || c.data.optString("status") != "done") continue
            if (Prefs.sp().getBoolean("told_${c.uid}", false)) continue
            Prefs.sp().edit().putBoolean("told_${c.uid}", true).apply()
            if (System.currentTimeMillis() / 1000 - c.data.optLong("created_at") > 120) continue
            val items = c.data.optJSONArray("items")
            val titles = (0 until (items?.length() ?: 0)).mapNotNull { Store.get("item", items!!.optString(it))?.data?.optString("title") }
            Notice.show(ctx, if (titles.isEmpty()) "Saved" else "Added " + titles.joinToString(", "))
        }
    }

    /** The text on screen, from the accessibility tree: every window but ours and the system bars. */
    private fun screenText(svc: AccessibilityService): String {
        val sb = StringBuilder()
        val seen = HashSet<String>()
        val wins = try { svc.windows } catch (_: Throwable) { emptyList() }
        val roots = wins.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.isActive }
            .mapNotNull { it.root }.ifEmpty { listOfNotNull(svc.rootInActiveWindow) }
        for (root in roots) {
            if (root.packageName == svc.packageName) continue
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(root)
            var n = 0
            while (stack.isNotEmpty() && n++ < 4000 && sb.length < 15000) {
                val node = stack.removeLast()
                if (node.isVisibleToUser) {
                    val t = (node.text ?: node.contentDescription)?.toString()?.trim()
                    if (!t.isNullOrEmpty() && t.length > 1 && seen.add(t) && !node.isPassword) sb.append(t).append('\n')
                }
                for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.add(it) }
            }
        }
        return sb.toString()
    }

    @Suppress("unused") private val sdk = Build.VERSION.SDK_INT
}
