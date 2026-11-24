package com.example.ai

import android.util.Log
import com.example.audio.AudioDspProcessor
import com.example.data.AudioLanguageOption
import com.example.data.DetectedEmotion
import com.example.data.EmotionAnalysis
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

data class AnalyzedVoiceTraits(
    val baseVoiceName: String,
    val toneDescription: String,
    val dialectNotes: String,
    val deliveryStyle: String,
    val recommendedPitch: Double,
    val recommendedRate: Double,
    val recommendedDepth: Double
)

data class SynthesizedAudio(
    val rawWavBytes: ByteArray,
    val playbackWavBytes: ByteArray,
    val mimeType: String,
    val usedClonedVoice: Boolean = false
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

    private val emotionAnalyzerModel by lazy {
        val schema = Schema.obj(
            properties = mapOf(
                "emotion" to Schema.enumeration(
                    values = DetectedEmotion.entries.map { it.name },
                    description = "Dominant emotion detected in the text."
                ),
                "intensity" to Schema.double(
                    description = "Emotional intensity between 0.25 and 1.0."
                ),
                "deliveryCueAr" to Schema.string(
                    description = "Concise Arabic vocal acting direction describing how the speaker should deliver the text."
                ),
                "suggestedPitchDelta" to Schema.double(
                    description = "Subtle pitch offset between -0.10 and 0.10."
                ),
                "suggestedRateDelta" to Schema.double(
                    description = "Subtle speaking speed offset between -0.14 and 0.14."
                ),
                "suggestedDepthDelta" to Schema.double(
                    description = "Subtle vocal chest depth offset between -0.25 and 0.30."
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
                "dialectNotes" to Schema.string(
                    description = "Arabic description of the speaker's dialect/accent (e.g. خليجي، مصري، شامي، فصحى) and distinctive cadence."
                ),
                "deliveryStyle" to Schema.string(
                    description = "Concise Arabic style instructions for TTS delivery matching this voice."
                ),
                "recommendedPitch" to Schema.double(
                    description = "Recommended pitch multiplier between 0.80 and 1.25 (1.0 is neutral)."
                ),
                "recommendedRate" to Schema.double(
                    description = "Recommended speaking rate multiplier between 0.85 and 1.20 (1.0 is neutral)."
                ),
                "recommendedDepth" to Schema.double(
                    description = "Recommended vocal depth/bass resonance between -0.60 and 0.60 (0.0 is neutral)."
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

    suspend fun analyzeTextEmotion(text: String): EmotionAnalysis {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return EmotionAnalysis()

        return try {
            val response = withTimeout(25_000L) {
                emotionAnalyzerModel.generateContent(
                    "حلّل المشاعر السائدة في النص التالي بدقة لتوجيه نبرة الإلقاء الصوتي (السعادة، الحزن، الغضب، الحماس، الهدوء، الجدية، التساؤل، أو الحياد). أعد النتيجة بصيغة JSON:\n$cleanText"
                )
            }
            val jsonText = response.text?.trim().orEmpty()
            if (jsonText.isEmpty()) return EmotionAnalysis()

            val json = JSONObject(jsonText)
            val emotion = DetectedEmotion.fromRaw(json.optString("emotion"))
            val intensity = json.optDouble("intensity", 0.65).coerceIn(0.25, 1.0)
            val cue = json.optString("deliveryCueAr").ifBlank { emotion.deliveryCueAr }
            val pitchDelta = json.optDouble("suggestedPitchDelta", emotion.pitchDelta).coerceIn(-0.10, 0.10)
            val rateDelta = json.optDouble("suggestedRateDelta", emotion.rateDelta).coerceIn(-0.14, 0.14)
            val depthDelta = json.optDouble("suggestedDepthDelta", emotion.depthDelta).coerceIn(-0.25, 0.30)

            EmotionAnalysis(
                emotion = emotion,
                intensity = intensity,
                deliveryCueAr = cue,
                suggestedPitchDelta = pitchDelta,
                suggestedRateDelta = rateDelta,
                suggestedDepthDelta = depthDelta
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Emotion analysis fallback to neutral", e)
            EmotionAnalysis()
        }
    }

    suspend fun analyzeVoiceSample(
        audioBytes: ByteArray,
        mimeType: String
    ): AnalyzedVoiceTraits {
        require(audioBytes.isNotEmpty()) { "التسجيل الصوتي فارغ." }
        require(audioBytes.size < MAX_INLINE_AUDIO_BYTES) {
            "حجم عينة الصوت كبير جداً، يُفضّل مقطع بين 5 إلى 30 ثانية."
        }

        val response = withTimeout(AI_TIMEOUT_MS) {
            voiceAnalyzerModel.generateContent(
                content {
                    inlineData(audioBytes, mimeType)
                    text(
                        "حلّل الخصائص الصوتية للمتحدث في هذه العينة الصوتية بدقة (طبقة الصوت، عمق الرخامة، الدفء، السرعة، واللهجة العربية أو اللكنة وطريقة النطق)، واختر أقرب صوت أساسي مع القيم المقترحة للطبقة والسرعة والعمق."
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
        val dialectNotes = json.optString("dialectNotes", "عربية واضحة بطابع شخصي متزن")
        val style = json.optString("deliveryStyle", "نطق عربي واضح وهادئ")
        val pitch = json.optDouble("recommendedPitch", 1.0).coerceIn(0.75, 1.35)
        val rate = json.optDouble("recommendedRate", 1.0).coerceIn(0.80, 1.25)
        val depth = json.optDouble("recommendedDepth", 0.0).coerceIn(-0.65, 0.65)

        return AnalyzedVoiceTraits(
            baseVoiceName = voice,
            toneDescription = toneDesc,
            dialectNotes = dialectNotes,
            deliveryStyle = style,
            recommendedPitch = pitch,
            recommendedRate = rate,
            recommendedDepth = depth
        )
    }

    @OptIn(PublicPreviewAPI::class)
    suspend fun synthesizeSpeech(
        text: String,
        profile: VoiceProfile,
        emotionAnalysis: EmotionAnalysis,
        effectiveDepth: Double,
        customApiKey: String,
        voiceCloneClient: VoiceCloneApiClient
    ): SynthesizedAudio {
        val cleanText = text.trim()
        require(cleanText.isNotEmpty()) { "أدخل النص الذي تريد تحويله إلى كلام." }

        val cloneId = profile.clonedVoiceId?.trim().orEmpty()
        if (cloneId.isNotEmpty() && customApiKey.isNotBlank()) {
            val apiResult = voiceCloneClient.synthesizeWithClonedVoice(
                apiKey = customApiKey,
                voiceId = cloneId,
                text = cleanText,
                emotion = emotionAnalysis.emotion,
                intensity = emotionAnalysis.intensity
            )
            val rawClonedWav = apiResult.getOrElse { throw it }
            val playbackWav = AudioDspProcessor.processForVoiceCharacteristics(
                wavBytes = rawClonedWav,
                voiceDepth = effectiveDepth,
                pitchForExport = 1.0,
                rateForExport = 1.0
            )
            return SynthesizedAudio(
                rawWavBytes = rawClonedWav,
                playbackWavBytes = playbackWav,
                mimeType = "audio/wav",
                usedClonedVoice = true
            )
        }

        val ttsModel = Firebase.ai.generativeModel(
            modelName = "gemini-3.1-flash-tts-preview",
            generationConfig = generationConfig {
                responseModalities = listOf(ResponseModality.AUDIO)
                speechConfig = SpeechConfig(
                    voice = Voice(profile.baseVoiceName.ifBlank { "Charon" })
                )
            }
        )

        val actingDirections = buildList {
            if (emotionAnalysis.deliveryCueAr.isNotBlank()) add(emotionAnalysis.deliveryCueAr)
            if (profile.dialectNotes.isNotBlank()) add("محاكاة اللهجة والأسلوب: ${profile.dialectNotes}")
            if (profile.deliveryStyle.isNotBlank()) add(profile.deliveryStyle)
            if (effectiveDepth > 0.25) add("بصوت عميق ورخيم")
            else if (effectiveDepth < -0.25) add("بصوت خفيف ومشرق")
        }.joinToString("، ")

        val directedPrompt = if (actingDirections.isNotBlank()) {
            "اقرأ النص التالي بأداء تمثيلي متقن ($actingDirections) دون إضافة أو حذف أي كلمات:\n$cleanText"
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

        val rawWav = ensurePlayableWav(audioPart.inlineData, audioPart.mimeType)
        val playbackWav = AudioDspProcessor.processForVoiceCharacteristics(
            wavBytes = rawWav,
            voiceDepth = effectiveDepth,
            pitchForExport = 1.0,
            rateForExport = 1.0
        )

        val resolvedMime = if (
            audioPart.mimeType.contains("pcm", ignoreCase = true) ||
            audioPart.mimeType.contains("L16", ignoreCase = true)
        ) {
            "audio/wav"
        } else {
            audioPart.mimeType
        }

        return SynthesizedAudio(
            rawWavBytes = rawWav,
            playbackWavBytes = playbackWav,
            mimeType = resolvedMime,
            usedClonedVoice = false
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

        return AudioDspProcessor.wrapPcm16MonoAsWav(bytes, sampleRate)
    }

    private companion object {
        const val TAG = "NabraAiService"
        const val AI_TIMEOUT_MS = 60_000L
        const val MAX_INLINE_AUDIO_BYTES = 18 * 1024 * 1024
    }
}
