package com.example.ai

import android.util.Log
import com.example.data.AudioLanguageOption
import com.example.data.TranscriptFormatStyle
import com.example.data.VoiceProfile
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AnalyzedVoiceTraits(
    val baseVoiceName: String,
    val toneDescription: String,
    val deliveryStyle: String,
    val recommendedPitch: Double,
    val recommendedRate: Double
)

data class SynthesizedAudio(
    val wavBytes: ByteArray,
    val mimeType: String
)

enum class TtsVoicePreset(
    val voiceId: String,
    val labelAr: String,
    val characterAr: String
) {
    CHARON("Charon", "عميق ومتزن", "نبرة ذكورية هادئة وعميقة"),
    FENRIR("Fenrir", "حماسي وقوي", "نبرة جريئة وواضحة ومؤثرة"),
    ORUS("Orus", "رسمي وواضح", "نبرة إلقاء احترافية ورصينة"),
    PUCK("Puck", "حيوي وسريع", "نبرة شبابية نشطة"),
    KORE("Kore", "ناعم ودافئ", "نبرة أنثوية هادئة وواضحة"),
    AOEDE("Aoede", "عذب ومعبّر", "نبرة سردية دافئة"),
    ZEPHYR("Zephyr", "خفيف ومشرق", "نبرة خفيفة ومريحة")
}

class NabraAiService {
    private val transcribeModel by lazy {
        Firebase.ai.generativeModel(modelName = "gemini-3.5-transcribe")
    }

    private val organizeModel by lazy {
        Firebase.ai.generativeModel(modelName = "gemini-3.8-flash")
    }

    private val voiceAnalyzerModel by lazy {
        val schema = Schema.obj(
            properties = mapOf(
                "baseVoiceName" to Schema.enumeration(
                    values = TtsVoicePreset.entries.map { it.voiceId },
                    description = "Closest prebuilt voice preset matching the speaker's register and warmth."
                ),
                "toneDescription" to Schema.string(
                    description = "Arabic summary (1-2 sentences) describing the speaker's vocal pitch, warmth, clarity, and speaking pace."
                ),
                "deliveryStyle" to Schema.string(
                    description = "Concise Arabic style instructions for TTS delivery matching this voice."
                ),
                "recommendedPitch" to Schema.double(
                    description = "Recommended pitch multiplier between 0.80 and 1.25 (1.0 is neutral)."
                ),
                "recommendedRate" to Schema.double(
                    description = "Recommended speaking rate multiplier between 0.85 and 1.20 (1.0 is neutral)."
                )
            )
        )
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = schema
            }
        )
    }

    suspend fun transcribeAudio(
        audioBytes: ByteArray,
        mimeType: String,
        language: AudioLanguageOption
    ): String {
        require(audioBytes.isNotEmpty()) { "الملف الصوتي فارغ." }
        require(audioBytes.size < MAX_INLINE_AUDIO_BYTES) {
            "حجم المقطع الصوتي يتجاوز الحد المسموح (18 ميجابايت)."
        }

        val languageInstruction = when (language) {
            AudioLanguageOption.ARABIC ->
                "اللغة المتوقعة هي العربية (الفصحى أو اللهجات العربية). انسخ الكلام بدقة متناهية مع علامات الترقيم المناسبة دون إضافة مقدمات أو تعليقات."
            AudioLanguageOption.ENGLISH ->
                "The expected language is English. Transcribe accurately with proper punctuation, returning only the spoken transcript."
            AudioLanguageOption.AUTO ->
                "انسخ الكلام باللغة المنطوقة نفسها (عربية أو إنجليزية أو مختلطة) بدقة مع علامات الترقيم، وأعد النص المنسوخ فقط دون مقدمات."
        }

        return try {
            val response = withTimeout(AI_TIMEOUT_MS) {
                transcribeModel.generateContent(
                    content {
                        inlineData(audioBytes, mimeType)
                        text(
                            "أنت ناسخ صوتي احترافي. حوّل هذا التسجيل الصوتي إلى نص مكتوب بدقة تامة. $languageInstruction"
                        )
                    }
                )
            }
            val resultText = response.text?.trim().orEmpty()
            if (resultText.isEmpty()) {
                throw IllegalStateException("لم يتم التعرف على كلام واضح في التسجيل.")
            }
            resultText
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            throw e
        }
    }

    fun organizeTranscriptStream(
        rawText: String,
        formatStyle: TranscriptFormatStyle,
        title: String
    ): Flow<String> = flow {
        require(rawText.isNotBlank()) { "لا يوجد نص لتنظيمه." }
        if (formatStyle == TranscriptFormatStyle.RAW) {
            emit(rawText)
            return@flow
        }

        val styleInstruction = when (formatStyle) {
            TranscriptFormatStyle.RAW -> ""
            TranscriptFormatStyle.CLEAN_PARAGRAPHS ->
                "نظّم النص التالي في فقرات مترابطة وواضحة مع تصحيح علامات الترقيم والأخطاء الإملائية الطفيفة دون تغيير المعنى أو اختصار المحتوى."
            TranscriptFormatStyle.MEETING_NOTES ->
                "حوّل النص التالي إلى محضر اجتماع منظم باللغة العربية يتضمن: 1) ملخص تنفيذي قصير، 2) النقاط والمحاور الرئيسية، 3) المهام والقرارات المقترحة."
            TranscriptFormatStyle.BULLET_SUMMARY ->
                "نظّم النص التالي في شكل عناوين فرعية واضحة ونقاط موجزة مركزة تسهل قراءتها وحفظها كملف نصي منظم."
        }

        val prompt = buildString {
            appendLine(styleInstruction)
            if (title.isNotBlank()) {
                appendLine("عنوان النص: $title")
            }
            appendLine("أعد النص المنظم مباشرة دون عبارات تمهيدية أو ختامية.")
            appendLine("---")
            append(rawText)
        }

        val accumulated = StringBuilder()
        withTimeout(AI_TIMEOUT_MS) {
            organizeModel.generateContentStream(prompt).collect { chunk ->
                val part = chunk.text.orEmpty()
                if (part.isNotEmpty()) {
                    accumulated.append(part)
                    emit(accumulated.toString())
                }
            }
        }
        if (accumulated.isBlank()) {
            throw IllegalStateException("تعذر تنظيم النص حالياً.")
        }
    }

    suspend fun analyzeVoiceSample(
        audioBytes: ByteArray,
        mimeType: String
    ): AnalyzedVoiceTraits {
        require(audioBytes.isNotEmpty()) { "التسجيل الصوتي فارغ." }
        require(audioBytes.size < MAX_INLINE_AUDIO_BYTES) {
            "حجم عينة الصوت كبير جداً، يُفضّل تسجيل 5 إلى 20 ثانية."
        }

        val response = withTimeout(AI_TIMEOUT_MS) {
            voiceAnalyzerModel.generateContent(
                content {
                    inlineData(audioBytes, mimeType)
                    text(
                        "حلّل الخصائص الصوتية للمتحدث في هذه العينة (طبقة الصوت، الدفء، السرعة، وضوح المخارج) واختر أقرب صوت أساسي من القائمة المتاحة مع معامل طبقة الصوت والسرعة باللغة العربية."
                    )
                }
            )
        }

        val jsonText = response.text?.trim()
            ?: throw IllegalStateException("لم يُرجع المحلل الصوتي بيانات صالحة.")

        val json = JSONObject(jsonText)
        val voice = json.optString("baseVoiceName", "Charon")
            .takeIf { candidate -> TtsVoicePreset.entries.any { it.voiceId == candidate } }
            ?: "Charon"
        val toneDesc = json.optString("toneDescription", "نبرة واضحة ومتزنة")
        val style = json.optString("deliveryStyle", "نطق عربي واضح وهادئ")
        val pitch = json.optDouble("recommendedPitch", 1.0).coerceIn(0.75, 1.35)
        val rate = json.optDouble("recommendedRate", 1.0).coerceIn(0.80, 1.25)

        return AnalyzedVoiceTraits(
            baseVoiceName = voice,
            toneDescription = toneDesc,
            deliveryStyle = style,
            recommendedPitch = pitch,
            recommendedRate = rate
        )
    }

    @OptIn(PublicPreviewAPI::class)
    suspend fun synthesizeSpeech(
        text: String,
        profile: VoiceProfile
    ): SynthesizedAudio {
        val cleanText = text.trim()
        require(cleanText.isNotEmpty()) { "أدخل النص الذي تريد تحويله إلى كلام." }

        val ttsModel = Firebase.ai.generativeModel(
            modelName = "gemini-3.1-flash-tts-preview",
            generationConfig = generationConfig {
                responseModalities = listOf(ResponseModality.AUDIO)
                speechConfig = SpeechConfig(
                    voice = Voice(profile.baseVoiceName.ifBlank { "Charon" })
                )
            }
        )

        val directedPrompt = if (profile.deliveryStyle.isNotBlank()) {
            "اقرأ النص التالي بنبرة (${profile.deliveryStyle}) وبوضوح تام دون إضافة أي كلمات:\n$cleanText"
        } else {
            cleanText
        }

        val response = withTimeout(AI_TIMEOUT_MS) {
            ttsModel.generateContent(directedPrompt)
        }

        val audioPart = response.candidates
            .firstOrNull()
            ?.content
            ?.parts
            ?.filterIsInstance<InlineDataPart>()
            ?.firstOrNull { it.mimeType.startsWith("audio/") && it.inlineData.isNotEmpty() }
            ?: throw IllegalStateException("لم يولّد النموذج مخرجاً صوتياً، حاول نصاً أقصر أو واضحاً.")

        val rawBytes = audioPart.inlineData
        val mime = audioPart.mimeType
        val playableWav = ensurePlayableWav(rawBytes, mime)
        return SynthesizedAudio(
            wavBytes = playableWav,
            mimeType = if (mime.contains("pcm", ignoreCase = true) || mime.contains("L16", ignoreCase = true)) {
                "audio/wav"
            } else {
                mime
            }
        )
    }

    private fun ensurePlayableWav(bytes: ByteArray, mimeType: String): ByteArray {
        val isRiffWav = bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()

        if (isRiffWav) return bytes

        val isRawPcm = mimeType.contains("L16", ignoreCase = true) ||
            mimeType.contains("pcm", ignoreCase = true)

        if (!isRawPcm && !mimeType.equals("audio/wav", ignoreCase = true)) {
            return bytes
        }

        val sampleRate = Regex("rate=(\\d+)")
            .find(mimeType)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 24_000

        return wrapPcm16MonoAsWav(bytes, sampleRate)
    }

    private fun wrapPcm16MonoAsWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
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
        header.putInt(16) // PCM subchunk size
        header.putShort(1) // AudioFormat = 1 (PCM)
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

    private companion object {
        const val TAG = "NabraAiService"
        const val AI_TIMEOUT_MS = 60_000L
        const val MAX_INLINE_AUDIO_BYTES = 18 * 1024 * 1024
    }
}
