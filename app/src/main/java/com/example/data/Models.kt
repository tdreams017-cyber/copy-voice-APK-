package com.example.data

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class TranscriptFormatStyle(val labelAr: String, val descriptionAr: String) {
    RAW("النص الحرفي", "تفريغ مباشر ودقيق كما ورد في التسجيل"),
    CLEAN_PARAGRAPHS("فقرات منظمة", "تنسيق تلقائي مع علامات الترقيم وتصحيح الإملاء"),
    MEETING_NOTES("محضر اجتماع", "ملخص تنفيذي، محاور رئيسية، ومهام مقترحة"),
    BULLET_SUMMARY("نقاط موجزة", "عناوين فرعية ونقاط مختصرة وسهلة القراءة");

    companion object {
        fun fromRaw(raw: String?): TranscriptFormatStyle {
            return entries.firstOrNull { it.name == raw } ?: RAW
        }
    }
}

enum class AudioLanguageOption(val code: String, val labelAr: String) {
    ARABIC("ar", "العربية"),
    AUTO("auto", "تلقائي (عربي / إنجليزي)"),
    ENGLISH("en", "English")
}

enum class AudioExportFormat(
    val extension: String,
    val mimeType: String,
    val labelAr: String,
    val descriptionAr: String
) {
    WAV("wav", "audio/wav", "WAV", "جودة استوديو أصلية بدون ضغط"),
    MP3("mp3", "audio/mpeg", "MP3", "صيغة مضغوطة خفيفة ومتوافقة مع كل الأجهزة")
}

enum class DetectedEmotion(
    val labelAr: String,
    val deliveryCueAr: String,
    val pitchDelta: Double,
    val rateDelta: Double,
    val depthDelta: Double
) {
    HAPPY("سعيد", "بنبرة مبتهجة ودافئة وحيوية واضحة", 0.06, 0.06, -0.08),
    SAD("حزين", "بنبرة خافتة متأثرة وحزينة وبإيقاع متمهل", -0.06, -0.10, 0.14),
    ANGRY("غاضب", "بنبرة قوية وحازمة ومشددة وبإيقاع حاد", 0.04, 0.08, 0.22),
    EXCITED("متحمس", "بنبرة حماسية مشرقة وسريعة ومتدفقة", 0.08, 0.11, -0.05),
    CALM("هادئ", "بنبرة هادئة ومريحة وواثقة وإيقاع متزن", -0.02, -0.05, 0.12),
    SERIOUS("جاد", "بنبرة رصينة ورسمية وواضحة ومؤكدة", -0.03, -0.02, 0.18),
    QUESTIONING("متسائل", "بنبرة مستفهمة صاعدة في نهاية الجمل", 0.05, 0.02, 0.0),
    NEUTRAL("محايد", "بنبرة طبيعية واضحة ومتزنة", 0.0, 0.0, 0.0);

    companion object {
        fun fromRaw(raw: String?): DetectedEmotion {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: NEUTRAL
        }
    }
}

data class EmotionAnalysis(
    val emotion: DetectedEmotion = DetectedEmotion.NEUTRAL,
    val intensity: Double = 0.65,
    val deliveryCueAr: String = DetectedEmotion.NEUTRAL.deliveryCueAr,
    val suggestedPitchDelta: Double = 0.0,
    val suggestedRateDelta: Double = 0.0,
    val suggestedDepthDelta: Double = 0.0
)

@Keep
@IgnoreExtraProperties
data class TranscriptNote(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val rawText: String = "",
    val organizedText: String? = null,
    val language: String = "ar",
    val formatStyle: String = TranscriptFormatStyle.RAW.name,
    val durationSeconds: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun resolvedFormatStyle(): TranscriptFormatStyle = TranscriptFormatStyle.fromRaw(formatStyle)

    fun effectiveBody(): String {
        val organized = organizedText?.trim().orEmpty()
        return if (organized.isNotEmpty()) organized else rawText
    }
}

@Keep
@IgnoreExtraProperties
data class VoiceProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val baseVoiceName: String = "Charon",
    val toneDescription: String = "",
    val dialectNotes: String = "",
    val deliveryStyle: String = "",
    val clonedVoiceId: String? = null,
    val pitchShift: Double = 1.0,
    val speechRate: Double = 1.0,
    val voiceDepth: Double = 0.0,
    val sampleDurationSeconds: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class GeneratedSpeech(
    @DocumentId val id: String = "",
    val userId: String = "",
    val text: String = "",
    val voiceProfileName: String = "",
    val baseVoiceName: String = "Charon",
    val detectedEmotion: String = DetectedEmotion.NEUTRAL.name,
    val pitchShift: Double = 1.0,
    val speechRate: Double = 1.0,
    val voiceDepth: Double = 0.0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
