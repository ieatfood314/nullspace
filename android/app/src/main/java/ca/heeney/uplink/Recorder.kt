package ca.heeney.uplink

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/** 16 kHz mono float capture on its own thread, handed over 30 ms at a time.
 *
 *  Holds exclusive transient audio focus while it records, so music players pause
 *  (rather than duck) and pick up again once the mic is let go. */
class Recorder(ctx: Context, private val onFrame: (FloatArray, Int) -> Unit) {
    private val audio = ctx.applicationContext.getSystemService(AudioManager::class.java)
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var failed = false
        private set

    fun start() {
        running = true
        thread = Thread({
            val focus = takeFocus()
            try { record() } finally { focus?.let { audio?.abandonAudioFocusRequest(it) } }
        }, "squelch-mic").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Ask other players to pause. Recording goes ahead even if they won't (say, during a call). */
    private fun takeFocus(): AudioFocusRequest? = try {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        if (audio?.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) req
        else { Log.w(TAG, "audio focus not granted"); null }
    } catch (t: Throwable) {
        Log.e(TAG, "audio focus failed", t); null
    }

    @SuppressLint("MissingPermission")
    private fun record() {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
                maxOf(min, FRAME * 4 * 8),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord failed", t); failed = true; return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized"); failed = true; rec.release(); return
        }
        rec.startRecording()
        val frame = FloatArray(FRAME)
        try {
            while (running) {
                val n = rec.read(frame, 0, FRAME, AudioRecord.READ_BLOCKING)
                if (n < 0) { failed = true; break }
                if (n > 0) onFrame(frame, n)
            }
        } finally {
            rec.stop()
            rec.release()
        }
    }

    /** Stop; returns once the last frame has been handed over. */
    fun stop() {
        running = false
        thread?.join(500)
    }

    companion object {
        const val RATE = 16000
        const val FRAME = 480 // 30 ms
    }
}
