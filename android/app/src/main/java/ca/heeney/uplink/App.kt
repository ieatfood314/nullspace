package ca.heeney.uplink

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Store.init(this)
        Sync.init(this)
        Engine.warm(this)
        Sync.soon(this)
        Reminders.channel(this)
        HubSync.schedule(this)
        HubSync.soon(this)
    }
}

object Prefs {
    private lateinit var app: Context
    private val sp get() = app.getSharedPreferences("uplink", Context.MODE_PRIVATE)

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** The Essential key sends Linux code 250, which Android has no keycode for. */
    const val ESSENTIAL_SCAN = 250

    var keyCode: Int
        get() = sp.getInt("key_code", KeyEvent.KEYCODE_UNKNOWN)
        set(v) = sp.edit().putInt("key_code", v).apply()
    var scanCode: Int
        get() = sp.getInt("scan_code", ESSENTIAL_SCAN)
        set(v) = sp.edit().putInt("scan_code", v).apply()

    var model: String
        get() = sp.getString("model", Models.ENGLISH.key)!!
        set(v) = sp.edit().putString("model", v).apply()

    /** the Squelch words-and-analytics hub, "" when that sync is off */
    var syncUrl: String
        get() = sp.getString("sync_url", BuildConfig.SQUELCH_HUB)!!
        set(v) = sp.edit().putString("sync_url", v).apply()

    /** the Uplink hub, "" when off */
    var hubUrl: String
        get() = sp.getString("hub_url", BuildConfig.HUB_URL)!!
        set(v) = sp.edit().putString("hub_url", v).apply()

    /** keep each dictation's audio, and how you edited it after, for training speech models */
    var keepRecordings: Boolean
        get() = sp.getBoolean("keep_recordings", true)
        set(v) = sp.edit().putBoolean("keep_recordings", v).apply()

    fun sp(): android.content.SharedPreferences = sp

    fun isTrigger(ev: KeyEvent): Boolean {
        val code = keyCode
        return if (code != KeyEvent.KEYCODE_UNKNOWN) ev.keyCode == code else ev.scanCode == scanCode
    }

    fun keyName(): String {
        val code = keyCode
        if (code == KeyEvent.KEYCODE_UNKNOWN) {
            return if (scanCode == ESSENTIAL_SCAN) "Essential key" else "Key $scanCode"
        }
        return KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").lowercase()
            .split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }
}

object Notice {
    private val main = Handler(Looper.getMainLooper())
    fun show(ctx: Context, text: String) {
        val app = ctx.applicationContext
        main.post { Toast.makeText(app, text, Toast.LENGTH_SHORT).show() }
    }
}
