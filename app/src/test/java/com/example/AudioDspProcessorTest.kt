package com.example

import com.example.audio.AudioDspProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioDspProcessorTest {

    private fun generateSineWav(sampleRate: Int = 24_000, durationSeconds: Double = 0.5): ByteArray {
        val sampleCount = (sampleRate * durationSeconds).toInt()
        val samples = ShortArray(sampleCount) { idx ->
            (sin(2.0 * PI * 220.0 * idx / sampleRate) * 12_000.0).toInt().toShort()
        }
        return AudioDspProcessor.shortsToWavBytes(samples, sampleRate)
    }

    @Test
    fun processForVoiceCharacteristics_preservesValidWavHeaderAndAdjustsLength() {
        val wav = generateSineWav()
        val deeperFaster = AudioDspProcessor.processForVoiceCharacteristics(
            wavBytes = wav,
            voiceDepth = 0.6,
            pitchForExport = 1.0,
            rateForExport = 1.25
        )
        assertTrue(deeperFaster.size > 44)
        assertEquals('R'.code.toByte(), deeperFaster[0])
        assertEquals('I'.code.toByte(), deeperFaster[1])
        assertEquals('F'.code.toByte(), deeperFaster[2])
        assertEquals('F'.code.toByte(), deeperFaster[3])
        assertTrue(deeperFaster.size < wav.size)
    }

    @Test
    fun encodeWavToMp3_producesValidMp3Frames() {
        val wav = generateSineWav()
        val mp3 = AudioDspProcessor.encodeWavToMp3(wav, bitrateKbps = 128)
        assertTrue("Expected non-empty MP3 output", mp3.size > 100)
        val hasSyncWord = (0 until mp3.size - 1).any { i ->
            (mp3[i].toInt() and 0xFF) == 0xFF && (mp3[i + 1].toInt() and 0xE0) == 0xE0
        }
        assertTrue("Expected MP3 MPEG frame sync word in output", hasSyncWord)
    }
}
