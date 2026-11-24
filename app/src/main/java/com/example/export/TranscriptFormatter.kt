package com.example.export

import com.example.data.AudioLanguageOption
import com.example.data.TranscriptNote
import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.Locale

object TranscriptFormatter {
    fun formatAsOrganizedTextFile(note: TranscriptNote): String {
        val date = (note.updatedAt ?: note.createdAt ?: Timestamp.now()).toDate()
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale("ar")).format(date)
        val langLabel = AudioLanguageOption.entries.firstOrNull { it.code == note.language }?.labelAr
            ?: note.language
        val styleLabel = note.resolvedFormatStyle().labelAr
        val durationFormatted = formatDuration(note.durationSeconds)
        val body = note.effectiveBody()

        return buildString {
            appendLine("==================================================")
            appendLine("العنوان: ${note.title.ifBlank { "نسخة صوتية" }}")
            appendLine("التاريخ: $dateStr")
            appendLine("المدة: $durationFormatted | اللغة: $langLabel | التنسيق: $styleLabel")
            appendLine("==================================================")
            appendLine()
            appendLine(body.trim())
            if (!note.organizedText.isNullOrBlank() && note.organizedText.trim() != note.rawText.trim()) {
                appendLine()
                appendLine("--------------------------------------------------")
                appendLine("النص الحرفي الأصلي:")
                appendLine("--------------------------------------------------")
                appendLine(note.rawText.trim())
            }
            appendLine()
            appendLine("— تم الإنشاء بواسطة تطبيق نبرة")
        }
    }

    fun suggestedFileName(title: String): String {
        val sanitized = title.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), "_")
            .take(40)
            .ifBlank { "nabra_transcript" }
        return "$sanitized.txt"
    }

    fun formatDuration(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}
