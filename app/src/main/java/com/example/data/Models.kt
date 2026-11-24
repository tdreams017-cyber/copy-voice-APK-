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
    val deliveryStyle: String = "",
    val pitchShift: Double = 1.0,
    val speechRate: Double = 1.0,
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
    val pitchShift: Double = 1.0,
    val speechRate: Double = 1.0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
