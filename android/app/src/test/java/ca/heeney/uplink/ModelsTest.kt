package ca.heeney.uplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test fun whisperTurboUsesTheWhisperDecoderFiles() {
        val model = Models.WHISPER_TURBO
        assertTrue(model.whisper)
        assertEquals("turbo-encoder.int8.onnx", model.encoder)
        assertEquals("turbo-decoder.int8.onnx", model.decoder)
        assertEquals("turbo-tokens.txt", model.tokens)
        assertEquals(1_036_613_791L, model.bytes)
        assertFalse(Models.ENGLISH.whisper)
        assertEquals("tokens.txt", Models.ENGLISH.tokens)
    }
}
