package ca.heeney.uplink

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Exercises the real Whisper Turbo weights and sherpa-onnx decoder without the live app UI. */
@RunWith(AndroidJUnit4::class)
class WhisperTurboTest {
    @Test fun decodesKnownPcm() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Prefs.init(ctx)
        val model = Models.WHISPER_TURBO
        val source = File("/data/local/tmp/nullspace-whisper-turbo-test")
        val dest = model.dir(ctx).apply { mkdirs() }
        for (name in model.files.keys) {
            File(source, name).copyTo(File(dest, name), overwrite = true)
        }
        assertTrue("Whisper Turbo model files are incomplete", model.installed(ctx))
        Prefs.model = model.key
        Prefs.modelOverride = true

        val pcm = File(source, "known-16k-mono.pcm").readBytes()
        val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(shorts.remaining()) { shorts.get() / 32768f }
        val started = SystemClock.elapsedRealtime()
        val heard = Engine.transcribe(ctx, samples).get(20, TimeUnit.MINUTES)
        val totalMs = SystemClock.elapsedRealtime() - started
        val decodeMs = Stats.lastDecodeMs
        Log.i(TAG, "Whisper Turbo test transcript: ${heard.text}")
        Log.i(TAG, "Whisper Turbo test latency: load+warm-up=${totalMs - decodeMs} ms, decode=$decodeMs ms, total=$totalMs ms")

        for (word in listOf("Gemini", "released", "new", "models")) {
            assertTrue("Expected '$word' in actual transcript: ${heard.text}", heard.text.contains(word, ignoreCase = true))
        }
    }
}
