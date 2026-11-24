package com.example.audio

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class DecodedPcm(
    val samples: ShortArray,
    val sampleRate: Int
)

object AudioDspProcessor {

    fun wrapPcm16MonoAsWav(pcmData: ByteArray, sampleRate: Int = 24_000): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val chunkSize = 36 + dataSize

        val out = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(chunkSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        out.write(header.array())
        out.write(pcmData)
        return out.toByteArray()
    }

    fun decodeWavOrPcm(bytes: ByteArray, fallbackSampleRate: Int = 24_000): DecodedPcm {
        val isWav = bytes.size >= 44 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()

        var sampleRate = fallbackSampleRate
        var pcmOffset = 0
        var pcmLength = bytes.size

        if (isWav) {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            sampleRate = buf.getInt(24).coerceIn(8_000, 48_000)
            var offset = 12
            while (offset + 8 <= bytes.size) {
                val id = String(bytes, offset, 4, Charsets.US_ASCII)
                val size = buf.getInt(offset + 4).coerceAtLeast(0)
                if (id == "data") {
                    pcmOffset = offset + 8
                    pcmLength = size.coerceAtMost(bytes.size - pcmOffset)
                    break
                }
                offset += 8 + size + (size % 2)
            }
        }

        val sampleCount = pcmLength / 2
        val samples = ShortArray(sampleCount)
        val pcmBuffer = ByteBuffer.wrap(bytes, pcmOffset, sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            samples[i] = pcmBuffer.short
        }
        return DecodedPcm(samples = samples, sampleRate = sampleRate)
    }

    fun shortsToWavBytes(samples: ShortArray, sampleRate: Int): ByteArray {
        val pcmBytes = ByteArray(samples.size * 2)
        val bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            bb.putShort(s)
        }
        return wrapPcm16MonoAsWav(pcmBytes, sampleRate)
    }

    /**
     * Applies vocal chest depth (biquad low-shelf filter around 185 Hz) and optional
     * pitch/rate resampling for file export.
     */
    fun processForVoiceCharacteristics(
        wavBytes: ByteArray,
        voiceDepth: Double,
        pitchForExport: Double = 1.0,
        rateForExport: Double = 1.0
    ): ByteArray {
        val decoded = decodeWavOrPcm(wavBytes)
        if (decoded.samples.isEmpty()) return wavBytes

        var samples = decoded.samples
        if (abs(voiceDepth) > 0.02) {
            samples = applyLowShelfDepth(samples, decoded.sampleRate, voiceDepth.toFloat())
        }

        val combinedFactor = (pitchForExport * rateForExport).coerceIn(0.65, 1.55)
        if (abs(combinedFactor - 1.0) > 0.02) {
            samples = resampleLinear(samples, combinedFactor)
        }

        return shortsToWavBytes(samples, decoded.sampleRate)
    }

    private fun applyLowShelfDepth(
        input: ShortArray,
        sampleRate: Int,
        depth: Float
    ): ShortArray {
        val gainDb = (depth.coerceIn(-1f, 1f)) * 9.5
        val a = 10.0.pow(gainDb / 40.0)
        val freq = 185.0
        val w0 = 2.0 * PI * freq / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val slope = 0.85
        val alpha = sinW0 / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val b0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)
        val b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
        val b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)
        val a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
        val a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
        val a2 = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha

        val nb0 = b0 / a0
        val nb1 = b1 / a0
        val nb2 = b2 / a0
        val na1 = a1 / a0
        val na2 = a2 / a0

        val out = ShortArray(input.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0

        for (i in input.indices) {
            val x0 = input[i].toDouble()
            val y0 = nb0 * x0 + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
            out[i] = y0.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun resampleLinear(input: ShortArray, speedFactor: Double): ShortArray {
        val outLength = (input.size / speedFactor).toInt().coerceAtLeast(1)
        val out = ShortArray(outLength)
        for (i in 0 until outLength) {
            val srcPos = i * speedFactor
            val idx0 = srcPos.toInt().coerceIn(0, input.lastIndex)
            val idx1 = (idx0 + 1).coerceIn(0, input.lastIndex)
            val frac = srcPos - idx0
            val sample = input[idx0] * (1.0 - frac) + input[idx1] * frac
            out[i] = sample.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /**
     * Pure-Java MP3 encoding via jump3r without javax.sound.sampled dependencies.
     */
    fun encodeWavToMp3(wavBytes: ByteArray, bitrateKbps: Int = 128): ByteArray {
        val decoded = decodeWavOrPcm(wavBytes)
        val samples = decoded.samples
        if (samples.isEmpty()) return ByteArray(0)

        val lame = Lame()
        val ga = GainAnalysis()
        val bs = BitStream()
        val p = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()

        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        p.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lame, bs, ver)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)

        val gfp = lame.lame_init()
        gfp.num_channels = 1
        gfp.in_samplerate = decoded.sampleRate
        gfp.out_samplerate = decoded.sampleRate
        gfp.brate = bitrateKbps
        gfp.quality = 2
        gfp.mode = MPEGMode.MONO
        id3.id3tag_init(gfp)
        gfp.write_id3tag_automatic = false
        lame.lame_init_params(gfp)

        val outStream = ByteArrayOutputStream()
        val chunkSize = 4096
        val intBuffer = IntArray(chunkSize)
        val mp3Buffer = ByteArray((chunkSize * 1.25 + 7200).toInt())

        try {
            var offset = 0
            while (offset < samples.size) {
                val count = minOf(chunkSize, samples.size - offset)
                for (i in 0 until count) {
                    // Jump3r lame_encode_buffer_int expects 16-bit PCM scaled to 32-bit MSB or standard 16-bit shift?
                    // Jump3r expects sample << 16 in lame_encode_buffer_int
                    intBuffer[i] = samples[offset + i].toInt() shl 16
                }
                val encoded = lame.lame_encode_buffer_int(
                    gfp,
                    intBuffer,
                    intBuffer,
                    count,
                    mp3Buffer,
                    0,
                    mp3Buffer.size
                )
                if (encoded > 0) {
                    outStream.write(mp3Buffer, 0, encoded)
                }
                offset += count
            }
            val flushed = lame.lame_encode_flush(gfp, mp3Buffer, 0, mp3Buffer.size)
            if (flushed > 0) {
                outStream.write(mp3Buffer, 0, flushed)
            }
        } finally {
            lame.lame_close(gfp)
        }

        return outStream.toByteArray()
    }
}
