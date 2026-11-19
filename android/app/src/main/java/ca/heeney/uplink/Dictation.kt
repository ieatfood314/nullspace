package ca.heeney.uplink

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log

/** One finished dictation. heard is the transcript; cut means you were already talking when
 *  the mic opened, so its start may be missing. id names it in the training archive. */
/** A finished dictation. heard is what the model transcribed; text is what goes in, which may
 *  have a guessed start (guess) and be tidied of repeats and false starts (tidied). */
class Said(val text: String, val id: String, val ts: Double, val cut: Boolean, val heard: String = text,
           val guess: String? = null, val tidied: Boolean = false)

/** One dictation at a time: listen while the key is held, transcribe, hand the text to a sink.
 *
 *  Finished sentences are transcribed while you're still talking: at each pause the audio
 *  so far is decoded, and everything up to its last full stop is kept. Letting go then only
 *  has to decode the sentence you were on, so the wait stays short however long you talk. */
object Dictation {
    enum class Phase { Idle, Listening, Working }

    val phase = Observable(Phase.Idle)
    val level = Observable(0f)

    private val main = Handler(Looper.getMainLooper())
    private var recorder: Recorder? = null
    private var fromMic = true
    private var sink: ((String) -> Unit)? = null
    private var onSaid: ((Said) -> Unit)? = null
    private var count = 0
    private var startedAt = 0L

    // Everything below is guarded by `lock`: the mic thread writes it, the engine thread
    // commits sentences into it, and the main thread finishes it.
    private val lock = Any()
    private var buf = FloatArray(16000 * 30)
    private var size = 0
    private var rms = FloatArray(1024)
    private var frames = 0
    private var floor = 1f
    private var speechFrames = 0
    private var heardSpeech = false
    private var quietFrames = 0
    private var commitAt = 0          // samples before this are already in `committed`
    private val committed = StringBuilder()
    private var aheadBusy = false
    private var aheadAt = 0           // buffer size when the last look-ahead started
    private var session = 0           // bumped per dictation so a stale look-ahead is ignored

    val listening get() = phase.value == Phase.Listening

    // Keeps the screen on from the key going down until the text is in: the key is
    // handled by the accessibility service, so holding it doesn't count as using the phone.
    @Suppress("DEPRECATION")
    private var awake: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    private fun stayAwake(app: Context) {
        if (awake != null) return
        val pm = app.getSystemService(PowerManager::class.java) ?: return
        // ON_AFTER_RELEASE restarts the screen timeout when it's let go, so the screen
        // doesn't go dark the moment the text lands.
        awake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "uplink:dictation")
            .apply { setReferenceCounted(false) }
        phase.listen { p ->
            val w = awake ?: return@listen
            try {
                // The timeout only matters if a dictation somehow never ends.
                if (p == Phase.Idle) { if (w.isHeld) w.release() } else w.acquire(10 * 60_000L)
            } catch (t: Throwable) { Log.e(TAG, "wake lock", t) }
        }
    }

    /** Start listening. sink gets the cleaned-up transcript (never empty), or onSaid gets it
     *  with what's known about it when given. mic = false is for debug-build wav runs, which
     *  feed() the audio themselves. keyDownAt (elapsedRealtime) is when the key went down, for a
     *  key that only starts the mic once it's held; a release before HOLD_MS from it is a tap. */
    fun start(ctx: Context, sink: (String) -> Unit, mic: Boolean = true, onSaid: ((Said) -> Unit)? = null, keyDownAt: Long? = null) {
        if (phase.value != Phase.Idle) { Log.i(TAG, "busy (${phase.value}), not starting"); return }
        val app = ctx.applicationContext
        if (mic && app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Notice.show(app, "Allow the microphone in Nullspace settings"); return
        }
        stayAwake(app)
        if (mic && !Engine.installed(app)) { Notice.show(app, "Open Nullspace settings to download the speech model"); return }
        Engine.warm(app)
        // Pick up dictionary changes from your other devices while you talk.
        if (mic) Sync.soon(app, minGapMs = 60_000)
        this.sink = sink
        this.onSaid = onSaid
        fromMic = mic
        synchronized(lock) {
            size = 0; frames = 0; floor = 1f; speechFrames = 0; heardSpeech = false; quietFrames = 0
            commitAt = 0; committed.setLength(0); aheadBusy = false; aheadAt = 0; session++
        }
        startedAt = keyDownAt ?: SystemClock.elapsedRealtime()
        phase.value = Phase.Listening
        if (!mic) return
        tick(app)
        recorder = Recorder(app) { f, n -> feed(app, f, n) }.also { it.start() }
    }

    /** One frame of audio. */
    fun feed(app: Context, frame: FloatArray, n: Int) {
        var sum = 0f
        for (i in 0 until n) sum += frame[i] * frame[i]
        val r = kotlin.math.sqrt(sum / n)
        var lookAhead = -1
        synchronized(lock) {
            if (size + n > buf.size) buf = buf.copyOf(buf.size * 2)
            System.arraycopy(frame, 0, buf, size, n)
            size += n
            if (frames == rms.size) rms = rms.copyOf(rms.size * 2)
            rms[frames++] = r
            // Noise floor: the quietest frame so far, rising slowly so it can follow a noisier room.
            floor = if (frames <= 3) minOf(floor, r) else minOf(floor * 1.003f, maxOf(r, 1e-5f))
            if (r > threshold()) { speechFrames++; quietFrames = 0 } else { speechFrames = 0; quietFrames++ }
            if (speechFrames >= 3) heardSpeech = true
            if (heardSpeech && !aheadBusy && quietFrames == PAUSE_FRAMES &&
                size - commitAt >= AHEAD_MIN && size - aheadAt >= AHEAD_STEP) {
                aheadBusy = true
                aheadAt = size
                lookAhead = session
            }
        }
        level.value = r
        if (lookAhead >= 0) lookAhead(app, lookAhead)
    }

    private fun lookAhead(app: Context, forSession: Int) {
        var from = 0
        Engine.transcribe(app, {
            synchronized(lock) { from = commitAt; buf.copyOfRange(commitAt, aheadAt) }
        }) { heard ->
            synchronized(lock) {
                aheadBusy = false
                if (forSession != session || from != commitAt) return@synchronized
                val (text, cut) = lastSentence(heard) ?: return@synchronized
                if (committed.isNotEmpty()) committed.append(' ')
                committed.append(text)
                commitAt = from + (cut * 16000).toInt()
                Log.i(TAG, "kept a sentence ahead${said(text)}")
            }
        }
    }

    /** Everything up to the last sentence end that has a word after it, and where to cut the audio. */
    private fun lastSentence(h: Heard): Pair<String, Float>? {
        for (i in h.tokens.size - 2 downTo 0) {
            val t = h.tokens[i].trim()
            if ((t.endsWith('.') || t.endsWith('?') || t.endsWith('!')) && h.tokens[i + 1].startsWith(" ")) {
                val text = h.tokens.sliceArray(0..i).joinToString("").trim()
                val cut = maxOf(h.times[i] + 0.08f, h.times[i + 1] - 0.16f)
                return if (text.isEmpty()) null else text to cut
            }
        }
        return null
    }

    private fun threshold() = maxOf(floor * 4f, 0.0015f)

    /** Speech in the first frames the mic delivered: you were talking before it opened.
     *  The floor here is the quietest frame of the whole clip, since the running one
     *  starts out at speech level when speech comes first. Call holding `lock`. */
    private fun startedMidSpeech(): Boolean {
        var i = 0
        while (i < frames && rms[i] == 0f) i++  // digital silence while the mic wakes
        if (frames - i < 10) return false
        var quiet = Float.MAX_VALUE
        for (k in i until frames) if (rms[k] > 0f) quiet = minOf(quiet, rms[k])
        val th = maxOf(quiet * 6f, 0.01f)
        var loud = 0
        for (k in i until minOf(i + 3, frames)) if (rms[k] > th) loud++
        return loud >= 2
    }

    private fun archiveId(ctx: Context, now: Long): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(now))
        return "$stamp-${Sync.deviceId(ctx)}-${++count}"
    }

    /** Stop listening and transcribe. */
    fun finish(ctx: Context) {
        if (phase.value != Phase.Listening) return
        val app = ctx.applicationContext
        val rec = recorder
        recorder = null
        rec?.stop()
        if (rec?.failed == true) {
            phase.value = Phase.Idle
            Notice.show(app, "Mic unavailable")
            return
        }
        val heldMs = SystemClock.elapsedRealtime() - startedAt
        // A tap rather than a hold. Quiet audio still gets decoded: a soft voice
        // shouldn't be thrown away by a level guess, and silence decodes to nothing.
        if (fromMic && heldMs < 350) {
            synchronized(lock) { session++ }
            phase.value = Phase.Idle
            return
        }
        phase.value = Phase.Working
        val out = sink
        val outSaid = onSaid
        val cut = synchronized(lock) { startedMidSpeech() }
        val t0 = SystemClock.elapsedRealtime()
        var audioMs = 0L
        // Runs after any look-ahead still in flight (one engine thread), so it sees its commit.
        Engine.transcribe(app, {
            synchronized(lock) { tail().also { audioMs = it.size / 16L } }
        }) { tailHeard ->
            val raw = synchronized(lock) {
                session++
                if (committed.isEmpty()) tailHeard.text else "$committed ${tailHeard.text}"
            }
            val text = Cleanup.tidy(app, raw)
            Stats.lastAudioMs = audioMs
            Stats.lastWaitMs = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "released to text in ${Stats.lastWaitMs} ms ($audioMs ms left to decode)")
            val now = System.currentTimeMillis()
            val said = Said(text, archiveId(app, now), now / 1000.0, cut)
            if (fromMic) {
                try {
                    Analytics.record(app, text, heldMs / 1000f, Stats.lastWaitMs / 1000f, Models.selected(app).key)
                } catch (t: Throwable) { Log.e(TAG, "stats", t) }
                if (text.isNotEmpty() && Prefs.archiving) {
                    val pcm = synchronized(lock) { buf.copyOf(size) }
                    Archive.save(app, said, pcm, Models.selected(app).key)
                }
                Sync.soon(app)
            }
            if (cut) Log.i(TAG, "already talking when the mic opened")
            main.post {
                if (text.isNotEmpty()) {
                    Stats.last.value = text
                    try { if (outSaid != null) outSaid(said) else out?.invoke(text) } catch (t: Throwable) { Log.e(TAG, "insert failed", t) }
                } else if (Engine.state.value == Engine.State.Failed) {
                    Notice.show(app, "Model failed to load")
                }
                phase.value = Phase.Idle
            }
        }
    }

    /** The audio not yet transcribed, without the silence at either end. Call holding `lock`. */
    private fun tail(): FloatArray {
        val th = threshold()
        var last = frames - 1
        while (last >= 0 && rms[last] <= th) last--
        var from = commitAt
        if (commitAt == 0) {
            var first = 0
            while (first < frames && rms[first] <= th) first++
            from = maxOf(0, (first - 8) * Recorder.FRAME)
        }
        val to = minOf(size, (last + 12) * Recorder.FRAME)
        return if (to - from >= 4000) buf.copyOfRange(from, to) else buf.copyOfRange(commitAt, size)
    }

    fun cancel() {
        recorder?.stop()
        recorder = null
        synchronized(lock) { session++ }
        if (phase.value == Phase.Listening) phase.value = Phase.Idle
    }

    private fun tick(ctx: Context) {
        try {
            val vm = ctx.getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } catch (_: Throwable) {}
    }

    /** a pause this long (in 30 ms frames) triggers a look-ahead */
    private const val PAUSE_FRAMES = 12
    /** only look ahead with at least this much untranscribed audio... */
    private const val AHEAD_MIN = 16000 * 5
    /** ...and this much new audio since the last try */
    private const val AHEAD_STEP = 16000 * 3
}
