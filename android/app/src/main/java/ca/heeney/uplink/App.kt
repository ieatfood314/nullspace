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

    /** your own hub, "" when there's none and the phone does everything itself */
    var hubUrl: String
        get() = sp.getString("hub_url", BuildConfig.HUB_URL)!!
        set(v) = sp.edit().putString("hub_url", v).apply()

    /** keep each dictation's audio, and how you edited it after, for your own Squelch hub to
     *  train speech models on (on by default only in builds that come with one) */
    var keepRecordings: Boolean
        get() = sp.getBoolean("keep_recordings", BuildConfig.SQUELCH_HUB.isNotEmpty())
        set(v) = sp.edit().putBoolean("keep_recordings", v).apply()

    /** your own LLM (see Brain): "groq" or "other", and for other its chat completions URL */
    var llmPreset: String
        get() = sp.getString("llm_preset", "groq")!!
        set(v) = sp.edit().putString("llm_preset", v).apply()
    var llmUrl: String
        get() = sp.getString("llm_url", "")!!
        set(v) = sp.edit().putString("llm_url", v).apply()
    var llmKey: String
        get() = sp.getString("llm_key", "")!!
        set(v) = sp.edit().putString("llm_key", v).apply()
    /** "" for the preset's default */
    var llmModel: String
        get() = sp.getString("llm_model", "")!!
        set(v) = sp.edit().putString("llm_model", v).apply()

    /** tidy rambling dictation (repeats, false starts) through the LLM, when there is one */
    var tidy: Boolean
        get() = sp.getBoolean("tidy", true)
        set(v) = sp.edit().putBoolean("tidy", v).apply()

    const val DEFAULT_ACCENT = 0xFF3D9BFF.toInt()
    val ACCENTS = listOf(DEFAULT_ACCENT, 0xFFD71921.toInt(), 0xFFFF8A3D.toInt(), 0xFFF5C542.toInt(),
        0xFF3DDC97.toInt(), 0xFF8B7CFF.toInt(), 0xFFFF5FA2.toInt())
    /** the one accent color, everywhere: the page, settings, the pill, notifications */
    var accent: Int
        get() = sp.getInt("accent", DEFAULT_ACCENT)
        set(v) = sp.edit().putInt("accent", v or 0xFF000000.toInt()).apply()
    fun accentHex(): String = "#%06X".format(accent and 0xFFFFFF)

    /** the monthly mobile data cap in MB (0 = use no mobile data at all) */
    var dataCapMb: Int
        get() = sp.getInt("data_cap_mb", 10)
        set(v) = sp.edit().putInt("data_cap_mb", v.coerceIn(0, 100_000)).apply()

    /** Opt-in only: send dictation audio, transcripts and your edits to the project, to train
     *  better speech models. Off unless you turn it on. Wi-Fi only. */
    var shareTraining: Boolean
        get() = BuildConfig.DONATE_URL.isNotEmpty() && sp.getBoolean("share_training", false)
        set(v) = sp.edit().putBoolean("share_training", v).apply()

    /** anything to keep each dictation for: your own archive, or sharing */
    val archiving: Boolean get() = keepRecordings || shareTraining

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
